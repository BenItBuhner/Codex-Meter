package dev.bennett.codexmeter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;

/**
 * Reports whether decorative motion should be skipped. {@link ValueAnimator#areAnimatorsEnabled()}
 * is the primary signal: the window manager pushes each process an animator scale of zero for the
 * Accessibility "Remove animations" switch, the developer animator-duration scale set to off, and
 * Battery Saver when its policy disables animations (a window-manager flag, not a settings write).
 * Reading {@link Settings.Global#ANIMATOR_DURATION_SCALE} as well is belt-and-braces for the scale
 * setting specifically. Functional state changes stay instant either way; only looping and
 * ornamental animations consult this.
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
