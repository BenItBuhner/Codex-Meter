package dev.bennett.codexmeter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Home-screen widgets in the Material 3 Expressive design: Material You's widget look. The widget
 * sits on a surface container with the system's widget corner radius, its meters fill with the
 * primary color over a secondary-container track, and the app accent means the primary color.
 * With Material You on (Android 12 and later) the colors come from the wallpaper, as the app's
 * do; otherwise from the same blue seed the app's Expressive theme uses.
 */
final class WidgetMaterial {
    private WidgetMaterial() {
    }

    /** True when widgets take this look: the Material 3 Expressive design. */
    static boolean applies(Context context) {
        return AppPreferences.isMaterialExpressiveDesign(context);
    }

    @androidx.annotation.ChecksSdkIntAtLeast(api = 31)
    private static boolean dynamic(Context context) {
        return Build.VERSION.SDK_INT >= 31 && AppPreferences.isMaterialYouEnabled(context);
    }

    /** The widget's surface: surface container, as Material You widgets use. */
    static int surface(Context context, boolean dark) {
        if (dynamic(context)) {
            return context.getColor(dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50);
        }
        return dark ? Color.rgb(29, 32, 36) : Color.rgb(236, 238, 244);
    }

    /** Meters' fill and the app accent: primary. */
    static int primary(Context context, boolean dark) {
        if (dynamic(context)) {
            return context.getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
        }
        return dark ? Color.rgb(158, 202, 255) : Color.rgb(0, 97, 164);
    }

    /** Meters' track: secondary container. */
    static int track(Context context, boolean dark) {
        if (dynamic(context)) {
            return context.getColor(dark ? android.R.color.system_accent2_700 : android.R.color.system_accent2_100);
        }
        return dark ? Color.rgb(59, 72, 88) : Color.rgb(215, 227, 247);
    }

    /**
     * Sets the widget's background: the surface at the widget's opacity, rounded like the
     * system's widgets. Android 12 tints a rounded shape; earlier versions keep the plain Material
     * background resource, which the caller sets when this returns false.
     */
    static boolean applyBackground(Context context, RemoteViews views, boolean dark, int opacity) {
        if (Build.VERSION.SDK_INT < 31 || opacity <= 0) {
            return false;
        }
        int color = surface(context, dark);
        int alpha = Math.round(Math.max(0, Math.min(100, opacity)) * 2.55f);
        views.setInt(android.R.id.background, "setBackgroundResource", R.drawable.widget_bg_m3);
        views.setColorStateList(android.R.id.background, "setBackgroundTintList",
                ColorStateList.valueOf(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))));
        return true;
    }
}
