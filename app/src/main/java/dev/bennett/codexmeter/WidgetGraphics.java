package dev.bennett.codexmeter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetGraphics {
    private WidgetGraphics() {
    }

    public static Bitmap ring(int i, int i2, int i3, int i4, String str) {
        return ring(i, i2, i3, i4, str, 1.0f);
    }

    public static Bitmap ring(int i, int i2, int i3, int i4, String str, float f) {
        float fClampScale = clampScale(f);
        int iRound = Math.round(220.0f * fClampScale);
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(iRound, iRound, Bitmap.Config.ARGB_8888);
        bitmapCreateBitmap.setDensity(160);
        Canvas canvas = new Canvas(bitmapCreateBitmap);
        Paint paint = new Paint(1);
        float f2 = 20.0f * fClampScale;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(18.0f * fClampScale);
        RectF rectF = new RectF(f2, f2, iRound - f2, iRound - f2);
        paint.setColor(i3);
        canvas.drawArc(rectF, -90.0f, 360.0f, false, paint);
        if (i >= 0) {
            paint.setColor(i2);
            float fClamp = (360.0f * clamp(i)) / 100.0f;
            canvas.drawArc(rectF, -90.0f, fClamp, false, paint);
            if (fClamp > 1.0f) {
                double radians = Math.toRadians((-90.0f) + fClamp);
                float fWidth = rectF.width() / 2.0f;
                float fCenterX = rectF.centerX() + (((float) Math.cos(radians)) * fWidth);
                float fCenterY = rectF.centerY() + (((float) Math.sin(radians)) * fWidth);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(fCenterX, fCenterY, 8.5f * fClampScale, paint);
                paint.setColor(withAlpha(i4, 0.22f));
                canvas.drawCircle(fCenterX, fCenterY, 4.0f * fClampScale, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif", 1));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(i4);
        paint.setTextSize(48.0f * fClampScale);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        canvas.drawText(i < 0 ? "—" : clamp(i) + "%", iRound / 2.0f, ((iRound / 2.0f) - ((fontMetrics.ascent + fontMetrics.descent) / 2.0f)) - (8.0f * fClampScale), paint);
        paint.setTypeface(Typeface.create("sans-serif-medium", 0));
        paint.setTextSize(20.0f * fClampScale);
        paint.setColor(withAlpha(i4, 0.68f));
        if (str == null) {
            str = "";
        }
        canvas.drawText(str, iRound / 2.0f, (iRound / 2.0f) + (42.0f * fClampScale), paint);
        return bitmapCreateBitmap;
    }

    public static Bitmap dial(int i, int i2, int i3, int i4, String str) {
        return dial(i, i2, i3, i4, str, 1.0f);
    }

    public static Bitmap dial(int i, int i2, int i3, int i4, String str, float f) {
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
        paint.setTypeface(Typeface.create("sans-serif", 1));
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

    public static int accentColor(String str, boolean z) {
        if (WidgetOptions.ACCENT_BLUE.equals(str)) {
            return Color.rgb(91, 167, 255);
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
