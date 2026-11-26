package com.huawei.vrvirtualscreen.mirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 把手机默认屏镜像到眼镜副屏（免 root），横屏 SBS、左右眼显示同一帧。
 *
 * <p>链路（公开 API）：</p>
 * <pre>
 *   MediaProjectionManager.createScreenCaptureIntent()  （Activity 里授权）
 *     -> MediaProjection.createVirtualDisplay(AUTO_MIRROR, 一路 SurfaceTexture)
 *     -> HvrMirrorGlView 在眼镜 display 上把这一帧左右各画一次
 * </pre>
 *
 * <p>华为 VR Glass 物理面板 3200x1600（2:1 横屏），SBS 左右并排 → 每眼 1600x1600
 * 正方形。2026-11-26 起改用<b>单路 VirtualDisplay + GL 双画</b>：左右眼采样同一帧、
 * 内容严格一致，正是「横屏 SBS、左右眼显示一样」。旧实现是两个 SurfaceView +
 * 两个 VirtualDisplay，Android 13+ 只有最后一块出图，导致双目内容不一致/单眼黑屏
 * （PATCH_AUDIT 遗留问题、CAST_NOROOT.md §Android 13+ 注意）。</p>
 *
 * <p>只依赖 SYSTEM_ALERT_WINDOW；不需要 root / LSPosed / 华为 VR 系统服务。</p>
 */
public final class HvrMirrorService extends Service {

    static final String EXTRA_RESULT_CODE = "hvr.resultCode";
    static final String EXTRA_RESULT_DATA = "hvr.resultData";

