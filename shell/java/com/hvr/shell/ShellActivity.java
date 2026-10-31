package com.hvr.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hvr.shell.cast.DisplayFinder;
import com.hvr.shell.pose.AutoPoseSource;
import com.hvr.shell.pose.PhoneSensorPoseSource;
import com.hvr.shell.pose.Pose6DofSensorSource;
import com.hvr.shell.pose.WalleHidPoseSource;
import com.hvr.shell.render.HomeScene;
import com.hvr.shell.render.LensProfile;
import com.hvr.shell.render.StereoRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * VR 桌面：手机屏是「控制器 + 状态」，眼镜里是双目渲染的桌面。
 *
 * 交互设计（用户要求：手柄连不上就用手机操控）：
 * <ul>
 *   <li>手机屏下半部分是一整块<b>触控板</b>，手指位置直接映射成眼镜里的指针；</li>
 *   <li>抬手（轻点）就是确认：指针停在应用格子上就启动它，停在「＋」上就打开应用选择器；</li>
 *   <li>手柄连上后同样走这套指针，不需要两套逻辑——手柄将来接的是同一份指针状态。</li>
 * </ul>
 *
 * 所以「手柄没连上」不是降级，而是默认可用：手机永远能操控。
 */
public final class ShellActivity extends Activity {

    static final String EXTRA_TRIGGER = "trigger";

    private static final long CONTENT_REFRESH_MS = 1200L;
    private static final long FILE_REFRESH_MS = 5000L;

    private static volatile boolean foreground;

    /** 当前选中的格子（-1 = 无）。包级可见，供 {@link HomeContent} 画高亮。 */
    static volatile int selectedSlot = -1;

    private TextView statusView;
    private GLSurfaceView preview;
    private PadView pad;

    private PhoneSensorPoseSource phoneSource;
    private WalleHidPoseSource hidSource;
    private Pose6DofSensorSource sixDofSource;
    private AutoPoseSource activeSource;

    private final HomeScene homeScene = new HomeScene();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<AppCatalog.Entry> pinnedApps = new ArrayList<>();
    private float pointerX = VrUi.PANEL_WIDTH / 2f;
    private float pointerY = VrUi.PANEL_HEIGHT / 2f;
    private boolean pointerVisible;

    private DisplayManagerHolder displayHolder;
    private Presentation presentation;
    private GLSurfaceView panelView;
    private boolean poseStarted;
    /** 当前界面是为哪种屏幕建的：true = 已被系统放到眼镜屏（vr_only 直接启动）。 */
    private boolean builtForGlasses;

    private final Runnable contentTick = new Runnable() {
        @Override
        public void run() {
            publishContent();
            mainHandler.postDelayed(this, CONTENT_REFRESH_MS);
        }
    };

    private final Runnable fileTick = new Runnable() {
        @Override
        public void run() {
            refreshVideoCount();
            mainHandler.postDelayed(this, FILE_REFRESH_MS);
        }
    };

