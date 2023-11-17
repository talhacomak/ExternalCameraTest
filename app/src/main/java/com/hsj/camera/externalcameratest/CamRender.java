package com.hsj.camera.externalcameratest;

import static android.opengl.GLES10.GL_RGB;
import static android.opengl.GLES10.GL_TEXTURE_2D;
import static android.opengl.GLES10.GL_UNSIGNED_BYTE;
import static android.opengl.GLES10.glTexImage2D;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.hsj.camera.externalcameratest.encoder.TextureMovieEncoder;
import com.hsj.camera.externalcameratest.gles.FullFrameRect;
import com.hsj.camera.externalcameratest.gles.Texture2dProgram;

import java.io.File;
import java.nio.ByteBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class CamRender implements GLSurfaceView.Renderer {
	private int mCurrentTextureId;
	private final float[] mVertices = new float[16];

	private static final int RECORDING_OFF = 0;
	private static final int RECORDING_ON = 1;
	private static final int RECORDING_RESUMED = 2;
	private final TextureMovieEncoder mVideoEncoder;
	private File mOutputFile;
	private int mFrameRate;
	private FullFrameRect mFullScreen;
	private boolean mRecordingEnabled;
	private int mRecordingStatus;

	// width/height of the incoming camera preview frames
	private boolean mIncomingSizeUpdated;
	private int mIncomingWidth;
	private int mIncomingHeight;

	private int mCurrentFilter;
	private int mNewFilter;
	private EGLConfig mEGLConfig;
	private Texture2dProgram.ProgramType mProgramType = Texture2dProgram.ProgramType.TOUPCAM_DEF;

	private SurfaceTexture mSurfaceTexture;

	static final int FILTER_NONE = 0;
	static final int FILTER_BLACK_WHITE = 1;
	static final int FILTER_BLUR = 2;
	static final int FILTER_SHARPEN = 3;
	static final int FILTER_EDGE_DETECT = 4;
	static final int FILTER_EMBOSS = 5;
	static final int FILTER_NEGATIVE = 6;
	static final int FILTER_GRAYSC_NEGATIVE = 7;

	private byte[] testData1;
	private byte[] testData2;

	CamRender(TextureMovieEncoder movieEncoder) {
		mVideoEncoder = movieEncoder;

		mCurrentTextureId = -1;

		mRecordingStatus = -1;
		mRecordingEnabled = false;

		mIncomingSizeUpdated = false;
		mIncomingWidth = 2048;
		mIncomingHeight = 1080;

		// We could preserve the old filter mode, but currently not bothering.
		mCurrentFilter = -1;
		mNewFilter = FILTER_NONE;

		testData1 = new byte[1080*2048*3];
		for (int i=0; i<1080*2048*3; i++) {
			testData1[i] = (byte) 65;
		}

		testData2 = new byte[1080*2048*3];
		for (int i=0; i<1080*2048*3; i++) {
			testData2[i] = (byte) 222;
		}
	}

	// TODO mIncomingWidth & mIncomingHeight ?
	public void notifyPausing() {
		if (mSurfaceTexture != null) {
			mSurfaceTexture.release();
			mSurfaceTexture = null;
		}
		if (mFullScreen != null) {
			mFullScreen.release(false);     // assume the GLSurfaceView EGL context is about
			mFullScreen = null;             //  to be destroyed
		}
		mIncomingWidth = 2048;
		mIncomingHeight = 1080;
	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		mEGLConfig = config;
		mRecordingEnabled = mVideoEncoder.isRecording();
		if (mRecordingEnabled) {
			mRecordingStatus = RECORDING_RESUMED;
		} else {
			mRecordingStatus = RECORDING_OFF;
		}

		mFullScreen = new FullFrameRect(new Texture2dProgram(mProgramType));
		mCurrentTextureId = mFullScreen.getProgram().getTextureId();

		mSurfaceTexture = new SurfaceTexture(mCurrentTextureId);
	}

	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
		GLES20.glViewport(0, 0, width, height);
	}

	int counter = 0;
	@Override
	public void onDrawFrame(GL10 gl10) {
		if (mCurrentTextureId == 0)
			return;

		if (mIncomingWidth <= 0 || mIncomingHeight <= 0) {
			// Texture size isn't set yet.  This is only used for the filters, but to be
			// safe we can just skip drawing while we wait for the various races to resolve.
			// (This seems to happen if you toggle the screen off/on with power button.)
			Log.i("TAG", "Drawing before incoming texture size set; skipping");
			return;
		}

		if (mCurrentFilter != mNewFilter) {
			updateFilter();
			if (mRecordingEnabled){
				mVideoEncoder.setProgram(mProgramType);
				mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
			}
		}
		if (mIncomingSizeUpdated) {
			mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
			mIncomingSizeUpdated = false;
		}

		mFullScreen.preDrawFrame();

		// nativeLib.nativeUpdate(); // native function replaced with below function for test
		if (counter < 30)
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, mIncomingWidth, mIncomingHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, ByteBuffer.wrap(testData1));
		else
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, mIncomingWidth, mIncomingHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, ByteBuffer.wrap(testData2));

		counter ++;
		if (counter == 60)
			counter = 0;

		mSurfaceTexture.getTransformMatrix(mVertices);
		mFullScreen.drawFrame(mCurrentTextureId, mVertices);

		handleRecorder();
	}

	public void updateFilter() {
		float[] kernel = null;
		float colorAdj = 0.0f;

		switch (mNewFilter) {
			case FILTER_NONE:
				mProgramType = Texture2dProgram.ProgramType.TOUPCAM_DEF;
				break;
			case FILTER_BLACK_WHITE:
				// (In a previous version the TEXTURE_EXT_BW variant was enabled by a flag called
				// ROSE_COLORED_GLASSES, because the shader set the red channel to the B&W color+
				// and green/blue to zero.)
				mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW;
				break;
			case FILTER_BLUR:
				mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
				kernel = new float[] {
						1f/16f, 2f/16f, 1f/16f,
						2f/16f, 4f/16f, 2f/16f,
						1f/16f, 2f/16f, 1f/16f };
				break;
			case FILTER_SHARPEN:
				mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
				kernel = new float[] {
						0f, -1f, 0f,
						-1f, 5f, -1f,
						0f, -1f, 0f };
				break;
			case FILTER_EDGE_DETECT:
				mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
				kernel = new float[] {
						-1f, -1f, -1f,
						-1f, 8f, -1f,
						-1f, -1f, -1f };
				break;
			case FILTER_EMBOSS:
				mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
				kernel = new float[] {
						2f, 0f, 0f,
						0f, -1f, 0f,
						0f, 0f, -1f };
				colorAdj = 0.5f;
				break;
			case FILTER_NEGATIVE:
				mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT_NEG;
				break;

			case FILTER_GRAYSC_NEGATIVE:
				mProgramType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW_NEG;
				break;
			default:
				throw new RuntimeException("Unknown filter mode " + mNewFilter);
		}

		// Do we need a whole new program?  (We want to avoid doing this if we don't have
		// too -- compiling a program could be expensive.)
		if (mProgramType != mFullScreen.getProgram().getProgramType()) {
			mFullScreen.changeProgram(new Texture2dProgram(mProgramType));
			// If we created a new program, we need to initialize the texture width/height.
			mIncomingSizeUpdated = true;
		}

		// Update the filter kernel (if any).
		if (kernel != null) {
			mFullScreen.getProgram().setKernel(kernel, colorAdj);
		}

		mCurrentFilter = mNewFilter;
	}

	private void handleRecorder() {
		if (mRecordingEnabled) {
			switch (mRecordingStatus) {
				case RECORDING_OFF:
					mVideoEncoder.setProgram(mProgramType);
					int bitrate = 24 * mIncomingWidth * mIncomingHeight * mFrameRate;
					mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(mOutputFile,
							mIncomingWidth, mIncomingHeight, bitrate, mFrameRate, EGL14.eglGetCurrentContext()));
					mRecordingStatus = RECORDING_ON;
					break;
				case RECORDING_RESUMED:
					mVideoEncoder.setProgram(mProgramType);
					mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
					mRecordingStatus = RECORDING_ON;
					break;
				case RECORDING_ON:
					// yay
					break;
				default:
					throw new RuntimeException("unknown status " + mRecordingStatus);
			}
		} else {
			switch (mRecordingStatus) {
				case RECORDING_ON:
				case RECORDING_RESUMED:
					mVideoEncoder.stopRecording();
					mRecordingStatus = RECORDING_OFF;
					break;
				case RECORDING_OFF:
					// yay
					break;
				default:
					throw new RuntimeException("unknown status " + mRecordingStatus);
			}
		}

		// Set the video encoder's texture name.  We only need to do this once, but in the
		// current implementation it has to happen after the video encoder is started, so
		// we just do it here.
		//
		// TODO: be less lame.
		mVideoEncoder.setTextureId(mCurrentTextureId);

		// Tell the video encoder thread that a new frame is available.
		// This will be ignored if we're not actually recording.

		mVideoEncoder.frameAvailable(mSurfaceTexture); // mCameraHandler.getTimeStamp();
	}

	public void changeRecordingState(boolean isRecording) {
		mRecordingEnabled = isRecording;
	}

	public void changeRecordingState(File recFile, int frameRate, boolean isRecording) {
		mOutputFile = recFile;
		mRecordingEnabled = isRecording;
		mFrameRate = frameRate;
	}

	/**
	 * Changes the filter that we're applying to the camera preview.
	 */
	public void changeFilterMode(int filter) {
		mNewFilter = filter;
	}

	/**
	 * Records the size of the incoming camera preview frames.
	 * <p>
	 * It's not clear whether this is guaranteed to execute before or after onSurfaceCreated(),
	 * so we assume it could go either way.  (Fortunately they both run on the same thread,
	 * so we at least know that they won't execute concurrently.)
	 */
	public void setCameraPreviewSize(int width, int height) {
		mIncomingWidth = width;
		mIncomingHeight = height;
		mIncomingSizeUpdated = true;
	}

	public void GenTexture() {
		if (mFullScreen != null && mFullScreen.getProgram() != null)
			mFullScreen.getProgram().GenTexture();
	}

}
