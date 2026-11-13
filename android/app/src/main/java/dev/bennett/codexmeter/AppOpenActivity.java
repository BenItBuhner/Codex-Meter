package dev.bennett.codexmeter;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import java.util.List;

/**
 * Where a home-screen or lock-screen widget tap opens the app. The app comes back exactly as the
 * user left it (the same tab, or the Settings page they had open) when it is still running or in
 * Recents, and starts on its usual first screen otherwise, the way its launcher icon does.
 *
 * <p>Widgets can't point straight at {@link MainActivity}: it is {@code singleTask}, so a new
 * intent for it clears every screen above it and lands on the dashboard. This activity runs in a
 * task of its own, brings the app's task to the front untouched, and closes without drawing.
 */
public class AppOpenActivity extends Activity {
    static final String ACTION_OPEN = "dev.bennett.codexmeter.action.OPEN_APP";

    /** A widget's tap intent. {@code tag} keeps each widget's PendingIntent its own. */
    static Intent widgetIntent(Context context, Uri tag) {
        return new Intent(context, AppOpenActivity.class)
                .setAction(ACTION_OPEN)
                .setData(tag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!resumeAppTask()) {
            startActivity(new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** Brings the app's own task (most recent first) to the front; false when there is none. */
    private boolean resumeAppTask() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null) return false;
        try {
            List<ActivityManager.AppTask> tasks = manager.getAppTasks();
            for (ActivityManager.AppTask task : tasks) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null || taskId(info) == getTaskId() || isOwnTask(info)) continue;
                task.moveToFront();
                return true;
            }
        } catch (RuntimeException e) {
            Log.w("CodexMeter", "Could not bring the app back; starting it instead", e);
        }
        return false;
    }

    /** Skips leftover tasks from earlier widget taps, which only ever hold this activity. */
    private boolean isOwnTask(ActivityManager.RecentTaskInfo info) {
        ComponentName base = info.baseIntent == null ? null : info.baseIntent.getComponent();
        return base != null && AppOpenActivity.class.getName().equals(base.getClassName());
    }

    @SuppressWarnings("deprecation")
    private static int taskId(ActivityManager.RecentTaskInfo info) {
        return Build.VERSION.SDK_INT >= 29 ? info.taskId : info.persistentId;
    }
}
