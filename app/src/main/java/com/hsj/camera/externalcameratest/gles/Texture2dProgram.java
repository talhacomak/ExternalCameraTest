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

package com.hsj.camera.externalcameratest.gles;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.opengles.GL10;

/**
 * GL program and supporting functions for textured 2D shapes.
 */
public class Texture2dProgram {
    private static final String TAG = GlUtil.TAG;
    private static final int FLOAT_BYTE_LENGTH = 4;

    public enum ProgramType {
        TEXTURE_2D, TEXTURE_EXT, TEXTURE_EXT_BW, TEXTURE_EXT_FILT, TEXTURE_EXT_NEG, TEXTURE_EXT_BW_NEG, TOUPCAM_DEF, TEXTURE_TOUPCAM_NEGATIVE
    }

    // Simple vertex shader, used for all programs.
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    // Simple fragment shader for use with "normal" 2D textures.
    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // Simple fragment shader for use with external 2D textures (e.g. what we get from
    // SurfaceTexture).
    private static final String FRAGMENT_SHADER_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // Fragment shader that converts color to black & white with a simple transformation.
    private static final String FRAGMENT_SHADER_EXT_BW =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    vec4 tc = texture2D(sTexture, vTextureCoord);\n" +
            "    float color = tc.r * 0.3 + tc.g * 0.59 + tc.b * 0.11;\n" +
            "    gl_FragColor = vec4(color, color, color, 1.0);\n" +
            "}\n";

    // Fragment shader that converts color to negative with a simple transformation.
    private static final String FRAGMENT_SHADER_EXT_NEGATIVE =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTextureCoord;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "void main() {\n"
            + "  vec4 color = texture2D(sTexture, vTextureCoord);\n"
            + "  float colorR = (1.0 - color.r) / 1.0;\n"
            + "  float colorG = (1.0 - color.g) / 1.0;\n"
            + "  float colorB = (1.0 - color.b) / 1.0;\n"
            + "  gl_FragColor = vec4(colorR, colorG, colorB, color.a);\n"
            + "}\n";

    // Fragment shader that converts color to black & white & negative with a simple transformation.
    private static final String FRAGMENT_SHADER_EXT_BW_NEG =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTextureCoord;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "void main() {\n"
            + "  vec4 tc = texture2D(sTexture, vTextureCoord);\n"
            + "  float color = tc.r * 0.3 + tc.g * 0.59 + tc.b * 0.11;\n"
            + "  tc = vec4(color, color, color, 1.0);\n"
            + "  float colorR = (1.0 - tc.r) / 1.0;\n"
            + "  float colorG = (1.0 - tc.g) / 1.0;\n"
            + "  float colorB = (1.0 - tc.b) / 1.0;\n"
            + "  gl_FragColor = vec4(colorR, colorG, colorB, tc.a);\n"
            + "}\n";

    // Fragment shader with a convolution filter.  The upper-left half will be drawn normally,
    // the lower-right half will have the filter applied, and a thin red line will be drawn
    // at the border.
    //
    // This is not optimized for performance.  Some things that might make this faster:
    // - Remove the conditionals.  They're used to present a half & half view with a red
    //   stripe across the middle, but that's only useful for a demo.
    // - Unroll the loop.  Ideally the compiler does this for you when it's beneficial.
    // - Bake the filter kernel into the shader, instead of passing it through a uniform
    //   array.  That, combined with loop unrolling, should reduce memory accesses.
    public static final int KERNEL_SIZE = 9;
    private static final String FRAGMENT_SHADER_EXT_FILT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#define KERNEL_SIZE " + KERNEL_SIZE + "\n" +
            "precision highp float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform float uKernel[KERNEL_SIZE];\n" +
            "uniform vec2 uTexOffset[KERNEL_SIZE];\n" +
            "uniform float uColorAdjust;\n" +
            "void main() {\n" +
            "    int i = 0;\n" +
            "    vec4 sum = vec4(0.0);\n" +
            "    if (vTextureCoord.x < vTextureCoord.y - 0.005) {\n" +
            "        for (i = 0; i < KERNEL_SIZE; i++) {\n" +
            "            vec4 texc = texture2D(sTexture, vTextureCoord + uTexOffset[i]);\n" +
            "            sum += texc * uKernel[i];\n" +
            "        }\n" +
            "    sum += uColorAdjust;\n" +
            "    } else if (vTextureCoord.x > vTextureCoord.y + 0.005) {\n" +
            "        sum = texture2D(sTexture, vTextureCoord);\n" +
            "    } else {\n" +
            "        sum.r = 1.0;\n" +
            "    }\n" +
            "    gl_FragColor = sum;\n" +
            "}\n";

