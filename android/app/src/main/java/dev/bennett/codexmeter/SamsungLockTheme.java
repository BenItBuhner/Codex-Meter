package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Lock-screen / AOD surface + ink colors for Samsung lock widgets.
 *
 * <p>Lock tiles use {@code widgetStyle=colorful} with
 * {@code @android:id/background} so One UI can host them like first-party lock
 * widgets. {@code widgetStyle=monotone} on One UI 6.x (Galaxy Tab S8) forced a
 * black monochrome pill that did not match wallpaper-tinted siblings and left
 * dark ink nearly invisible.
 *
 * <p>Pre-One UI 7 lock chrome is wallpaper-tinted (darkish) even in light theme,
 * so those hosts get a dark translucent app-drawn pill and light ink. One UI 7+
 * leaves the background transparent for SystemUI's adaptive frame and follows
 * night mode for ink. Force Dark is disabled on the tile so the host cannot
 * crush light ink back to black.
 */
final class SamsungLockTheme {
    private static final String TAG = "CodexMeterLock";
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

    /** Pre-One UI 7 lock chrome is wallpaper-tinted and needs light ink like sibling tiles. */
    static boolean usesWallpaperTintedLockChrome() {
        int oneUi = oneUiVersionCode();
        if (oneUi > 0) {
            return oneUi < ONE_UI_7;
        }
        return Build.VERSION.SDK_INT < 35;
    }

    static boolean useLightInk(Context context, boolean aod) {
        if (aod || usesWallpaperTintedLockChrome()) {
            return true;
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

    static void apply(RemoteViews views, Context context, boolean aod) {
        if (views == null) {
            return;
        }
        disableForceDark(views);
        if (aod) {
            views.setInt(android.R.id.background, "setBackgroundResource",
                    R.drawable.widget_lock_monochrome_bg_aod);
            applyInk(views, true);
            clearImageColorFilter(views);
            return;
        }
        if (usesWallpaperTintedLockChrome()) {
            // Match One UI 6 lock siblings: dark translucent pill + white content.
            views.setInt(android.R.id.background, "setBackgroundResource",
                    R.drawable.widget_lock_monochrome_bg_dark);
            applyInk(views, true);
            clearImageColorFilter(views);
            return;
        }
        views.setInt(android.R.id.background, "setBackgroundColor", Color.TRANSPARENT);
        applyInk(views, isSystemDark(context));
        clearImageColorFilter(views);
    }

    private static void disableForceDark(RemoteViews views) {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        setBooleanIfPresent(views, android.R.id.background, "setForceDarkAllowed", false);
        setBooleanIfPresent(views, R.id.lock_graphic_image, "setForceDarkAllowed", false);
    }

    private static void clearImageColorFilter(RemoteViews views) {
        // Clear any host-applied ColorFilter so our white/black ink bitmaps stay visible.
        setIntIfPresent(views, R.id.lock_graphic_image, "setColorFilter", 0);
        setIntIfPresent(views, R.id.lock_graphic_primary_icon, "setColorFilter", 0);
        setIntIfPresent(views, R.id.lock_graphic_secondary_icon, "setColorFilter", 0);
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

    private static void setIntIfPresent(RemoteViews views, int viewId, String method, int value) {
        try {
            views.setInt(viewId, method, value);
        } catch (RuntimeException ignored) {
            // Layout variant without this id.
        }
    }

    private static void setBooleanIfPresent(RemoteViews views, int viewId, String method, boolean value) {
        try {
            views.setBoolean(viewId, method, value);
        } catch (RuntimeException ignored) {
            // Layout variant without this id / method.
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
