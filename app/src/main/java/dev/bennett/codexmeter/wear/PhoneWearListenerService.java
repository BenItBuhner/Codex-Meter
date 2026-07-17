package dev.bennett.codexmeter.wear;

import android.net.Uri;
import android.util.Log;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import dev.bennett.codexmeter.AppPreferences;
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
            Uri uri = event.getDataItem().getUri();
            String path = uri == null ? "" : uri.getPath();
            if (!WearSyncPaths.PATH_SETTINGS.equals(path)) {
                continue;
            }
            applySettings(event);
        }
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        String path = event == null ? "" : event.getPath();
        if (WearSyncPaths.MSG_REFRESH.equals(path)) {
            refreshInBackground();
        } else if (WearSyncPaths.MSG_START_MONITOR.equals(path)) {
            NowBarManager.start(getApplicationContext());
            PhoneWearSync.pushSettings(getApplicationContext());
        } else if (WearSyncPaths.MSG_STOP_MONITOR.equals(path)) {
            NowBarManager.stop(getApplicationContext(), true);
            PhoneWearSync.pushSettings(getApplicationContext());
        }
    }

    private void applySettings(DataEvent event) {
        try {
            String payload = PhoneWearSync.payloadString(event.getDataItem());
            if (payload == null || payload.isEmpty()) return;
            WearSettingsState remote = WearSettingsState.fromJson(new JSONObject(payload));
            PhoneWearSync.applyRemoteSettings(getApplicationContext(), remote);
        } catch (Exception exception) {
            Log.w(TAG, "Could not apply Wear settings payload", exception);
        }
    }

    private void refreshInBackground() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                UsageApi.refreshAndCache(app);
            } catch (Exception exception) {
                AppPreferences.setLastError(app, exception.getMessage());
                Log.w(TAG, "Wear-requested usage refresh failed", exception);
            }
        }, "codex-wear-refresh").start();
    }
}
