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
    private SamsungLockGraphics() {
    }

    static Bitmap render(Context context, SamsungLockWidgetSupport.Shape shape,
            SamsungLockWidgetSupport.Style style, int fiveHour, int weekly, boolean signedIn,
            int requestedWidth, int requestedHeight, LockWidgetOptions options, int resetCredits) {
        return render(context, shape, style, fiveHour, weekly, signedIn, requestedWidth,
                requestedHeight, options, resetCredits, SamsungLockTheme.useLightInk(context));
    }

    static Bitmap render(Context context, SamsungLockWidgetSupport.Shape shape,
            SamsungLockWidgetSupport.Style style, int fiveHour, int weekly, boolean signedIn,
            int requestedWidth, int requestedHeight, LockWidgetOptions options, int resetCredits,
            boolean lightInk) {
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
        float spacer = (width - (92.0f * dialScale)) / 3.0f;
        Drawable fiveHourIcon = context == null ? null : context.getDrawable(R.drawable.ic_oui_time);
        Drawable weeklyIcon = context == null ? null : context.getDrawable(R.drawable.ic_oui_calendar_week);
        int foreground = SamsungLockTheme.ink(lightInk);
        int track = SamsungLockTheme.track(lightInk);
        drawDial(canvas, paint, spacer + (23.0f * dialScale), top, dialScale,
                fiveHour, fiveHourIcon, foreground, track);
        drawDial(canvas, paint, (2.0f * spacer) + (69.0f * dialScale), top, dialScale,
                weekly, weeklyIcon, foreground, track);
        return bitmap;
    }

    static Bitmap renderSingle(Context context, SamsungLockWidgetSupport.Metric metric, int value,
            boolean signedIn, int requestedWidth, int requestedHeight) {
        return renderSingle(context, metric, value, signedIn, requestedWidth, requestedHeight,
                SamsungLockTheme.useLightInk(context));
    }

    static Bitmap renderSingle(Context context, SamsungLockWidgetSupport.Metric metric, int value,
            boolean signedIn, int requestedWidth, int requestedHeight, boolean lightInk) {
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
        int foreground = SamsungLockTheme.ink(lightInk);
        int track = SamsungLockTheme.track(lightInk);
        if (!signedIn) {
            drawSignIn(canvas, paint, width, height, foreground);
            return bitmap;
        }
        float dialScale = Math.min(1.0f, Math.min(height / 56.0f, width / 46.0f));
        float top = (height - (56.0f * dialScale)) / 2.0f;
        Drawable icon = context == null ? null : context.getDrawable(
                metric == SamsungLockWidgetSupport.Metric.FIVE_HOUR
                        ? R.drawable.ic_oui_time
                        : R.drawable.ic_oui_calendar_week);
        drawDial(canvas, paint, width / 2.0f, top, dialScale, value, icon, foreground, track);
        return bitmap;
    }

    private static void drawSignIn(Canvas canvas, Paint paint, int width, int height, int foreground) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(foreground);
        paint.setTypeface(Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.create("sec", Typeface.NORMAL), 600, false)
                : Typeface.create("sec", Typeface.BOLD));
        paint.setTextSize(9.0f);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = (height / 2.0f) - ((metrics.ascent + metrics.descent) / 2.0f);
        canvas.drawText("SIGN IN", width / 2.0f, baseline, paint);
    }

    private static void drawDial(Canvas canvas, Paint paint, float cx, float top, float scale,
            int value, Drawable icon, int foreground, int track) {
        float graphicTop = top + (3.0f * scale);
        float cy = graphicTop + (23.0f * scale);
        float radius = 20.0f * scale;
        float stroke = 6.0f * scale;
        RectF arc = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        paint.setColor(track);
        canvas.drawArc(arc, 156.43f, 227.14f, false, paint);
        if (value >= 0) {
            paint.setColor(foreground);
            canvas.drawArc(arc, 156.43f, clampPercent(value) * 2.2714f, false, paint);
        }

        if (icon != null) {
            int size = Math.max(7, Math.round(20.0f * scale));
            int left = Math.round(cx - (size / 2.0f));
            int iconTop = Math.round(graphicTop + (12.0f * scale));
            icon.setBounds(left, iconTop, left + size, iconTop + size);
            icon.setTint(foreground);
            icon.draw(canvas);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(foreground);
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
