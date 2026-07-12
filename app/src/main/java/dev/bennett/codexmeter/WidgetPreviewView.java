package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetPreviewView extends View {
    private final RectF cardRect;
    private WidgetOptions options;
    private final Paint paint;

    public WidgetPreviewView(Context context) {
        super(context);
        this.paint = new Paint(129);
        this.cardRect = new RectF();
        this.options = WidgetOptions.defaults();
        setMinimumHeight(Ui.dp(context, 250.0f));
        setLayerType(1, null);
    }

    public void setOptions(WidgetOptions widgetOptions) {
        if (widgetOptions == null) {
            widgetOptions = WidgetOptions.defaults();
        }
        this.options = widgetOptions;
        invalidate();
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        int iRgb;
        super.onDraw(canvas);
        float f = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        boolean zPreviewDark = previewDark();
        boolean zEquals = WidgetOptions.SURFACE_ONE_UI.equals(this.options.surfaceStyle);
        int iRgb2 = zPreviewDark ? -1 : Color.rgb(17, 19, 21);
        int iArgb = zPreviewDark ? Color.argb(210, 255, 255, 255) : Color.argb(205, 17, 19, 21);
        int iArgb2 = zPreviewDark ? Color.argb(145, 255, 255, 255) : Color.argb(145, 17, 19, 21);
        int iTrackColor = WidgetGraphics.trackColor(zPreviewDark);
        int iAccentColor = WidgetGraphics.accentColor(getContext(), this.options.accent,
                zPreviewDark);
        if (zEquals) {
            iRgb = zPreviewDark ? Color.rgb(26, 29, 33) : Color.rgb(250, 250, 252);
        } else {
            iRgb = zPreviewDark ? Color.rgb(27, 26, 31) : Color.rgb(243, 240, 247);
        }
        int iRound = Math.round((255.0f * this.options.opacity) / 100.0f);
        float f2 = (zEquals ? 32.0f : 28.0f) * f;
        this.cardRect.set(1.5f * f, 1.5f * f, width - (1.5f * f), height - (1.5f * f));
        if (this.options.opacity > 0) {
            this.paint.setStyle(Paint.Style.FILL);
            this.paint.setPathEffect(null);
            this.paint.setColor(Color.argb(iRound, Color.red(iRgb), Color.green(iRgb), Color.blue(iRgb)));
            canvas.drawRoundRect(this.cardRect, f2, f2, this.paint);
        } else {
            this.paint.setStyle(Paint.Style.STROKE);
            this.paint.setStrokeWidth(1.0f * f);
            this.paint.setPathEffect(new DashPathEffect(new float[]{5.0f * f, 5.0f * f}, 0.0f));
            this.paint.setColor(zPreviewDark ? Color.argb(55, 255, 255, 255) : Color.argb(45, 0, 0, 0));
            canvas.drawRoundRect(this.cardRect, f2, f2, this.paint);
            this.paint.setPathEffect(null);
        }
        float f3 = 18.0f * f;
        float f4 = width - (18.0f * f);
        float f5 = 29.0f * f;
        if (this.options.showTitle) {
            text(canvas, "CODEX", f3, f5, 12.0f * f, iRgb2, Paint.Align.LEFT, true);
        }
        if (this.options.showPlan) {
            text(canvas, "PRO", width - (48.0f * f), f5, 11.0f * f, iArgb2, Paint.Align.RIGHT, true);
        }
        if (this.options.showRefresh) {
            text(canvas, "↻", width - (18.0f * f), f5 + (1.0f * f), 22.0f * f, iRgb2, Paint.Align.RIGHT, true);
        }
        boolean z = this.options.showTitle || this.options.showPlan || this.options.showRefresh;
        boolean z2 = this.options.showResetCredits || this.options.showResetAction;
        float f6 = 14.0f * f;
        if (this.options.showUpdated) {
            f6 += 18.0f * f;
        }
        if (z2) {
            f6 += 34.0f * f;
        }
        float f7 = (z ? 48.0f : 20.0f) * f;
        float fMax = Math.max((42.0f * f) + f7, height - f6);
        String strNormalizeStyle = WidgetOptions.normalizeStyle(this.options.layout);
        if ("auto".equals(strNormalizeStyle)) {
            strNormalizeStyle = WidgetOptions.STYLE_BARS;
        }
        if (WidgetOptions.STYLE_RINGS.equals(strNormalizeStyle)) {
            drawRings(canvas, f3, f7, f4, fMax, iRgb2, iArgb, iArgb2, iTrackColor, iAccentColor);
        } else if (WidgetOptions.STYLE_DIALS.equals(strNormalizeStyle)) {
            drawDials(canvas, f3, f7, f4, fMax, iRgb2, iArgb, iArgb2, iTrackColor, iAccentColor);
        } else if (WidgetOptions.STYLE_MINIMAL.equals(strNormalizeStyle)) {
            drawMinimal(canvas, f3, f7, f4, fMax, iRgb2, iArgb, iArgb2, iTrackColor, iAccentColor);
        } else {
            drawBars(canvas, f3, f7, f4, fMax, iRgb2, iArgb, iArgb2, iTrackColor, iAccentColor);
        }
        float f8 = height - (14.0f * f);
        if (z2) {
            drawResetCredits(canvas, f3, f4, f8 - (this.options.showUpdated ? 18.0f * f : 0.0f), iRgb2, iArgb2, iAccentColor, zPreviewDark);
        }
        if (this.options.showUpdated) {
            text(canvas, "Updated just now", f3, f8, 10.0f * f, iArgb2, Paint.Align.LEFT, false);
        }
    }

    private void drawBars(Canvas canvas, float f, float f2, float f3, float f4, int i, int i2, int i3, int i4, int i5) {
        float f5 = getResources().getDisplayMetrics().density;
        if (this.options.singleMetric()) {
            boolean zShowsFiveHour = this.options.showsFiveHour();
            drawBarRow(canvas, zShowsFiveHour ? "5-hour" : "Weekly", zShowsFiveHour ? 73 : 44, f, Math.max(0.0f, ((f4 - f2) - Math.min(f4 - f2, 78.0f * f5)) / 2.0f) + f2 + (5.0f * f5), f3, i, i2, i3, i4, i5, zShowsFiveHour ? "Resets today at 5:36 PM" : "Resets Friday at 12:36 PM");
        } else {
            drawBarRow(canvas, "5-hour", 73, f, f2 + (5.0f * f5), f3, i, i2, i3, i4, i5, "Resets today at 5:36 PM");
            drawBarRow(canvas, "Weekly", 44, f, ((f4 - f2) / 2.0f) + f2 + (1.0f * f5), f3, i, i2, i3, i4, i5, "Resets Friday at 12:36 PM");
        }
    }

    private void drawBarRow(Canvas canvas, String str, int i, float f, float f2, float f3, int i2, int i3, int i4, int i5, int i6, String str2) {
        float f4 = getResources().getDisplayMetrics().density;
        text(canvas, str, f, f2 + (14.0f * f4), 12.0f * f4, i3, Paint.Align.LEFT, true);
        text(canvas, i + "% " + qualifier(), f3, f2 + (14.0f * f4), this.options.singleMetric() ? 21.0f * f4 : 18.0f * f4, i2, Paint.Align.RIGHT, true);
        float f5 = (24.0f * f4) + f2;
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setColor(i5);
        canvas.drawRoundRect(new RectF(f, f5, f3, (8.0f * f4) + f5), 5.0f * f4, 5.0f * f4, this.paint);
        this.paint.setColor(i6);
        canvas.drawRoundRect(new RectF(f, f5, (((f3 - f) * i) / 100.0f) + f, (8.0f * f4) + f5), 5.0f * f4, 5.0f * f4, this.paint);
        if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
            text(canvas, str2, f, f5 + (25.0f * f4), 10.0f * f4, i4, Paint.Align.LEFT, false);
        }
    }

    private void drawMinimal(Canvas canvas, float f, float f2, float f3, float f4, int i, int i2, int i3, int i4, int i5) {
        float f5 = getResources().getDisplayMetrics().density;
        if (this.options.singleMetric()) {
            boolean zShowsFiveHour = this.options.showsFiveHour();
            float f6 = ((f4 - f2) / 2.0f) + f2 + (3.0f * f5);
            drawMinimalRow(canvas, zShowsFiveHour ? "5h" : "Week", zShowsFiveHour ? 73 : 44, f, f6, f3, i, i2, i4, i5);
            if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
                text(canvas, zShowsFiveHour ? "Resets in 2h 14m" : "Resets in 4d", f, Math.min(f4 - (2.0f * f5), (24.0f * f5) + f6), 10.0f * f5, i3, Paint.Align.LEFT, false);
                return;
            }
            return;
        }
        float fMax = f2 + Math.max(31.0f * f5, (f4 - f2) * 0.34f);
        drawMinimalRow(canvas, "5h", 73, f, fMax, f3, i, i2, i4, i5);
        drawMinimalRow(canvas, "Week", 44, f, fMax + (43.0f * f5), f3, i, i2, i4, i5);
        if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
            text(canvas, "5h in 2h 14m · Week in 4d", f, f4 - (3.0f * f5), 10.0f * f5, i3, Paint.Align.LEFT, false);
        }
    }

    private void drawMinimalRow(Canvas canvas, String str, int i, float f, float f2, float f3, int i2, int i3, int i4, int i5) {
        float f4 = getResources().getDisplayMetrics().density;
        text(canvas, str, f, f2, 11.0f * f4, i3, Paint.Align.LEFT, true);
        float f5 = (50.0f * f4) + f;
        float f6 = f3 - (54.0f * f4);
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setColor(i4);
        canvas.drawRoundRect(new RectF(f5, f2 - (8.0f * f4), f6, f2), 5.0f * f4, 5.0f * f4, this.paint);
        this.paint.setColor(i5);
        canvas.drawRoundRect(new RectF(f5, f2 - (8.0f * f4), (((f6 - f5) * i) / 100.0f) + f5, f2), 5.0f * f4, 5.0f * f4, this.paint);
        text(canvas, i + "%", f3, f2 + (1.0f * f4), this.options.singleMetric() ? 18.0f * f4 : 15.0f * f4, i2, Paint.Align.RIGHT, true);
    }

    private void drawRings(Canvas canvas, float f, float f2, float f3, float f4, int i, int i2, int i3, int i4, int i5) {
        float f5 = getResources().getDisplayMetrics().density;
        float fGraphicFactor = graphicFactor();
        float f6 = WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode) ? 24.0f * f5 : 40.0f * f5;
        if (this.options.singleMetric()) {
            boolean zShowsFiveHour = this.options.showsFiveHour();
            float fMax = Math.max(40.0f * f5, (f4 - f2) - f6);
            float fMax2 = Math.max(27.0f * f5, Math.min(fGraphicFactor * (f3 - f) * 0.25f, 0.48f * fMax));
            float f7 = (f + f3) / 2.0f;
            float f8 = f2 + (fMax / 2.0f);
            drawRing(canvas, f7, f8, fMax2, zShowsFiveHour ? 73 : 44, i, i4, i5);
            float f9 = f8 + fMax2 + (15.0f * f5);
            text(canvas, zShowsFiveHour ? "5-hour" : "Weekly", f7, f9, 11.0f * f5, i2, Paint.Align.CENTER, true);
            if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
                text(canvas, zShowsFiveHour ? "5:36 PM" : "Fri 12:36 PM", f7, Math.min(f4 - (2.0f * f5), (15.0f * f5) + f9), 9.0f * f5, i3, Paint.Align.CENTER, false);
                return;
            }
            return;
        }
        float f10 = (f3 - f) / 4.0f;
        float fMin = Math.min(Math.min(fGraphicFactor * (f3 - f) * 0.16f, Math.max(18.0f * f5, ((f4 - f2) - f6) / 2.0f)), 0.87f * f10);
        float f11 = f2 + fMin + (4.0f * f5);
        drawRing(canvas, f + f10, f11, fMin, 73, i, i4, i5);
        drawRing(canvas, f + (3.0f * f10), f11, fMin, 44, i, i4, i5);
        float f12 = f11 + fMin + (18.0f * f5);
        text(canvas, "5-hour", f + f10, f12, 11.0f * f5, i2, Paint.Align.CENTER, true);
        text(canvas, "Weekly", f + (3.0f * f10), f12, 11.0f * f5, i2, Paint.Align.CENTER, true);
        if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
            text(canvas, "5:36 PM", f + f10, f12 + (16.0f * f5), 9.0f * f5, i3, Paint.Align.CENTER, false);
            text(canvas, "Fri 12:36 PM", f + (3.0f * f10), f12 + (16.0f * f5), 9.0f * f5, i3, Paint.Align.CENTER, false);
        }
    }

    private void drawRing(Canvas canvas, float f, float f2, float f3, int i, int i2, int i3, int i4) {
        float f4 = getResources().getDisplayMetrics().density;
        float fMax = Math.max(7.0f * f4, 0.16f * f3);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setStrokeWidth(fMax);
        RectF rectF = new RectF(f - f3, f2 - f3, f + f3, f2 + f3);
        this.paint.setColor(i3);
        canvas.drawArc(rectF, -90.0f, 360.0f, false, this.paint);
        this.paint.setColor(i4);
        canvas.drawArc(rectF, -90.0f, (360.0f * i) / 100.0f, false, this.paint);
        text(canvas, i + "%", f, f2 + (7.0f * f4), Math.min(28.0f, Math.max(17.0f, (f3 / f4) * 0.4f)) * f4, i2, Paint.Align.CENTER, true);
    }

    private void drawDials(Canvas canvas, float f, float f2, float f3, float f4, int i, int i2, int i3, int i4, int i5) {
        float f5 = getResources().getDisplayMetrics().density;
        float fGraphicFactor = graphicFactor();
        float f6 = WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode) ? 20.0f * f5 : 37.0f * f5;
        if (this.options.singleMetric()) {
            boolean zShowsFiveHour = this.options.showsFiveHour();
            float fMax = Math.max(42.0f * f5, (f4 - f2) - f6);
            float fMax2 = Math.max(31.0f * f5, Math.min(fGraphicFactor * (f3 - f) * 0.28f, 0.62f * fMax));
            float f7 = (f + f3) / 2.0f;
            float f8 = f2 + (fMax * 0.5f);
            drawDial(canvas, f7, f8, fMax2, zShowsFiveHour ? 73 : 44, i, i4, i5);
            float f9 = (0.72f * fMax2) + f8 + (16.0f * f5);
            text(canvas, zShowsFiveHour ? "5-hour" : "Weekly", f7, f9, 11.0f * f5, i2, Paint.Align.CENTER, true);
            if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
                text(canvas, zShowsFiveHour ? "5:36 PM" : "Fri 12:36 PM", f7, Math.min(f4 - (2.0f * f5), (15.0f * f5) + f9), 9.0f * f5, i3, Paint.Align.CENTER, false);
                return;
            }
            return;
        }
        float f10 = (f3 - f) / 4.0f;
        float fMin = Math.min(Math.min(fGraphicFactor * (f3 - f) * 0.19f, Math.max(22.0f * f5, ((f4 - f2) - f6) * 0.58f)), 0.9f * f10);
        float f11 = f2 + (0.78f * fMin);
        drawDial(canvas, f + f10, f11, fMin, 73, i, i4, i5);
        drawDial(canvas, f + (3.0f * f10), f11, fMin, 44, i, i4, i5);
        float f12 = (0.72f * fMin) + f11 + (18.0f * f5);
        text(canvas, "5-hour", f + f10, f12, 11.0f * f5, i2, Paint.Align.CENTER, true);
        text(canvas, "Weekly", f + (3.0f * f10), f12, 11.0f * f5, i2, Paint.Align.CENTER, true);
        if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
            text(canvas, "5:36 PM", f + f10, f4 - (3.0f * f5), 9.0f * f5, i3, Paint.Align.CENTER, false);
            text(canvas, "Fri 12:36 PM", f + (3.0f * f10), f4 - (3.0f * f5), 9.0f * f5, i3, Paint.Align.CENTER, false);
        }
    }

    private void drawDial(Canvas canvas, float f, float f2, float f3, int i, int i2, int i3, int i4) {
        float f4 = getResources().getDisplayMetrics().density;
        float fMax = Math.max(7.0f * f4, 0.15f * f3);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setStrokeWidth(fMax);
        RectF rectF = new RectF(f - f3, f2 - f3, f + f3, f2 + f3);
        this.paint.setColor(i3);
        canvas.drawArc(rectF, 145.0f, 250.0f, false, this.paint);
        this.paint.setColor(i4);
        canvas.drawArc(rectF, 145.0f, (250.0f * i) / 100.0f, false, this.paint);
        text(canvas, i + "%", f, f2 + (8.0f * f4), Math.min(28.0f, Math.max(17.0f, (f3 / f4) * 0.38f)) * f4, i2, Paint.Align.CENTER, true);
    }

    private void drawResetCredits(Canvas canvas, float f, float f2, float f3, int i, int i2, int i3, boolean z) {
        float f4 = getResources().getDisplayMetrics().density;
        float f5 = f3 - (13.0f * f4);
        float f6 = this.options.showResetAction ? 78.0f * f4 : 0.0f;
        if (this.options.showResetCredits) {
            text(canvas, "2 resets · expires in 3d", f, f5 + (4.0f * f4), 10.0f * f4, i2, Paint.Align.LEFT, false);
        }
        if (this.options.showResetAction) {
            RectF rectF = new RectF(f2 - f6, f5 - (14.0f * f4), f2, (14.0f * f4) + f5);
            this.paint.setStyle(Paint.Style.FILL);
            this.paint.setColor(z ? Color.argb(38, 255, 255, 255) : Color.argb(24, 0, 0, 0));
            canvas.drawRoundRect(rectF, 14.0f * f4, 14.0f * f4, this.paint);
            text(canvas, "Use reset", rectF.centerX(), f5 + (4.0f * f4), 10.0f * f4, i, Paint.Align.CENTER, true);
            if (!this.options.showResetCredits) {
                text(canvas, "2 resets available", f, f5 + (4.0f * f4), 10.0f * f4, i2, Paint.Align.LEFT, false);
            }
        }
    }

    private float graphicFactor() {
        if (WidgetOptions.GRAPHIC_MAX.equals(this.options.graphicScale)) {
            return 1.42f;
        }
        return WidgetOptions.GRAPHIC_LARGE.equals(this.options.graphicScale) ? 1.22f : 1.0f;
    }

    private String qualifier() {
        return WidgetOptions.DISPLAY_USED.equals(this.options.displayMode) ? WidgetOptions.DISPLAY_USED : "left";
    }

    private boolean previewDark() {
        if (WidgetOptions.THEME_DARK.equals(this.options.theme)) {
            return true;
        }
        return !WidgetOptions.THEME_LIGHT.equals(this.options.theme) && (getResources().getConfiguration().uiMode & 48) == 32;
    }

    private void text(Canvas canvas, String str, float f, float f2, float f3, int i, Paint.Align align, boolean z) {
        this.paint.setPathEffect(null);
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setTextAlign(align);
        this.paint.setTextSize(f3);
        this.paint.setColor(i);
        this.paint.setTypeface(Typeface.create(WidgetOptions.SURFACE_ONE_UI.equals(this.options.surfaceStyle) ? "sec" : "sans-serif", z ? 1 : 0));
        canvas.drawText(str, f, f2, this.paint);
    }
}
