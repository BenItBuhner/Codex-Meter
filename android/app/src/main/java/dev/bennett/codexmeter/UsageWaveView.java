package dev.bennett.codexmeter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import androidx.appcompat.content.res.AppCompatResources;

/** Animated percentage fill used by the Figma usage-counter cards. */
public final class UsageWaveView extends View {
    private static final long NORMAL_WAVE_DURATION_MS = 2400L;
    private static final long WARNING_WAVE_DURATION_MS = 700L;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint resetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint percentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fillPath = new Path();
    private ValueAnimator animator;
    private Drawable icon;
    private String title = "";
    private String resetTop = "";
    private String resetBottom = "";
    private String pace = "";
    private int percent;
    private boolean warning;
    private float phase;
    private float phaseOffset;

    public UsageWaveView(Context context) {
        this(context, null);
    }

    public UsageWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        titlePaint.setTypeface(Typeface.create("sec", Typeface.BOLD));
        resetPaint.setTypeface(Typeface.create("sec", Typeface.NORMAL));
        pacePaint.setTypeface(Typeface.create("sec", Typeface.BOLD));
        percentPaint.setTypeface(Typeface.create("sec", Typeface.BOLD));
        percentPaint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setUsage(String label, String reset, String paceEstimate, int remainingPercent,
            int iconRes, boolean invertedWave, boolean acceleratedWarning) {
        title = label;
        percent = Math.max(0, Math.min(100, remainingPercent));
        pace = paceEstimate == null ? "" : paceEstimate;
        warning = acceleratedWarning;
        if (animator != null) {
            animator.setDuration(warning ? WARNING_WAVE_DURATION_MS : NORMAL_WAVE_DURATION_MS);
        }
        phaseOffset = invertedWave ? (float) Math.PI : 0f;
        if (reset != null && reset.startsWith("Resets in ")) {
            resetTop = "Resets in";
            resetBottom = reset.substring("Resets in ".length());
        } else {
            resetTop = reset == null ? "" : reset;
            resetBottom = "";
        }
        icon = AppCompatResources.getDrawable(getContext(), iconRes);
        String description = label + ", " + percent + " percent. " + reset;
        if (!pace.isEmpty()) description += ". " + pace.replace("Est.", "Estimated");
        if (warning) description += ". Accelerated usage warning";
        setContentDescription(description);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(warning ? WARNING_WAVE_DURATION_MS : NORMAL_WAVE_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (Float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        boolean dark = Ui.isDark(getContext());
        if (warning) {
            trackPaint.setColor(Ui.warningTrack(getContext(), dark));
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), trackPaint);
        }
        float edge = getWidth() * percent / 100f;
        float amplitude = (warning ? 14f : 8f) * density;
        fillPath.reset();
        fillPath.moveTo(0, 0);
        fillPath.lineTo(edge, 0);
        int steps = warning ? 54 : 28;
        for (int i = 1; i <= steps; i++) {
            float y = getHeight() * i / (float) steps;
            float envelope = (float) Math.sin(Math.PI * y / getHeight());
            float angle = (float) ((warning ? Math.PI * 6 : Math.PI * 2)
                    * y / getHeight() + phase + phaseOffset);
            float wave = warning
                    ? (float) (Math.asin(Math.sin(angle)) * 2 / Math.PI)
                    : (float) Math.sin(angle);
            float x = edge + amplitude * envelope * wave;
            fillPath.lineTo(x, y);
        }
        fillPath.lineTo(0, getHeight());
        fillPath.close();
        fillPaint.setColor(warning ? Ui.warning(dark)
                : Ui.desaturatedAccent(getContext(), dark));
        canvas.drawPath(fillPath, fillPaint);

        int foreground = warning ? (dark ? 0xFFFFFFFF : 0xFF000000) : Ui.mainText(dark);
        titlePaint.setColor(foreground);
        titlePaint.setTextSize(20f * density);
        // Reset duration must match title/percent contrast (black light / white dark).
        resetPaint.setColor(foreground);
        resetPaint.setTextSize(13f * density);
        pacePaint.setColor(foreground);
        pacePaint.setTextSize(12.5f * density);
        canvas.drawText(title, 12f * density, 34f * density, titlePaint);
        if (!resetTop.isEmpty()) {
            canvas.drawText(resetTop, 12f * density, 67f * density, resetPaint);
            if (!resetBottom.isEmpty()) {
                canvas.drawText(resetBottom, 12f * density, 87f * density, resetPaint);
                if (!pace.isEmpty()) {
                    float paceX = 18f * density
                            + resetPaint.measureText(resetBottom);
                    canvas.drawText("· " + pace, paceX, 87f * density, pacePaint);
                }
            }
        } else if (!pace.isEmpty()) {
            canvas.drawText(pace, 12f * density, 87f * density, pacePaint);
        }
        float rightCenter = getWidth() - 48f * density;
        if (icon != null) {
            int size = Math.round(36f * density);
            int left = Math.round(rightCenter - size / 2f);
            int top = Math.round(13f * density);
            icon.setBounds(left, top, left + size, top + size);
            icon.setTint(foreground);
            icon.draw(canvas);
        }
        percentPaint.setColor(foreground);
        percentPaint.setTextSize(22f * density);
        canvas.drawText(percent + "%", rightCenter, 85f * density, percentPaint);
    }
}
