package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetRefreshReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (intent != null && AppConstants.ACTION_REFRESH_WIDGET.equals(intent.getAction())) {
            RefreshScheduler.scheduleImmediate(context);
            WidgetRenderer.updateAll(context);
        }
    }
}
