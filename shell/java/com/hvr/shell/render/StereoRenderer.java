package com.hvr.shell.render;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.hvr.shell.pose.Pose;
import com.hvr.shell.pose.PoseSource;

/**
 * 双目立体渲染器。
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li>{@link #MODE_STEREO}：眼镜面板。左右眼各渲染一次到 FBO，再用一个全屏
 *       pass 做桶形畸变 + 色差补偿，最后整屏输出。对应原版
 *       {@code libxr_renderer} 的「每眼 prepareSwapchain + 一次 submit」；</li>
 *   <li>{@link #MODE_MONO}：手机上的预览，单眼直接画到窗口，不做畸变。</li>
 * </ul>
 *
 * <p>视图矩阵：{@code view = T(-eyeOffset) · T(-position) · Rᵀ}，
 * 其中 {@code R} 是姿态四元数转出的「机体→世界」旋转。eyeOffset 在头坐标系里，
 * 所以它右乘在 {@code Rᵀ} 之后（等价于在世界系里偏移
 * {@code R·(±IPD/2,0,0)}）。</p>
 */
public final class StereoRenderer implements GLSurfaceView.Renderer {

    public static final int MODE_STEREO = 0;
    public static final int MODE_MONO = 1;

    private static final String TAG = "StereoRenderer";

    private static final String VERTEX_QUAD =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aUv;\n"
                    + "varying vec2 vUv;\n"
                    + "void main() {\n"
                    + "    vUv = aUv;\n"
                    + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                    + "}\n";

    private static final String FRAGMENT_DISTORT =
            "precision mediump float;\n"
                    + "varying vec2 vUv;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "uniform float uK1;\n"
                    + "uniform float uK2;\n"
                    + "uniform float uChroma;\n"
                    + "void main() {\n"
                    + "    float eye = step(0.5, vUv.x);\n"
                    + "    vec2 local = vec2(vUv.x * 2.0 - eye, vUv.y);\n"
                    + "    vec2 p = local * 2.0 - 1.0;\n"
                    + "    float r2 = dot(p, p);\n"
                    + "    vec2 warped = p * (1.0 + uK1 * r2 + uK2 * r2 * r2);\n"
                    + "    if (abs(warped.x) > 1.0 || abs(warped.y) > 1.0) {\n"
                    + "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
                    + "        return;\n"
                    + "    }\n"
                    + "    vec2 uv = vec2((warped.x * 0.5 + 0.5 + eye) * 0.5,\n"
                    + "                   warped.y * 0.5 + 0.5);\n"
                    + "    float left = eye * 0.5;\n"
                    + "    if (uv.x < left || uv.x > left + 0.5) {\n"
                    + "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
                    + "        return;\n"
                    + "    }\n"
                    + "    vec2 shift = warped * uChroma;\n"
                    + "    float r = texture2D(uTexture, uv + shift).r;\n"
                    + "    float g = texture2D(uTexture, uv).g;\n"
                    + "    float b = texture2D(uTexture, uv - shift).b;\n"
                    + "    gl_FragColor = vec4(r, g, b, 1.0);\n"
                    + "}\n";

    private final PoseSource poseSource;
    private final LensProfile lens;
    private final int mode;
    private final Renderable scene;

    private final Pose pose = new Pose();
    private final float[] rotation = new float[16];
    private final float[] view = new float[16];
    private final float[] viewTemp = new float[16];
    private final float[] translation = new float[16];
    private final float[] eyeView = new float[16];
    private final float[] projection = new float[16];
    private final float[] viewProjection = new float[16];

    private int width = 1;
    private int height = 1;

    private int framebuffer;
    private int colorTexture;
    private int depthBuffer;
    private boolean offscreen;

    private int distortProgram;
    private int quadPosition;
    private int quadUv;
    private int quadTexture;
    private int quadK1;
    private int quadK2;
    private int quadChroma;
    private FloatBuffer quadBuffer;

    private float fovYDegrees = 90f;
    private float nearPlane = 0.05f;
    private float farPlane = 100f;
    private float ipdMeters = 0.063f;
    private float predictionSeconds = 0.010f;
    /**
     * true = 面板锁定在视线正前方（HUD 式），不随头部旋转。
     * 用于管线验证与「重定位不可用」时的兜底；正常模式应做启动时锁定朝向。
     */
    private boolean headLock = true;

    public void setHeadLock(boolean value) {
        headLock = value;
    }

    public StereoRenderer(PoseSource poseSource, LensProfile lens, int mode) {
        this(poseSource, lens, mode, new Scene());
    }

