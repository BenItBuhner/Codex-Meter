package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* JADX INFO: loaded from: classes.dex */
public final class WidgetRefreshReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (intent != null && AppConstants.ACTION_REFRESH_WIDGET.equals(intent.getAction())) {
            // The tap asks for new numbers now, so it fetches the widget's providers however
            // fresh their readings are (the background schedule's five-minute gate would make
            // a tap right after a refresh do nothing).
            int widgetId = intent.getIntExtra("appWidgetId", -1);
            if (widgetId < 0) {
                RefreshScheduler.scheduleImmediate(context);
            } else {
                RefreshScheduler.scheduleWidgetRefresh(context,
                        ProviderPreferences.getWidgetProviders(context, widgetId));
            }
            WidgetRenderer.updateAll(context);
        }
    }
}