    private final ProgramType mProgramType;

    // Handles to the GL program and various components of it.
    private int mProgramHandle;
    /*private final int muMVPMatrixLoc;
    private final int muTexMatrixLoc;
    private int muKernelLoc;
    private final int muTexOffsetLoc;
    private final int muColorAdjustLoc;*/
    private final int maPositionLoc;
    private final int maTextureCoordLoc;

    private final int mTextureTarget;

    private final float[] mKernel = new float[KERNEL_SIZE];
    private float[] mTexOffset;
    private float mColorAdjust;
    private int mCurrentTextureId;
    private final float[] mVertices = {-1.f, -1.f, 0.f, 0.f, 1.f, 1.f, -1.f, 0.f, 1.f, 1.f, -1.f, 1.f, 0.f, 0.f, 0.f, 1.f, 1.f, 0.f, 1.f, 0.f};
    private FloatBuffer mVertexBuffer;
    private String mVertexShader =
            "attribute vec4 position; " +
            "attribute vec2 TexCoordIn; " +
            "varying vec2 TexCoordOut;" +
            "void main() { " +
            "	gl_Position = position; " +
            "	TexCoordOut = TexCoordIn;" +
            "}";

    private String mFragmentShader =
            "uniform bool bDrawWindow; " +
            "precision mediump float; varying vec2 TexCoordOut;" +
            "uniform vec4 vColor; " +
            "uniform sampler2D texture1; void main() { " +
            " 	if (bDrawWindow) gl_FragColor = vec4(1,1,1,0); " +
            "	else gl_FragColor = texture2D(texture1, TexCoordOut); " +
            "}";

    private String mFragmentShaderNegative =
            "#extension GL_OES_EGL_image_external : require\n"
            + "uniform bool bDrawWindow;\n"
            + "precision mediump float;\n"
            + "varying vec2 TexCoordOut;\n"
            + "uniform vec4 vColor;\n"
            + "uniform samplerExternalOES texture1;\n"
            + "void main() {\n"
            + "  if (bDrawWindow) gl_FragColor = vec4(1,1,1,0);\n"
            + "  else {\n"
            + "     uniform vec4 color = texture2D(texture1, TexCoordOut);\n"
            + "     float colorR = (1.0 - color.r) / 1.0;\n"
            + "     float colorG = (1.0 - color.g) / 1.0;\n"
            + "     float colorB = (1.0 - color.b) / 1.0;\n"
            + "     gl_FragColor = vec4(colorR, colorG, colorB, color.a);"
            + "  }\n"
            + "}\n";

    private int createProgram(String mVertexShader, String fragmentShader) {
        final int program = GLES20.glCreateProgram();
        final int vShader = getShader(GLES20.GL_VERTEX_SHADER, mVertexShader);
        final int fShader = getShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        GLES20.glAttachShader(program, vShader);
        GLES20.glAttachShader(program, fShader);
        GLES20.glLinkProgram(program);
        GLES20.glUseProgram(program);
        return program;
    }

