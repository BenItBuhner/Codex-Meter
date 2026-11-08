package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.List;

/**
 * The optional "New models" digest. It follows the app's notification switch and delivery style,
 * but on its own low-key channels: silent stays silent, and both "Notification sound" and
 * "Alarm sound" use the ordinary notification sound. A new model is never an alarm.
 */
final class ModelDiscoveryNotifier {
    private static final String CHANNEL_SILENT = "codex_new_models_silent";
    private static final String CHANNEL_NOTIFY = "codex_new_models";
    /** One id, so a newer digest replaces an unread older one instead of stacking. */
    private static final int NOTIFICATION_ID = 7310;

    private ModelDiscoveryNotifier() {}

    static boolean show(Context context, List<ModelDiscovery.Entry> items) {
        if (items.isEmpty() || !ResetAlertPreferences.enabled(context)
                || !ModelDiscoveryStore.notifyEnabled(context)) {
            return false;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                        "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        boolean silent = ResetAlertPreferences.STYLE_SILENT.equals(ResetAlertPreferences.getStyle(context));
        String channel = silent ? CHANNEL_SILENT : CHANNEL_NOTIFY;
        NotificationChannel c = new NotificationChannel(channel, "New models",
                silent ? NotificationManager.IMPORTANCE_LOW : NotificationManager.IMPORTANCE_DEFAULT);
        c.setDescription("Models newly listed in public catalogs for your connected providers");
        if (silent) {
            c.setSound(null, null);
            c.enableVibration(false);
        }
        manager.createNotificationChannel(c);
        NotificationChannel existing = manager.getNotificationChannel(channel);
        if (existing == null || existing.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }
        PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = items.size() == 1 ? "New model listed" : items.size() + " new models listed";
        String text = ModelDiscovery.digestText(items);
        manager.notify(NOTIFICATION_ID, new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text.split("\n", 2)[0])
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setShowWhen(true)
                .build());
        return true;
    }
}
