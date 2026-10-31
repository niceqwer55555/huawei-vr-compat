package com.hvr.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;

/**
 * 常驻服务：盯着眼镜，在位就把 VR 主界面拉起来。
 *
 * 与旧方案的区别只有一句话：<b>不再拉起任何华为组件</b>。
 * 以前这里要跨应用启动 hvrsdkserverapp 的监督服务、唤醒 vrservice、让原版桌面
 * 走握手；现在这些包都不装了，VR 主界面就是本应用的 {@link ShellActivity}，
 * 它自己创建 Presentation 输出到眼镜、自己用姿态数据渲染双目。
 *
 * 于是「插上眼镜 → 直接进 VR 桌面」这条链上不再有第三方依赖：
 * 开机广播 → 本服务 → 检测到眼镜 → 启动本应用的主界面。
 */
public final class ShellService extends Service implements DisplayManager.DisplayListener {

    static final String ACTION_RECHECK = "com.hvr.shell.action.RECHECK";

    private static final long POLL_MS = 2000L;
    /**
     * 在位期间每 15 秒确认一次主界面还在。用户戴上眼镜后可能把界面滑掉了
     * （或者被系统回收），这里能把它重新拉回来，而不需要用户再插一次。
     */
    private static final long ENSURE_MS = 15000L;

    private static final String CHANNEL_ID = "shell";
    private static final int FOOTFULL_SCREEN_ID = 42;
    private static final int NOTIFICATION_ID = 2001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displayManager;
    private boolean displayListenerRegistered;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            ShellState.event("USB 事件 " + action);
            evaluate("usb");
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            evaluate("poll");
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable ensure = new Runnable() {
        @Override
        public void run() {
            if (ShellState.glassPresent && !ShellActivity.isForeground()) {
                launchDesktop("keep-alive");
            }
            handler.postDelayed(this, ENSURE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ShellState.serviceStartedAt = System.currentTimeMillis();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerDisplayListener();
        registerUsbReceiver();
        handler.post(poll);
        handler.post(ensure);
        ShellState.event("VR 服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RECHECK.equals(intent.getAction())) {
            ShellState.event("收到手动检测");
            evaluate("manual");
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (displayListenerRegistered && displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(this);
            } catch (Throwable error) {
                ShellState.event("注销显示监听失败", error);
            }
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Throwable error) {
            ShellState.event("注销 USB 监听失败", error);
        }
        ControllerLink.stop();
        ShellState.event("VR 服务已停止");
        super.onDestroy();
    }

    private void evaluate(String reason) {
        boolean present = GlassPresence.sample(this);
        if (present) {
            if (!"poll".equals(reason)) {
                ShellState.event("眼镜在位（" + reason + "）：" + GlassPresence.describe());
            }
            if (!ShellActivity.isForeground()) {
                launchDesktop(reason);
            }
            ControllerLink.start(this);
        } else {
            if ("poll".equals(reason)) {
                return;
            }
            ShellState.event("眼镜不在位（" + reason + "）：" + GlassPresence.describe());
            ControllerLink.stop();
        }
    }

    private void launchDesktop(String reason) {
        Intent intent = new Intent(this, ShellActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(ShellActivity.EXTRA_TRIGGER, reason);
        try {
            startActivity(intent);
            ShellState.environmentNote = "已请求启动 VR 主界面（" + reason + "）";
        } catch (Throwable error) {
            // 后台启动 Activity 被拒：改走全屏意图通知（锁屏/黑屏下也能拉起 Activity），
            // 这正是原厂流程里「等用户解锁」那一环的替代品。
            ShellState.environmentNote = "直接启动被拒，改用全屏意图（" + reason + "）";
            ShellState.event("直接启动被拒，改用全屏意图", error);
            launchViaFullScreenIntent(intent);
        }
    }

    /** 全屏意图：USE_FULL_SCREEN_INTENT 允许在锁屏/灭屏时直接打开 Activity。 */
    private void launchViaFullScreenIntent(Intent intent) {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String fsiChannel = "shell_fsi";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
                NotificationChannel channel = new NotificationChannel(fsiChannel,
                        "眼镜接入提醒", NotificationManager.IMPORTANCE_HIGH);
                manager.createNotificationChannel(channel);
            }
            PendingIntent fullScreen = PendingIntent.getActivity(this, 1, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, fsiChannel)
                    : new Notification.Builder(this);
            builder.setContentTitle("VR 桌面待进入")
                    .setContentText("眼镜已连接，点击进入 VR 桌面")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setFullScreenIntent(fullScreen, true);
            if (manager != null) {
                manager.notify(FOOTFULL_SCREEN_ID, builder.build());
            }
        } catch (Throwable error) {
            ShellState.event("全屏意图启动失败", error);
        }
    }

    private void registerDisplayListener() {
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            ShellState.event("没有 DisplayManager，退化为轮询");
            return;
        }
        try {
            displayManager.registerDisplayListener(this, handler);
            displayListenerRegistered = true;
        } catch (Throwable error) {
            ShellState.event("注册显示监听失败", error);
        }
    }

    private void registerUsbReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
            filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
            registerReceiver(usbReceiver, filter);
        } catch (Throwable error) {
            ShellState.event("注册 USB 监听失败", error);
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        evaluate("displayAdded:" + displayId);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        evaluate("displayRemoved:" + displayId);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
        if (display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return;
        }
        evaluate("displayChanged:" + displayId);
    }

    private Notification buildNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "VR 环境", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, ShellActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("VR 运行中")
                .setContentText("插上眼镜即进入 VR 桌面")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    static void ensureRunning(Context context) {
        Intent intent = new Intent(context, ShellService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable error) {
            ShellState.event("启动 VR 服务失败", error);
        }
    }
}
