package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Compact local-history chart showing actual, sustainable, and projected quota burn. */
public final class UsageBurnChartView extends View {
    /** Reports finger-scrub positions so hosts can surface point-in-time detail. */
    public interface OnScrubListener {
        void onScrub(long timeMillis, double usedPercent, boolean historicalWindow);

        void onScrubEnd();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF bubbleRect = new RectF();
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
    private boolean scrubEnabled;
    private OnScrubListener scrubListener;
    private int selectedWindowIndex = -1;
    private boolean scrubbing;
    private long scrubTimeMillis;
    private double scrubPercent = -1d;
    private long lastHapticBucket = Long.MIN_VALUE;

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
        this.selectedWindowIndex = -1;
        String detail = samples.size() < 2 ? "Building local history"
                : samples.size() + " local samples";
        if (pace != null && pace.available) {
            detail += ", projected exhaustion "
                    + UsageFormat.relative(pace.estimatedExhaustionAtMillis,
                            System.currentTimeMillis());
        }
        if (scrubEnabled) {
            detail += ". Touch and drag to inspect points in time";
        }
        setContentDescription(this.label + " usage burn chart. " + detail + ".");
        invalidate();
    }

    /** Enables finger scrubbing across the burn line for point-in-time inspection. */
    public void setScrubEnabled(boolean enabled) {
        this.scrubEnabled = enabled;
    }

    public void setOnScrubListener(OnScrubListener listener) {
        this.scrubListener = listener;
    }

    /**
     * Highlights one recorded window and points scrubbing at it. Accepts an index into
     * {@code recentWindows(5)} ordering (oldest first); any other value selects the
     * current window.
     */
    public void setSelectedWindow(int index) {
        this.selectedWindowIndex = index >= 0 && index < windows.size() - 1 ? index : -1;
        this.scrubbing = false;
        invalidate();
    }

    public int windowCount() {
        return windows.size();
    }

    private boolean historicalSelection() {
        return selectedWindowIndex >= 0 && selectedWindowIndex < windows.size() - 1;
    }

    private List<UsageSample> activeSamples() {
        return historicalSelection() ? windows.get(selectedWindowIndex) : samples;
    }

