package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import dev.bennett.codexmeter.wear.PhoneWearSync;

/** User controls for usage-life estimates and accelerated-consumption warnings. */
public final class UsagePacePreferences {
    private static final String KEY_ENABLED = "usage_pace_enabled";
    private static final String KEY_SENSITIVITY = "usage_pace_sensitivity";
    private static final String PREFS = "codex_meter_settings_v1";

    private UsagePacePreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        PhoneWearSync.pushSettings(context);
    }

    public static String getSensitivity(Context context) {
        return UsagePace.normalizeSensitivity(
                prefs(context).getString(KEY_SENSITIVITY, UsagePace.BALANCED));
    }

    public static void setSensitivity(Context context, String sensitivity) {
        prefs(context).edit().putString(KEY_SENSITIVITY,
                UsagePace.normalizeSensitivity(sensitivity)).apply();
        PhoneWearSync.pushSettings(context);
    }

    /** Estimates may still show when this is false; only accelerated warnings are suppressed. */
    public static boolean areWarningsEnabled(Context context) {
        return isEnabled(context) && UsagePace.warningsEnabled(getSensitivity(context));
    }

    public static UsagePace.Assessment assess(Context context, UsageSnapshot snapshot,
            UsageWindow window, long nowMillis) {
        if (!isEnabled(context) || snapshot == null) {
            return UsagePace.assess(null, 0L, nowMillis, getSensitivity(context));
        }
        String kind = window == snapshot.weekly ? UsageHistory.WEEKLY : UsageHistory.FIVE_HOUR;
        return UsagePace.assess(window, AppPreferences.loadUsageHistory(context, kind),
                snapshot.fetchedAtMillis, nowMillis, getSensitivity(context));
    }
}
