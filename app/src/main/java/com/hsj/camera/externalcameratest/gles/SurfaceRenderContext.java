package com.hsj.camera.externalcameratest.gles;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLSurfaceView;
import android.view.Surface;

public class SurfaceRenderContext {
    public static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private final int width, height;

    private final EGLDisplay eglDisplay;
    private final EGLContext eglContext;
    private final EGLSurface eglSurface;

    public SurfaceRenderContext(Surface surface, int width, int height, EGLContext sharedContext) {
        this.width = width;
        this.height = height;

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if(eglDisplay == EGL14.EGL_NO_DISPLAY)
            throw new RuntimeException("EGL14.EGL_NO_DISPLAY");

        int[] version = {-1, -1};
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        EGLConfig eglConfig = chooseConfig(eglDisplay, surface != null);

        if(sharedContext == null)
            sharedContext = EGL14.EGL_NO_CONTEXT;
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedContext,
                new int[]{
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                        EGL14.EGL_NONE
                }, 0);
        if(eglContext == EGL14.EGL_NO_CONTEXT)
            throw new RuntimeException("EGL14.EGL_NO_CONTEXT");

        if(surface != null) {
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, new int[]{
                    EGL14.EGL_NONE
            }, 0);
        }
        else {
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, new int[]{
                    EGL14.EGL_WIDTH, width,
                    EGL14.EGL_HEIGHT, height,
                    EGL14.EGL_NONE
            }, 0);
        }
        if(eglSurface == EGL14.EGL_NO_SURFACE)
            throw new RuntimeException("EGL14.EGL_NO_SURFACE");

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private EGLConfig chooseConfig(EGLDisplay display, boolean renderable) {
        int[] attributes;
        if(renderable) {
            attributes = new int[]{
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, EGL14.EGL_TRUE,
                    EGL14.EGL_NONE
            };
        }
        else {
            attributes = new int[]{
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
        }

        int[] num = {-1};
        EGLConfig[] configs = new EGLConfig[1];
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, num, 0);

        return configs[0];
    }

    public void setRenderer(GLSurfaceView.Renderer renderer) {

        renderer.onSurfaceCreated(null, null);
        renderer.onSurfaceChanged(null, width, height);
    }

    public EGLContext getEglContext() {
        return eglContext;
    }

    public void destroy() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglTerminate(eglDisplay);
    }

    public void swapBuffers(long presentationTimeNs) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }
}
