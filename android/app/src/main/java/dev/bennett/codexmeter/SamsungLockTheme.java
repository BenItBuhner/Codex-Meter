package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Lock-screen / AOD surface + ink colors for Samsung monotone widgets.
 *
 * <p>One UI 7+ AppWidget hosts paint an adaptive monotone frame around a transparent
 * {@code @android:id/background} root and recolor black ink. Older One UI builds (including
 * Galaxy Tab S8 on One UI 6.x) do not, so those hosts need Face-Widget-style surfaces and
 * contrasting ink applied by the app.
 */
final class SamsungLockTheme {
    private static final String TAG = "CodexMeterLock";
    /** One UI 7.0 ships as {@code ro.build.version.oneui=70000}. */
    private static final int ONE_UI_7 = 70000;

    private SamsungLockTheme() {
    }

    static boolean isSystemDark(Context context) {
        if (context == null || context.getResources() == null) {
            return true;
        }
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** True when SystemUI owns the adaptive monotone pill (One UI 7+). */
    static boolean hostOwnsMonotoneFrame(Context context) {
        int oneUi = oneUiVersionCode();
        if (oneUi > 0) {
            return oneUi >= ONE_UI_7;
        }
        // Property missing: API 35+ phones/tablets are One UI 7-class enough to leave the frame alone.
        return Build.VERSION.SDK_INT >= 35;
    }

    /**
     * Whether bitmap/text ink should be light-on-dark. One UI 7+ AppWidgets keep black ink so the
     * host can tint it; older hosts and AOD payloads use contrast against the painted surface.
     */
    static boolean useLightInk(Context context, boolean aod) {
        if (aod) {
            return true;
        }
        if (hostOwnsMonotoneFrame(context)) {
            return false;
        }
        return isSystemDark(context);
    }

    static boolean useLightInk(Context context) {
        return useLightInk(context, false);
    }

    static int ink(boolean lightInk) {
        return lightInk ? Color.WHITE : Color.BLACK;
    }

    static int inkMuted(boolean lightInk) {
        return lightInk ? Color.argb(200, 255, 255, 255) : Color.argb(200, 0, 0, 0);
    }

    static int track(boolean lightInk) {
        return lightInk ? Color.argb(51, 255, 255, 255) : Color.argb(51, 0, 0, 0);
    }

    static int surfaceResource(boolean darkSurface, boolean aod) {
        if (aod) {
            return R.drawable.widget_lock_monochrome_bg_aod;
        }
        return darkSurface ? R.drawable.widget_lock_monochrome_bg_dark
                : R.drawable.widget_lock_monochrome_bg;
    }

    /**
     * Applies lock-screen surface + ink. AOD/ServiceBox always paints a Face-Widget surface.
     * One UI 7+ AppWidget updates leave a transparent {@code @android:id/background} so SystemUI
     * can own the adaptive pill; older One UI paints light/dark surfaces itself.
     */
    static void apply(RemoteViews views, Context context, boolean aod) {
        if (views == null) {
            return;
        }
        boolean selfTheme = aod || !hostOwnsMonotoneFrame(context);
        if (!selfTheme) {
            views.setInt(android.R.id.background, "setBackgroundColor", Color.TRANSPARENT);
            return;
        }
        boolean darkSurface = aod || isSystemDark(context);
        boolean lightInk = aod || darkSurface;
        views.setInt(android.R.id.background, "setBackgroundResource",
                surfaceResource(darkSurface, aod));
        applyInk(views, lightInk);
    }

    private static void applyInk(RemoteViews views, boolean lightInk) {
        int ink = ink(lightInk);
        int muted = inkMuted(lightInk);
        setTextColorIfPresent(views, R.id.lock_square_value, ink);
        setTextColorIfPresent(views, R.id.lock_wide_value, ink);
        setTextColorIfPresent(views, R.id.lock_countdown, muted);
        setTextColorIfPresent(views, R.id.lock_graphic_center_value, ink);
        setTextColorIfPresent(views, R.id.lock_graphic_primary_value, ink);
        setTextColorIfPresent(views, R.id.lock_graphic_secondary_value, ink);
        setTextColorIfPresent(views, R.id.lock_graphic_primary_label, muted);
        setTextColorIfPresent(views, R.id.lock_graphic_secondary_label, muted);
        setTextColorIfPresent(views, R.id.lock_primary_countdown, muted);
        setTextColorIfPresent(views, R.id.lock_secondary_countdown, muted);
        setTextColorIfPresent(views, R.id.lock_bar_primary_value, ink);
        setTextColorIfPresent(views, R.id.lock_bar_secondary_value, ink);
        setTextColorIfPresent(views, R.id.lock_bar_primary_label, muted);
        setTextColorIfPresent(views, R.id.lock_bar_secondary_label, muted);
    }

    private static void setTextColorIfPresent(RemoteViews views, int viewId, int color) {
        try {
            views.setTextColor(viewId, color);
        } catch (RuntimeException ignored) {
            // Layout variant without this id.
        }
    }

    static int oneUiVersionCode() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            String value = (String) systemProperties
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.build.version.oneui", "0");
            if (value != null) {
                value = value.trim();
                if (!value.isEmpty()) {
                    return Integer.parseInt(value);
                }
            }
        } catch (Throwable exception) {
            Log.d(TAG, "Unable to read One UI version", exception);
        }
        return 0;
    }
}
