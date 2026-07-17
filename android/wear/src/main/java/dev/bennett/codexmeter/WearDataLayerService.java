package dev.bennett.codexmeter;

import android.net.Uri;
import android.util.Log;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSyncPaths;
import org.json.JSONObject;

public final class WearDataLayerService extends WearableListenerService {
    private static final String TAG = "CodexWearListener";
    private static final String EMPTY = "";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED || event.getDataItem() == null) {
                continue;
            }
            Uri uri = event.getDataItem().getUri();
            String path = uri == null ? "" : uri.getPath();
            String payload = WearPhoneSync.payloadString(event.getDataItem());
            if (payload == null || payload.isEmpty()) continue;
            if (WearSyncPaths.PATH_USAGE.equals(path)) {
                WearPhoneSync.applyRemoteUsage(getApplicationContext(), payload);
            } else if (WearSyncPaths.PATH_SETTINGS.equals(path)) {
                applySettings(payload);
            } else if (WearSyncPaths.PATH_MONITOR.equals(path)) {
                WearPhoneSync.applyRemoteMonitor(getApplicationContext(), payload);
            }
        }
    }
    @Override
    public void onMessageReceived(MessageEvent event) {
        String path = event == null ? EMPTY : event.getPath();
        if (WearSyncPaths.MSG_START_MONITOR.equals(path)) {
            WearPreferences.setMonitorActive(getApplicationContext(), true, false);
            WearOngoingMonitor.restore(getApplicationContext());
        } else if (WearSyncPaths.MSG_STOP_MONITOR.equals(path)) {
            WearOngoingMonitor.stop(getApplicationContext(), false);
        } else if (WearSyncPaths.MSG_REFRESH.equals(path)) {
            WearPreferences.markConnected(getApplicationContext(), true);
        }
    }

    private void applySettings(String payload) {
        try {
            WearSettingsState remote = WearSettingsState.fromJson(new JSONObject(payload));
            if (remote == null || WearSettingsState.SOURCE_WEAR.equals(remote.sourceNode)) {
                return;
            }
            WearPhoneSync.applyRemoteSettings(getApplicationContext(), payload);
        } catch (Exception exception) {
            Log.w(TAG, "Could not apply phone settings payload", exception);
        }
    }
}