    /**
     * @param scene 画什么。传 null 时退化为演示场景（网格 + 立方体），
     *              这样老的调用点（示例工程）不用改。
     */
    public StereoRenderer(PoseSource poseSource, LensProfile lens, int mode, Renderable scene) {
        this.poseSource = poseSource;
        this.lens = lens == null ? LensProfile.NONE : lens;
        this.mode = mode;
        this.scene = scene == null ? new Scene() : scene;
    }

    public void setPredictionMillis(float millis) {
        predictionSeconds = Math.max(0f, millis) / 1000f;
    }

    public void setIpdMeters(float meters) {
        ipdMeters = Math.max(0.02f, Math.min(0.09f, meters));
    }

    public void setVerticalFovDegrees(float degrees) {
        fovYDegrees = Math.max(40f, Math.min(120f, degrees));
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 与 HomeContent 的面板底色一致（0x0B0D12），接缝与边缘融为一体。
        GLES20.glClearColor(0.043f, 0.051f, 0.071f, 1f);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        scene.init();
        buildDistortionPass();
        offscreen = false;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int surfaceWidth, int surfaceHeight) {
        width = Math.max(1, surfaceWidth);
        height = Math.max(1, surfaceHeight);
        if (mode == MODE_STEREO) {
            createOffscreen(width, height);
        }
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (poseSource == null) {
            pose.reset();
        } else {
            poseSource.predict(pose, predictionSeconds);
        }

        if (mode == MODE_MONO) {
            drawMono();
        } else if (offscreen) {
            drawStereoOffscreen();
            drawDistortion();
        } else {
            drawStereoDirect();
        }
        logPixelProbe();
    }

    /**
     * 像素探针：每 120 帧回读一次「FBO 左眼中心」与「最终输出中心」的颜色，
     * 用于无头诊断黑屏在哪一级（场景没画出来 / 畸变 pass 丢了）。
     */
    private void logPixelProbe() {
        frameCount++;
        if (frameCount % 120 != 1 || mode != MODE_STEREO) {
            return;
        }
        StringBuilder line = new StringBuilder("像素探针");
        byte[] pixel = new byte[4];
        if (offscreen) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            line.append(" FBO[左眼心]=").append(probe(width / 4, height / 2));
            line.append(" FBO[右眼心]=").append(probe(width * 3 / 4, height / 2));
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
        line.append(" 屏幕[左1/4]=").append(probe(width / 4, height / 2));
        line.append(" 屏幕[心]=").append(probe(width / 2, height / 2));
        line.append(" 屏幕[右1/4]=").append(probe(width * 3 / 4, height / 2));
        Log.i(TAG, line.toString());
    }

    private String probe(int x, int y) {
        byte[] pixel = new byte[4];
        GLES20.glReadPixels(x, y, 1, 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(pixel));
        return (pixel[0] & 0xff) + "," + (pixel[1] & 0xff) + "," + (pixel[2] & 0xff);
    }

    private long frameCount;

    // ---------------------------------------------------------------- 渲染路径

    private void drawMono() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClearColor(0.043f, 0.051f, 0.071f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        Matrix.perspectiveM(projection, 0, fovYDegrees,
                (float) width / (float) height, nearPlane, farPlane);
        buildView(0f);
        Matrix.multiplyMM(viewProjection, 0, projection, 0, eyeView, 0);
        scene.draw(viewProjection);
    }

    private void drawStereoOffscreen() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        // 必须每帧重设：drawDistortion 会把清屏色改成黑色（GL 全局状态），
        // 不重设的话从第二帧起 FBO 背景就是纯黑（已实机验证的黑屏根因之一）。
        GLES20.glClearColor(0.043f, 0.051f, 0.071f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        int eyeWidth = Math.max(1, width / 2);
        float aspect = (float) eyeWidth / (float) height;
        Matrix.perspectiveM(projection, 0, fovYDegrees, aspect, nearPlane, farPlane);

        for (int eye = 0; eye < 2; eye++) {
            GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, height);
            buildView(eye == 0 ? -ipdMeters * 0.5f : ipdMeters * 0.5f);
            Matrix.multiplyMM(viewProjection, 0, projection, 0, eyeView, 0);
            scene.draw(viewProjection);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
    }

