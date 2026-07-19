package dev.bennett.codexmeter;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import dev.bennett.codexmeter.wear.WearSyncPaths;

public final class WearDataLayerService extends WearableListenerService {
    private static final String EMPTY = "";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED || event.getDataItem() == null) {
                continue;
            }
            WearPhoneSync.applyDataItem(getApplicationContext(), event.getDataItem());
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

}
