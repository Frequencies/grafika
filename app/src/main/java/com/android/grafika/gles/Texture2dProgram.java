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

package com.android.grafika.gles;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.FloatBuffer;

public class Texture2dProgram {
    private static final String TAG = GlUtil.TAG;

    public enum ProgramType {
        TEXTURE_2D, TEXTURE_EXT, TEXTURE_EXT_BW, TEXTURE_EXT_FILT
    }

    private static final String VERTEX_SHADER =
                    "uniform mat4 uTexMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
                    "}\n";

    // MODIFIED: Circle mask applied for TEXTURE_2D
    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "uniform vec2 uVideoSize;\n" +
                    "void main() {\n" +
                    "    vec2 center = vec2(0.5, 0.5);\n" +
                    "    float dist = distance(vTexCoord, center);\n" +
                    "    if (dist > 0.5) {\n" +
                    "        discard;\n" +
                    "    }\n" +
                    "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +



                    /*"vec2 uv = vTexCoord;\n" +
                    "                vec2 center = vec2(0.5, 0.5);\n" +
                    "                float radius = 0.5;\n" +
                    "                float dist = distance(uv, center);\n" +
                    "                if (dist > radius) {\n" +
                    "                    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);\n" +
                    "                    return;\n" +
                    "                }\n" +
                    "                float videoAspect = uVideoSize.x / uVideoSize.y;\n" +
                    "                float viewAspect = 1.0;\n" +
                    "                vec2 adjustedUv = uv - 0.5;\n" +
                    "                if (videoAspect > viewAspect) {\n" +
                    "                    adjustedUv.x *= (viewAspect / videoAspect);\n" +
                    "                } else {\n" +
                    "                    adjustedUv.y *= (videoAspect / viewAspect);\n" +
                    "                }\n" +
                    "                adjustedUv += 0.5;\n" +
                    "                if (adjustedUv.x < 0.0 || adjustedUv.x > 1.0 || adjustedUv.y < 0.0 || adjustedUv.y > 1.0) {\n" +
                    "                    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);\n" +
                    "                } else {\n" +
                    "                    gl_FragColor = texture2D(sTexture, adjustedUv);\n" +
                    "                }" +*/
                    "}\n";

    private static final String FRAGMENT_SHADER_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_EXT_BW =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    vec4 tc = texture2D(sTexture, vTexCoord);\n" +
                    "    float color = tc.r * 0.3 + tc.g * 0.59 + tc.b * 0.11;\n" +
                    "    gl_FragColor = vec4(color, color, color, 1.0);\n" +
                    "}\n";

    public static final int KERNEL_SIZE = 9;
    private static final String FRAGMENT_SHADER_EXT_FILT =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "#define KERNEL_SIZE " + KERNEL_SIZE + "\n" +
                    "precision highp float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "uniform float uKernel[KERNEL_SIZE];\n" +
                    "uniform vec2 uTexOffset[KERNEL_SIZE];\n" +
                    "uniform float uColorAdjust;\n" +
                    "uniform vec2 uVideoSize;;\n" +
                    "void main() {\n" +
                    /*"    int i = 0;\n" +
                    "    vec4 sum = vec4(0.0);\n" +
                    "    if (vTexCoord.x < vTexCoord.y - 0.005) {\n" +
                    "        for (i = 0; i < KERNEL_SIZE; i++) {\n" +
                    "            vec4 texc = texture2D(sTexture, vTexCoord + uTexOffset[i]);\n" +
                    "            sum += texc * uKernel[i];\n" +
                    "        }\n" +
                    "        sum += uColorAdjust;\n" +
                    "    } else if (vTexCoord.x > vTexCoord.y + 0.005) {\n" +
                    "        sum = texture2D(sTexture, vTexCoord);\n" +
                    "    } else {\n" +
                    "        sum.r = 1.0;\n" +
                    "    }\n" +
                    "    gl_FragColor = sum;\n" +*/






                    "vec2 uv = vTexCoord;\n" +
                    "                vec2 center = vec2(0.5, 0.5);\n" +
                    "                float radius = 0.5;\n" +
                    "                float dist = distance(uv, center);\n" +
                    "                if (dist > radius) {\n" +
                    "                    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);\n" +
                    "                    return;\n" +
                    "                }\n" +
                    "                float videoAspect = uVideoSize.x / uVideoSize.y;\n" +
                    "                float viewAspect = 1.0; // квадратный viewport\n" +
                    "                vec2 adjustedUv = uv - 0.5; // смещаем в центр\n" +
                    "                if (videoAspect > viewAspect) {\n" +
                    "                    // Видео шире - сжимаем по X\n" +
                    "                    adjustedUv.x *= (viewAspect / videoAspect);\n" +
                    "                } else {\n" +
                    "                    // Видео уже - сжимаем по Y\n" +
                    "                    adjustedUv.y *= (videoAspect / viewAspect);\n" +
                    "                }\n" +
                    "                adjustedUv += 0.5; // возвращаем на место\n" +
                    "                if (adjustedUv.x < 0.0 || adjustedUv.x > 1.0 || adjustedUv.y < 0.0 || adjustedUv.y > 1.0) {\n" +
                    "                    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);\n" +
                    "                } else {\n" +
                    "                    gl_FragColor = texture2D(sTexture, adjustedUv);\n" +
                    "                }" +
                    "}\n";

