package dev.bennett.codexmeter;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;
import dev.bennett.codexmeter.SamsungLockWidgetSupport;

/* JADX INFO: loaded from: classes.dex */
abstract class SamsungLockWidgetProvider extends AppWidgetProvider {
    protected abstract SamsungLockWidgetSupport.Shape shape();

    protected abstract SamsungLockWidgetSupport.Style style();

    protected SamsungLockWidgetSupport.Metric metric() {
        return SamsungLockWidgetSupport.Metric.BOTH;
    }

    SamsungLockWidgetProvider() {
    }

    @Override // android.appwidget.AppWidgetProvider
    public final void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] iArr) {
        SamsungLockWidgetSupport.updateIds(context, appWidgetManager, iArr, shape(), style(), metric());
    }

    @Override // android.appwidget.AppWidgetProvider
    public final void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int i, Bundle bundle) {
        SamsungLockWidgetSupport.update(context, appWidgetManager, i, shape(), style(), metric());
    }

    @Override // android.appwidget.AppWidgetProvider
    public final void onRestored(Context context, int[] iArr, int[] iArr2) {
        if (iArr != null && iArr2 != null) {
            int iMin = Math.min(iArr.length, iArr2.length);
            for (int i = 0; i < iMin; i++) {
                AppPreferences.saveLockWidgetOptions(context, iArr2[i], AppPreferences.loadLockWidgetOptions(context, iArr[i]));
                AppPreferences.deleteLockWidgetOptions(context, iArr[i]);
            }
        }
        SamsungLockWidgetSupport.updateIds(context, AppWidgetManager.getInstance(context), iArr2, shape(), style(), metric());
    }

    @Override // android.appwidget.AppWidgetProvider
    public final void onDeleted(Context context, int[] iArr) {
        if (iArr != null) {
            for (int i : iArr) {
                AppPreferences.deleteLockWidgetOptions(context, i);
            }
        }
    }

    @Override // android.appwidget.AppWidgetProvider
    public final void onEnabled(Context context) {
        SamsungLockWidgetSupport.updateAll(context);
    }
}
