package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import dev.bennett.codexmeter.SamsungLockWidgetSupport;

/* JADX INFO: loaded from: classes.dex */
final class SamsungLockGraphics {
    private static final float SAFE = 3.0f;
    private static final int TRACK = Color.argb(66, 255, 255, 255);
    private static final int WHITE = -1;

    private SamsungLockGraphics() {
    }

    static Bitmap render(Context context, SamsungLockWidgetSupport.Shape shape, SamsungLockWidgetSupport.Style style, int i, int i2, boolean z, int i3, int i4, LockWidgetOptions lockWidgetOptions, int i5) {
        int iClamp = clamp(i3, 44, shape == SamsungLockWidgetSupport.Shape.SQUARE ? 96 : 200, shape == SamsungLockWidgetSupport.Shape.SQUARE ? 56 : 124);
        int iClamp2 = clamp(i4, 44, 96, 56);
        DisplayMetrics displayMetrics = context == null ? null : context.getResources().getDisplayMetrics();
        float fMax = Math.max(4.0f, Math.min(4.5f, (displayMetrics == null || displayMetrics.density <= 0.0f) ? 1.0f : displayMetrics.density));
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(Math.max(1, Math.round(iClamp * fMax)), Math.max(1, Math.round(iClamp2 * fMax)), Bitmap.Config.ARGB_8888);
        bitmapCreateBitmap.setDensity(Math.round(160.0f * fMax));
        bitmapCreateBitmap.eraseColor(0);
        if (!z) {
            return bitmapCreateBitmap;
        }
        Canvas canvas = new Canvas(bitmapCreateBitmap);
        canvas.scale(fMax, fMax);
        Paint paint = new Paint(7);
        paint.setDither(true);
        paint.setFilterBitmap(true);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        LockWidgetOptions lockWidgetOptionsDefaults = lockWidgetOptions == null ? LockWidgetOptions.defaults() : lockWidgetOptions;
        RectF rectF = new RectF(SAFE, SAFE, iClamp - SAFE, iClamp2 - SAFE);
        if (style == SamsungLockWidgetSupport.Style.RINGS) {
            drawRings(canvas, paint, shape, i, i2, lockWidgetOptionsDefaults, rectF);
        } else if (style == SamsungLockWidgetSupport.Style.DIALS) {
            drawDials(canvas, paint, shape, i, i2, lockWidgetOptionsDefaults, rectF);
        }
        return bitmapCreateBitmap;
    }

    private static void drawRings(Canvas canvas, Paint paint, SamsungLockWidgetSupport.Shape shape, int i, int i2, LockWidgetOptions lockWidgetOptions, RectF rectF) {
        if (lockWidgetOptions.singleMetric()) {
            int i3 = lockWidgetOptions.showsFiveHour() ? i : i2;
            float fMin = Math.min(rectF.width(), rectF.height()) * 0.43f;
            drawRing(canvas, paint, rectF.centerX(), rectF.centerY(), fMin, Math.max(3.1f, fMin * 0.18f), i3);
        } else {
            if (shape == SamsungLockWidgetSupport.Shape.SQUARE) {
                float fMin2 = Math.min(rectF.width(), rectF.height()) * 0.43f;
                float f = fMin2 * 0.66f;
                drawRing(canvas, paint, rectF.centerX(), rectF.centerY(), fMin2, Math.max(2.8f, 0.14f * fMin2), i2);
                drawRing(canvas, paint, rectF.centerX(), rectF.centerY(), f, Math.max(2.6f, 0.19f * f), i);
                return;
            }
            float fWidth = rectF.width() / 2.0f;
            float fMin3 = Math.min(fWidth, rectF.height()) * 0.4f;
            float fMax = Math.max(SAFE, fMin3 * 0.18f);
            drawRing(canvas, paint, rectF.left + (0.5f * fWidth), rectF.centerY(), fMin3, fMax, i);
            drawRing(canvas, paint, rectF.left + (1.5f * fWidth), rectF.centerY(), fMin3, fMax, i2);
        }
    }

    private static void drawRing(Canvas canvas, Paint paint, float f, float f2, float f3, float f4, int i) {
        float f5 = (f4 / 2.0f) + 0.3f;
        RectF rectF = new RectF((f - f3) + f5, (f2 - f3) + f5, (f + f3) - f5, (f2 + f3) - f5);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(f4);
        paint.setColor(TRACK);
        canvas.drawArc(rectF, -90.0f, 360.0f, false, paint);
        if (i >= 0) {
            paint.setColor(WHITE);
            canvas.drawArc(rectF, -90.0f, (percent(i) * 360.0f) / 100.0f, false, paint);
        }
    }

    private static void drawDials(Canvas canvas, Paint paint, SamsungLockWidgetSupport.Shape shape, int i, int i2, LockWidgetOptions lockWidgetOptions, RectF rectF) {
        if (lockWidgetOptions.singleMetric()) {
            if (lockWidgetOptions.showsFiveHour()) {
                i2 = i;
            }
            float fMin = Math.min(rectF.width(), rectF.height()) * 0.9f;
            drawDial(canvas, paint, centered(rectF.centerX(), rectF.centerY() + 1.0f, fMin), i2, Math.max(3.4f, fMin * 0.1f));
            return;
        }
        if (shape == SamsungLockWidgetSupport.Shape.SQUARE) {
            float fMin2 = Math.min(rectF.width(), rectF.height()) * 0.9f;
            float f = 0.66f * fMin2;
            drawDial(canvas, paint, centered(rectF.centerX(), rectF.centerY() + 1.0f, fMin2), i2, Math.max(SAFE, fMin2 * 0.085f));
            drawDial(canvas, paint, centered(rectF.centerX(), rectF.centerY() + 1.0f, f), i, Math.max(2.8f, f * 0.11f));
            return;
        }
        float fWidth = rectF.width() / 2.0f;
        float fMin3 = Math.min(0.86f * fWidth, rectF.height() * 0.9f);
        float fMax = Math.max(3.4f, fMin3 * 0.1f);
        drawDial(canvas, paint, centered(rectF.left + (0.5f * fWidth), rectF.centerY() + 1.0f, fMin3), i, fMax);
        drawDial(canvas, paint, centered((fWidth * 1.5f) + rectF.left, rectF.centerY() + 1.0f, fMin3), i2, fMax);
    }

    private static void drawDial(Canvas canvas, Paint paint, RectF rectF, int i, float f) {
        float f2 = (f / 2.0f) + 0.3f;
        RectF rectF2 = new RectF(rectF.left + f2, rectF.top + f2, rectF.right - f2, rectF.bottom - f2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(f);
        paint.setColor(TRACK);
        canvas.drawArc(rectF2, 135.0f, 270.0f, false, paint);
        if (i >= 0) {
            paint.setColor(WHITE);
            canvas.drawArc(rectF2, 135.0f, (percent(i) * 270.0f) / 100.0f, false, paint);
        }
    }

    private static RectF centered(float f, float f2, float f3) {
        float f4 = f3 / 2.0f;
        return new RectF(f - f4, f2 - f4, f + f4, f4 + f2);
    }

    private static int percent(int i) {
        return Math.max(0, Math.min(100, i));
    }

    private static int clamp(int i, int i2, int i3, int i4) {
        return i <= 0 ? i4 : Math.max(i2, Math.min(i3, i));
    }
}
