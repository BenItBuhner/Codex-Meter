package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;

/** Battery-widget-inspired monochrome dials for Samsung lock and AOD surfaces. */
final class SamsungLockGraphics {
    private static final int TRACK = Color.argb(51, 0, 0, 0);
    private static final int FOREGROUND = Color.BLACK;
    /** The five-hour ring inside a weekly dial: the same ink, a lighter shade. */
    private static final int INNER = Color.argb(140, 0, 0, 0);
    private static final int INNER_TRACK = Color.argb(31, 0, 0, 0);

    private SamsungLockGraphics() {
    }

    static Bitmap render(Context context, SamsungLockWidgetSupport.Shape shape,
            SamsungLockWidgetSupport.Style style, int fiveHour, int weekly, boolean signedIn,
            int requestedWidth, int requestedHeight, LockWidgetOptions options, int resetCredits) {
        return render(context, shape, style, fiveHour, weekly, signedIn, requestedWidth,
                requestedHeight, options, resetCredits,
                R.drawable.ic_oui_time, R.drawable.ic_oui_calendar_week);
    }

    static Bitmap render(Context context, SamsungLockWidgetSupport.Shape shape,
            SamsungLockWidgetSupport.Style style, int fiveHour, int weekly, boolean signedIn,
            int requestedWidth, int requestedHeight, LockWidgetOptions options, int resetCredits,
            int primaryIconRes, int secondaryIconRes) {
        return render(context, shape, style, fiveHour, -1, weekly, -1, signedIn, requestedWidth,
                requestedHeight, options, resetCredits, primaryIconRes, secondaryIconRes);
    }

