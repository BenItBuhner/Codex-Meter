package dev.bennett.codexmeter;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/* JADX INFO: loaded from: classes.dex */
public final class CodexUsageWidget extends AppWidgetProvider {
    @Override // android.appwidget.AppWidgetProvider
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] iArr) {
        if (iArr != null) {
            for (int i : iArr) {
                WidgetRenderer.update(context, appWidgetManager, i);
            }
        }
        RefreshScheduler.schedulePeriodic(context);
        RefreshScheduler.scheduleImmediate(context);
    }

    @Override // android.appwidget.AppWidgetProvider
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int i, Bundle bundle) {
        WidgetRenderer.update(context, appWidgetManager, i);
    }

    @Override // android.appwidget.AppWidgetProvider
    public void onEnabled(Context context) {
        RefreshScheduler.schedulePeriodic(context);
        RefreshScheduler.scheduleImmediate(context);
    }

    @Override // android.appwidget.AppWidgetProvider
    public void onDisabled(Context context) {
        RefreshScheduler.schedulePeriodic(context);
    }

    @Override // android.appwidget.AppWidgetProvider
    public void onDeleted(Context context, int[] iArr) {
        if (iArr != null) {
            for (int i : iArr) {
                AppPreferences.deleteWidgetOptions(context, i);
            }
        }
    }

    @Override
    public void onRestored(Context context, int[] oldWidgetIds, int[] newWidgetIds) {
        if (oldWidgetIds != null && newWidgetIds != null) {
            int count = Math.min(oldWidgetIds.length, newWidgetIds.length);
            for (int index = 0; index < count; index++) {
                int oldId = oldWidgetIds[index];
                int newId = newWidgetIds[index];
                AppPreferences.saveWidgetOptions(context, newId,
                        AppPreferences.loadWidgetOptions(context, oldId));
                AppPreferences.saveWidgetTapAction(context, newId,
                        AppPreferences.getWidgetTapAction(context, oldId));
                AppPreferences.deleteWidgetOptions(context, oldId);
            }
        }
        if (newWidgetIds != null) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            for (int appWidgetId : newWidgetIds) {
                WidgetRenderer.update(context, manager, appWidgetId);
            }
        }
    }
}
