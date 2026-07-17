package dev.bennett.codexmeter.wear;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import dev.bennett.codexmeter.AppPreferences;
import dev.bennett.codexmeter.NowBarManager;
import dev.bennett.codexmeter.NowBarPreferences;
import dev.bennett.codexmeter.UsageSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class PhoneWearSync {
    private static final String KEY_LAST_APPLIED_SETTINGS_AT = "last_applied_settings_at";
    private static final String KEY_LOCAL_SETTINGS_AT = "local_settings_at";
    private static final String KEY_PAYLOAD = "payload";
    private static final String PREFS = "codex_meter_wear_sync_v1";
    private static final ThreadLocal<Boolean> SUPPRESS_SETTINGS_PUSH = new ThreadLocal<>();
    private static final String TAG = "CodexWearSync";

    private PhoneWearSync() {
    }

    public static void pushUsage(Context context, UsageSnapshot snapshot) {
        if (context == null || snapshot == null) return;
        long now = System.currentTimeMillis();
        pushJson(context, WearSyncPaths.PATH_USAGE,
                new WearUsageState(snapshot, now, WearSettingsState.SOURCE_PHONE));
    }

    public static void pushSettings(Context context) {
        if (context == null || isSettingsPushSuppressed()) return;
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        prefs(app).edit().putLong(KEY_LOCAL_SETTINGS_AT, now).apply();
        pushJson(app, WearSyncPaths.PATH_SETTINGS, settingsState(app, now));
    }

    public static void pushMonitorState(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        boolean active = NowBarManager.isActive(app);
        pushJson(app, WearSyncPaths.PATH_MONITOR, new WearMonitorState(
                active,
                NowBarManager.isPreview(app),
                active ? NowBarManager.activeUntil(app) : 0L,
                active ? NowBarManager.activeFocusMetric(app) : null,
                active ? NowBarManager.postedDisplayMode(app)
                        : NowBarPreferences.getDisplayMode(app),
                now));
    }

    public static boolean applyRemoteSettings(Context context, WearSettingsState remote) {
        if (context == null || remote == null
                || WearSettingsState.SOURCE_PHONE.equals(remote.sourceNode)) {
            return false;
        }
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        long localStamp = Math.max(prefs.getLong(KEY_LOCAL_SETTINGS_AT, 0L),
                prefs.getLong(KEY_LAST_APPLIED_SETTINGS_AT, 0L));
        if (remote.updatedAtMillis <= localStamp) {
            return false;
        }

        boolean wasActive = NowBarManager.isActive(app);
        SUPPRESS_SETTINGS_PUSH.set(Boolean.TRUE);
        try {
            NowBarPreferences.setDisplayMode(app, remote.displayMode);
            NowBarPreferences.setPercentMode(app, remote.percentMode);
            NowBarPreferences.save(app, remote.autoStartEnabled, remote.metric, remote.threshold);
            // Phone owns the refresh interval. Wear may echo a cached/default value before the
            // first phone→Wear settings item arrives; never let that clobber the phone schedule.
            if (remote.monitorActive != wasActive) {
                if (remote.monitorActive) {
                    NowBarManager.start(app);
                } else {
                    NowBarManager.stop(app, true);
                }
            } else {
                pushMonitorState(app);
            }
        } finally {
            SUPPRESS_SETTINGS_PUSH.remove();
        }
        prefs.edit()
                .putLong(KEY_LAST_APPLIED_SETTINGS_AT, remote.updatedAtMillis)
                .putLong(KEY_LOCAL_SETTINGS_AT, remote.updatedAtMillis)
                .apply();
        return true;
    }

    static void sendMessageToWear(Context context, String path) {
        if (context == null || path == null || path.isEmpty()) return;
        Context app = context.getApplicationContext();
        Wearable.getNodeClient(app).getConnectedNodes()
                .addOnSuccessListener(nodes -> sendMessageToNodes(app, nodes, path))
                .addOnFailureListener(error -> Log.w(TAG,
                        "Could not resolve Wear nodes for " + path, error));
    }

    private static void sendMessageToNodes(Context context, List<Node> nodes, String path) {
        byte[] auth = PhoneWearTrust.messageAuthPayload(context);
        for (Node node : nodes) {
            Wearable.getMessageClient(context)
                    .sendMessage(node.getId(), path, auth)
                    .addOnFailureListener(error -> Log.w(TAG,
                            "Could not send Wear message " + path, error));
        }
    }

    private static WearSettingsState settingsState(Context context, long updatedAtMillis) {
        return new WearSettingsState(
                NowBarPreferences.getDisplayMode(context),
                NowBarPreferences.getPercentMode(context),
                NowBarPreferences.isAutoStartEnabled(context),
                NowBarPreferences.getMetric(context),
                NowBarPreferences.getThreshold(context),
                NowBarManager.isActive(context),
                AppPreferences.getRefreshMinutes(context),
                updatedAtMillis,
                WearSettingsState.SOURCE_PHONE,
                context.getPackageName());
    }

    private static void pushJson(Context context, String path, Object state) {
        try {
            String json;
            if (state instanceof WearUsageState) {
                json = ((WearUsageState) state).toJson().toString();
            } else if (state instanceof WearSettingsState) {
                json = ((WearSettingsState) state).toJson().toString();
            } else if (state instanceof WearMonitorState) {
                json = ((WearMonitorState) state).toJson().toString();
            } else {
                return;
            }
            PutDataMapRequest map = PutDataMapRequest.create(path);
            map.getDataMap().putString(KEY_PAYLOAD, json);
            map.getDataMap().putLong("sent_at_millis", System.currentTimeMillis());
            PutDataRequest request = map.asPutDataRequest().setUrgent();
            Wearable.getDataClient(context.getApplicationContext())
                    .putDataItem(request)
                    .addOnFailureListener(error -> Log.w(TAG,
                            "Could not push Wear data " + path, error));
        } catch (Exception exception) {
            Log.w(TAG, "Could not encode Wear data " + path, exception);
        }
    }

    static String payloadString(com.google.android.gms.wearable.DataItem item) {
        try {
            return com.google.android.gms.wearable.DataMapItem.fromDataItem(item)
                    .getDataMap()
                    .getString(KEY_PAYLOAD);
        } catch (RuntimeException exception) {
            byte[] bytes = item == null ? null : item.getData();
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean isSettingsPushSuppressed() {
        return Boolean.TRUE.equals(SUPPRESS_SETTINGS_PUSH.get());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