    private static final String TAG = "HvrMirrorService";
    private static final String CHANNEL_ID = "hvr_cast";
    private static final int NOTIFICATION_ID = 0x48565201;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private int castDensityDpi = DisplayMetrics.DENSITY_DEFAULT;
    private WindowManager windowManager;
    private View overlay;
    private Presentation presentation;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (intent == null) {
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (projection == null && resultCode != 0 && data != null) {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                Log.w(TAG, "MediaProjectionManager unavailable");
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                projection = manager.getMediaProjection(resultCode, data);
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.i(TAG, "projection stopped by the system");
                        stopSelf();
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (Throwable error) {
                Log.w(TAG, "getMediaProjection failed: " + error);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (projection == null) {
            Log.w(TAG, "no projection token");
            stopSelf();
            return START_NOT_STICKY;
        }
        showMirror();
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "VR 投屏", NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("VR 投屏")
                .setContentText("正在把手机屏幕投射到眼镜（横屏 SBS，左右眼一致）")
                .setOngoing(true);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void showMirror() {
        if (overlay != null || presentation != null) {
            return;
        }
        final Display display = HvrPanelFinder.find(this);
        if (display == null) {
            Log.w(TAG, "no external display; " + HvrPanelFinder.describeAll(this));
            Toast.makeText(this, "未找到眼镜副屏，投屏停止", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        final Context displayContext = createDisplayContext(display);
        final DisplayMetrics panelMetrics = new DisplayMetrics();
        display.getRealMetrics(panelMetrics);
        final int panelWidth = Math.max(1, panelMetrics.widthPixels);
        final int panelHeight = Math.max(1, panelMetrics.heightPixels);

        DisplayMetrics phoneMetrics = new DisplayMetrics();
        try {
            DisplayManager manager =
                    (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display phone = manager == null ? null
                    : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (phone != null) {
                phone.getRealMetrics(phoneMetrics);
            }
        } catch (Throwable error) {
            Log.w(TAG, "cannot read the phone display metrics: " + error);
        }
        int phoneWidth = Math.max(1, phoneMetrics.widthPixels);
        int phoneHeight = Math.max(1, phoneMetrics.heightPixels);
        if (phoneMetrics.widthPixels <= 0 || phoneMetrics.heightPixels <= 0) {
            phoneWidth = panelWidth;
            phoneHeight = panelHeight;
        }

        // 每眼面板格子 = 面板宽/2 x 面板高（物理 3200x1600 → 1600x1600 正方形）。
        // 虚拟屏按手机比例缩放后居中进这个格子，左右眼用同一份内容。
        int eyeWidth = Math.max(1, panelWidth / 2);
        float scale = Math.min((float) eyeWidth / phoneWidth,
                (float) panelHeight / phoneHeight);
        final int viewWidth = Math.max(1, Math.round(phoneWidth * scale));
        final int viewHeight = Math.max(1, Math.round(phoneHeight * scale));
        castDensityDpi = phoneMetrics.densityDpi > 0
                ? phoneMetrics.densityDpi
                : (panelMetrics.densityDpi > 0
                        ? panelMetrics.densityDpi : DisplayMetrics.DENSITY_DEFAULT);

        HvrMirrorGlView view = new HvrMirrorGlView(displayContext);
        view.mirror(viewWidth, viewHeight, new HvrMirrorGlView.InputSurfaceListener() {
            @Override
            public void onInputSurface(Surface surface, int width, int height) {
                attachProjection(surface, width, height);
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.FILL;
        try {
            windowManager = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
            windowManager.addView(view, params);
            overlay = view;
            Log.i(TAG, "mirror overlay shown on display " + display.getDisplayId()
                    + " (" + display.getName() + ") " + panelWidth + "x" + panelHeight
                    + ", eye " + (panelWidth / 2) + "x" + panelHeight
                    + ", SBS 左右眼各一份相同画面");
            Toast.makeText(this, "投屏中（横屏 SBS）：" + display.getName(),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Log.w(TAG, "overlay rejected (" + error + "), trying a Presentation");
            Toast.makeText(this, "悬浮窗被拒绝，尝试 Presentation",
                    Toast.LENGTH_SHORT).show();
            overlay = null;
            showPresentation(displayContext, display, view);
        }
    }

    private void showPresentation(Context displayContext, Display display, View content) {
        try {
            Presentation window = new Presentation(displayContext, display);
            window.setContentView(content);
            window.setOnDismissListener(dialog -> {
                Log.i(TAG, "mirror presentation dismissed");
                presentation = null;
                stopSelf();
            });
            window.show();
            presentation = window;
            Log.i(TAG, "mirror presentation shown on display " + display.getDisplayId());
            Toast.makeText(this, "投屏中（Presentation）：" + display.getName(),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Log.w(TAG, "presentation failed, cannot mirror: " + error);
            Toast.makeText(this, "无法显示到眼镜：" + error, Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    /** GL 的 SurfaceTexture 就绪后回调；只建一路 VirtualDisplay。 */
    private void attachProjection(Surface surface, int width, int height) {
        if (projection == null || surface == null || !surface.isValid()) {
            return;
        }
        releaseVirtualDisplay();
        try {
            VirtualDisplay created = projection.createVirtualDisplay(
                    "HW-VR-Cast", width, height, castDensityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null);
            if (created == null) {
                Log.w(TAG, "createVirtualDisplay returned null");
                Toast.makeText(this, "眼镜画面创建失败", Toast.LENGTH_LONG).show();
                stopSelf();
                return;
            }
            virtualDisplay = created;
            Log.i(TAG, "mirror virtual display " + width + "x" + height
                    + " created (single source, GL draws both eyes)");
        } catch (Throwable error) {
            Log.w(TAG, "createVirtualDisplay failed: " + error);
            Toast.makeText(this, "眼镜画面创建失败：" + error, Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void releaseVirtualDisplay() {
        if (virtualDisplay == null) {
            return;
        }
        try {
            virtualDisplay.release();
        } catch (Throwable error) {
            Log.w(TAG, "release virtualDisplay failed: " + error);
        }
        virtualDisplay = null;
    }

    @Override
    public void onDestroy() {
        // 先断开捕获，再销毁承载 Surface 的窗口。
        releaseVirtualDisplay();

        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Throwable error) {
                Log.w(TAG, "dismiss presentation failed: " + error);
            }
            presentation = null;
        }
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Throwable error) {
                Log.w(TAG, "remove overlay failed: " + error);
            }
        }
        overlay = null;
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable error) {
                Log.w(TAG, "stop projection failed: " + error);
            }
        }
        projection = null;
        stopForeground(true);
        super.onDestroy();
    }
}
