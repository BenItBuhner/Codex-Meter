package dev.bennett.codexmeter.wear;

import android.net.Uri;
import android.util.Log;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;
import dev.bennett.codexmeter.AppPreferences;
import dev.bennett.codexmeter.DiagnosticLog;
import dev.bennett.codexmeter.NowBarManager;
import dev.bennett.codexmeter.UsageApi;
import org.json.JSONObject;

public final class PhoneWearListenerService extends WearableListenerService {
    private static final String TAG = "CodexWearListener";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED || event.getDataItem() == null) {
                continue;
            }
            DataItem item = event.getDataItem();
            Uri uri = item.getUri();
            String path = uri == null ? "" : uri.getPath();
            if (!WearSyncPaths.PATH_SETTINGS.equals(path)) {
                continue;
            }
            applySettings(uri == null ? null : uri.getHost(), item);
        }
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (event == null) return;
        if (!PhoneWearTrust.isTrustedWearMessage(this, event.getSourceNodeId(), event.getData())) {
            DiagnosticLog.warn(this, "wear", "untrusted_message_rejected",
                    "path", event.getPath());
            Log.w(TAG, "Ignoring message from untrusted Wear sender: " + event.getSourceNodeId());
            return;
        }
        String path = event.getPath();
        DiagnosticLog.info(this, "wear", "message_received", "path", path);
        if (WearSyncPaths.MSG_REFRESH.equals(path)) {
            refreshInBackground();
        } else if (WearSyncPaths.MSG_SYNC_NOW.equals(path)) {
            PhoneWearSync.pushAll(getApplicationContext());
        } else if (WearSyncPaths.MSG_START_MONITOR.equals(path)) {
            NowBarManager.start(getApplicationContext());
            PhoneWearSync.pushSettings(getApplicationContext());
        } else if (WearSyncPaths.MSG_STOP_MONITOR.equals(path)) {
            NowBarManager.stop(getApplicationContext(), true);
            PhoneWearSync.pushSettings(getApplicationContext());
        }
    }

    @Override
    public void onPeerConnected(Node peer) {
        DiagnosticLog.info(this, "wear", "peer_connected");
        PhoneWearSync.pushAll(getApplicationContext());
    }

    private void applySettings(String nodeId, DataItem item) {
        try {
            String payload = PhoneWearSync.payloadString(item);
            if (payload == null || payload.isEmpty()) return;
            WearSettingsState remote = WearSettingsState.fromJson(new JSONObject(payload));
            if (!PhoneWearTrust.isTrustedWearSettings(this, nodeId, remote)) {
                DiagnosticLog.warn(this, "wear", "untrusted_settings_rejected");
                Log.w(TAG, "Ignoring settings from untrusted Wear sender: " + nodeId);
                return;
            }
            PhoneWearSync.applyRemoteSettings(getApplicationContext(), remote);
        } catch (Exception exception) {
            DiagnosticLog.error(this, "wear", "settings_apply_failed", exception);
            Log.w(TAG, "Could not apply Wear settings payload", exception);
        }
    }

    private void refreshInBackground() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            DiagnosticLog.info(app, "wear", "wear_refresh_started");
            PhoneWearSync.pushStatus(app, true, "");
            try {
                UsageApi.refreshAndCache(app);
            } catch (Exception exception) {
                DiagnosticLog.error(app, "wear", "wear_refresh_failed", exception);
                AppPreferences.setLastError(app, exception.getMessage());
                PhoneWearSync.pushStatus(app, false, exception.getMessage());
                Log.w(TAG, "Wear-requested usage refresh failed", exception);
                return;
            }
            DiagnosticLog.info(app, "wear", "wear_refresh_succeeded");
            PhoneWearSync.pushStatus(app, false, "");
        }, "codex-wear-refresh").start();
    }
}
