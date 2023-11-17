package com.hsj.camera.externalcameratest;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import com.hsj.camera.externalcameratest.encoder.TextureMovieEncoder;
import java.io.File;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public class CamView extends GLSurfaceView {
	private Context context;
	private CamRender mRender = null;
	private boolean mRecordingEnabled;
	// this is static so it survives activity restarts
	private static TextureMovieEncoder sVideoEncoder;

	public CamView(Context context) {
		super(context);
		this.context = context;
		init();
	}

	public CamView(Context context, AttributeSet attrs) {
		super(context, attrs);
		this.context = context;
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
		// Define a handler that receives camera-control messages from other threads.  All calls
		// to Camera must be made on the same thread.  Note we create this before the renderer
		// thread, so we know the fully-constructed object will be visible.
		sVideoEncoder = new TextureMovieEncoder();
		mRecordingEnabled = sVideoEncoder.isRecording();
		if (mRecordingEnabled) {
			sVideoEncoder.stopRecording();
			mRecordingEnabled = false;
		}
		setEGLContextFactory(new ContextFactory());
		setEGLContextClientVersion(2);
		setEGLConfigChooser(new ConfigChooser(5, 6, 5, 0, 0, 0));
		if (null == mRender)
			mRender = new CamRender(sVideoEncoder);
		setRenderer(mRender);
		setRenderMode(RENDERMODE_WHEN_DIRTY);
	}

	public void changeRecordingState(File videoFile, int frameRate) {
		mRender.changeRecordingState(videoFile, frameRate, true);
	}

	public void changeRecordingState(boolean state) {
		mRender.changeRecordingState(state);
	}


	private static class ContextFactory implements EGLContextFactory {
		public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig eglConfig) {
			int[] attrib = {0x3098, 2, EGL10.EGL_NONE};
			return egl.eglCreateContext(display, eglConfig, EGL10.EGL_NO_CONTEXT, attrib);
		}

		public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
			egl.eglDestroyContext(display, context);
		}
	}

	private static class ConfigChooser implements EGLConfigChooser {
		ConfigChooser(int r, int g, int b, int a, int depth, int stencil) {
			mRedSize = r;
			mGreenSize = g;
			mBlueSize = b;
			mAlphaSize = a;
			mDepthSize = depth;
			mStencilSize = stencil;
		}

		private static final int[] s_configAttribs2 = {EGL10.EGL_RED_SIZE, 4, EGL10.EGL_GREEN_SIZE, 4, EGL10.EGL_BLUE_SIZE, 4, EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE};

		public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
			int[] num_config = new int[1];
			egl.eglChooseConfig(display, s_configAttribs2, null, 0, num_config);
			int numConfigs = num_config[0];
			if (numConfigs <= 0)
				throw new IllegalArgumentException("No configs match configSpec");
			EGLConfig[] configs = new EGLConfig[numConfigs];
			egl.eglChooseConfig(display, s_configAttribs2, configs, numConfigs, num_config);
			return chooseConfig(egl, display, configs);
		}

		EGLConfig chooseConfig(EGL10 egl, EGLDisplay display, EGLConfig[] configs) {
			for (EGLConfig config : configs) {
				int d = findConfigAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE);
				int s = findConfigAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE);
				if (d < mDepthSize || s < mStencilSize)
					continue;
				int r = findConfigAttrib(egl, display, config, EGL10.EGL_RED_SIZE);
				int g = findConfigAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE);
				int b = findConfigAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE);
				int a = findConfigAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE);

				if (r == mRedSize && g == mGreenSize && b == mBlueSize && a == mAlphaSize)
					return config;
			}
			return null;
		}

		private int findConfigAttrib(EGL10 egl, EGLDisplay display, EGLConfig config, int attribute) {
			if (egl.eglGetConfigAttrib(display, config, attribute, mValue))
				return mValue[0];
			return 0;
		}

		int mRedSize;
		int mGreenSize;
		int mBlueSize;
		int mAlphaSize;
		int mDepthSize;
		int mStencilSize;
		private final int[] mValue = new int[1];
	}

	@Override
	public void onPause() {
		super.onPause();
		mRender.notifyPausing();
	}


}
