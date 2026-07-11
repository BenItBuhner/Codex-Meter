package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/* JADX INFO: loaded from: classes.dex */
public final class ResetAlertReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ALARM = "codex_reset_alarm";
    private static final String CHANNEL_NOTIFY = "codex_reset_notify";
    private static final String CHANNEL_SILENT = "codex_reset_silent";

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (context != null && intent != null && AppConstants.ACTION_RESET_ALERT.equals(intent.getAction()) && SecureTokenStore.isSignedIn(context) && ResetAlertPreferences.enabled(context)) {
            String stringExtra = intent.getStringExtra("metric");
            if (!"weekly".equals(stringExtra)) {
                stringExtra = "five_hour";
            }
            if (stillRelevant(context, stringExtra, intent.getLongExtra("reset_at", 0L))) {
                showNotification(context, stringExtra);
                RefreshScheduler.scheduleImmediate(context);
                WidgetRenderer.updateAll(context);
            }
        }
    }

    private static boolean stillRelevant(Context context, String str, long j) {
        UsageSnapshot usageSnapshotLoadSnapshot;
        if (j <= 0 || (usageSnapshotLoadSnapshot = AppPreferences.loadSnapshot(context)) == null) {
            return true;
        }
        UsageWindow usageWindow = "weekly".equals(str) ? usageSnapshotLoadSnapshot.weekly : usageSnapshotLoadSnapshot.fiveHour;
        return usageWindow == null || usageWindow.resetAtMillis() <= 0 || Math.abs(usageWindow.resetAtMillis() - j) < 60000;
    }

    private static void showNotification(Context context, String str) {
        NotificationManager notificationManager;
        if ((Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0) && (notificationManager = (NotificationManager) context.getSystemService(ResetAlertPreferences.STYLE_NOTIFICATION)) != null) {
            String strCreateChannel = createChannel(notificationManager, ResetAlertPreferences.getStyle(context));
            boolean zEquals = "weekly".equals(str);
            String str2 = zEquals ? "Weekly" : "5-hour";
            notificationManager.notify(zEquals ? 74407 : 74405, new Notification.Builder(context, strCreateChannel).setSmallIcon(R.drawable.ic_reset_notification).setContentTitle("Codex " + str2 + " usage reset").setContentText("Your " + str2 + " allowance should be available again. Refreshing usage now.").setContentIntent(PendingIntent.getActivity(context, zEquals ? 74307 : 74305, new Intent(context, (Class<?>) MainActivity.class).addFlags(335544320), 201326592)).setAutoCancel(true).setCategory("reminder").setVisibility(0).setShowWhen(true).build());
        }
    }

    private static String createChannel(NotificationManager notificationManager, String str) {
        if (ResetAlertPreferences.STYLE_SILENT.equals(str)) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_SILENT, "Codex reset reminders", 2);
            notificationChannel.setSound(null, null);
            notificationChannel.enableVibration(false);
            notificationManager.createNotificationChannel(notificationChannel);
            return CHANNEL_SILENT;
        }
        if (ResetAlertPreferences.STYLE_ALARM.equals(str)) {
            NotificationChannel notificationChannel2 = new NotificationChannel(CHANNEL_ALARM, "Codex reset alarms", 4);
            notificationChannel2.setSound(RingtoneManager.getDefaultUri(4), new AudioAttributes.Builder().setUsage(4).setContentType(4).build());
            notificationChannel2.enableVibration(true);
            notificationManager.createNotificationChannel(notificationChannel2);
            return CHANNEL_ALARM;
        }
        NotificationChannel notificationChannel3 = new NotificationChannel(CHANNEL_NOTIFY, "Codex reset notifications", 3);
        notificationChannel3.setSound(RingtoneManager.getDefaultUri(2), new AudioAttributes.Builder().setUsage(5).setContentType(4).build());
        notificationManager.createNotificationChannel(notificationChannel3);
        return CHANNEL_NOTIFY;
    }
}