    private ProgramType mProgramType;
    private int mProgramHandle;
    private int muTexMatrixLoc;
    private int muKernelLoc;

    private int muVideoSize;
    private int muTexOffsetLoc;
    private int muColorAdjustLoc;
    private int maPositionLoc;
    private int maTextureCoordLoc;
    private int mTextureTarget;

    private float[] mKernel = new float[KERNEL_SIZE];
    private float[] mTexOffset;
    private float mColorAdjust;

    public Texture2dProgram(ProgramType programType) {
        mProgramType = programType;

        switch (programType) {
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

        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
        GlUtil.checkLocation(maPositionLoc, "aPosition");
        maTextureCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "aTexCoord");
        GlUtil.checkLocation(maTextureCoordLoc, "aTexCoord");
        muTexMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix");
        GlUtil.checkLocation(muTexMatrixLoc, "uTexMatrix");
        muKernelLoc = GLES20.glGetUniformLocation(mProgramHandle, "uKernel");
        muVideoSize = GLES20.glGetUniformLocation(mProgramHandle, "uKernel");

        if (muKernelLoc < 0) {
            muKernelLoc = -1;
            muTexOffsetLoc = -1;
            muColorAdjustLoc = -1;
        } else {
            muTexOffsetLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexOffset");
            GlUtil.checkLocation(muTexOffsetLoc, "uTexOffset");
            muColorAdjustLoc = GLES20.glGetUniformLocation(mProgramHandle, "uColorAdjust");
            GlUtil.checkLocation(muColorAdjustLoc, "uColorAdjust");

            setKernel(new float[] {0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f}, 0f);
            setTexSize(256, 256);
        }
    }

    public void release() {
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;
    }

    public ProgramType getProgramType() {
        return mProgramType;
    }

    public int createTextureObject() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int texId = textures[0];
        GLES20.glBindTexture(mTextureTarget, texId);

        GLES20.glTexParameterf(mTextureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(mTextureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(mTextureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(mTextureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        return texId;
    }

    public void setKernel(float[] values, float colorAdj) {
        if (values.length != KERNEL_SIZE) {
            throw new IllegalArgumentException("Kernel size is " + values.length + " vs. " + KERNEL_SIZE);
        }
        System.arraycopy(values, 0, mKernel, 0, KERNEL_SIZE);
        mColorAdjust = colorAdj;
    }

    public void setTexSize(int width, int height) {
        float rw = 1.0f / width;
        float rh = 1.0f / height;

        mTexOffset = new float[] {
                -rw, -rh,  0f, -rh,  rw, -rh,
                -rw, 0f,   0f, 0f,   rw, 0f,
                -rw, rh,   0f, rh,   rw, rh
        };
    }

    public void draw(float[] mvpMatrix, FloatBuffer vertexBuffer, int firstVertex,
                     int vertexCount, int coordsPerVertex, int vertexStride,
                     float[] texMatrix, FloatBuffer texBuffer, int textureId, int texStride, int videoWidth, int videoHeight) {
        GLES20.glUseProgram(mProgramHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(mTextureTarget, textureId);

        GLES20.glUniformMatrix4fv(muTexMatrixLoc, 1, false, texMatrix, 0);
        Log.d("MyLog", "GLES20.glUniform2f(muVideoSize, videoWidth, videoHeight) videoWidth = "+videoWidth+", videoHeight = "+videoHeight);
        GLES20.glUniform2f(muVideoSize, videoWidth, videoHeight);

        GLES20.glEnableVertexAttribArray(maPositionLoc);
        GLES20.glVertexAttribPointer(maPositionLoc, coordsPerVertex, GLES20.GL_FLOAT, false, vertexStride, vertexBuffer);

        GLES20.glEnableVertexAttribArray(maTextureCoordLoc);
        GLES20.glVertexAttribPointer(maTextureCoordLoc, 2, GLES20.GL_FLOAT, false, texStride, texBuffer);

        if (muKernelLoc >= 0) {
            GLES20.glUniform1fv(muKernelLoc, KERNEL_SIZE, mKernel, 0);
            GLES20.glUniform2fv(muTexOffsetLoc, KERNEL_SIZE, mTexOffset, 0);
            GLES20.glUniform1f(muColorAdjustLoc, mColorAdjust);
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, firstVertex, vertexCount);

        GLES20.glDisableVertexAttribArray(maPositionLoc);
        GLES20.glDisableVertexAttribArray(maTextureCoordLoc);
        GLES20.glBindTexture(mTextureTarget, 0);
        GLES20.glUseProgram(0);
    }
}