    /**
     * Two dials. {@code primaryInner} and {@code secondaryInner} are the optional lighter
     * five-hour rings drawn inside a weekly dial (-1 for none).
     */
    static Bitmap render(Context context, SamsungLockWidgetSupport.Shape shape,
            SamsungLockWidgetSupport.Style style, int primary, int primaryInner, int secondary,
            int secondaryInner, boolean signedIn, int requestedWidth, int requestedHeight,
            LockWidgetOptions options, int resetCredits, int primaryIconRes,
            int secondaryIconRes) {
        int fiveHour = primary;
        int weekly = secondary;
        int width = clamp(requestedWidth, 44, shape == SamsungLockWidgetSupport.Shape.SQUARE ? 96 : 200,
                shape == SamsungLockWidgetSupport.Shape.SQUARE ? 56 : 124);
        int height = clamp(requestedHeight, 44, 96, 56);
        DisplayMetrics metrics = context == null ? null : context.getResources().getDisplayMetrics();
        float scale = Math.max(4.0f, Math.min(4.5f,
                metrics == null || metrics.density <= 0.0f ? 1.0f : metrics.density));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Math.round(160.0f * scale));
        bitmap.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (!signedIn) {
            return bitmap;
        }
        float dialScale = Math.min(1.0f, Math.min(height / 56.0f, width / 124.0f));
        float top = (height - (56.0f * dialScale)) / 2.0f;
        Drawable primaryIcon = context == null ? null : context.getDrawable(primaryIconRes);
        if (secondaryIconRes == 0) {
            // Only one meter to show: one centered dial instead of a blank second one.
            drawDial(canvas, paint, width / 2.0f, top, dialScale, fiveHour, primaryInner,
                    primaryIcon);
            return bitmap;
        }
        float spacer = (width - (92.0f * dialScale)) / 3.0f;
        Drawable secondaryIcon = context == null ? null : context.getDrawable(secondaryIconRes);
        drawDial(canvas, paint, spacer + (23.0f * dialScale), top, dialScale,
                fiveHour, primaryInner, primaryIcon);
        drawDial(canvas, paint, (2.0f * spacer) + (69.0f * dialScale), top, dialScale,
                weekly, secondaryInner, secondaryIcon);
        return bitmap;
    }

    static Bitmap renderSingle(Context context, SamsungLockWidgetSupport.Metric metric, int value,
            boolean signedIn, int requestedWidth, int requestedHeight) {
        return renderSingle(context, value, signedIn, requestedWidth, requestedHeight,
                metric == SamsungLockWidgetSupport.Metric.FIVE_HOUR
                        ? R.drawable.ic_oui_time : R.drawable.ic_oui_calendar_week);
    }

    /** One gauge with {@code iconRes} (a meter icon or a provider mark) in its center. */
    static Bitmap renderSingle(Context context, int value, boolean signedIn, int requestedWidth,
            int requestedHeight, int iconRes) {
        return renderSingle(context, value, -1, signedIn, requestedWidth, requestedHeight,
                iconRes);
    }

    /** One gauge; {@code inner} >= 0 adds the lighter five-hour ring inside it. */
    static Bitmap renderSingle(Context context, int value, int inner, boolean signedIn,
            int requestedWidth, int requestedHeight, int iconRes) {
        int width = clamp(requestedWidth, 44, 96, 56);
        int height = clamp(requestedHeight, 44, 96, 56);
        DisplayMetrics metrics = context == null ? null : context.getResources().getDisplayMetrics();
        float bitmapScale = Math.max(4.0f, Math.min(4.5f,
                metrics == null || metrics.density <= 0.0f ? 1.0f : metrics.density));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * bitmapScale)),
                Math.max(1, Math.round(height * bitmapScale)), Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Math.round(160.0f * bitmapScale));
        bitmap.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(bitmapScale, bitmapScale);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (!signedIn) {
            drawSignIn(canvas, paint, width, height);
            return bitmap;
        }
        float dialScale = Math.min(1.0f, Math.min(height / 56.0f, width / 46.0f));
        float top = (height - (56.0f * dialScale)) / 2.0f;
        Drawable icon = context == null ? null : context.getDrawable(iconRes);
        drawDial(canvas, paint, width / 2.0f, top, dialScale, value, inner, icon);
        return bitmap;
    }

    private static void drawSignIn(Canvas canvas, Paint paint, int width, int height) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(FOREGROUND);
        paint.setTypeface(Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.create("sec", Typeface.NORMAL), 600, false)
                : Typeface.create("sec", Typeface.BOLD));
        paint.setTextSize(9.0f);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = (height / 2.0f) - ((metrics.ascent + metrics.descent) / 2.0f);
        canvas.drawText("SIGN IN", width / 2.0f, baseline, paint);
    }

    private static void drawDial(Canvas canvas, Paint paint, float cx, float top, float scale,
            int value, int inner, Drawable icon) {
        float graphicTop = top + (3.0f * scale);
        float cy = graphicTop + (23.0f * scale);
        float radius = 20.0f * scale;
        float stroke = 6.0f * scale;
        RectF arc = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        paint.setColor(TRACK);
        canvas.drawArc(arc, 156.43f, 227.14f, false, paint);
        if (value >= 0) {
            paint.setColor(FOREGROUND);
            canvas.drawArc(arc, 156.43f, clampPercent(value) * 2.2714f, false, paint);
        }
        boolean nested = inner >= 0;
        if (nested) {
            // Both windows in one dial: weekly on the outer ring, five-hour as a thinner,
            // lighter ring just inside it.
            float innerRadius = 13.0f * scale;
            RectF innerArc = new RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius,
                    cy + innerRadius);
            paint.setStrokeWidth(3.0f * scale);
            paint.setColor(INNER_TRACK);
            canvas.drawArc(innerArc, 156.43f, 227.14f, false, paint);
            paint.setColor(INNER);
            canvas.drawArc(innerArc, 156.43f, clampPercent(inner) * 2.2714f, false, paint);
        }

        if (icon != null) {
            int size = Math.max(7, Math.round((nested ? 13.0f : 20.0f) * scale));
            int left = Math.round(cx - (size / 2.0f));
            int iconTop = Math.round(cy - (scale) - (size / 2.0f));
            icon.setBounds(left, iconTop, left + size, iconTop + size);
            icon.setTint(FOREGROUND);
            icon.draw(canvas);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(FOREGROUND);
        Typeface sec = Typeface.create("sec", Typeface.NORMAL);
        paint.setTypeface(Build.VERSION.SDK_INT >= 28
                ? Typeface.create(sec, 600, false)
                : Typeface.create("sec", Typeface.BOLD));
        paint.setTextSize(11.0f * scale);
        canvas.drawText(value < 0 ? "—" : clampPercent(value) + "%", cx,
                top + (48.0f * scale), paint);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value <= 0 ? fallback : Math.max(min, Math.min(max, value));
    }
}
