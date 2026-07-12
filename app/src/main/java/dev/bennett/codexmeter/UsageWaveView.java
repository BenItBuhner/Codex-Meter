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
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint resetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint percentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fillPath = new Path();
    private ValueAnimator animator;
    private Drawable icon;
    private String title = "";
    private String resetTop = "";
    private String resetBottom = "";
    private int percent;
    private float phase;
    private float phaseOffset;

    public UsageWaveView(Context context) {
        this(context, null);
    }

    public UsageWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        titlePaint.setTypeface(Typeface.create("sec", Typeface.BOLD));
        resetPaint.setTypeface(Typeface.create("sec", Typeface.NORMAL));
        percentPaint.setTypeface(Typeface.create("sec", Typeface.BOLD));
        percentPaint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setUsage(String label, String reset, int remainingPercent, int iconRes, boolean invertedWave) {
        title = label;
        percent = Math.max(0, Math.min(100, remainingPercent));
        phaseOffset = invertedWave ? (float) Math.PI : 0f;
        if (reset != null && reset.startsWith("Resets in ")) {
            resetTop = "Resets in";
            resetBottom = reset.substring("Resets in ".length());
        } else {
            resetTop = reset == null ? "" : reset;
            resetBottom = "";
        }
        icon = AppCompatResources.getDrawable(getContext(), iconRes);
        setContentDescription(label + ", " + percent + " percent. " + reset);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(2400L);
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
        float edge = getWidth() * percent / 100f;
        float amplitude = 8f * density;
        fillPath.reset();
        fillPath.moveTo(0, 0);
        fillPath.lineTo(edge, 0);
        int steps = 28;
        for (int i = 1; i <= steps; i++) {
            float y = getHeight() * i / (float) steps;
            float envelope = (float) Math.sin(Math.PI * y / getHeight());
            float x = edge + amplitude * envelope
                    * (float) Math.sin((Math.PI * 2 * y / getHeight()) + phase + phaseOffset);
            fillPath.lineTo(x, y);
        }
        fillPath.lineTo(0, getHeight());
        fillPath.close();
        fillPaint.setColor(Ui.desaturatedAccent(getContext(), Ui.isDark(getContext())));
        canvas.drawPath(fillPath, fillPaint);

        titlePaint.setColor(0xFF000000);
        titlePaint.setTextSize(20f * density);
        resetPaint.setColor(0x99000000);
        resetPaint.setTextSize(14f * density);
        canvas.drawText(title, 12f * density, 34f * density, titlePaint);
        canvas.drawText(resetTop, 12f * density, 67f * density, resetPaint);
        if (!resetBottom.isEmpty()) {
            canvas.drawText(resetBottom, 12f * density, 87f * density, resetPaint);
        }

        boolean dark = Ui.isDark(getContext());
        int foreground = Ui.mainText(dark);
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
