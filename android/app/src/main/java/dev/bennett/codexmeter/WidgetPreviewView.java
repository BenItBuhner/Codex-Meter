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
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
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
        int cardColor;
        super.onDraw(canvas);
        float dp = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        boolean dark = previewDark();
        boolean oneUi = WidgetOptions.SURFACE_ONE_UI.equals(this.options.surfaceStyle);
        int textColor = dark ? -1 : Color.rgb(17, 19, 21);
        int subtextColor = dark ? Color.argb(210, 255, 255, 255) : Color.argb(205, 17, 19, 21);
        int mutedColor = dark ? Color.argb(145, 255, 255, 255) : Color.argb(145, 17, 19, 21);
        int trackColor = WidgetGraphics.trackColor(dark);
        int accentColor = WidgetGraphics.accentColor(getContext(), this.options.accent,
                dark);
        if (oneUi) {
            cardColor = dark ? Color.rgb(26, 29, 33) : Color.rgb(250, 250, 252);
        } else {
            cardColor = dark ? Color.rgb(27, 26, 31) : Color.rgb(243, 240, 247);
        }
        int alpha = Math.round((255.0f * this.options.opacity) / 100.0f);
        float corner = (oneUi ? 32.0f : 28.0f) * dp;
        this.cardRect.set(1.5f * dp, 1.5f * dp, width - (1.5f * dp), height - (1.5f * dp));
        if (this.options.opacity > 0) {
            this.paint.setStyle(Paint.Style.FILL);
            this.paint.setPathEffect(null);
            this.paint.setColor(Color.argb(alpha, Color.red(cardColor), Color.green(cardColor), Color.blue(cardColor)));
            canvas.drawRoundRect(this.cardRect, corner, corner, this.paint);
        } else {
            this.paint.setStyle(Paint.Style.STROKE);
            this.paint.setStrokeWidth(1.0f * dp);
            this.paint.setPathEffect(new DashPathEffect(new float[]{5.0f * dp, 5.0f * dp}, 0.0f));
            this.paint.setColor(dark ? Color.argb(55, 255, 255, 255) : Color.argb(45, 0, 0, 0));
            canvas.drawRoundRect(this.cardRect, corner, corner, this.paint);
            this.paint.setPathEffect(null);
        }
        float left = 18.0f * dp;
        float right = width - (18.0f * dp);
        float headerY = 29.0f * dp;
        if (this.options.showTitle) {
            text(canvas, "CODEX", left, headerY, 12.0f * dp, textColor, Paint.Align.LEFT, true);
        }
        if (this.options.showPlan) {
            text(canvas, "PRO", width - (48.0f * dp), headerY, 11.0f * dp, mutedColor, Paint.Align.RIGHT, true);
        }
        if (this.options.showRefresh) {
            text(canvas, "↻", width - (18.0f * dp), headerY + (1.0f * dp), 22.0f * dp, textColor, Paint.Align.RIGHT, true);
        }
        boolean hasHeader = this.options.showTitle || this.options.showPlan || this.options.showRefresh;
        boolean hasResetRow = this.options.showResetCredits || this.options.showResetAction;
        float footer = 14.0f * dp;
        if (this.options.showUpdated) {
            footer += 18.0f * dp;
        }
        if (hasResetRow) {
            footer += 34.0f * dp;
        }
        float top = (hasHeader ? 48.0f : 20.0f) * dp;
        float bottom = Math.max((42.0f * dp) + top, height - footer);
        String style = WidgetOptions.normalizeStyle(this.options.layout);
        if ("auto".equals(style)) {
            style = WidgetOptions.STYLE_BARS;
        }
        if (WidgetOptions.STYLE_RINGS.equals(style)) {
            drawRings(canvas, left, top, right, bottom, textColor, subtextColor, mutedColor, trackColor, accentColor);
        } else if (WidgetOptions.STYLE_DIALS.equals(style)) {
            drawDials(canvas, left, top, right, bottom, textColor, subtextColor, mutedColor, trackColor, accentColor);
        } else if (WidgetOptions.STYLE_MINIMAL.equals(style)) {
            drawMinimal(canvas, left, top, right, bottom, textColor, subtextColor, mutedColor, trackColor, accentColor);
        } else {
            drawBars(canvas, left, top, right, bottom, textColor, subtextColor, mutedColor, trackColor, accentColor);
        }
        float footerY = height - (14.0f * dp);
        if (hasResetRow) {
            drawResetCredits(canvas, left, right, footerY - (this.options.showUpdated ? 18.0f * dp : 0.0f), textColor, mutedColor, accentColor, dark);
        }
        if (this.options.showUpdated) {
            text(canvas, "Updated just now", left, footerY, 10.0f * dp, mutedColor, Paint.Align.LEFT, false);
        }
    }

    private void drawBars(Canvas canvas, float left, float top, float right, float bottom, int textColor, int subtextColor, int mutedColor, int trackColor, int accentColor) {
        float dp = getResources().getDisplayMetrics().density;
        if (this.options.singleMetric()) {
            boolean fiveHour = this.options.showsFiveHour();
            drawBarRow(canvas, fiveHour ? "5-hour" : "Weekly", fiveHour ? 73 : 44, left, Math.max(0.0f, ((bottom - top) - Math.min(bottom - top, 78.0f * dp)) / 2.0f) + top + (5.0f * dp), right, textColor, subtextColor, mutedColor, trackColor, accentColor, fiveHour ? "Resets today at 5:36 PM" : "Resets Friday at 12:36 PM");
        } else {
            drawBarRow(canvas, "5-hour", 73, left, top + (5.0f * dp), right, textColor, subtextColor, mutedColor, trackColor, accentColor, "Resets today at 5:36 PM");
            drawBarRow(canvas, "Weekly", 44, left, ((bottom - top) / 2.0f) + top + (1.0f * dp), right, textColor, subtextColor, mutedColor, trackColor, accentColor, "Resets Friday at 12:36 PM");
        }
    }

    private void drawBarRow(Canvas canvas, String label, int percent, float left, float top, float right, int textColor, int labelColor, int resetColor, int trackColor, int accentColor, String resetText) {
        float dp = getResources().getDisplayMetrics().density;
        text(canvas, label, left, top + (14.0f * dp), 12.0f * dp, labelColor, Paint.Align.LEFT, true);
        text(canvas, percent + "% " + qualifier(), right, top + (14.0f * dp), this.options.singleMetric() ? 21.0f * dp : 18.0f * dp, textColor, Paint.Align.RIGHT, true);
        float barY = (24.0f * dp) + top;
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setColor(trackColor);
        canvas.drawRoundRect(new RectF(left, barY, right, (8.0f * dp) + barY), 5.0f * dp, 5.0f * dp, this.paint);
        this.paint.setColor(accentColor);
        canvas.drawRoundRect(new RectF(left, barY, (((right - left) * percent) / 100.0f) + left, (8.0f * dp) + barY), 5.0f * dp, 5.0f * dp, this.paint);
        if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
            text(canvas, resetText, left, barY + (25.0f * dp), 10.0f * dp, resetColor, Paint.Align.LEFT, false);
        }
    }

    private void drawMinimal(Canvas canvas, float left, float top, float right, float bottom, int textColor, int subtextColor, int mutedColor, int trackColor, int accentColor) {
        float dp = getResources().getDisplayMetrics().density;
        if (this.options.singleMetric()) {
            boolean fiveHour = this.options.showsFiveHour();
            float rowY = ((bottom - top) / 2.0f) + top + (3.0f * dp);
            drawMinimalRow(canvas, fiveHour ? "5h" : "Week", fiveHour ? 73 : 44, left, rowY, right, textColor, subtextColor, trackColor, accentColor);
            if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
                text(canvas, fiveHour ? "Resets in 2h 14m" : "Resets in 4d", left, Math.min(bottom - (2.0f * dp), (24.0f * dp) + rowY), 10.0f * dp, mutedColor, Paint.Align.LEFT, false);
                return;
            }
            return;
        }
        float firstRowY = top + Math.max(31.0f * dp, (bottom - top) * 0.34f);
        drawMinimalRow(canvas, "5h", 73, left, firstRowY, right, textColor, subtextColor, trackColor, accentColor);
        drawMinimalRow(canvas, "Week", 44, left, firstRowY + (43.0f * dp), right, textColor, subtextColor, trackColor, accentColor);
        if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
            text(canvas, "5h in 2h 14m · Week in 4d", left, bottom - (3.0f * dp), 10.0f * dp, mutedColor, Paint.Align.LEFT, false);
        }
    }

    private void drawMinimalRow(Canvas canvas, String label, int percent, float left, float baseline, float right, int textColor, int labelColor, int trackColor, int accentColor) {
        float dp = getResources().getDisplayMetrics().density;
        text(canvas, label, left, baseline, 11.0f * dp, labelColor, Paint.Align.LEFT, true);
        float barLeft = (50.0f * dp) + left;
        float barRight = right - (54.0f * dp);
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setColor(trackColor);
        canvas.drawRoundRect(new RectF(barLeft, baseline - (8.0f * dp), barRight, baseline), 5.0f * dp, 5.0f * dp, this.paint);
        this.paint.setColor(accentColor);
        canvas.drawRoundRect(new RectF(barLeft, baseline - (8.0f * dp), (((barRight - barLeft) * percent) / 100.0f) + barLeft, baseline), 5.0f * dp, 5.0f * dp, this.paint);
        text(canvas, percent + "%", right, baseline + (1.0f * dp), this.options.singleMetric() ? 18.0f * dp : 15.0f * dp, textColor, Paint.Align.RIGHT, true);
    }

    private void drawRings(Canvas canvas, float left, float top, float right, float bottom, int textColor, int subtextColor, int mutedColor, int trackColor, int accentColor) {
        float dp = getResources().getDisplayMetrics().density;
        float factor = graphicFactor();
        float resetSpace = WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode) ? 24.0f * dp : 40.0f * dp;
        if (this.options.singleMetric()) {
            boolean fiveHour = this.options.showsFiveHour();
            float area = Math.max(40.0f * dp, (bottom - top) - resetSpace);
            float radius = Math.max(27.0f * dp, Math.min(factor * (right - left) * 0.25f, 0.48f * area));
            float centerX = (left + right) / 2.0f;
            float centerY = top + (area / 2.0f);
            drawRing(canvas, centerX, centerY, radius, fiveHour ? 73 : 44, textColor, trackColor, accentColor);
            float labelY = centerY + radius + (15.0f * dp);
            text(canvas, fiveHour ? "5-hour" : "Weekly", centerX, labelY, 11.0f * dp, subtextColor, Paint.Align.CENTER, true);
            if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
                text(canvas, fiveHour ? "5:36 PM" : "Fri 12:36 PM", centerX, Math.min(bottom - (2.0f * dp), (15.0f * dp) + labelY), 9.0f * dp, mutedColor, Paint.Align.CENTER, false);
                return;
            }
            return;
        }
        float quarter = (right - left) / 4.0f;
        float pairRadius = Math.min(Math.min(factor * (right - left) * 0.16f, Math.max(18.0f * dp, ((bottom - top) - resetSpace) / 2.0f)), 0.87f * quarter);
        float pairY = top + pairRadius + (4.0f * dp);
        drawRing(canvas, left + quarter, pairY, pairRadius, 73, textColor, trackColor, accentColor);
        drawRing(canvas, left + (3.0f * quarter), pairY, pairRadius, 44, textColor, trackColor, accentColor);
        float pairLabelY = pairY + pairRadius + (18.0f * dp);
        text(canvas, "5-hour", left + quarter, pairLabelY, 11.0f * dp, subtextColor, Paint.Align.CENTER, true);
        text(canvas, "Weekly", left + (3.0f * quarter), pairLabelY, 11.0f * dp, subtextColor, Paint.Align.CENTER, true);
        if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
            text(canvas, "5:36 PM", left + quarter, pairLabelY + (16.0f * dp), 9.0f * dp, mutedColor, Paint.Align.CENTER, false);
            text(canvas, "Fri 12:36 PM", left + (3.0f * quarter), pairLabelY + (16.0f * dp), 9.0f * dp, mutedColor, Paint.Align.CENTER, false);
        }
    }

    private void drawRing(Canvas canvas, float centerX, float centerY, float radius, int percent, int textColor, int trackColor, int accentColor) {
        float dp = getResources().getDisplayMetrics().density;
        float stroke = Math.max(7.0f * dp, 0.16f * radius);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setStrokeWidth(stroke);
        RectF bounds = new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        this.paint.setColor(trackColor);
        canvas.drawArc(bounds, -90.0f, 360.0f, false, this.paint);
        this.paint.setColor(accentColor);
        canvas.drawArc(bounds, -90.0f, (360.0f * percent) / 100.0f, false, this.paint);
        text(canvas, percent + "%", centerX, centerY + (7.0f * dp), Math.min(28.0f, Math.max(17.0f, (radius / dp) * 0.4f)) * dp, textColor, Paint.Align.CENTER, true);
    }

    private void drawDials(Canvas canvas, float left, float top, float right, float bottom, int textColor, int subtextColor, int mutedColor, int trackColor, int accentColor) {
        float dp = getResources().getDisplayMetrics().density;
        float factor = graphicFactor();
        float resetSpace = WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode) ? 20.0f * dp : 37.0f * dp;
        if (this.options.singleMetric()) {
            boolean fiveHour = this.options.showsFiveHour();
            float area = Math.max(42.0f * dp, (bottom - top) - resetSpace);
            float radius = Math.max(31.0f * dp, Math.min(factor * (right - left) * 0.28f, 0.62f * area));
            float centerX = (left + right) / 2.0f;
            float centerY = top + (area * 0.5f);
            drawDial(canvas, centerX, centerY, radius, fiveHour ? 73 : 44, textColor, trackColor, accentColor);
            float labelY = (0.72f * radius) + centerY + (16.0f * dp);
            text(canvas, fiveHour ? "5-hour" : "Weekly", centerX, labelY, 11.0f * dp, subtextColor, Paint.Align.CENTER, true);
            if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
                text(canvas, fiveHour ? "5:36 PM" : "Fri 12:36 PM", centerX, Math.min(bottom - (2.0f * dp), (15.0f * dp) + labelY), 9.0f * dp, mutedColor, Paint.Align.CENTER, false);
                return;
            }
            return;
        }
        float quarter = (right - left) / 4.0f;
        float pairRadius = Math.min(Math.min(factor * (right - left) * 0.19f, Math.max(22.0f * dp, ((bottom - top) - resetSpace) * 0.58f)), 0.9f * quarter);
        float pairY = top + (0.78f * pairRadius);
        drawDial(canvas, left + quarter, pairY, pairRadius, 73, textColor, trackColor, accentColor);
        drawDial(canvas, left + (3.0f * quarter), pairY, pairRadius, 44, textColor, trackColor, accentColor);
        float pairLabelY = (0.72f * pairRadius) + pairY + (18.0f * dp);
        text(canvas, "5-hour", left + quarter, pairLabelY, 11.0f * dp, subtextColor, Paint.Align.CENTER, true);
        text(canvas, "Weekly", left + (3.0f * quarter), pairLabelY, 11.0f * dp, subtextColor, Paint.Align.CENTER, true);
        if (!WidgetOptions.RESET_HIDDEN.equals(this.options.resetMode)) {
            text(canvas, "5:36 PM", left + quarter, bottom - (3.0f * dp), 9.0f * dp, mutedColor, Paint.Align.CENTER, false);
            text(canvas, "Fri 12:36 PM", left + (3.0f * quarter), bottom - (3.0f * dp), 9.0f * dp, mutedColor, Paint.Align.CENTER, false);
        }
    }

    private void drawDial(Canvas canvas, float centerX, float centerY, float radius, int percent, int textColor, int trackColor, int accentColor) {
        float dp = getResources().getDisplayMetrics().density;
        float stroke = Math.max(7.0f * dp, 0.15f * radius);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setStrokeWidth(stroke);
        RectF bounds = new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        this.paint.setColor(trackColor);
        canvas.drawArc(bounds, 145.0f, 250.0f, false, this.paint);
        this.paint.setColor(accentColor);
        canvas.drawArc(bounds, 145.0f, (250.0f * percent) / 100.0f, false, this.paint);
        text(canvas, percent + "%", centerX, centerY + (8.0f * dp), Math.min(28.0f, Math.max(17.0f, (radius / dp) * 0.38f)) * dp, textColor, Paint.Align.CENTER, true);
    }

    private void drawResetCredits(Canvas canvas, float left, float right, float baseline, int textColor, int mutedColor, int accentColor, boolean dark) {
        float dp = getResources().getDisplayMetrics().density;
        float centerY = baseline - (13.0f * dp);
        float buttonWidth = this.options.showResetAction ? 78.0f * dp : 0.0f;
        if (this.options.showResetCredits) {
            text(canvas, "2 resets · expires in 3d", left, centerY + (4.0f * dp), 10.0f * dp, mutedColor, Paint.Align.LEFT, false);
        }
        if (this.options.showResetAction) {
            RectF button = new RectF(right - buttonWidth, centerY - (14.0f * dp), right, (14.0f * dp) + centerY);
            this.paint.setStyle(Paint.Style.FILL);
            this.paint.setColor(dark ? Color.argb(38, 255, 255, 255) : Color.argb(24, 0, 0, 0));
            canvas.drawRoundRect(button, 14.0f * dp, 14.0f * dp, this.paint);
            text(canvas, "Use reset", button.centerX(), centerY + (4.0f * dp), 10.0f * dp, textColor, Paint.Align.CENTER, true);
            if (!this.options.showResetCredits) {
                text(canvas, "2 resets available", left, centerY + (4.0f * dp), 10.0f * dp, mutedColor, Paint.Align.LEFT, false);
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

    private void text(Canvas canvas, String value, float x, float y, float size, int color, Paint.Align align, boolean bold) {
        this.paint.setPathEffect(null);
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setTextAlign(align);
        this.paint.setTextSize(size);
        this.paint.setColor(color);
        this.paint.setTypeface(Typeface.create(WidgetOptions.SURFACE_ONE_UI.equals(this.options.surfaceStyle) ? "sec" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        canvas.drawText(value, x, y, this.paint);
    }
}
