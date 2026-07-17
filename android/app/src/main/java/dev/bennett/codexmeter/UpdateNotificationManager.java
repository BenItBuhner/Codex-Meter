package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Posts a one-shot “update available” notification with Open and Update actions. */
public final class UpdateNotificationManager {
    static final String CHANNEL_ID = "codex_update_available";
    static final int NOTIFICATION_ID = 73450;

    private UpdateNotificationManager() {
    }

    public static void ensureChannel(Context context) {
        NotificationManager manager = manager(context);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "App updates", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Alerts when a signed Codex Meter release is available");
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    /** After release metadata changes, notify once per available version when enabled. */
    public static void onReleasesUpdated(Context context) {
        if (context == null) {
            return;
        }
        Context app = application(context);
        if (!UpdatePreferences.notifyUpdatesEnabled(app)
                || !UpdatePreferences.automaticChecks(app)) {
            return;
        }
        GitHubRelease update = UpdatePreferences.availableUpdate(app);
        if (update == null) {
            dismiss(app);
            UpdatePreferences.clearNotifiedVersion(app);
            return;
        }
        String already = UpdatePreferences.notifiedVersion(app);
        if (update.version.equals(already)) {
            return;
        }
        if (post(app, update)) {
            UpdatePreferences.setNotifiedVersion(app, update.version);
        }
    }

    public static void dismiss(Context context) {
        NotificationManager manager = manager(context);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    private static boolean post(Context context, GitHubRelease release) {
        NotificationManager manager = manager(context);
        if (manager == null) {
            return false;
        }
        ensureChannel(context);
        if (!canPost(context, manager)) {
            return false;
        }
        String title = "Codex Meter " + release.version + " is available";
        String text = "A signed GitHub release is ready to install.";
        PendingIntent open = activityPending(context, NOTIFICATION_ID,
                updateIntent(context, release.version, false));
        PendingIntent update = activityPending(context, NOTIFICATION_ID + 1,
                updateIntent(context, release.version, true));
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(R.drawable.ic_notification,
                        "Open", open).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_notification,
                        "Update", update).build())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
        return true;
    }

    private static Intent updateIntent(Context context, String version, boolean startInstall) {
        Intent intent = new Intent(context, UpdateActivity.class)
                .putExtra(UpdateActivity.EXTRA_VERSION, version)
                .putExtra(UpdateActivity.EXTRA_START_INSTALL, startInstall)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static PendingIntent activityPending(Context context, int requestCode, Intent intent) {
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean canPost(Context context, NotificationManager manager) {
        if (!manager.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
