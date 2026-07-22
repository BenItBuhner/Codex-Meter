package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import java.util.Collections;
import java.util.List;

/** Compact local-history chart showing actual, sustainable, and projected quota burn. */
public final class UsageBurnChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final DashPathEffect budgetDash;
    private final DashPathEffect projectionDash;
    private final Typeface regularTypeface = Typeface.create("sec", Typeface.NORMAL);
    private final Typeface boldTypeface = Typeface.create("sec", Typeface.BOLD);
    private String label = "";
    private UsageWindow window;
    private List<UsageSample> samples = Collections.emptyList();
    private List<List<UsageSample>> windows = Collections.emptyList();
    private UsagePace.Assessment pace;
    private long observedAtMillis;

    public UsageBurnChartView(Context context) {
        this(context, null);
    }

    public UsageBurnChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        budgetDash = new DashPathEffect(new float[]{5f * density, 5f * density}, 0);
        projectionDash = new DashPathEffect(new float[]{7f * density, 5f * density}, 0);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setData(String label, UsageWindow window, UsageHistory history,
            long observedAtMillis, UsagePace.Assessment pace) {
        this.label = label == null ? "" : label;
        this.window = window;
        this.samples = history == null ? Collections.emptyList() : history.currentWindowSamples();
        this.windows = history == null ? Collections.emptyList() : history.recentWindows(5);
        this.observedAtMillis = observedAtMillis;
        this.pace = pace;
        String detail = samples.size() < 2 ? "Building local history"
                : samples.size() + " local samples";
        if (pace != null && pace.available) {
            detail += ", projected exhaustion "
                    + UsageFormat.relative(pace.estimatedExhaustionAtMillis,
                            System.currentTimeMillis());
        }
        setContentDescription(this.label + " usage burn chart. " + detail + ".");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        boolean dark = Ui.isDark(getContext());
        float left = 16f * density;
        float right = getWidth() - 16f * density;
        float top = 34f * density;
        float bottom = getHeight() - 24f * density;
        paint.setTypeface(boldTypeface);
        paint.setTextSize(14f * density);
        paint.setColor(Ui.mainText(dark));
        canvas.drawText(label, left, 20f * density, paint);

        paint.setTypeface(regularTypeface);
        paint.setTextSize(10f * density);
        paint.setColor(Ui.secondaryText(dark));
        String sampleLabel = samples.size() < 2 ? "Building history"
                : samples.size() + " samples";
        canvas.drawText(sampleLabel, right - paint.measureText(sampleLabel), 20f * density, paint);

        paint.setStrokeWidth(1f * density);
        paint.setColor(Color.argb(dark ? 52 : 38, 128, 128, 128));
        canvas.drawLine(left, bottom, right, bottom, paint);
        canvas.drawLine(left, top, right, top, paint);
        if (window == null || observedAtMillis <= 0L) {
            drawEmpty(canvas, left, top, dark, density, "Waiting for usage data");
            return;
        }
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        long duration = window.windowSeconds * 1000L;
        long startAt = resetAt - duration;
        if (resetAt <= startAt) {
            drawEmpty(canvas, left, top, dark, density, "Reset window unavailable");
            return;
        }

        // Sustainable budget: reaching 100% used exactly at reset.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setPathEffect(budgetDash);
        paint.setColor(Color.argb(dark ? 115 : 95, 128, 128, 128));
        canvas.drawLine(left, bottom, right, top, paint);
        paint.setPathEffect(null);

        // Normalize completed windows to the same x-axis so historical burn shapes are comparable.
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(Color.argb(dark ? 62 : 48, 128, 128, 128));
        for (int index = 0; index < windows.size() - 1; index++) {
            List<UsageSample> historical = windows.get(index);
            if (historical.size() < 2) continue;
            UsageSample reference = historical.get(historical.size() - 1);
            long historicalStart = reference.resetAtMillis - reference.windowSeconds * 1000L;
            path.reset();
            for (int sampleIndex = 0; sampleIndex < historical.size(); sampleIndex++) {
                UsageSample sample = historical.get(sampleIndex);
                float historicalX = x(sample.observedAtMillis, historicalStart,
                        reference.resetAtMillis, left, right);
                float historicalY = y(sample.usedPercent, top, bottom);
                if (sampleIndex == 0) path.moveTo(historicalX, historicalY);
                else path.lineTo(historicalX, historicalY);
            }
            canvas.drawPath(path, paint);
        }

        if (!samples.isEmpty()) {
            path.reset();
            boolean started = false;
            for (UsageSample sample : samples) {
                float x = x(sample.observedAtMillis, startAt, resetAt, left, right);
                float y = y(sample.usedPercent, top, bottom);
                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
                }
            }
            paint.setColor(Ui.accent(getContext(), dark));
            paint.setStrokeWidth(3f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
        }

        if (pace != null && pace.available && !samples.isEmpty()) {
            UsageSample latest = samples.get(samples.size() - 1);
            float fromX = x(latest.observedAtMillis, startAt, resetAt, left, right);
            float fromY = y(latest.usedPercent, top, bottom);
            float toX = x(Math.min(resetAt, pace.estimatedExhaustionAtMillis),
                    startAt, resetAt, left, right);
            float toY = y(pace.estimatedExhaustionAtMillis <= resetAt ? 100 : latest.usedPercent,
                    top, bottom);
            paint.setColor(pace.accelerated ? Ui.warning(dark)
                    : Ui.desaturatedAccent(getContext(), dark));
            paint.setStrokeWidth(2f * density);
            paint.setPathEffect(projectionDash);
            canvas.drawLine(fromX, fromY, toX, toY, paint);
            paint.setPathEffect(null);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(regularTypeface);
        paint.setTextSize(10f * density);
        paint.setColor(Ui.secondaryText(dark));
        canvas.drawText("0%", left, getHeight() - 7f * density, paint);
        String reset = "reset";
        canvas.drawText(reset, right - paint.measureText(reset), getHeight() - 7f * density, paint);
    }

    private void drawEmpty(Canvas canvas, float left, float top, boolean dark, float density,
            String text) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(regularTypeface);
        paint.setTextSize(12f * density);
        paint.setColor(Ui.secondaryText(dark));
        canvas.drawText(text, left, top + 24f * density, paint);
    }

    private static float x(long time, long start, long end, float left, float right) {
        double ratio = Math.max(0d, Math.min(1d, (time - start) / (double) (end - start)));
        return left + (float) ratio * (right - left);
    }

    private static float y(int usedPercent, float top, float bottom) {
        return bottom - Math.max(0, Math.min(100, usedPercent)) / 100f * (bottom - top);
    }
}
