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
    /** The long limit in overlapped dials: between the track and the full fill. */
    private static final int LIGHT = Color.argb(120, 0, 0, 0);

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
                requestedHeight, primaryIconRes, secondaryIconRes);
    }

    /**
     * Two dials (or one when {@code secondaryIconRes} is 0). A {@code *Long} value >= 0 overlaps
     * that dial's long limit in a lighter shade.
     */
    static Bitmap render(Context context, SamsungLockWidgetSupport.Shape shape,
            SamsungLockWidgetSupport.Style style, int primary, int primaryLong, int secondary,
            int secondaryLong, boolean signedIn, int requestedWidth, int requestedHeight,
            int primaryIconRes, int secondaryIconRes) {
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
            drawDial(canvas, paint, width / 2.0f, top, dialScale, fiveHour, primaryLong,
                    primaryIcon);
            return bitmap;
        }
        float spacer = (width - (92.0f * dialScale)) / 3.0f;
        Drawable secondaryIcon = context == null ? null : context.getDrawable(secondaryIconRes);
        drawDial(canvas, paint, spacer + (23.0f * dialScale), top, dialScale,
                fiveHour, primaryLong, primaryIcon);
        drawDial(canvas, paint, (2.0f * spacer) + (69.0f * dialScale), top, dialScale,
                weekly, secondaryLong, secondaryIcon);
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

    /** One gauge; {@code longValue} >= 0 overlaps the long limit in a lighter shade. */
    static Bitmap renderSingle(Context context, int value, int longValue, boolean signedIn,
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
        drawDial(canvas, paint, width / 2.0f, top, dialScale, value, longValue, icon);
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
            int value, int longValue, Drawable icon) {
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
        if (longValue >= 0) {
            drawOverlap(canvas, paint, arc, value, longValue);
        } else if (value >= 0) {
            paint.setColor(FOREGROUND);
            canvas.drawArc(arc, 156.43f, clampPercent(value) * 2.2714f, false, paint);
        }

        if (icon != null) {
            int size = Math.max(7, Math.round(20.0f * scale));
            int left = Math.round(cx - (size / 2.0f));
            int iconTop = Math.round(graphicTop + (12.0f * scale));
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
        canvas.drawText(longValue >= 0 ? MeterOverlap.text(value, longValue, false)
                        : value < 0 ? "—" : clampPercent(value) + "%", cx,
                top + (48.0f * scale), paint);
    }

    /**
     * Larger fill underneath, smaller on top. The colors are translucent black (hosts tint lock
     * widgets), so the top fill's span is cleared first to keep the lighter shade visible.
     */
    private static void drawOverlap(Canvas canvas, Paint paint, RectF arc, int shortValue,
            int longValue) {
        boolean longUnder = MeterOverlap.longUnderneath(shortValue, longValue);
        int under = MeterOverlap.under(shortValue, longValue);
        int over = MeterOverlap.over(shortValue, longValue);
        if (under >= 0) {
            paint.setColor(longUnder ? LIGHT : FOREGROUND);
            canvas.drawArc(arc, 156.43f, under * 2.2714f, false, paint);
        }
        if (over >= 0) {
            paint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.CLEAR));
            canvas.drawArc(arc, 156.43f, over * 2.2714f, false, paint);
            paint.setXfermode(null);
            paint.setColor(longUnder ? FOREGROUND : LIGHT);
            canvas.drawArc(arc, 156.43f, over * 2.2714f, false, paint);
        }
    }

    /**
     * Overlapped bars, one row per provider: its mark, a pill with the short limit over the long
     * one, and the two values. {@code rows} holds {icon, short, long} triples.
     */
    static Bitmap renderBars(Context context, int[][] rows, int requestedWidth,
            int requestedHeight) {
        int width = clamp(requestedWidth, 44, 200, 124);
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
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        int count = Math.max(1, Math.min(3, rows.length));
        float rowHeight = 14.0f;
        float gap = count == 1 ? 0 : Math.min(8.0f, (height - 8.0f - count * rowHeight) / (count - 1));
        float y = (height - (count * rowHeight + (count - 1) * gap)) / 2.0f;
        Typeface sec = Typeface.create("sec", Typeface.NORMAL);
        Typeface bold = Build.VERSION.SDK_INT >= 28
                ? Typeface.create(sec, 600, false) : Typeface.create("sec", Typeface.BOLD);
        for (int index = 0; index < count; index++) {
            int[] row = rows[index];
            float cy = y + rowHeight / 2.0f;
            Drawable icon = context == null || row[0] == 0 ? null : context.getDrawable(row[0]);
            float left = 6.0f;
            if (icon != null) {
                int size = 12;
                icon.setBounds(Math.round(left), Math.round(cy - size / 2.0f),
                        Math.round(left) + size, Math.round(cy - size / 2.0f) + size);
                icon.setTint(FOREGROUND);
                icon.draw(canvas);
                left += 16.0f;
            }
            paint.setTypeface(bold);
            paint.setTextSize(10.0f);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(FOREGROUND);
            String text = MeterOverlap.text(row[1], row[2], false);
            float textWidth = paint.measureText("100 · 100");
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(text, width - 6.0f, cy - (fm.ascent + fm.descent) / 2.0f, paint);
            float right = width - 10.0f - textWidth;
            float barHeight = 8.0f;
            RectF track = new RectF(left, cy - barHeight / 2.0f, right, cy + barHeight / 2.0f);
            paint.setColor(TRACK);
            canvas.drawRoundRect(track, barHeight / 2.0f, barHeight / 2.0f, paint);
            boolean longUnder = MeterOverlap.longUnderneath(row[1], row[2]);
            int under = MeterOverlap.under(row[1], row[2]);
            int over = MeterOverlap.over(row[1], row[2]);
            if (under > 0) {
                paint.setColor(longUnder ? LIGHT : FOREGROUND);
                canvas.drawRoundRect(fill(track, under), barHeight / 2.0f, barHeight / 2.0f,
                        paint);
            }
            if (over > 0) {
                RectF top = fill(track, over);
                paint.setXfermode(new android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.CLEAR));
                canvas.drawRoundRect(top, barHeight / 2.0f, barHeight / 2.0f, paint);
                paint.setXfermode(null);
                paint.setColor(longUnder ? FOREGROUND : LIGHT);
                canvas.drawRoundRect(top, barHeight / 2.0f, barHeight / 2.0f, paint);
            }
            y += rowHeight + gap;
        }
        return bitmap;
    }

    private static RectF fill(RectF track, int percent) {
        float w = Math.max(track.height(), track.width() * Math.min(100, percent) / 100.0f);
        return new RectF(track.left, track.top, track.left + w, track.bottom);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value <= 0 ? fallback : Math.max(min, Math.min(max, value));
    }
}
