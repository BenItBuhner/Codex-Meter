package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * "Show in AI apps" needs two switches: the one in Codex Meter and Codex Meter's app detection in
 * Android's Accessibility settings. Android or the phone maker can turn the second one off on its
 * own, for example around an app update, and then the live notification silently stops appearing
 * in AI apps. After an update or a reboot, and whenever Codex Meter opens, this checks the two
 * agree; if detection is off while the feature is on, one notification says so and opens the
 * setting. It goes away once detection is back on.
 */
final class AppSessionHealth {
    private static final String CHANNEL = "codex_app_detection";
    private static final int NOTIFICATION_ID = 7320;

    private AppSessionHealth() {}

    /** Whether the user wants "Show in AI apps" but Android has app detection off. Pure. */
    static boolean detectionLost(boolean featureOn, boolean detectionAllowed) {
        return featureOn && !detectionAllowed;
    }

    static void check(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        boolean featureOn = AppSessionPreferences.isEnabled(app);
        boolean allowed = AppSessionPreferences.isDetectionAllowed(app);
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (!detectionLost(featureOn, allowed)) {
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        DiagnosticLog.info(app, "app_session", "detection_off_in_android");
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(
                "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)) {
            return;
        }
        NotificationChannel c = new NotificationChannel(CHANNEL, "Show in AI apps",
                NotificationManager.IMPORTANCE_DEFAULT);
        c.setDescription("When Android turns off app detection, so the live notification can't show in AI apps");
        manager.createNotificationChannel(c);
        PendingIntent open = PendingIntent.getActivity(app, NOTIFICATION_ID,
                new Intent(app, SettingsActivity.class)
                        .putExtra(SettingsActivity.EXTRA_PAGE, "app_sessions")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = "Android turned off Codex Meter's app detection, so the live notification "
                + "won't show in AI apps. Tap to turn it back on.";
        manager.notify(NOTIFICATION_ID, new Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Show in AI apps is off")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build());
    }
}
