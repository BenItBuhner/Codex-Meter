package dev.bennett.codexmeter;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * Material 3 Expressive's motion, in one place: pages open and close along the shared X axis, a
 * new dashboard tab and Edit mode fade through (out of a slight scale, emphasized decelerate), and
 * the floating toolbar slides off the bottom edge and back. Everything is instant when the system
 * asks for reduced motion.
 */
final class ExpressiveMotion {
    /** Material's emphasized decelerate curve. */
    private static final PathInterpolator EMPHASIZED_DECELERATE =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** Material's emphasized accelerate curve. */
    private static final PathInterpolator EMPHASIZED_ACCELERATE =
            new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private ExpressiveMotion() {
    }

    /**
     * Opens and closes {@code activity} along the shared X axis: it slides in from the right with a
     * fade over the page underneath, and back out the same way. Call in onCreate (API 34 and later
     * set both transitions there; earlier versions keep the system's).
     */
    static void sharedAxis(Activity activity) {
        if (Build.VERSION.SDK_INT < 34 || ReducedMotion.isRequested(activity)) {
            return;
        }
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.m3_axis_open_enter, R.anim.m3_axis_open_exit);
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.m3_axis_close_enter, R.anim.m3_axis_close_exit);
    }

    /** Fades {@code view} in from 92% scale, Material's fade through for content that changed. */
    static void fadeThrough(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        if (ReducedMotion.isRequested(view.getContext())) {
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            return;
        }
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(0f);
        view.setAlpha(0f);
        view.setScaleX(0.92f);
        view.setScaleY(0.92f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(300)
                .setInterpolator(EMPHASIZED_DECELERATE).start();
    }

    /**
     * Slides the floating toolbar below the screen's edge ({@code show} false, then hides it) or
     * back up into place (when it's showing).
     */
    static void slideToolbar(View toolbar, boolean show) {
        if (toolbar == null) {
            return;
        }
        toolbar.animate().cancel();
        float away = toolbar.getHeight() + bottomMargin(toolbar) + Ui.dp(toolbar.getContext(), 24);
        boolean instant = ReducedMotion.isRequested(toolbar.getContext());
        if (!show) {
            if (toolbar.getVisibility() != View.VISIBLE) {
                return;
            }
            if (instant) {
                toolbar.setVisibility(View.GONE);
                return;
            }
            toolbar.animate().translationY(away).setDuration(200).setInterpolator(EMPHASIZED_ACCELERATE)
                    .withEndAction(() -> {
                        toolbar.setVisibility(View.GONE);
                        toolbar.setTranslationY(0f);
                    }).start();
            return;
        }
        if (toolbar.getVisibility() != View.VISIBLE || instant) {
            toolbar.setTranslationY(0f);
            return;
        }
        toolbar.setTranslationY(away);
        toolbar.animate().translationY(0f).setDuration(400).setInterpolator(EMPHASIZED_DECELERATE).start();
    }

    private static int bottomMargin(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) params).bottomMargin : 0;
    }
}
