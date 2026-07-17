package dev.bennett.codexmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import dev.bennett.codexmeter.SamsungLockWidgetSupport;

/* JADX INFO: loaded from: classes.dex */
public final class SamsungLockServiceBoxReceiver extends BroadcastReceiver {
    private static final String ACTION_REQUEST = "com.samsung.android.intent.action.REQUEST_SERVICEBOX_REMOTEVIEWS";
    private static final String ACTION_RESPONSE = "com.samsung.android.intent.action.RESPONSE_SERVICEBOX_REMOTEVIEWS";
    private static final Entry[] ENTRIES = {new Entry("codex_meter_numbers_square", SamsungLockWidgetSupport.Shape.SQUARE, SamsungLockWidgetSupport.Style.NUMBERS), new Entry("codex_meter_numbers_wide", SamsungLockWidgetSupport.Shape.WIDE, SamsungLockWidgetSupport.Style.NUMBERS), new Entry("codex_meter_rings_square", SamsungLockWidgetSupport.Shape.SQUARE, SamsungLockWidgetSupport.Style.RINGS), new Entry("codex_meter_rings_wide", SamsungLockWidgetSupport.Shape.WIDE, SamsungLockWidgetSupport.Style.RINGS), new Entry("codex_meter_dials_square", SamsungLockWidgetSupport.Shape.SQUARE, SamsungLockWidgetSupport.Style.DIALS), new Entry("codex_meter_dials_wide", SamsungLockWidgetSupport.Shape.WIDE, SamsungLockWidgetSupport.Style.DIALS), new Entry("codex_meter_bars_square", SamsungLockWidgetSupport.Shape.SQUARE, SamsungLockWidgetSupport.Style.BARS), new Entry("codex_meter_bars_wide", SamsungLockWidgetSupport.Shape.WIDE, SamsungLockWidgetSupport.Style.BARS)};
    private static final String SYSTEM_UI = "com.android.systemui";

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (context != null && intent != null && ACTION_REQUEST.equals(intent.getAction())) {
            String stringExtra = intent.getStringExtra("pageId");
            for (Entry entry : ENTRIES) {
                if (stringExtra == null || stringExtra.isEmpty() || entry.pageId.equals(stringExtra)) {
                    send(context, entry);
                }
            }
        }
    }

    private static void send(Context context, Entry entry) {
        try {
            context.sendBroadcast(new Intent(ACTION_RESPONSE).setPackage(SYSTEM_UI).putExtra("package", context.getPackageName()).putExtra("pageId", entry.pageId).putExtra("show", true).putExtra("origin", SamsungLockWidgetSupport.buildViews(context, entry.shape, entry.style)).putExtra("aod", SamsungLockWidgetSupport.buildViews(context, entry.shape, entry.style)));
        } catch (RuntimeException e) {
            Log.w("CodexMeterLock", "ServiceBox response failed", e);
        }
    }

    private static final class Entry {
        final String pageId;
        final SamsungLockWidgetSupport.Shape shape;
        final SamsungLockWidgetSupport.Style style;

        Entry(String str, SamsungLockWidgetSupport.Shape shape, SamsungLockWidgetSupport.Style style) {
            this.pageId = str;
            this.shape = shape;
            this.style = style;
        }
    }
}
