package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import dev.bennett.codexmeter.wear.WearMonitorState;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSurfaceMode;
import dev.bennett.codexmeter.wear.WearSyncStatus;
import org.json.JSONObject;

public final class WearPreferences {
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_ACCELERATED_START = "accelerated_start";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_DISPLAY_MODE = "display_mode";
    private static final String KEY_LAST_APPLIED_MONITOR_AT = "last_applied_monitor_at";
    private static final String KEY_LAST_APPLIED_SETTINGS_AT = "last_applied_settings_at";
    private static final String KEY_LAST_APPLIED_USAGE_AT = "last_applied_usage_at";
    private static final String KEY_LAST_APPLIED_STATUS_AT = "last_applied_status_at";
    private static final String KEY_LAST_LOCAL_SETTINGS_AT = "last_local_settings_at";
    private static final String KEY_METRIC = "metric";
    private static final String KEY_MONITOR_ACTIVE = "monitor_active";
    private static final String KEY_MONITOR_DESIRED = "monitor_desired";
    private static final String KEY_MONITOR_FOCUS = "monitor_focus";
    private static final String KEY_MONITOR_POSTED_MODE = "monitor_posted_mode";
    private static final String KEY_MONITOR_PREVIEW = "monitor_preview";
    private static final String KEY_MONITOR_UNTIL = "monitor_until";
    private static final String KEY_PERCENT_MODE = "percent_mode";
    private static final String KEY_PHONE_VERSION = "phone_version";
    private static final String KEY_REFRESH_IN_PROGRESS = "refresh_in_progress";
    private static final String KEY_REFRESH_MINUTES = "refresh_minutes";
    private static final String KEY_SIGNED_IN = "signed_in";
    private static final String KEY_SNAPSHOT_JSON = "snapshot_json";
    private static final String KEY_STATUS_ERROR = "status_error";
    private static final String KEY_STATUS_LAST_SUCCESS = "status_last_success";
    private static final String KEY_SYNCED = "synced";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String KEY_USAGE_PACE_ENABLED = "usage_pace_enabled";
    private static final String KEY_USAGE_PACE_SENSITIVITY = "usage_pace_sensitivity";
    private static final String PREFS = "codex_meter_wear_v1";

    private WearPreferences() {
    }

