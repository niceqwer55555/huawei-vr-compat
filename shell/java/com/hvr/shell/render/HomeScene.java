package com.hvr.shell.render;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * VR 主界面：一块贴在眼前 2 米处的面板，内容是 {@link Bitmap}（由 Canvas 画出来的
 * 文件列表与状态），双目各画一次，靠 {@link StereoRenderer} 的 IPD 偏移产生景深。
 *
 * 为什么用位图而不是 3D 文字：位图方案只需要一次 {@code Canvas.drawText}，
 * 换内容就是换一张图，不用引入字形图集、也不用为中文找字体文件。
 * 代价是文字是「印在面板上」的，不具备 3D 文字的遮挡关系——对这种平铺面板正合适。
 */
public final class HomeScene implements Renderable {

    /** 面板物理尺寸（米）。2 米处 1.6x0.9 大约占 45° 视场，读小字不累。 */
    private static final float PANEL_WIDTH = 1.6f;
    private static final float PANEL_HEIGHT = 0.9f;
    private static final float PANEL_DISTANCE = 2.0f;

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n"
                    + "attribute vec4 aPosition;\n"
                    + "attribute vec2 aUv;\n"
                    + "varying vec2 vUv;\n"
                    + "void main() {\n"
                    + "    vUv = aUv;\n"
                    + "    gl_Position = uMvp * aPosition;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "varying vec2 vUv;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(uTexture, vUv);\n"
                    + "}\n";

    private final float[] model = new float[16];
    private final float[] mvp = new float[16];

    private int program;
    private int aPosition;
    private int aUv;
    private int uMvp;
    private int uTexture;
    private int texture;
    private FloatBuffer vertexBuffer;
    private FloatBuffer uvBuffer;

    /** 由界面线程写入，GL 线程消费。 */
    private Bitmap pending;
    private boolean uploadPending;

    public HomeScene() {
        // 面板正对 -Z，中心在眼前 PANEL_DISTANCE 处。
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, 0f, 0f, -PANEL_DISTANCE);
    }

    /** 换内容。可以在任意线程调用，真正的上传发生在下一帧。 */
    public void setContent(Bitmap bitmap) {
        synchronized (this) {
            if (pending != null) {
                // 上一张还没被 GL 线程接手就又被替换了，直接回收旧的，避免泄漏。
                pending.recycle();
            }
            pending = bitmap;
            uploadPending = bitmap != null;
        }
    }

    @Override
    public void init() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aUv = GLES20.glGetAttribLocation(program, "aUv");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uTexture = GLES20.glGetUniformLocation(program, "uTexture");

        float halfW = PANEL_WIDTH / 2f;
        float halfH = PANEL_HEIGHT / 2f;
        // 三角带顺序：左下、右下、左上、右上。
        vertexBuffer = floats(new float[]{
                -halfW, -halfH, 0f,
                halfW, -halfH, 0f,
                -halfW, halfH, 0f,
                halfW, halfH, 0f,
        });
        uvBuffer = floats(new float[]{
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f,
        });

        int[] handle = new int[1];
        GLES20.glGenTextures(1, handle, 0);
        texture = handle[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    @Override
    public void draw(float[] viewProjection) {
        if (program == 0) {
            return;
        }
        uploadIfNeeded();
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(uTexture, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aUv);
    }

    private void uploadIfNeeded() {
        Bitmap bitmap;
        synchronized (this) {
            if (!uploadPending) {
                return;
            }
            bitmap = pending;
            pending = null;
            uploadPending = false;
        }
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        bitmap.recycle();
    }

    @Override
    public void release() {
        if (texture != 0) {
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            texture = 0;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private static int buildProgram(String vertex, String fragment) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            throw new IllegalStateException("链接着色器失败: "
                    + GLES20.glGetProgramInfoLog(program));
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            throw new IllegalStateException("编译着色器失败: "
                    + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