    static boolean isForeground() {
        return foreground;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 锁屏穿透：系统在眼镜插入时会把本 Activity 直接拉到眼镜屏，
        // 原厂 vrservice 流程要等用户解锁（USER_PRESENT → SCREEN_UNLOCK）才放行；
        // 这里直接越过：锁屏上也显示、点亮屏幕，并在 onResume 里请求解除滑锁。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        displayHolder = new DisplayManagerHolder(this);
        phoneSource = new PhoneSensorPoseSource(this);
        hidSource = new WalleHidPoseSource();
        sixDofSource = new Pose6DofSensorSource(this);
        activeSource = new AutoPoseSource(sixDofSource, hidSource, phoneSource);

        pinnedApps.addAll(AppCatalog.pinned(this));
        buildUi();
        ShellService.ensureRunning(this);

        // adb 远程诊断入口：am start --ez kitprobe true
        registerReceiver(KitProbe.grantReceiver(),
                new IntentFilter(KitProbe.ACTION_GRANTED));
        if (getIntent() != null && getIntent().getBooleanExtra("kitprobe", false)) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    KitProbe.logOnce(ShellActivity.this);
                    Log.i("HvrProbe", CapabilityProbe.run(ShellActivity.this));
                }
            }, 800);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        // 滑动锁（无 PIN）直接自动解除——用户不用再上滑；有 PIN 时弹出解锁界面。
        try {
            android.app.KeyguardManager keyguard =
                    (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguard != null && keyguard.isKeyguardLocked()) {
                keyguard.requestDismissKeyguard(this, null);
                ShellState.event("检测到锁屏，已请求解除");
            }
        } catch (Throwable error) {
            ShellState.event("解除锁屏请求失败", error);
        }
        activeSource.start();
        poseStarted = true;
        if (preview != null) {
            preview.onResume();
        }
        displayHolder.register(new Runnable() {
            @Override
            public void run() {
                onDisplaysChanged();
            }
        });
        if (panelView != null) {
            panelView.onResume();
        } else {
            showOnGlasses();
        }
        mainHandler.post(contentTick);
        mainHandler.post(fileTick);
        refreshVideoCount();
    }

    @Override
    protected void onPause() {
        foreground = false;
        mainHandler.removeCallbacks(contentTick);
        mainHandler.removeCallbacks(fileTick);
        displayHolder.unregister();
        if (panelView != null) {
            panelView.onPause();
        }
        if (preview != null) {
            preview.onPause();
        }
        if (poseStarted) {
            activeSource.stop();
            poseStarted = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissPresentation();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 界面

    private void buildUi() {
        // 系统在眼镜插入 + vr_only 元数据齐全时，会把本 Activity 直接启动到眼镜屏。
        // 这时根视图就是全屏双目 GL（对齐原厂 vrlauncherx 的做法），不再走 Presentation。
        builtForGlasses = isOnGlassesDisplay();
        if (builtForGlasses) {
            ShellState.event("Activity 已在眼镜屏，直接全屏双目输出");
            FrameLayout stereoRoot = new FrameLayout(this);
            stereoRoot.setBackgroundColor(Color.BLACK);

            panelView = new GLSurfaceView(this);
            panelView.setEGLContextClientVersion(2);
            panelView.setPreserveEGLContextOnPause(true);
            // 先不做畸变：排除 shader 越界导致的全黑，验证渲染链路后再调镜片参数。
            panelView.setRenderer(new StereoRenderer(activeSource, LensProfile.NONE,
                    StereoRenderer.MODE_STEREO, homeScene));
            panelView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            stereoRoot.addView(panelView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 全屏透明触控层：眼镜腿触控板 / 手机触摸都会落到这里，指针由 GL 场景画。
            pad = new PadView(this);
            pad.setOverlay(true);
            stereoRoot.addView(pad, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            statusView = null;
            preview = null;
            presentation = null;
            setContentView(stereoRoot);
            return;
        }
        panelView = null;
        presentation = null;

        int padding = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101014);
        root.setPadding(padding, padding, padding, padding);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12.5f);
        statusView.setLineSpacing(0f, 1.1f);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(button("重定位", new Runnable() {
            @Override
            public void run() {
                activeSource.recenter();
                publishContent();
            }
        }), weight());
        row.addView(button("添加应用", new Runnable() {
            @Override
            public void run() {
                showAddDialog();
            }
        }), weight());
        row.addView(button("移除所选", new Runnable() {
            @Override
            public void run() {
                removeSelected();
            }
        }), weight());
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, 0, 0, dp(6));
        row2.addView(button("硬件诊断", new Runnable() {
            @Override
            public void run() {
                showProbeDialog();
            }
        }), weight());
        row2.addView(button("全屏投射", new Runnable() {
            @Override
            public void run() {
                showOnGlasses();
            }
        }), weight());
        root.addView(row2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pad = new PadView(this);
        root.addView(pad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 手机上的小预览：方便不戴眼镜时也能看到桌面长什么样。
        preview = new GLSurfaceView(this);
        preview.setEGLContextClientVersion(2);
        preview.setPreserveEGLContextOnPause(true);
        preview.setRenderer(new StereoRenderer(activeSource, LensProfile.NONE,
                StereoRenderer.MODE_MONO, homeScene));
        preview.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        root.addView(preview, previewParams);

        setContentView(root);
    }

    private Button button(String text, final Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ---------------------------------------------------------------- 触控板

    /**
     * 手机触控板：手指在哪，眼镜里的指针就在哪；抬手即确认。
     *
     * 用绝对位置而不是「相对滑动」：戴着头显看不见手机，相对滑动很容易把指针
     * 甩出屏幕；绝对映射永远能一指头指到想指的地方。
     */
    private final class PadView extends View {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** true = 全屏透明触控层（眼镜屏模式），只收触摸不画背景。 */
        private boolean overlay;
        private float downX;
        private float downY;

        PadView(Context context) {
            super(context);
            fill.setColor(Color.rgb(0x17, 0x1B, 0x25));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(2));
            edge.setColor(Color.rgb(0x2C, 0x35, 0x48));
            text.setColor(Color.rgb(0x6E, 0x7A, 0x94));
            text.setTextSize(dp(13));
            text.setAntiAlias(true);
        }

        void setOverlay(boolean value) {
            overlay = value;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (overlay) {
                return; // 指针由 GL 场景（HomeScene）绘制
            }
            float radius = dp(16);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, fill);
            canvas.drawRoundRect(1, 1, getWidth() - 1, getHeight() - 1, radius, radius, edge);
            canvas.drawText("触控板：移动手指选应用，抬手确认", dp(16),
                    getHeight() - dp(14), text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    pointerVisible = true;
                    movePointer(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    movePointer(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                    movePointer(event.getX(), event.getY());
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (dx * dx + dy * dy < dp(24) * dp(24)) {
                        confirmSelection();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pointerVisible = false;
                    publishContent();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void movePointer(float x, float y) {
            float normalizedX = getWidth() == 0 ? 0f : (x / getWidth()) * 2f - 1f;
            float normalizedY = getHeight() == 0 ? 0f : (y / getHeight()) * 2f - 1f;
            pointerX = VrUi.toPanelX(normalizedX);
            pointerY = VrUi.toPanelY(normalizedY);
            selectedSlot = VrUi.slotAt(pointerX, pointerY);
            publishContent();
        }
    }

    /** 抬手确认：格子是应用就启动，「＋」就打开选择器。 */
    private void confirmSelection() {
        int slot = VrUi.slotAt(pointerX, pointerY);
        if (slot < 0) {
            return;
        }
        if (slot < pinnedApps.size()) {
            AppCatalog.Entry entry = pinnedApps.get(slot);
            boolean launched = AppCatalog.launch(this, entry.packageName);
            ShellState.environmentNote = launched
                    ? "已启动 " + entry.label + "（画面在手机屏，可用投屏拉进眼镜）"
                    : "启动失败：" + entry.label;
        } else if (slot == pinnedApps.size()) {
            showAddDialog();
        }
        publishContent();
    }

    // ---------------------------------------------------------------- 硬件诊断

    /**
     * 弹出硬件能力报告。内容同时打到 logcat（tag {@code HvrProbe}），
     * 方便用 {@code adb logcat -s HvrProbe} 直接取。
     */
    private void showProbeDialog() {
        final String report = CapabilityProbe.run(this);

        TextView body = new TextView(this);
        body.setText(report);
        body.setTextSize(11.5f);
        body.setTextColor(Color.WHITE);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        int padding = dp(12);
        body.setPadding(padding, padding, padding, padding);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("硬件能力诊断")
                .setView(scroll)
                .setPositiveButton("知道了", null)
                .setNeutralButton("打印到日志", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.i("HvrProbe", report);
                        ShellState.event("诊断结果已写入日志");
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- 应用管理

    private void showAddDialog() {
        final List<AppCatalog.Entry> installed = AppCatalog.listLaunchable(this);
        if (installed.isEmpty()) {
            ShellState.event("没有可添加的应用");
            return;
        }
        String[] labels = new String[installed.size()];
        for (int i = 0; i < installed.size(); i++) {
            labels[i] = installed.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle("添加到 VR 桌面（最多 " + AppCatalog.MAX_PINNED + " 个）")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AppCatalog.addPinned(ShellActivity.this, installed.get(which));
                        reloadPinned();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void removeSelected() {
        if (selectedSlot < 0 || selectedSlot >= pinnedApps.size()) {
            ShellState.event("先用触控板选中一个应用，再点移除");
            return;
        }
        AppCatalog.removePinned(this, pinnedApps.get(selectedSlot).packageName);
        reloadPinned();
    }

    private void reloadPinned() {
        pinnedApps.clear();
        pinnedApps.addAll(AppCatalog.pinned(this));
        if (selectedSlot >= pinnedApps.size()) {
            selectedSlot = -1;
        }
        publishContent();
    }

    // ---------------------------------------------------------------- 眼镜输出

    /** 本 Activity 的宿主窗口是否正落在眼镜屏上（系统 vr_only 直接启动的结果）。 */
    private boolean isOnGlassesDisplay() {
        Display glasses = DisplayFinder.find(this);
        Display here = getWindowManager().getDefaultDisplay();
        return glasses != null && here != null
                && here.getDisplayId() == glasses.getDisplayId();
    }

    private void onDisplaysChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // 系统可能把 Activity 在手机屏/眼镜屏之间搬动，搬动后重建界面。
                if (isOnGlassesDisplay() != builtForGlasses) {
                    dismissPresentation();
                    buildUi();
                    if (panelView != null) {
                        panelView.onResume();
                    }
                }
                if (DisplayFinder.find(ShellActivity.this) == null) {
                    dismissPresentation();
                } else if (panelView == null) {
                    showOnGlasses();
                }
                publishContent();
            }
        });
    }

    private void showOnGlasses() {
        Display display = DisplayFinder.find(this);
        if (display == null) {
            return;
        }
        dismissPresentation();

        Context displayContext = createDisplayContext(display);
        panelView = new GLSurfaceView(displayContext);
        panelView.setEGLContextClientVersion(2);
        panelView.setPreserveEGLContextOnPause(true);
        panelView.setRenderer(new StereoRenderer(activeSource, LensProfile.GENERIC_BARREL,
                StereoRenderer.MODE_STEREO, homeScene));
        panelView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // 眼镜屏是 FLAG_SECURE 显示器：SurfaceFlinger 只合成 secure 图层，
        // 普通 App 的 buffer 会被整个黑掉。SurfaceView.setSecure 是隐藏 API，反射调用。
        try {
            java.lang.reflect.Method secure =
                    SurfaceView.class.getMethod("setSecure", boolean.class);
            secure.invoke(panelView, Boolean.TRUE);
            ShellState.event("setSecure(true) 反射成功");
        } catch (Throwable error) {
            ShellState.event("setSecure 不可用：" + error.getClass().getSimpleName());
        }

        // 2D 诊断浮层：能看到文字 = 视图在合成、问题在 GL；全黑 = SECURE 拦了整个图层。
        FrameLayout panel = new FrameLayout(displayContext);
        panel.addView(panelView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView osd = new TextView(displayContext);
        osd.setText("HVR 桌面运行中\n(只看到这行字=GL故障；全黑=SECURE拦截)");
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(28f);
        osd.setBackgroundColor(0x66000000);
        int pad = dp(24);
        osd.setPadding(pad, pad, pad, pad);
        panel.addView(osd, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Presentation created = new Presentation(displayContext, display);
        created.setContentView(panel);
        created.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (presentation == created) {
                    presentation = null;
                    panelView = null;
                }
            }
        });
        presentation = created;

        try {
            created.show();
            panelView.onResume();
            ShellState.event("VR 桌面已显示到 " + DisplayFinder.describe(display));
            ShellState.environmentNote = "已输出到眼镜";
        } catch (Throwable error) {
            ShellState.event("在眼镜上显示失败", error);
            ShellState.environmentNote = "显示失败：" + error.getClass().getSimpleName();
            dismissPresentation();
        }
    }

    private void dismissPresentation() {
        Presentation current = presentation;
        GLSurfaceView currentPanel = panelView;
        presentation = null;
        panelView = null;
        if (currentPanel != null) {
            try {
                currentPanel.onPause();
            } catch (Throwable ignored) {
                // 上下文已销毁
            }
        }
        if (current != null) {
            try {
                current.dismiss();
            } catch (Throwable ignored) {
                // 已经关闭
            }
        }
    }

    // ---------------------------------------------------------------- 状态刷新

    private void refreshVideoCount() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int count = LocalFiles.listVideos(getApplicationContext(), 50).size();
                ShellState.fileNote = LocalFiles.hasAllFilesAccess(getApplicationContext())
                        ? (count + " 个本地视频")
                        : "未授权全盘访问";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        publishContent();
                    }
                });
            }
        }, "shell-files").start();
    }

    private void publishContent() {
        homeScene.setContent(HomeContent.render(
                this, new ArrayList<>(pinnedApps), pointerX, pointerY, pointerVisible));
        if (statusView != null) {
            StringBuilder text = new StringBuilder();
            text.append("眼镜：").append(GlassPresence.describe()).append('\n');
            text.append("姿态：").append(activeSource.name())
                    .append(activeSource.isPositionTracked() ? " · 6DOF" : " · 3DOF").append('\n');
            text.append("手柄：").append(ShellState.controllerNote).append('\n');
            text.append("文件：").append(ShellState.fileNote).append('\n');
            text.append("输出：").append(panelView == null ? "未连眼镜" : "已进眼镜")
                    .append(" · 桌面应用 ").append(pinnedApps.size()).append(" 个");
            statusView.setText(text);
        }
    }

    /** DisplayManager 的注册/注销包一层，避免每个回调都判空。 */
    private static final class DisplayManagerHolder implements
            android.hardware.display.DisplayManager.DisplayListener {

        private final Activity activity;
        private final android.hardware.display.DisplayManager manager;
        private Runnable callback;

        DisplayManagerHolder(Activity activity) {
            this.activity = activity;
            this.manager = (android.hardware.display.DisplayManager)
                    activity.getSystemService(Context.DISPLAY_SERVICE);
        }

        void register(Runnable onChanged) {
            callback = onChanged;
            if (manager == null) {
                return;
            }
            manager.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
        }

        void unregister() {
            callback = null;
            if (manager == null) {
                return;
            }
            try {
                manager.unregisterDisplayListener(this);
            } catch (Throwable ignored) {
                // 已经注销
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            dispatch();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            dispatch();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            dispatch();
        }

        private void dispatch() {
            Runnable target = callback;
            if (target != null) {
                activity.runOnUiThread(target);
            }
        }
    }
}