    public static UsageSnapshot loadSnapshot(Context context) {
        String json = prefs(context).getString(KEY_SNAPSHOT_JSON, null);
        if (json == null || json.isEmpty()) return null;
        try {
            return UsageSnapshot.fromJson(new JSONObject(json));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void saveSnapshot(Context context, UsageSnapshot snapshot, long updatedAtMillis) {
        if (snapshot == null) return;
        try {
            prefs(context).edit()
                    .putString(KEY_SNAPSHOT_JSON, snapshot.toJson().toString())
                    .putLong(KEY_LAST_APPLIED_USAGE_AT, Math.max(0L,
                            snapshot.fetchedAtMillis > 0L
                                    ? snapshot.fetchedAtMillis : updatedAtMillis))
                    .putBoolean(KEY_SYNCED, true)
                    .putBoolean(KEY_CONNECTED, true)
                    .apply();
            WearSurfaceUpdater.requestAll(context);
        } catch (Exception ignored) {
        }
    }

    public static void clearSnapshot(Context context, long updatedAtMillis) {
        clearSnapshot(context, updatedAtMillis, true);
    }

    public static void clearSnapshot(Context context, long updatedAtMillis,
            boolean stopMonitor) {
        prefs(context).edit()
                .remove(KEY_SNAPSHOT_JSON)
                .putLong(KEY_LAST_APPLIED_USAGE_AT, Math.max(0L, updatedAtMillis))
                .putBoolean(KEY_SYNCED, true)
                .putBoolean(KEY_CONNECTED, true)
                .apply();
        if (stopMonitor) {
            WearOngoingMonitor.stop(context, false);
        }
        WearSurfaceUpdater.requestAll(context);
    }

    public static WearSettingsState settingsState(Context context, long updatedAtMillis,
            String sourceNode) {
        SharedPreferences prefs = prefs(context);
        return new WearSettingsState(
                prefs.getString(KEY_DISPLAY_MODE, NowBarDisplayMode.AUTO),
                prefs.getString(KEY_PERCENT_MODE, NowBarPercentMode.AUTO),
                prefs.getBoolean(KEY_AUTO_START, false),
                prefs.getString(KEY_METRIC, NowBarAutoStart.METRIC_BOTH),
                prefs.getInt(KEY_THRESHOLD, 25),
                isMonitorDesired(context),
                prefs.getInt(KEY_REFRESH_MINUTES, 30),
                updatedAtMillis,
                sourceNode,
                context.getPackageName(),
                prefs.getBoolean(KEY_USAGE_PACE_ENABLED, true),
                prefs.getString(KEY_USAGE_PACE_SENSITIVITY, UsagePace.BALANCED),
                prefs.getBoolean(KEY_ACCELERATED_START, false));
    }

    public static boolean applyRemoteSettings(Context context, WearSettingsState remote) {
        if (remote == null || WearSettingsState.SOURCE_WEAR.equals(remote.sourceNode)) {
            return false;
        }
        SharedPreferences prefs = prefs(context);
        long localStamp = Math.max(prefs.getLong(KEY_LAST_LOCAL_SETTINGS_AT, 0L),
                prefs.getLong(KEY_LAST_APPLIED_SETTINGS_AT, 0L));
        if (remote.updatedAtMillis <= localStamp) {
            return false;
        }
        saveSettings(context, remote, false);
        prefs.edit()
                .putLong(KEY_LAST_APPLIED_SETTINGS_AT, remote.updatedAtMillis)
                .putBoolean(KEY_CONNECTED, true)
                .apply();
        return true;
    }

    public static boolean applyRemoteStatus(Context context, WearSyncStatus status) {
        if (status == null) return false;
        SharedPreferences prefs = prefs(context);
        if (status.updatedAtMillis < prefs.getLong(KEY_LAST_APPLIED_STATUS_AT, 0L)) {
            return false;
        }
        prefs.edit()
                .putBoolean(KEY_SIGNED_IN, status.signedIn)
                .putBoolean(KEY_REFRESH_IN_PROGRESS, status.refreshInProgress)
                .putLong(KEY_STATUS_LAST_SUCCESS, status.lastSuccessAtMillis)
                .putString(KEY_STATUS_ERROR, status.lastError)
                .putString(KEY_PHONE_VERSION, status.phoneVersion)
                .putLong(KEY_LAST_APPLIED_STATUS_AT, status.updatedAtMillis)
                .putBoolean(KEY_CONNECTED, true)
                .apply();
        if (!status.signedIn) {
            clearSnapshot(context, status.updatedAtMillis);
        } else {
            WearSurfaceUpdater.requestAll(context);
        }
        return true;
    }

    public static WearSyncStatus syncStatus(Context context) {
        SharedPreferences prefs = prefs(context);
        return new WearSyncStatus(
                prefs.getBoolean(KEY_SIGNED_IN, false),
                prefs.getBoolean(KEY_REFRESH_IN_PROGRESS, false),
                prefs.getLong(KEY_STATUS_LAST_SUCCESS, 0L),
                prefs.getString(KEY_STATUS_ERROR, ""),
                prefs.getString(KEY_PHONE_VERSION, ""),
                prefs.getLong(KEY_LAST_APPLIED_STATUS_AT, 0L));
    }

    public static void saveLocalSettings(Context context, WearSettingsState state) {
        saveSettings(context, state, true);
    }

    public static void setMonitorActive(Context context, boolean active) {
        setMonitorActive(context, active, true);
    }

    public static void setMonitorActive(Context context, boolean active, boolean localChange) {
        // Compatibility entry point: "active" here means user/phone desire, not a successful post.
        setMonitorDesired(context, active, localChange);
        if (!active) {
            clearMonitorPosted(context);
        }
    }

    public static void setMonitorDesired(Context context, boolean desired, boolean localChange) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(context).edit()
                .putBoolean(KEY_MONITOR_DESIRED, desired);
        if (localChange) {
            editor.putLong(KEY_LAST_LOCAL_SETTINGS_AT, now);
        }
        editor.apply();
        WearSurfaceUpdater.requestAll(context);
    }

    /** Records that the Wear monitor notification was actually posted. */
    public static void markMonitorPosted(Context context, long untilMillis) {
        prefs(context).edit()
                .putBoolean(KEY_MONITOR_DESIRED, true)
                .putBoolean(KEY_MONITOR_ACTIVE, true)
                .putLong(KEY_MONITOR_UNTIL, Math.max(0L, untilMillis))
                .apply();
        WearSurfaceUpdater.requestAll(context);
    }

    public static void clearMonitorPosted(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_MONITOR_ACTIVE, false)
                .putLong(KEY_MONITOR_UNTIL, 0L)
                .apply();
    }

