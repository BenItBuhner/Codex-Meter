package dev.bennett.codexmeter;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import dev.bennett.codexmeter.wear.WearMonitorState;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSyncPaths;
import dev.bennett.codexmeter.wear.WearSyncStatus;
import dev.bennett.codexmeter.wear.WearUsageState;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;

public final class WearPhoneSync {
    private static final String KEY_PAYLOAD = "payload";
    private static final String TAG = "CodexWearSync";

    private WearPhoneSync() {
    }

    public static void pushSettings(Context context) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        WearSettingsState state = WearPreferences.settingsState(context, now,
                WearSettingsState.SOURCE_WEAR);
        WearPreferences.saveLocalSettings(context, state);
        pushJson(context, WearSyncPaths.PATH_SETTINGS, state);
    }

    public static boolean applyRemoteUsage(Context context, String payload) {
        try {
            WearUsageState state = WearUsageState.fromJson(new JSONObject(payload));
            if (state == null) return false;
            if (state.snapshot == null) {
                WearPreferences.clearSnapshot(context, state.updatedAtMillis,
                        !state.signedIn);
                return true;
            }
            WearPreferences.saveSnapshot(context, state.snapshot, state.updatedAtMillis);
            WearOngoingMonitor.updateFromSnapshot(context, state.snapshot);
            return true;
        } catch (Exception exception) {
            Log.w(TAG, "Could not apply phone usage payload", exception);
            return false;
        }
    }

    public static boolean applyRemoteStatus(Context context, String payload) {
        try {
            WearSyncStatus status = WearSyncStatus.fromJson(new JSONObject(payload));
            return WearPreferences.applyRemoteStatus(context, status);
        } catch (Exception exception) {
            Log.w(TAG, "Could not apply phone status payload", exception);
            return false;
        }
    }

    public static void syncFromPhone(Context context) {
        syncFromPhone(context, null);
    }

    public static void syncFromPhone(Context context, Runnable onComplete) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Wearable.getDataClient(app).getDataItems()
                .addOnSuccessListener(items -> {
                    try {
                        for (DataItem item : items) {
                            applyDataItem(app, item);
                        }
                    } finally {
                        items.release();
                    }
                    WearSurfaceUpdater.requestAll(app);
                    if (onComplete != null) onComplete.run();
                })
                .addOnFailureListener(error ->
                        Log.w(TAG, "Could not load current phone data", error));
        sendMessageToPhone(app, WearSyncPaths.MSG_SYNC_NOW);
    }

    public static boolean applyDataItem(Context context, DataItem item) {
        if (context == null || item == null) return false;
        Uri uri = item.getUri();
        String path = uri == null ? "" : uri.getPath();
        String payload = payloadString(item);
        if (payload == null || payload.isEmpty()) return false;
        if (WearSyncPaths.PATH_USAGE.equals(path)) {
            return applyRemoteUsage(context, payload);
        } else if (WearSyncPaths.PATH_SETTINGS.equals(path)) {
            return applyRemoteSettings(context, payload);
        } else if (WearSyncPaths.PATH_MONITOR.equals(path)) {
            return applyRemoteMonitor(context, payload);
        } else if (WearSyncPaths.PATH_STATUS.equals(path)) {
            return applyRemoteStatus(context, payload);
        }
        return false;
    }

    public static boolean applyRemoteSettings(Context context, String payload) {
        try {
            WearSettingsState state = WearSettingsState.fromJson(new JSONObject(payload));
            boolean applied = WearPreferences.applyRemoteSettings(context, state);
            if (applied) {
                if (!state.monitorActive) {
                    WearOngoingMonitor.stop(context, false);
                } else if (WearPreferences.loadSnapshot(context) != null) {
                    // Usage may arrive on a separate DataItem; only restore when we can post.
                    WearOngoingMonitor.restore(context);
                }
                // else: keep monitorActive desired flag; usage sync starts the surface later.
            }
            return applied;
        } catch (Exception exception) {
            Log.w(TAG, "Could not apply phone settings payload", exception);
            return false;
        }
    }

    public static boolean applyRemoteMonitor(Context context, String payload) {
        try {
            WearMonitorState state = WearMonitorState.fromJson(new JSONObject(payload));
            if (state == null) return false;
            WearPreferences.applyRemoteMonitor(context, state);
            if (!state.active) {
                WearOngoingMonitor.stop(context, false);
            } else if (WearPreferences.loadSnapshot(context) != null) {
                WearOngoingMonitor.restore(context);
            }
            return true;
        } catch (Exception exception) {
            Log.w(TAG, "Could not apply phone monitor payload", exception);
            return false;
        }
    }

    public static void sendMessageToPhone(Context context, String path) {
        if (context == null || path == null || path.isEmpty()) return;
        // The watch never talks to the OpenAI/ChatGPT APIs directly; the phone remains the
        // OAuth/API source of truth and handles refresh/start/stop requests from MessageClient.
        Context app = context.getApplicationContext();
        Wearable.getNodeClient(app).getConnectedNodes()
                .addOnSuccessListener(nodes -> sendMessageToNodes(app, nodes, path))
                .addOnFailureListener(error -> Log.w(TAG,
                        "Could not resolve phone nodes for " + path, error));
    }

    public static String payloadString(DataItem item) {
        try {
            return DataMapItem.fromDataItem(item).getDataMap().getString(KEY_PAYLOAD);
        } catch (RuntimeException exception) {
            byte[] bytes = item == null ? null : item.getData();
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void sendMessageToNodes(Context context, List<Node> nodes, String path) {
        WearPreferences.markConnected(context, nodes != null && !nodes.isEmpty());
        if (nodes == null) return;
        byte[] auth = context.getPackageName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (Node node : nodes) {
            Wearable.getMessageClient(context)
                    .sendMessage(node.getId(), path, auth)
                    .addOnFailureListener(error -> Log.w(TAG,
                            "Could not send phone message " + path, error));
        }
    }

    private static void pushJson(Context context, String path, Object state) {
        try {
            String json;
            if (state instanceof WearSettingsState) {
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
                            "Could not push phone data " + path, error));
        } catch (Exception exception) {
            Log.w(TAG, "Could not encode phone data " + path, exception);
        }
    }
}