    /** Start and end of the time axis for the actively scrubbed window. */
    private long[] activeAxis() {
        if (historicalSelection()) {
            List<UsageSample> selected = windows.get(selectedWindowIndex);
            UsageSample reference = selected.get(selected.size() - 1);
            long start = reference.resetAtMillis - reference.windowSeconds * 1000L;
            return new long[]{start, reference.resetAtMillis};
        }
        if (window == null || observedAtMillis <= 0L) return null;
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        long duration = window.windowSeconds * 1000L;
        long startAt = resetAt - duration;
        if (resetAt <= startAt) return null;
        return new long[]{startAt, resetAt};
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!scrubEnabled) return super.onTouchEvent(event);
        List<UsageSample> active = activeSamples();
        long[] axis = activeAxis();
        if (active.isEmpty() || axis == null) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                getParent().requestDisallowInterceptTouchEvent(true);
                updateScrub(event.getX(), active, axis);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                scrubbing = false;
                lastHapticBucket = Long.MIN_VALUE;
                if (scrubListener != null) scrubListener.onScrubEnd();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateScrub(float touchX, List<UsageSample> active, long[] axis) {
        float density = getResources().getDisplayMetrics().density;
        float left = 16f * density;
        float right = getWidth() - 16f * density;
        double ratio = Math.max(0d, Math.min(1d,
                (touchX - left) / (double) Math.max(1f, right - left)));
        long time = axis[0] + Math.round(ratio * (axis[1] - axis[0]));
        long first = active.get(0).observedAtMillis;
        long last = active.get(active.size() - 1).observedAtMillis;
        long clamped = Math.max(first, Math.min(last, time));
        double percent = UsageStats.usedPercentAt(active, clamped);
        if (percent < 0d) percent = active.get(active.size() - 1).usedPercent;
        boolean changed = !scrubbing || clamped != scrubTimeMillis;
        scrubbing = true;
        scrubTimeMillis = clamped;
        scrubPercent = percent;
        // One gentle tick per 24th of the axis keeps scrubbing tactile without buzzing.
        long bucket = (axis[1] - axis[0]) <= 0L ? 0L
                : (clamped - axis[0]) / Math.max(1L, (axis[1] - axis[0]) / 24L);
        if (bucket != lastHapticBucket) {
            lastHapticBucket = bucket;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (changed && scrubListener != null) {
            scrubListener.onScrub(clamped, percent, historicalSelection());
        }
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
        paint.setStyle(Paint.Style.FILL);
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

        // Normalize completed windows to the same x-axis so historical burn shapes are
        // comparable; the selected window is emphasized and drawn last.
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < windows.size() - 1; index++) {
                boolean selected = index == selectedWindowIndex;
                if ((pass == 0) == selected) continue;
                List<UsageSample> historical = windows.get(index);
                if (historical.size() < 2) continue;
                UsageSample reference = historical.get(historical.size() - 1);
                long historicalStart = reference.resetAtMillis
                        - reference.windowSeconds * 1000L;
                path.reset();
                for (int sampleIndex = 0; sampleIndex < historical.size(); sampleIndex++) {
                    UsageSample sample = historical.get(sampleIndex);
                    float historicalX = x(sample.observedAtMillis, historicalStart,
                            reference.resetAtMillis, left, right);
                    float historicalY = y(sample.usedPercent, top, bottom);
                    if (sampleIndex == 0) path.moveTo(historicalX, historicalY);
                    else path.lineTo(historicalX, historicalY);
                }
                paint.setStrokeWidth(selected ? 2.5f * density : 1.5f * density);
                paint.setColor(selected ? Ui.desaturatedAccent(getContext(), dark)
                        : Color.argb(dark ? 62 : 48, 128, 128, 128));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(path, paint);
            }
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
            boolean dimmed = historicalSelection();
            int accent = Ui.accent(getContext(), dark);
            paint.setColor(dimmed ? Color.argb(96, Color.red(accent), Color.green(accent),
                    Color.blue(accent)) : accent);
            paint.setStrokeWidth(3f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
        }

        if (pace != null && pace.available && !samples.isEmpty() && !historicalSelection()) {
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

        if (scrubbing && scrubPercent >= 0d) {
            drawScrub(canvas, left, right, top, bottom, density, dark);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(regularTypeface);
        paint.setTextSize(10f * density);
        paint.setColor(Ui.secondaryText(dark));
        canvas.drawText("0%", left, getHeight() - 7f * density, paint);
        String reset = "reset";
        canvas.drawText(reset, right - paint.measureText(reset), getHeight() - 7f * density, paint);
    }

    private void drawScrub(Canvas canvas, float left, float right, float top, float bottom,
            float density, boolean dark) {
        long[] axis = activeAxis();
        if (axis == null) return;
        float scrubX = x(scrubTimeMillis, axis[0], axis[1], left, right);
        float scrubY = y((int) Math.round(scrubPercent), top, bottom);
        int accent = historicalSelection() ? Ui.desaturatedAccent(getContext(), dark)
                : Ui.accent(getContext(), dark);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(Color.argb(dark ? 130 : 110, Color.red(accent), Color.green(accent),
                Color.blue(accent)));
        canvas.drawLine(scrubX, top, scrubX, bottom, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.cardColor(getContext(), dark));
        canvas.drawCircle(scrubX, scrubY, 6f * density, paint);
        paint.setColor(accent);
        canvas.drawCircle(scrubX, scrubY, 4f * density, paint);

        String bubble = scrubTimeLabel() + " · " + Math.round(scrubPercent) + "%";
        paint.setTypeface(regularTypeface);
        paint.setTextSize(11f * density);
        float textWidth = paint.measureText(bubble);
        float padding = 8f * density;
        float bubbleLeft = Math.max(left,
                Math.min(right - textWidth - padding * 2f, scrubX - textWidth / 2f - padding));
        float bubbleTop = top - 30f * density;
        bubbleRect.set(bubbleLeft, bubbleTop, bubbleLeft + textWidth + padding * 2f,
                bubbleTop + 22f * density);
        paint.setColor(Ui.controlSurface(getContext(), dark));
        canvas.drawRoundRect(bubbleRect, 11f * density, 11f * density, paint);
        paint.setColor(Ui.mainText(dark));
        canvas.drawText(bubble, bubbleLeft + padding, bubbleTop + 15f * density, paint);
    }

    private String scrubTimeLabel() {
        boolean weekly = window != null
                && window.windowSeconds > 24L * 60L * 60L;
        boolean is24Hour = DateFormat.is24HourFormat(getContext());
        String pattern = weekly
                ? (is24Hour ? "EEE HH:mm" : "EEE h:mm a")
                : (is24Hour ? "HH:mm" : "h:mm a");
        return new SimpleDateFormat(pattern, Locale.getDefault())
                .format(new Date(scrubTimeMillis));
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