    public static void applyRemoteMonitor(Context context, WearMonitorState state) {
        if (state == null) return;
        SharedPreferences prefs = prefs(context);
        if (state.updatedAtMillis <= prefs.getLong(KEY_LAST_APPLIED_MONITOR_AT, 0L)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(KEY_MONITOR_DESIRED, state.active)
                .putBoolean(KEY_MONITOR_PREVIEW, state.preview)
                .putString(KEY_MONITOR_POSTED_MODE, state.postedMode)
                .putLong(KEY_LAST_APPLIED_MONITOR_AT, state.updatedAtMillis)
                .putBoolean(KEY_CONNECTED, true);
        if (!state.active) {
            // Phone stopped the monitor — clear posted state too.
            editor.putBoolean(KEY_MONITOR_ACTIVE, false).putLong(KEY_MONITOR_UNTIL, 0L);
        }
        // Do not invent a local posted notification from the phone payload when active.
        if (state.focusMetric == null) {
            editor.remove(KEY_MONITOR_FOCUS);
        } else {
            editor.putString(KEY_MONITOR_FOCUS, state.focusMetric);
        }
        editor.apply();
        WearSurfaceUpdater.requestAll(context);
    }

    public static WearMonitorState monitorState(Context context, long updatedAtMillis) {
        SharedPreferences prefs = prefs(context);
        return new WearMonitorState(
                isMonitorActive(context),
                prefs.getBoolean(KEY_MONITOR_PREVIEW, false),
                prefs.getLong(KEY_MONITOR_UNTIL, 0L),
                prefs.getString(KEY_MONITOR_FOCUS, null),
                prefs.getString(KEY_MONITOR_POSTED_MODE, NowBarDisplayMode.AUTO),
                updatedAtMillis);
    }

    /**
     * True when settings/phone ask for a live monitor, even before a successful post.
     * Legacy installs that only wrote KEY_MONITOR_ACTIVE still count as desired.
     */
    public static boolean isMonitorDesired(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KEY_MONITOR_DESIRED)) {
            return prefs.getBoolean(KEY_MONITOR_DESIRED, false);
        }
        return prefs.getBoolean(KEY_MONITOR_ACTIVE, false);
    }

    /** True only when a Wear monitor notification has been posted and not yet expired. */
    public static boolean isMonitorActive(Context context) {
        return prefs(context).getBoolean(KEY_MONITOR_ACTIVE, false)
                && prefs(context).getLong(KEY_MONITOR_UNTIL, 0L)
                > System.currentTimeMillis();
    }

    public static void markConnected(Context context, boolean connected) {
        prefs(context).edit().putBoolean(KEY_CONNECTED, connected).apply();
    }

    public static boolean isConnected(Context context) {
        return prefs(context).getBoolean(KEY_CONNECTED, false);
    }

    public static boolean hasSyncedUsage(Context context) {
        return prefs(context).getBoolean(KEY_SYNCED, false);
    }

    public static long lastUsageAt(Context context) {
        return prefs(context).getLong(KEY_LAST_APPLIED_USAGE_AT, 0L);
    }

    public static WearSurfaceMode surfaceMode(Context context) {
        // Wear maps phone Now Bar choices to Wear-native surfaces: Samsung private
        // phone extras never render on Wear, while API 36+ can try local Live Updates.
        return WearSurfaceMode.resolve(settingsState(context, 0L,
                WearSettingsState.SOURCE_WEAR).displayMode,
                android.os.Build.VERSION.SDK_INT,
                WearOngoingMonitor.canUseLocalLiveUpdates(context));
    }

    private static void saveSettings(Context context, WearSettingsState state, boolean local) {
        long stamp = state.updatedAtMillis > 0L ? state.updatedAtMillis : System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_DISPLAY_MODE, state.displayMode)
                .putString(KEY_PERCENT_MODE, state.percentMode)
                .putBoolean(KEY_AUTO_START, state.autoStartEnabled)
                .putBoolean(KEY_ACCELERATED_START, state.acceleratedStartEnabled)
                .putString(KEY_METRIC, state.metric)
                .putInt(KEY_THRESHOLD, state.threshold)
                .putBoolean(KEY_MONITOR_DESIRED, state.monitorActive)
                .putInt(KEY_REFRESH_MINUTES, state.refreshMinutes)
                .putBoolean(KEY_USAGE_PACE_ENABLED, state.usagePaceEnabled)
                .putString(KEY_USAGE_PACE_SENSITIVITY, state.usagePaceSensitivity)
                .putLong(local ? KEY_LAST_LOCAL_SETTINGS_AT : KEY_LAST_APPLIED_SETTINGS_AT, stamp);
        if (!state.monitorActive) {
            editor.putBoolean(KEY_MONITOR_ACTIVE, false).putLong(KEY_MONITOR_UNTIL, 0L);
        }
        editor.apply();
        WearSurfaceUpdater.requestAll(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
