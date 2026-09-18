package dev.bennett.codexmeter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;

/**
 * Reports whether decorative motion should be skipped. Android's Accessibility "Remove
 * animations" switch, the developer animator-duration scale, and Battery Saver all zero the
 * global animator scale that {@link ValueAnimator#areAnimatorsEnabled()} mirrors; the setting is
 * read as well so a freshly started process answers correctly before the system pushes that scale
 * to it. Functional state changes stay instant either way; only looping and ornamental
 * animations consult this.
 */
public final class ReducedMotion {
    private ReducedMotion() {
    }

    public static boolean isRequested(Context context) {
        if (!ValueAnimator.areAnimatorsEnabled()) return true;
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }
}
