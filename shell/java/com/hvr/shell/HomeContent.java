package com.hvr.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * VR 面板的内容：状态 + 应用格子 + 指针。
 *
 * 尺寸与坐标全部取自 {@link VrUi}，因为同一套坐标还要给触控板判命中用。
 * 内容变了就重新画一张位图（1024x640），交给 {@link com.hvr.shell.render.HomeScene}
 * 上传成纹理——面板是静态的，一张图足够，不需要每帧重绘文字。
 */
final class HomeContent {

    private HomeContent() {
    }

    static Bitmap render(Context context, List<AppCatalog.Entry> apps,
                         float pointerX, float pointerY, boolean pointerVisible) {
        Bitmap bitmap = Bitmap.createBitmap(
                VrUi.PANEL_WIDTH, VrUi.PANEL_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(0x0B, 0x0D, 0x12));

        float radius = 18f;
        Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        panel.setColor(Color.rgb(0x14, 0x17, 0x1F));
        RectF bounds = new RectF(14, 14, VrUi.PANEL_WIDTH - 14, VrUi.PANEL_HEIGHT - 14);
        canvas.drawRoundRect(bounds, radius, radius, panel);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f);
        border.setColor(Color.rgb(0x2A, 0x31, 0x42));
        canvas.drawRoundRect(bounds, radius, radius, border);

        drawHeader(canvas, context);
        drawStatus(canvas);
        drawGrid(canvas, apps);
        drawPointer(canvas, pointerX, pointerY, pointerVisible);
        return bitmap;
    }

    private static void drawHeader(Canvas canvas, Context context) {
        Paint title = text(38, Color.rgb(0xF2, 0xF5, 0xFF), true);
        canvas.drawText("VR 桌面", 40, 76, title);

        Paint small = text(18, Color.rgb(0x8E, 0x99, 0xB3), false);
        String stamp = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String hint = ShellState.glassPresent
                ? "眼镜已连接 · 用手机屏幕当触控板 · 轻点确认"
                : "等待眼镜接入 · " + stamp;
        canvas.drawText(hint, 42, 104, small);
        canvas.drawText("手柄：" + ShellState.controllerNote, 42, 130, small);
    }

    private static void drawStatus(Canvas canvas) {
        Paint label = text(19, Color.rgb(0x7F, 0xD1, 0xFF), true);
        Paint value = text(19, Color.rgb(0xC8, 0xD2, 0xE6), false);

        float y = 176;
        canvas.drawText("姿态", 40, y, label);
        String pose = ShellState.glassPresent
                ? (ShellState.displayName == null ? "3DOF（手机 IMU）" : "运行中")
                : "未启动";
        canvas.drawText(pose, 110, y, value);

        canvas.drawText("文件", 300, y, label);
        canvas.drawText("见手机端列表", 370, y, value);

        canvas.drawText("网格", 610, y, label);
        canvas.drawText(ShellState.controllerNote, 680, y, value);

        Paint divider = new Paint();
        divider.setColor(Color.rgb(0x2A, 0x31, 0x42));
        canvas.drawRect(40, 200, VrUi.PANEL_WIDTH - 40, 201, divider);
    }

    private static void drawGrid(Canvas canvas, List<AppCatalog.Entry> apps) {
        Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        tile.setColor(Color.rgb(0x1E, 0x23, 0x31));

        Paint tileBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        tileBorder.setStyle(Paint.Style.STROKE);
        tileBorder.setStrokeWidth(2f);
        tileBorder.setColor(Color.rgb(0x33, 0x3C, 0x52));

        Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setStrokeWidth(4f);
        highlight.setColor(Color.rgb(0x5A, 0xC8, 0xFA));

        Paint label = text(19, Color.rgb(0xEC, 0xF1, 0xFA), false);
        Paint plus = text(34, Color.rgb(0x5A, 0xC8, 0xFA), true);

        for (int slot = 0; slot < VrUi.SLOTS; slot++) {
            float left = VrUi.tileLeft(slot);
            float top = VrUi.tileTop(slot);
            RectF rect = new RectF(left, top, left + VrUi.TILE_WIDTH, top + VrUi.TILE_HEIGHT);

            if (slot < apps.size()) {
                canvas.drawRoundRect(rect, 12, 12, tile);
                canvas.drawRoundRect(rect, 12, 12, tileBorder);
                AppCatalog.Entry entry = apps.get(slot);
                drawCentered(canvas, entry.label, label, rect, 1);
            } else if (slot == apps.size()) {
                // 第一格空位画成「＋」，点它就打开手机端的应用选择器。
                canvas.drawRoundRect(rect, 12, 12, tile);
                canvas.drawRoundRect(rect, 12, 12, tileBorder);
                canvas.drawText("＋", rect.centerX() - plus.measureText("＋") / 2f,
                        rect.centerY() + 12, plus);
            } else {
                tileBorder.setColor(Color.rgb(0x22, 0x28, 0x36));
                canvas.drawRoundRect(rect, 12, 12, tileBorder);
                tileBorder.setColor(Color.rgb(0x33, 0x3C, 0x52));
            }
        }

        if (ShellActivity.selectedSlot >= 0 && ShellActivity.selectedSlot < VrUi.SLOTS) {
            int slot = ShellActivity.selectedSlot;
            RectF rect = new RectF(VrUi.tileLeft(slot), VrUi.tileTop(slot),
                    VrUi.tileLeft(slot) + VrUi.TILE_WIDTH,
                    VrUi.tileTop(slot) + VrUi.TILE_HEIGHT);
            canvas.drawRoundRect(rect, 12, 12, highlight);
        }
    }

    /** 名称居中，一行放不下就缩成两行（长应用名很常见）。 */
    private static void drawCentered(Canvas canvas, String text, Paint paint,
                                     RectF rect, int lines) {
        String value = text == null ? "" : text;
        float maxWidth = rect.width() - 24;
        if (paint.measureText(value) <= maxWidth) {
            canvas.drawText(value, rect.centerX() - paint.measureText(value) / 2f,
                    rect.centerY() + paint.getTextSize() / 3f, paint);
            return;
        }
        int split = value.length() / 2;
        String first = value.substring(0, split);
        String second = value.substring(split);
        while (paint.measureText(first) > maxWidth && first.length() > 1) {
            second = first.substring(first.length() - 1) + second;
            first = first.substring(0, first.length() - 1);
        }
        while (paint.measureText(second) > maxWidth && second.length() > 1) {
            second = second.substring(0, second.length() - 1);
        }
        if (lines >= 2) {
            canvas.drawText(first, rect.centerX() - paint.measureText(first) / 2f,
                    rect.centerY() - 6, paint);
            canvas.drawText(second, rect.centerX() - paint.measureText(second) / 2f,
                    rect.centerY() + 20, paint);
        } else {
            String withEllipsis = second.isEmpty() ? first : first + "…";
            canvas.drawText(withEllipsis, rect.centerX() - paint.measureText(withEllipsis) / 2f,
                    rect.centerY() + paint.getTextSize() / 3f, paint);
        }
    }

    private static void drawPointer(Canvas canvas, float x, float y, boolean visible) {
        if (!visible) {
            return;
        }
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(Color.argb(90, 0x5A, 0xC8, 0xFA));
        canvas.drawCircle(x, y, 22, glow);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.rgb(0xE8, 0xF6, 0xFF));
        canvas.drawCircle(x, y, 8, dot);

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f);
        ring.setColor(Color.rgb(0x5A, 0xC8, 0xFA));
        canvas.drawCircle(x, y, 14, ring);
    }

    private static Paint text(float size, int color, boolean bold) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return paint;
    }
}
