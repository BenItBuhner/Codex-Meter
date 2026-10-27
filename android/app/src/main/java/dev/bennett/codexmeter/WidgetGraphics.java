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
        return compactDial(context, value, -1, iconRes, progressColor, trackColor, textColor,
                valueText, scale);
    }

    /**
     * Compact dial; with {@code longValue} >= 0 the long limit shares the arc in a lighter shade
     * of {@code progressColor} (see {@link MeterOverlap}).
     */
    public static Bitmap compactDial(Context context, int value, int longValue, int iconRes,
            int progressColor, int trackColor, int textColor, String valueText, float scale) {
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
        if (longValue >= 0) {
            drawOverlapArcs(canvas, paint, rectF, arcStart, arcSweep, value, longValue,
                    progressColor, overlapColor(progressColor, trackColor));
        } else if (value >= 0) {
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
        paint.setTypeface(Typeface.create("sec", Typeface.BOLD));
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
        return dial(i, i2, i3, i4, i < 0 ? "—" : clamp(i) + "%", str, f);
    }

    /** Gauge dial with an explicit center value (countdowns, credit counts, plain percents). */
    public static Bitmap dial(int i, int i2, int i3, int i4, String centerText, String str,
            float f) {
        return dial(i, -1, i2, i3, i4, centerText, str, f);
    }

    /** Large dial; {@code longValue} >= 0 overlaps the long limit in a lighter shade. */
    public static Bitmap dial(int i, int longValue, int i2, int i3, int i4, String centerText,
            String str, float f) {
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
        if (longValue >= 0) {
            drawOverlapArcs(canvas, paint, rectF, 145.0f, 250.0f, i, longValue, i2,
                    overlapColor(i2, i3));
        } else if (i >= 0) {
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
        String center = centerText == null || centerText.isEmpty() ? "—" : centerText;
        // Percent values fit at full size; longer countdown strings scale down to stay inside.
        float centerSize = center.length() <= 4
                ? 48.0f : Math.max(24.0f, 48.0f * (4.5f / center.length()));
        paint.setTextSize(centerSize * fClampScale);
        canvas.drawText(center, iRound / 2.0f, 130.0f * fClampScale, paint);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(19.0f * fClampScale);
        paint.setColor(withAlpha(i4, 0.68f));
        if (str == null) {
            str = "";
        }
        canvas.drawText(str, iRound / 2.0f, 160.0f * fClampScale, paint);
        return bitmapCreateBitmap;
    }

    /**
     * Larger fill behind, smaller in front. Colors follow position, not the limit: the front fill
     * keeps the full accent and the one behind it a shade between the accent and the track, so
     * the meter steps evenly from the front fill to the widget. Equal values show as one fill.
     */
    static void drawOverlapArcs(Canvas canvas, Paint paint, RectF rect, float start,
            float sweep, int shortValue, int longValue, int frontColor, int backColor) {
        int under = MeterOverlap.under(shortValue, longValue);
        int over = MeterOverlap.over(shortValue, longValue);
        if (under >= 0) {
            paint.setColor(over >= 0 ? backColor : frontColor);
            canvas.drawArc(rect, start, (sweep * under) / 100.0f, false, paint);
        }
        if (over >= 0) {
            paint.setColor(frontColor);
            canvas.drawArc(rect, start, (sweep * over) / 100.0f, false, paint);
        }
    }

    /**
     * The back fill's shade: the accent mixed halfway toward the track as it shows on the widget,
     * kept opaque. Mixing toward the track keeps the order accent, shade, track in both themes.
     */
    public static int overlapColor(int accent, int track) {
        // Tracks are translucent: light ink on dark widgets, dark ink on light ones.
        int base = Color.red(track) > 128 ? Color.BLACK : Color.WHITE;
        float alpha = Color.alpha(track) / 255.0f;
        float mix = 0.5f;
        return Color.rgb(
                blend(Color.red(accent), blend(Color.red(base), Color.red(track), alpha), mix),
                blend(Color.green(accent), blend(Color.green(base), Color.green(track), alpha), mix),
                blend(Color.blue(accent), blend(Color.blue(base), Color.blue(track), alpha), mix));
    }

    private static int blend(int from, int to, float amount) {
        return Math.round(from + (to - from) * amount);
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
