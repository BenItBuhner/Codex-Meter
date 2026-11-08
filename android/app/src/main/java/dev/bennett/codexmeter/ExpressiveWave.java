package dev.bennett.codexmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Material 3 Expressive's wavy linear progress indicator, drawn directly: a wavy active indicator,
 * a gap, a flat track and a stop dot at the end. The wave flattens near empty and full, as in the
 * spec, and travels slowly unless Android asks for reduced motion.
 *
 * <p>Material Components' own wavy bar shipped in the fork the app uses, but its wave never showed
 * on the dashboard, so the meters draw it here with the same proportions (4dp stroke, 3dp
 * amplitude, 40dp wavelength, 4dp gap).
 */
final class ExpressiveWave extends View {
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float stroke;
    private final float amplitude;
    private final float wavelength;
    private final float gap;
    private final boolean moving;
    private float fraction;
    private float phase;
    private long lastFrame;

    ExpressiveWave(Context context, int activeColor, int trackColor) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        this.stroke = 4 * d;
        this.amplitude = 3 * d;
        this.wavelength = 40 * d;
        this.gap = 4 * d;
        this.moving = !ReducedMotion.isRequested(context);
        for (Paint p : new Paint[] {this.active, this.track}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(this.stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        this.active.setColor(activeColor);
        this.track.setColor(trackColor);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setFraction(double value) {
        this.fraction = (float) Math.max(0, Math.min(1, value));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = (int) Math.ceil(this.stroke + this.amplitude * 2 + 4 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float half = this.stroke / 2f;
        float left = getPaddingLeft() + half;
        float right = getWidth() - getPaddingRight() - half;
        float cy = getHeight() / 2f;
        float width = right - left;
        if (width <= 0) {
            return;
        }
        float end = left + width * this.fraction;
        // Flat at the very ends, full wave in between.
        float amp = this.fraction < 0.04f || this.fraction > 0.97f ? 0f
                : this.amplitude * Math.min(1f, Math.min(this.fraction, 1f - this.fraction) / 0.1f);
        if (this.fraction > 0f) {
            this.path.reset();
            float step = Math.max(1f, this.stroke / 4f);
            for (float x = left; ; x += step) {
                float cx = Math.min(x, end);
                double angle = 2 * Math.PI * (cx - left) / this.wavelength + this.phase;
                float y = cy + (float) (amp * Math.sin(angle));
                if (cx == left) {
                    this.path.moveTo(cx, y);
                } else {
                    this.path.lineTo(cx, y);
                }
                if (cx >= end) {
                    break;
                }
            }
            canvas.drawPath(this.path, this.active);
        }
        float trackStart = this.fraction > 0f ? end + this.gap + this.stroke : left;
        if (this.fraction < 1f && trackStart < right) {
            canvas.drawLine(trackStart, cy, right, cy, this.track);
            // The stop indicator: a dot in the active color where the track ends.
            this.active.setStyle(Paint.Style.FILL);
            canvas.drawCircle(right, cy, half, this.active);
            this.active.setStyle(Paint.Style.STROKE);
        }
        if (this.moving && amp > 0f && isShown()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (this.lastFrame > 0) {
                // One wavelength every 2.5 seconds, toward the end of the bar.
                this.phase -= (float) (2 * Math.PI * (now - this.lastFrame) / 2500.0);
                this.phase %= (float) (2 * Math.PI);
            }
            this.lastFrame = now;
            postInvalidateOnAnimation();
        } else {
            this.lastFrame = 0;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.lastFrame = 0;
    }
}