    /** FBO 创建失败时的退路：左右半屏各画一只眼，没有畸变。 */
    private void drawStereoDirect() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClearColor(0.043f, 0.051f, 0.071f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        int eyeWidth = Math.max(1, width / 2);
        float aspect = (float) eyeWidth / (float) height;
        Matrix.perspectiveM(projection, 0, fovYDegrees, aspect, nearPlane, farPlane);

        for (int eye = 0; eye < 2; eye++) {
            GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            buildView(eye == 0 ? -ipdMeters * 0.5f : ipdMeters * 0.5f);
            Matrix.multiplyMM(viewProjection, 0, projection, 0, eyeView, 0);
            scene.draw(viewProjection);
        }
    }

    private void drawDistortion() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0.043f, 0.051f, 0.071f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(distortProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture);
        GLES20.glUniform1i(quadTexture, 0);
        GLES20.glUniform1f(quadK1, lens.k1);
        GLES20.glUniform1f(quadK2, lens.k2);
        GLES20.glUniform1f(quadChroma, lens.chroma);

        GLES20.glEnableVertexAttribArray(quadPosition);
        GLES20.glEnableVertexAttribArray(quadUv);
        quadBuffer.position(0);
        GLES20.glVertexAttribPointer(quadPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
        quadBuffer.position(2);
        GLES20.glVertexAttribPointer(quadUv, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(quadPosition);
        GLES20.glDisableVertexAttribArray(quadUv);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /**
     * 由姿态构造 view 矩阵。
     *
     * @param eyeOffsetX 眼偏移（米），左眼为负
     */
    private void buildView(float eyeOffsetX) {
        if (headLock) {
            // 面板锁定在视线正前方：view = 单位阵（仅保留眼偏移）。
            Matrix.setIdentityM(eyeView, 0);
            Matrix.translateM(eyeView, 0, -eyeOffsetX, 0f, 0f);
            return;
        }
        pose.orientation.toMatrix(rotation, 0);        // 机体 -> 世界
        Matrix.transposeM(view, 0, rotation, 0);       // 世界 -> 机体

        if (pose.hasPosition) {
            System.arraycopy(view, 0, viewTemp, 0, 16);
            Matrix.setIdentityM(translation, 0);
            Matrix.translateM(translation, 0,
                    -pose.position[0], -pose.position[1], -pose.position[2]);
            // view = T(-position) * Rᵀ
            Matrix.multiplyMM(view, 0, translation, 0, viewTemp, 0);
        }

        System.arraycopy(view, 0, eyeView, 0, 16);
        // 眼偏移在头坐标系里：view = view * T(-eyeOffset)
        Matrix.translateM(eyeView, 0, -eyeOffsetX, 0f, 0f);
    }

    // ---------------------------------------------------------------- 资源

    private void buildDistortionPass() {
        distortProgram = GlUtil.linkProgram(VERTEX_QUAD, FRAGMENT_DISTORT);
        quadPosition = GLES20.glGetAttribLocation(distortProgram, "aPosition");
        quadUv = GLES20.glGetAttribLocation(distortProgram, "aUv");
        quadTexture = GLES20.glGetUniformLocation(distortProgram, "uTexture");
        quadK1 = GLES20.glGetUniformLocation(distortProgram, "uK1");
        quadK2 = GLES20.glGetUniformLocation(distortProgram, "uK2");
        quadChroma = GLES20.glGetUniformLocation(distortProgram, "uChroma");

        float[] quad = {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
        };
        quadBuffer = ByteBuffer.allocateDirect(quad.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quadBuffer.put(quad).position(0);
    }

    private void createOffscreen(int w, int h) {
        releaseOffscreen();
        try {
            colorTexture = GlUtil.createTexture();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            int[] ids = new int[1];
            GLES20.glGenFramebuffers(1, ids, 0);
            framebuffer = ids[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTexture, 0);

            GLES20.glGenRenderbuffers(1, ids, 0);
            depthBuffer = ids[0];
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthBuffer);
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER,
                    GLES20.GL_DEPTH_COMPONENT16, w, h);
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthBuffer);

            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "framebuffer incomplete: 0x" + Integer.toHexString(status));
                releaseOffscreen();
                return;
            }
            offscreen = true;
        } catch (Throwable error) {
            Log.w(TAG, "createOffscreen failed: " + error);
            releaseOffscreen();
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0);
        }
    }

    private void releaseOffscreen() {
        offscreen = false;
        if (framebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
            framebuffer = 0;
        }
        if (depthBuffer != 0) {
            GLES20.glDeleteRenderbuffers(1, new int[]{depthBuffer}, 0);
            depthBuffer = 0;
        }
        if (colorTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{colorTexture}, 0);
            colorTexture = 0;
        }
    }
}
