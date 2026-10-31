package com.huawei.vrvirtualscreen.mirror;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import com.huawei.vrframework.compat.VrPlatformCompat;

import java.util.Locale;

/**
 * 找眼镜那块副屏，并把当前所有屏幕打印出来，方便没有 adb 时排查。
 *
 * 顺序：
 *   1. 名字里带 HUAWEI / VR / AV03 / GLASS 的副屏（即使当前是 OFF 也能认出来）；
 *   2. 系统标记为 presentation 的屏；
 *   3. 带 {@link Display#FLAG_PRESENTATION} 的副屏；
 *   4. 兼容层原来的「第一块非默认且不是 OFF 的屏」。
 *
 * 注意：折叠屏的 cover 屏也可能满足第 3 条，所以名字匹配放在最前面。
 */
public final class HvrPanelFinder {

    private static final String TAG = "HvrPanelFinder";

    private HvrPanelFinder() {
    }

    public static Display find(Context context) {
        if (context == null) {
            return null;
        }
        DisplayManager manager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return null;
        }
        try {
            Display[] displays = manager.getDisplays();
            for (Display display : displays) {
                if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    continue;
                }
                String name = display.getName() == null
                        ? "" : display.getName().toUpperCase(Locale.US);
                if (name.contains("HUAWEI") || name.contains("VR")
                        || name.contains("AV03") || name.contains("GLASS")) {
                    Log.i(TAG, "panel by name: " + describe(display));
                    return display;
                }
            }
            for (Display display : manager.getDisplays(
                    DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    Log.i(TAG, "panel by presentation category: " + describe(display));
                    return display;
                }
            }
            for (Display display : displays) {
                if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    continue;
                }
                if ((display.getFlags() & Display.FLAG_PRESENTATION) != 0) {
                    Log.i(TAG, "panel by FLAG_PRESENTATION: " + describe(display));
                    return display;
                }
            }
            Display compat = VrPlatformCompat.externalDisplay(context);
            if (compat != null) {
                Log.i(TAG, "panel by compat fallback: " + describe(compat));
                return compat;
            }
        } catch (Throwable error) {
            Log.w(TAG, "display scan failed: " + error);
        }
        Log.w(TAG, "no panel found; " + describeAll(context));
        return null;
    }

    public static String describe(Display display) {
        if (display == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder();
        out.append('#').append(display.getDisplayId()).append(' ').append(display.getName());
        out.append(" state=").append(stateName(display.getState()));
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            out.append(' ').append(metrics.widthPixels).append('x')
                    .append(metrics.heightPixels).append(" @").append(metrics.densityDpi);
        } catch (Throwable error) {
            out.append(" size=?");
        }
        out.append(" flags=0x").append(Integer.toHexString(display.getFlags()));
        if (display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            out.append(" (default)");
        }
        return out.toString();
    }

    public static String describeAll(Context context) {
        if (context == null) {
            return "no context";
        }
        DisplayManager manager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return "DisplayManager unavailable";
        }
        StringBuilder out = new StringBuilder();
        try {
            Display[] displays = manager.getDisplays();
            out.append("displays=").append(displays.length).append('\n');
            for (Display display : displays) {
                if (display != null) {
                    out.append("  ").append(describe(display)).append('\n');
                }
            }
        } catch (Throwable error) {
            out.append("display list failed: ").append(error);
        }
        return out.toString();
    }

    private static String stateName(int state) {
        switch (state) {
            case Display.STATE_ON:
                return "ON";
            case Display.STATE_OFF:
                return "OFF";
            case Display.STATE_DOZE:
                return "DOZE";
            case Display.STATE_DOZE_SUSPEND:
                return "DOZE_SUSPEND";
            case Display.STATE_VR:
                return "VR";
            case Display.STATE_UNKNOWN:
                return "UNKNOWN";
            default:
                return Integer.toString(state);
        }
    }
}
