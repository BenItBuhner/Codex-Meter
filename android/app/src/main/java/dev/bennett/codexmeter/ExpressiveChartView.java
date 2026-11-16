package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import dev.bennett.codexmeter.glass.GlassModel;

/**
 * Material 3 Expressive's usage burn chart: the same chart data Liquid Glass draws
 * ({@link GlassModel.Chart}, built by {@link GlassHistoryScreen#chart}), in the Material color
 * scheme. This window's line in the primary color, earlier windows faint on the same axis, the
 * even-pace diagonal, the projection (error color when it runs out early), and a finger scrub that
 * reports the moment under it.
 */
final class ExpressiveChartView extends View {
    /** Reads out the scrubbed moment, or null when the finger lifts. */
    interface OnReadout {
        void on(String text);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private final int primary;
    private final int error;
    private final int outline;
    private final int surface;
    private GlassModel.Chart chart;
    private OnReadout readout;
    private double scrub = -1;
    /** The earlier window picked to compare (an index into {@code chart.previous}), or -1 for this one. */
    private int window = -1;
    private int lastBucket = -1;

    ExpressiveChartView(Context context, int primary, int error, int outline, int surface) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.primary = primary;
        this.error = error;
        this.outline = outline;
        this.surface = surface;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setWindow(int window) {
        this.window = window;
        invalidate();
    }

    void setChart(GlassModel.Chart chart, OnReadout readout) {
        this.chart = chart;
        this.readout = readout;
        String label = chart == null ? "" : chart.label;
        setContentDescription("Usage burn chart. " + label
                + (chart != null && chart.emptyText != null ? ". " + chart.emptyText : ""));
        invalidate();
    }

    private float left() {
        return getPaddingLeft() + 4 * density;
    }

    private float right() {
        return getWidth() - getPaddingRight() - 4 * density;
    }

    private float top() {
        return getPaddingTop() + 8 * density;
    }

    private float bottom() {
        return getHeight() - getPaddingBottom() - 4 * density;
    }

    private float x(double ratio) {
        return left() + (float) Math.max(0, Math.min(1, ratio)) * (right() - left());
    }

    private float y(double percent) {
        return bottom() - (float) (Math.max(0, Math.min(100, percent)) / 100.0) * (bottom() - top());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setPathEffect(null);
        paint.setStrokeWidth(density);
        paint.setColor(alpha(outline, 0.35f));
        canvas.drawLine(left(), bottom(), right(), bottom(), paint);
        canvas.drawLine(left(), top(), right(), top(), paint);
        if (chart == null) {
            return;
        }
        if (chart.emptyText == null) {
            paint.setColor(alpha(outline, 0.6f));
            paint.setStrokeWidth(1.5f * density);
            paint.setPathEffect(new DashPathEffect(new float[] {6 * density, 5 * density}, 0));
            canvas.drawLine(left(), bottom(), right(), top(), paint);
            paint.setPathEffect(null);
        }
        for (int i = 0; i < chart.previous.size(); i++) {
            boolean on = i == window;
            paint.setStrokeWidth((on ? 3f : 1.5f) * density);
            paint.setColor(on ? primary : alpha(outline, 0.4f));
            line(canvas, chart.previous.get(i));
        }
        if (chart.emptyText != null) {
            return;
        }
        paint.setStrokeWidth(3.5f * density);
        paint.setColor(window >= 0 ? alpha(primary, 0.45f) : primary);
        line(canvas, chart.current);
        if (chart.projection != null && window < 0) {
            double[] pr = chart.projection;
            paint.setStrokeWidth(2.5f * density);
            paint.setColor(chart.projectionWarning ? error : alpha(primary, 0.55f));
            paint.setPathEffect(new DashPathEffect(new float[] {7 * density, 5 * density}, 0));
            canvas.drawLine(x(pr[0]), y(pr[1]), x(pr[2]), y(pr[3]), paint);
            paint.setPathEffect(null);
        }
        if (scrub >= 0 && chart.scrub != null) {
            double pct = chart.scrub.percent(window, scrub);
            if (pct >= 0) {
                float sx = x(scrub);
                float sy = y(pct);
                paint.setStrokeWidth(1.5f * density);
                paint.setColor(alpha(primary, 0.5f));
                canvas.drawLine(sx, top(), sx, bottom(), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(surface);
                canvas.drawCircle(sx, sy, 7 * density, paint);
                paint.setColor(primary);
                canvas.drawCircle(sx, sy, 5 * density, paint);
            }
        }
    }

    private void line(Canvas canvas, double[] xy) {
        if (xy == null || xy.length < 4) {
            return;
        }
        path.reset();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            if (i == 0) {
                path.moveTo(x(xy[i]), y(xy[i + 1]));
            } else {
                path.lineTo(x(xy[i]), y(xy[i + 1]));
            }
        }
        canvas.drawPath(path, paint);
    }

    // ACTION_DOWN starts a scrub and then moves to the touch point.
    @SuppressWarnings("fallthrough")
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (chart == null || chart.scrub == null || chart.emptyText != null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                scrub = Math.max(0, Math.min(1, (e.getX() - left()) / Math.max(1, right() - left())));
                int bucket = (int) (scrub * 24);
                if (bucket != lastBucket) {
                    lastBucket = bucket;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                if (readout != null) {
                    readout.on(chart.scrub.at(window, scrub));
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                scrub = -1;
                lastBucket = -1;
                if (readout != null) {
                    readout.on(null);
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private static int alpha(int color, float a) {
        return Color.argb(Math.round(Color.alpha(color) * a), Color.red(color), Color.green(color),
                Color.blue(color));
    }
}
