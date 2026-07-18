package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetGraphics {
    private WidgetGraphics() {
    }

    public static Bitmap ring(int i, int i2, int i3, int i4, String str) {
        return ring(i, i2, i3, i4, str, 1.0f);
    }

    public static Bitmap ring(int i, int i2, int i3, int i4, String str, float f) {
        return batteryDial(null, i, 0, i2, i3, i4, f);
    }

    public static Bitmap batteryDial(Context context, int value, int iconRes, int progressColor,
            int trackColor, int textColor, float scale) {
        return compactDial(context, value, iconRes, progressColor, trackColor, textColor,
                value < 0 ? "—" : Integer.toString(clamp(value)), scale);
    }

    public static Bitmap compactDial(Context context, int value, int iconRes, int progressColor,
            int trackColor, int textColor, String valueText, float scale) {
        float fClampScale = clampScale(scale);
        // Match the established 56x56 One UI dial geometry once fit into a 58dp row.
        // Keeping this canvas tight prevents four-column widgets from scaling the arc down
        // merely to accommodate unused horizontal bitmap padding.
        int width = Math.round(150.0f * fClampScale);
        int height = Math.round(132.0f * fClampScale);
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmapCreateBitmap.setDensity(160);
        Canvas canvas = new Canvas(bitmapCreateBitmap);
        Paint paint = new Paint(1);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(17.0f * fClampScale);
        float cx = width / 2.0f;
        float cy = 64.0f * fClampScale;
        float radius = 56.0f * fClampScale;
        RectF rectF = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setColor(trackColor);
        // One UI's dial leaves a wider opening at the bottom (roughly 228 degrees),
        // rather than curling the arc ends inward like a 270-degree gauge.
        final float arcStart = 156.0f;
        final float arcSweep = 228.0f;
        canvas.drawArc(rectF, arcStart, arcSweep, false, paint);
        if (value >= 0) {
            paint.setColor(progressColor);
            canvas.drawArc(rectF, arcStart, (arcSweep * clamp(value)) / 100.0f,
                    false, paint);
        }

        if (context != null && iconRes != 0) {
            Drawable icon = context.getDrawable(iconRes);
            if (icon != null) {
                int iconSize = Math.round(52.0f * fClampScale);
                int left = Math.round(cx - (iconSize / 2.0f));
                int top = Math.round(cy - (iconSize / 2.0f));
                icon.setBounds(left, top, left + iconSize, top + iconSize);
                icon.setTint(textColor);
                icon.draw(canvas);
            }
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sec", 1));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(textColor);
        paint.setTextSize((valueText != null && valueText.length() > 5 ? 24.0f : 33.0f)
                * fClampScale);
        canvas.drawText(valueText == null ? "—" : valueText, cx, 121.0f * fClampScale, paint);
        return bitmapCreateBitmap;
    }

    public static Bitmap dial(int i, int i2, int i3, int i4, String str) {
        return dial(i, i2, i3, i4, str, 1.0f);
    }

    public static Bitmap dial(int i, int i2, int i3, int i4, String str, float f) {
        return dialInternal(i, i2, i3, i4, str, f);
    }

    /**
     * M3E dial — thicker stroke, black display type, stronger end-cap.
     * Canvas and oval stay fully in-bounds so home-screen RemoteViews never clip the arc.
     */
    public static Bitmap expressiveDial(int value, int progressColor, int trackColor, int textColor,
            String label, float scale) {
        float s = clampScale(scale);
        // Compact canvas for 2x1 hosts. All strokes and labels remain inside the bitmap.
        int width = Math.round(160.0f * s);
        int height = Math.round(158.0f * s);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float stroke = 18.0f * s;
        paint.setStrokeWidth(stroke);
        float pad = (stroke * 0.5f) + 4.0f * s;
        boolean hasLabel = label != null && !label.isEmpty();
        float chipHeight = hasLabel ? 22.0f * s : 0.0f;
        // The label chip occupies the dial's open bottom rather than shrinking the arc.
        float arcSpan = Math.min(width - (pad * 2.0f), height - (pad * 2.0f));
        float cx = width / 2.0f;
        float cy = pad + (arcSpan / 2.0f);
        RectF oval = new RectF(cx - (arcSpan / 2.0f), cy - (arcSpan / 2.0f),
                cx + (arcSpan / 2.0f), cy + (arcSpan / 2.0f));
        final float arcStart = 150.0f;
        final float arcSweep = 240.0f;
        paint.setColor(trackColor);
        canvas.drawArc(oval, arcStart, arcSweep, false, paint);
        if (value >= 0) {
            paint.setColor(progressColor);
            float sweep = (arcSweep * clamp(value)) / 100.0f;
            canvas.drawArc(oval, arcStart, sweep, false, paint);
            double radians = Math.toRadians(arcStart + sweep);
            float radius = oval.width() / 2.0f;
            float tipX = oval.centerX() + (((float) Math.cos(radians)) * radius);
            float tipY = oval.centerY() + (((float) Math.sin(radians)) * radius);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(tipX, tipY, 10.0f * s, paint);
            paint.setColor(withAlpha(textColor, 0.22f));
            canvas.drawCircle(tipX, tipY, 4.4f * s, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        paint.setColor(textColor);
        paint.setTextSize(34.0f * s);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float valueBaseline = cy - ((fm.ascent + fm.descent) / 2.0f);
        canvas.drawText(value < 0 ? "—" : clamp(value) + "%", cx, valueBaseline, paint);
        if (hasLabel) {
            float chipWidth = 50.0f * s;
            float chipTop = height - chipHeight - (2.0f * s);
            RectF chip = new RectF(cx - (chipWidth / 2.0f), chipTop,
                    cx + (chipWidth / 2.0f), chipTop + chipHeight);
            paint.setColor(withAlpha(progressColor, 0.14f));
            canvas.drawRoundRect(chip, chipHeight / 2.0f, chipHeight / 2.0f, paint);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            paint.setTextSize(16.0f * s);
            paint.setColor(progressColor);
            Paint.FontMetrics chipFm = paint.getFontMetrics();
            float chipBaseline = chip.centerY() - ((chipFm.ascent + chipFm.descent) / 2.0f);
            canvas.drawText(label, cx, chipBaseline, paint);
        }
        return bitmap;
    }

    private static Bitmap dialInternal(int i, int i2, int i3, int i4, String str, float f) {
        // One UI path only — keep the established geometry untouched.
        float fClampScale = clampScale(f);
        int iRound = Math.round(260.0f * fClampScale);
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(iRound, Math.round(190.0f * fClampScale), Bitmap.Config.ARGB_8888);
        bitmapCreateBitmap.setDensity(160);
        Canvas canvas = new Canvas(bitmapCreateBitmap);
        Paint paint = new Paint(1);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(20.0f * fClampScale);
        RectF rectF = new RectF(22.0f * fClampScale, 20.0f * fClampScale, iRound - (22.0f * fClampScale), iRound - (24.0f * fClampScale));
        paint.setColor(i3);
        canvas.drawArc(rectF, 145.0f, 250.0f, false, paint);
        if (i >= 0) {
            paint.setColor(i2);
            float sweep = (250.0f * clamp(i)) / 100.0f;
            canvas.drawArc(rectF, 145.0f, sweep, false, paint);
            double radians = Math.toRadians(145.0f + sweep);
            float fWidth = rectF.width() / 2.0f;
            float fCenterX = rectF.centerX() + (((float) Math.cos(radians)) * fWidth);
            float fCenterY = rectF.centerY() + (((float) Math.sin(radians)) * fWidth);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(fCenterX, fCenterY, 9.5f * fClampScale, paint);
            paint.setColor(withAlpha(i4, 0.22f));
            canvas.drawCircle(fCenterX, fCenterY, 4.2f * fClampScale, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setColor(i4);
        paint.setTextSize(48.0f * fClampScale);
        canvas.drawText(i < 0 ? "—" : clamp(i) + "%", iRound / 2.0f, 130.0f * fClampScale, paint);
        paint.setTypeface(Typeface.create("sans-serif-medium", 0));
        paint.setTextSize(19.0f * fClampScale);
        paint.setColor(withAlpha(i4, 0.68f));
        if (str == null) {
            str = "";
        }
        canvas.drawText(str, iRound / 2.0f, 160.0f * fClampScale, paint);
        return bitmapCreateBitmap;
    }

    public static int accentColor(Context context, String str, boolean z) {
        if (WidgetOptions.ACCENT_APP.equals(str)) {
            return Ui.accent(context, z);
        }
        return accentColor(str, z);
    }

    public static int accentColor(String str, boolean z) {
        if (WidgetOptions.ACCENT_BLUE.equals(str)) {
            return z ? Color.rgb(92, 169, 255) : Color.rgb(3, 129, 254);
        }
        if (WidgetOptions.ACCENT_AMBER.equals(str)) {
            return Color.rgb(244, 185, 95);
        }
        if (WidgetOptions.ACCENT_VIOLET.equals(str)) {
            return Color.rgb(155, 140, 255);
        }
        if (WidgetOptions.ACCENT_ROSE.equals(str)) {
            return Color.rgb(255, 122, 162);
        }
        if (WidgetOptions.ACCENT_CYAN.equals(str)) {
            return Color.rgb(71, 200, 232);
        }
        if (WidgetOptions.ACCENT_LIME.equals(str)) {
            return Color.rgb(164, 214, 94);
        }
        return WidgetOptions.ACCENT_MONO.equals(str) ? z ? Color.rgb(244, 247, 248) : Color.rgb(32, 35, 38) : z ? Color.rgb(66, 214, 164) : Color.rgb(20, 168, 121);
    }

    public static int trackColor(boolean z) {
        return z ? Color.argb(72, 255, 255, 255) : Color.argb(48, 17, 19, 21);
    }

    public static int mainTextColor(boolean z) {
        if (z) {
            return -1;
        }
        return Color.rgb(17, 19, 21);
    }

    private static int withAlpha(int i, float f) {
        return Color.argb(Math.round(255.0f * f), Color.red(i), Color.green(i), Color.blue(i));
    }

    private static int clamp(int i) {
        return Math.max(0, Math.min(100, i));
    }

    private static float clampScale(float f) {
        return Math.max(0.8f, Math.min(1.36f, f));
    }
}
