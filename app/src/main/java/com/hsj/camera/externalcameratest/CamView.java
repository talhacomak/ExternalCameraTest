package com.hsj.camera.externalcameratest;

import android.content.Context;
import android.media.MediaRecorder;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import com.hsj.camera.externalcameratest.gles.SurfaceRenderContext;

import java.io.File;
import java.io.IOException;

public class CamView extends SurfaceView {
	private CamRender mRender;
	private MediaRecorder mediaRecorder;

	private SurfaceRenderContext baseContext; // Only used as a connection between the other contexts
	private SurfaceRenderContext previewContext; // Used to draw the preview
	private SurfaceRenderContext recorderContext; // Used to draw to the encoder surface

	public CamView(Context context) {
		super(context);
		init();
	}

	public CamView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	@Override
	public void scrollTo(int x, int y) {
		if (x < 0) x = 0;
		else {
			int maxWidth = computeHorizontalScrollRange() - getWidth();
			if (maxWidth < 0) x = 0;
			else if (x > maxWidth) x = maxWidth;
		}

		if (y < 0) y = 0;
		else {
			int maxHeight = computeVerticalScrollRange() - getHeight();
			if (maxHeight < 0) y = 0;
			if (y > maxHeight) y = maxHeight;
		}

		super.scrollTo(x, y);
	}

	private void init() {
		baseContext = new SurfaceRenderContext(null, 1, 1, null);

		getHolder().addCallback(new SurfaceHolder.Callback() {
			@Override
			public void surfaceCreated(@NonNull SurfaceHolder holder) {

			}

			@Override
			public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
				if(previewContext != null)
					previewContext.destroy();
				previewContext = new SurfaceRenderContext(holder.getSurface(), width, height, baseContext.getEglContext());
			}

			@Override
			public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
				previewContext.destroy();
				previewContext = null;
			}
		});

		mRender = new CamRender();
		baseContext.makeCurrent();
		mRender.onSurfaceCreated(null, null);
	}

	private void makeMediaRecorder(File videoFile, int frameRate, int width, int height) {
		mediaRecorder = new MediaRecorder();

		mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
		mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

		mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

		mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT);
		mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);

		mediaRecorder.setVideoSize(width, height);
		mediaRecorder.setVideoEncodingBitRate(300000);
		mediaRecorder.setVideoFrameRate(frameRate);

		mediaRecorder.setOutputFile(videoFile);
		try {
			mediaRecorder.prepare();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		recorderContext = new SurfaceRenderContext(mediaRecorder.getSurface(), width, height, baseContext.getEglContext());
	}

	private void destroyMediaRecorder() {
		mediaRecorder.release();
		mediaRecorder = null;
		recorderContext.destroy();
	}

	public void changeRecordingState(File videoFile, int frameRate, int width, int height) {
		if(mediaRecorder != null)
			destroyMediaRecorder();
		makeMediaRecorder(videoFile, frameRate, width, height);

		mediaRecorder.start();
	}

	public void changeRecordingState(boolean state) {
		if(state)
			mediaRecorder.start();
		else
			mediaRecorder.stop();
	}

	int frame = 0;
	public void onFrameAvailable() {
		mRender.setFrame(frame);

		if(previewContext != null) {
			previewContext.makeCurrent();
			mRender.onDrawFrame(null);
			previewContext.swapBuffers();
		}

		if(recorderContext != null) {
			recorderContext.makeCurrent();
			mRender.onDrawFrame(null);
			recorderContext.swapBuffers();
		}

		frame++;
		if (frame == 60)
			frame = 0;
	}

}
