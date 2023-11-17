/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hsj.camera.externalcameratest.encoder;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * This class wraps up the core components used for surface-input video encoding.
 * <p>
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 * <p>
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 */
public class MediaEncoderCore {
    private static final String TAG = "VideoEncoderCore";
    private static final boolean VERBOSE = false;

    // TODO: these ought to be configurable as well
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BIT_RATE = 128000;

    private final Surface mInputSurface;
    private MediaMuxer mMuxer;
    private MediaCodec videoEncoder;
    private final MediaCodec.BufferInfo mBufferInfo, audioBufferInfo;
    private int videoTrackIndex, audioTrackIndex;
    private boolean mMuxerStarted;
    private final AudioThread audioThread;
    private MediaCodec audioEncoder;
    private AudioRecord mAudioRecord;
    private boolean audioRecording;
    private int mBufferSize;


    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    public MediaEncoderCore(int width, int height, int bitRate, int framRate, File outputFile)
            throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();
        audioBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, framRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();

        mBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, mBufferSize);

        MediaFormat audioFormat = new MediaFormat();
        audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);

        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        audioThread = new AudioThread();

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        videoTrackIndex = -1;
        audioTrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * Releases encoder resources.
     */
    public void release() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if (audioEncoder != null) {
            audioEncoder.stop();
            audioEncoder.release();
            audioEncoder = null;
        }
        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder.release();
            videoEncoder = null;
        }
        if (mMuxer != null) {
            // TODO: stop() throws an exception if you haven't fed it any data.  Keep track
            //       of frames submitted, and don't call stop() if we haven't written anything.
            try {
                mMuxer.stop();
            } catch (Exception ignored) {}
            mMuxer.release();
            mMuxer = null;
        }
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    public void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            audioRecording = false;
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            videoEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = videoEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = videoEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = videoEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat videoFormat = videoEncoder.getOutputFormat();
                videoTrackIndex = mMuxer.addTrack(videoFormat);
                Log.d(TAG, "encoder output format changed: " + videoFormat);
                Log.d(TAG, "videoTrackIndex: " + videoTrackIndex);

                MediaFormat audioFormat = audioEncoder.getOutputFormat();
                audioTrackIndex = mMuxer.addTrack(audioFormat);
                Log.d(TAG, "audioTrackIndex: " + audioTrackIndex);

                audioThread.start();

                mMuxer.start();
                mMuxerStarted = true;

                audioRecording = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(videoTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) {
                        Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                mBufferInfo.presentationTimeUs);
                    }
                }

                videoEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    audioRecording = false;
                    break;      // out of while
                }
            }
        }
    }

    private class AudioThread extends Thread {
        @Override
        public void run() {
            ByteBuffer[] codecInputBuffers = audioEncoder.getInputBuffers();
            ByteBuffer[] codecOutputBuffers = audioEncoder.getOutputBuffers();
            MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();
            mAudioRecord.startRecording();

            if (!mMuxerStarted || audioTrackIndex == -1)
                Log.d(TAG, "mMuxerStarted: " + mMuxerStarted + ", audioTrackIndex: " + audioTrackIndex);

            while (audioRecording) {
                int bufferIndex = audioEncoder.dequeueInputBuffer(10000);
                if (bufferIndex >= 0) {
                    ByteBuffer dstBuf = codecInputBuffers[bufferIndex];
                    int bytesRead = mAudioRecord.read(dstBuf, dstBuf.capacity());
                    if (bytesRead == AudioRecord.ERROR_BAD_VALUE || bytesRead == AudioRecord.ERROR_INVALID_OPERATION)
                        Log.e(TAG, "Error reading from microphone.");
                    else {
                        if (bytesRead >= 0) {
                            audioEncoder.queueInputBuffer(bufferIndex, 0, bytesRead, System.nanoTime() / 1000, 0);
                        }
                    }
                }

                int encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000);
                while (encoderStatus >= 0) {
                    ByteBuffer encodedData = codecOutputBuffers[encoderStatus];
                    if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && audioBufferInfo.size != 0) {
                        audioEncoder.releaseOutputBuffer(encoderStatus, false);
                    }
                    else {
                        try {
                            // Log.d("TAG", "buffer size: " +  encodedData.remaining());
                            mMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo);
                            audioEncoder.releaseOutputBuffer(encoderStatus, false);
                        }
                        catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 0);
                    if (!audioRecording)
                        break;
                }
            }
        }
    }
}
