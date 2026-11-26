package com.huawei.vrvirtualscreen.mirror;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 单路 MediaProjection 画面 → 横屏 SBS 双目（左右眼显示同一帧）。
 *
 * <p>眼镜物理面板 3200x1600（2:1 横屏），SBS 左右并排 → 每眼 1600x1600（1:1 正方形）。
 * 本 view 铺满面板，把 InputSurface 上那一路画面在 GL 里画两次：左眼 viewport 与右眼
 * viewport 采样**同一个 SurfaceTexture、同一帧、同一缩放**，因此左右眼内容严格一致。
 * 这正是用户要的「横屏 SBS、左右眼显示一样」——不是把一张画面从中间劈成左右各半，
 * 也不是带水平视差的真 3D。</p>
 *
 * <p>为什么不是两个 SurfaceView + 两个 VirtualDisplay：从 Android 13 起同一个
 * MediaProjection 创建多个 VirtualDisplay 时只有最后一个能出图，Android 14 更直接
 * 限制 token 复用；旧的 HvrMirrorService 就是这样，导致「双目画面左右眼内容不一致」
 * （PATCH_AUDIT 遗留问题）甚至只有一只眼有画面。正确做法是
 * <b>只建一个 VirtualDisplay → 一个 SurfaceTexture → GL 左右各画一次</b>。</p>
 *
 * <p>本类自包含（把 linkProgram 内联），不依赖 shell 模块的 GlUtil，因为 compat dex
 * 只编 shared/java 这一棵。</p>
 */
public final class HvrMirrorGlView extends GLSurfaceView
        implements SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "HvrMirrorGlView";

    /** GL 上下文就绪、可以把输入 Surface 交给 createVirtualDisplay 时回调。 */
    public interface InputSurfaceListener {
        void onInputSurface(Surface surface, int width, int height);
    }

    private static final String VERTEX_QUAD =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aUv;\n"
                    + "uniform vec2 uScale;\n"
                    + "varying vec2 vUv;\n"
                    + "void main() {\n"
                    + "    vUv = aUv;\n"
                    + "    gl_Position = vec4(aPosition * uScale, 0.0, 1.0);\n"
                    + "}\n";

    private static final String FRAGMENT_OES =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "varying vec2 vUv;\n"
                    + "void main() {\n"
                    + "    vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;\n"
                    + "    gl_FragColor = texture2D(uTexture, uv);\n"
                    + "}\n";

    private final Renderer renderer = new Renderer();

    /** 主线程写、GL 线程读。 */
    private volatile int contentWidth = 1;
    private volatile int contentHeight = 1;
    private volatile InputSurfaceListener listener;

    private int viewWidth = 1;
    private int viewHeight = 1;

    public HvrMirrorGlView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** 指定镜像画面尺寸（虚拟屏宽高，按手机比例），挂到窗口前调用。 */
    public void mirror(int width, int height, InputSurfaceListener listener) {
        contentWidth = Math.max(1, width);
        contentHeight = Math.max(1, height);
        this.listener = listener;
        requestRender();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        requestRender();
    }

    private void notifyInputSurface(Surface surface, int width, int height) {
        InputSurfaceListener current = listener;
        if (current == null || surface == null || !surface.isValid()) {
            return;
        }
        current.onInputSurface(surface, width, height);
    }

    private final class Renderer implements GLSurfaceView.Renderer {

        private int program;
        private int aPosition;
        private int aUv;
        private int uTexture;
        private int uTexMatrix;
        private int uScale;
        private FloatBuffer quad;

        private int textureId;
        private SurfaceTexture surfaceTexture;
        private Surface inputSurface;
        private final float[] texMatrix = new float[16];
        private boolean ready;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            releaseGl();
            GLES20.glClearColor(0f, 0f, 0f, 1f);

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setDefaultBufferSize(contentWidth, contentHeight);
            surfaceTexture.setOnFrameAvailableListener(HvrMirrorGlView.this);
            inputSurface = new Surface(surfaceTexture);

            program = linkProgram(VERTEX_QUAD, FRAGMENT_OES);
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aUv = GLES20.glGetAttribLocation(program, "aUv");
            uTexture = GLES20.glGetUniformLocation(program, "uTexture");
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uScale = GLES20.glGetUniformLocation(program, "uScale");

            float[] data = {
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
            };
            quad = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            quad.put(data).position(0);

            ready = true;
            Log.i(TAG, "GL ready, input " + contentWidth + "x" + contentHeight);
            post(new Runnable() {
                @Override
                public void run() {
                    notifyInputSurface(inputSurface, contentWidth, contentHeight);
                }
            });
            requestRender();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewWidth = Math.max(1, width);
            viewHeight = Math.max(1, height);
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
            Log.i(TAG, "panel view " + viewWidth + "x" + viewHeight
                    + ", eye " + (viewWidth / 2) + "x" + viewHeight);
            requestRender();
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (!ready || surfaceTexture == null) {
                return;
            }
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(texMatrix);
            } catch (Throwable error) {
                Log.w(TAG, "updateTexImage failed: " + error);
                return;
            }

            // 每眼面板格子 = viewWidth/2 x viewHeight（物理 1600x1600 正方形）。
            // 内容按自身比例居中（letterbox），左右两眼用完全相同的 scale。
            int eyeWidth = Math.max(1, viewWidth / 2);
            float eyeAspect = (float) eyeWidth / (float) viewHeight;
            float contentAspect = (float) contentWidth / (float) contentHeight;
            float scaleX = 1f;
            float scaleY = 1f;
            if (contentAspect > eyeAspect) {
                scaleY = eyeAspect / contentAspect;
            } else {
                scaleX = contentAspect / eyeAspect;
            }

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
            GLES20.glUniform2f(uScale, scaleX, scaleY);

            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glEnableVertexAttribArray(aUv);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad);
            quad.position(2);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);

            // 同一帧画两次：左眼与右眼采样相同纹理、相同矩阵、相同缩放 → 内容一致。
            for (int eye = 0; eye < 2; eye++) {
                GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, viewHeight);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aUv);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glViewport(0, 0, viewWidth, viewHeight);
        }

        private void releaseGl() {
            ready = false;
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (surfaceTexture != null) {
                surfaceTexture.setOnFrameAvailableListener(null);
                surfaceTexture.release();
                surfaceTexture = null;
            }
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                textureId = 0;
            }
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            if (shader == 0) {
                throw new IllegalStateException("glCreateShader failed: "
                        + GLES20.glGetError());
            }
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("shader compile failed: " + log);
            }
            return shader;
        }

        private int linkProgram(String vertexSource, String fragmentSource) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vertex);
            GLES20.glAttachShader(p, fragment);
            GLES20.glLinkProgram(p);
            int[] status = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            if (status[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(p);
                GLES20.glDeleteProgram(p);
                throw new IllegalStateException("program link failed: " + log);
            }
            return p;
        }
    }
}