    private int getShader(int type, String shaderSource) {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderSource);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        return shader;
    }

    public int getTextureId(){
        return mCurrentTextureId;
    }

    /**
     * Prepares the program in the current EGL context.
     */
    public Texture2dProgram(ProgramType programType) {
        mProgramType = programType;

        switch (programType) {
            case TOUPCAM_DEF:
                mTextureTarget = GLES20.GL_TEXTURE_2D;
                mProgramHandle = createProgram(mVertexShader, mFragmentShader);
                break;
            case TEXTURE_TOUPCAM_NEGATIVE:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = createProgram(mVertexShader, mFragmentShaderNegative);
                break;
            case TEXTURE_2D:
                mTextureTarget = GLES20.GL_TEXTURE_2D;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
                break;
            case TEXTURE_EXT:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT);
                break;
            case TEXTURE_EXT_BW:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_BW);
                break;
            case TEXTURE_EXT_NEG:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_NEGATIVE);
                break;
            case TEXTURE_EXT_BW_NEG:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_BW_NEG);
                break;
            case TEXTURE_EXT_FILT:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_FILT);
                break;
            default:
                throw new RuntimeException("Unhandled type " + programType);
        }
        if (mProgramHandle == 0) {
            throw new RuntimeException("Unable to create program");
        }
        Log.d(TAG, "Created program " + mProgramHandle + " (" + programType + ")");

        // get locations of attributes and uniforms

        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "position");
        maTextureCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "TexCoordIn");
        GLES20.glDisable(GL10.GL_CULL_FACE);
        mVertexBuffer = ByteBuffer.allocateDirect(mVertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVertexBuffer.put(mVertices);
        mVertexBuffer.position(0);
        GenTexture();
    }

    public int GenTexture() {
        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        mCurrentTextureId = textures[0];
        return mCurrentTextureId;
    }

    /**
     * Releases the program.
     * <p>
     * The appropriate EGL context must be current (i.e. the one that was used to create
     * the program).
     */
    public void release() {
        Log.d(TAG, "deleting program " + mProgramHandle);
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;
    }

    /**
     * Returns the program type.
     */
    public ProgramType getProgramType() {
        return mProgramType;
    }


    public int getProgramHandle(){
        return mProgramHandle;
    }

    /**
     * Configures the convolution filter values.
     *
     * @param values Normalized filter values; must be KERNEL_SIZE elements.
     */
    public void setKernel(float[] values, float colorAdj) {
        if (values.length != KERNEL_SIZE) {
            throw new IllegalArgumentException("Kernel size is " + values.length +
                    " vs. " + KERNEL_SIZE);
        }
        System.arraycopy(values, 0, mKernel, 0, KERNEL_SIZE);
        mColorAdjust = colorAdj;
        //Log.d(TAG, "filt kernel: " + Arrays.toString(mKernel) + ", adj=" + colorAdj);
    }

    /**
     * Sets the size of the texture.  This is used to find adjacent texels when filtering.
     */
    public void setTexSize(int width, int height) {
        float rw = 1.0f / width;
        float rh = 1.0f / height;

        // Don't need to create a new array here, but it's syntactically convenient.
        mTexOffset = new float[] {
            -rw, -rh,   0f, -rh,    rw, -rh,
            -rw, 0f,    0f, 0f,     rw, 0f,
            -rw, rh,    0f, rh,     rw, rh
        };
        //Log.d(TAG, "filt size: " + width + "x" + height + ": " + Arrays.toString(mTexOffset));
    }

    public void preDraw(){
        GLES20.glClearColor(0.1490196f, 0.1490196f, 0.1490196f, 0.f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgramHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mCurrentTextureId);
    }

    public void draw(float[] mvpMatrix, FloatBuffer vertexBuffer, int firstVertex,
            int vertexCount, int coordsPerVertex, int vertexStride,
            float[] texMatrix, FloatBuffer texBuffer, int texStride) {
        GLES20.glUniform1i(GLES20.glGetUniformLocation(mProgramHandle, "bDrawWindow"), 0);
        mVertexBuffer.position(0);
        mVertexBuffer.put(mVertices);
        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(maPositionLoc, 3, GLES20.GL_FLOAT, false, 5 * FLOAT_BYTE_LENGTH, mVertexBuffer);
        mVertexBuffer.position(3);
        GLES20.glVertexAttribPointer(maTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 5 * FLOAT_BYTE_LENGTH, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(maPositionLoc);
        GLES20.glEnableVertexAttribArray(maTextureCoordLoc);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
}
