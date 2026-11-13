package dev.bennett.codexmeter;

import android.app.Activity;
import android.app.ActivityManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.util.Log;

/**
 * Debug-only helper for the open-routing check (copied in by the workflow, never shipped).
 * Extra "do":
 *   pin              asks the launcher to add a home-screen widget
 *   widget_provider  shows provider "id" on every home widget
 *   live             starts the live notification for provider "id", as its app opening would
 *   stop_live        stops the live notification
 *   clear_tasks      closes the app's tasks, so the next open is a cold one
 */
public class RoutingHarnessActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent().getStringExtra("do");
        String id = getIntent().getStringExtra("id");
        try {
            if ("pin".equals(action)) {
                AppWidgetManager.getInstance(this).requestPinAppWidget(
                        new ComponentName(this, CodexUsageWidget.class), null, null);
            } else if ("widget_provider".equals(action)) {
                AppWidgetManager manager = AppWidgetManager.getInstance(this);
                for (int widget : manager.getAppWidgetIds(
                        new ComponentName(this, CodexUsageWidget.class))) {
                    ProviderPreferences.setWidgetProvider(this, widget, UsageProvider.fromId(id));
                }
                WidgetRenderer.updateAll(this);
            } else if ("live".equals(action)) {
                UsageProvider provider = UsageProvider.fromId(id);
                boolean ok = NowBarManager.startForApp(this, provider, "harness." + provider.id);
                Log.i("RoutingHarness", "live " + id + " started=" + ok);
            } else if ("stop_live".equals(action)) {
                NowBarManager.stop(this, false);
            } else if ("clear_tasks".equals(action)) {
                for (ActivityManager.AppTask task
                        : getSystemService(ActivityManager.class).getAppTasks()) {
                    if (task.getTaskInfo().taskId != getTaskId()) task.finishAndRemoveTask();
                }
            }
        } catch (RuntimeException e) {
            Log.e("RoutingHarness", "failed: " + action, e);
        }
        finish();
    }
}
