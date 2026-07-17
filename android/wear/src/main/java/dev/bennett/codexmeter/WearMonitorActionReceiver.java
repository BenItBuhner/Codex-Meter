package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import dev.bennett.codexmeter.wear.WearSyncPaths;

public final class WearMonitorActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (WearOngoingMonitor.ACTION_STOP.equals(action)) {
            WearOngoingMonitor.stop(context, true);
        } else if (WearOngoingMonitor.ACTION_REFRESH.equals(action)) {
            WearPhoneSync.sendMessageToPhone(context, WearSyncPaths.MSG_REFRESH);
        }
    }
}
