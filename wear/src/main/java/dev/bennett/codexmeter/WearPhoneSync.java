package dev.bennett.codexmeter;

import android.content.Context;
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
            if (state == null || state.snapshot == null) return false;
            WearPreferences.saveSnapshot(context, state.snapshot, state.updatedAtMillis);
            WearOngoingMonitor.updateFromSnapshot(context, state.snapshot);
            return true;
        } catch (Exception exception) {
            Log.w(TAG, "Could not apply phone usage payload", exception);
            return false;
        }
    }

    public static boolean applyRemoteSettings(Context context, String payload) {
        try {
            WearSettingsState state = WearSettingsState.fromJson(new JSONObject(payload));
            boolean applied = WearPreferences.applyRemoteSettings(context, state);
            if (applied) {
                if (state.monitorActive) {
                    WearOngoingMonitor.restore(context);
                } else {
                    WearOngoingMonitor.stop(context, false);
                }
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
            if (state.active) {
                WearOngoingMonitor.restore(context);
            } else {
                WearOngoingMonitor.stop(context, false);
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
        for (Node node : nodes) {
            Wearable.getMessageClient(context)
                    .sendMessage(node.getId(), path, new byte[0])
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
