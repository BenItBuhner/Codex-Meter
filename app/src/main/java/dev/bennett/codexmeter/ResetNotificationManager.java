package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/** Posts and deduplicates Codex usage, reset-time, and reset-credit notifications. */
public final class ResetNotificationManager {
    private static final String CHANNEL_ALARM = "codex_reset_alarm";
    private static final String CHANNEL_EXTERNAL_CELEBRATION = "codex_external_reset_celebration_v1";
    private static final String CHANNEL_EXTERNAL_NOTIFY = "codex_external_reset_notify_v1";
    private static final String CHANNEL_EXTERNAL_SILENT = "codex_external_reset_silent_v1";
    private static final String CHANNEL_NOTIFY = "codex_reset_notify";
    private static final String CHANNEL_SILENT = "codex_reset_silent";
    private static final String PREFS = "codex_meter_notification_state_v1";
    private static final String KEY_FIVE_HOUR_LAST_RESET_AT = "five_hour_last_reset_at";
    private static final String KEY_FIVE_HOUR_LAST_USED = "five_hour_last_used";
    private static final String KEY_FIVE_HOUR_WINDOW = "low_five_hour_window";
    private static final String KEY_MANUAL_RESET_PENDING_UNTIL = "manual_reset_pending_until";
    private static final String KEY_WEEKLY_LAST_RESET_AT = "weekly_last_reset_at";
    private static final String KEY_WEEKLY_LAST_USED = "weekly_last_used";
    private static final String KEY_WEEKLY_WINDOW = "low_weekly_window";
    private static final String KEY_CREDIT_COUNT = "known_reset_credit_count";
    private static final long MANUAL_RESET_SUPPRESSION_MS = 5 * 60_000L;
    private static final int NOTIFICATION_TEST = 74400;
    private static final int NOTIFICATION_EXTERNAL_RESET_TEST = 74410;
    private static final int NOTIFICATION_EXTERNAL_RESET = 74411;
    private static final int NOTIFICATION_RESET_FIVE_HOUR = 74405;
    private static final int NOTIFICATION_RESET_WEEKLY = 74407;
    private static final int NOTIFICATION_LOW_FIVE_HOUR = 74505;
    private static final int NOTIFICATION_LOW_WEEKLY = 74507;
    private static final int NOTIFICATION_NEW_CREDIT = 74509;

    private ResetNotificationManager() {
    }

    public static void onUsageUpdated(Context context, UsageSnapshot snapshot) {
        if (context == null || snapshot == null || !ResetAlertPreferences.enabled(context)) return;
        int unexpectedResets = detectUnexpectedResets(context, snapshot);
        int celebratedResets = ResetAlertPreferences.externalResetEnabled(context)
                ? unexpectedResets : ExternalResetDetector.NONE;
        if (celebratedResets != ExternalResetDetector.NONE) {
            postExternalReset(context, celebratedResets, NOTIFICATION_EXTERNAL_RESET);
        }
        String metric = ResetAlertPreferences.getMetric(context);
        if (!ResetAlertPreferences.METRIC_WEEKLY.equals(metric)
                && (celebratedResets & ExternalResetDetector.FIVE_HOUR) == 0) {
            notifyLowWindow(context, snapshot.fiveHour, snapshot.fetchedAtMillis,
                    "5-hour", KEY_FIVE_HOUR_WINDOW, NOTIFICATION_LOW_FIVE_HOUR);
        }
        if (!ResetAlertPreferences.METRIC_FIVE_HOUR.equals(metric)
                && (celebratedResets & ExternalResetDetector.WEEKLY) == 0) {
            notifyLowWindow(context, snapshot.weekly, snapshot.fetchedAtMillis,
                    "Weekly", KEY_WEEKLY_WINDOW, NOTIFICATION_LOW_WEEKLY);
        }
    }

    public static void markManualReset(Context context, long observedAtMillis) {
        if (context == null) return;
        state(context).edit()
                .putLong(KEY_MANUAL_RESET_PENDING_UNTIL,
                        observedAtMillis + MANUAL_RESET_SUPPRESSION_MS)
                .putInt(KEY_FIVE_HOUR_LAST_USED, 0)
                .putInt(KEY_WEEKLY_LAST_USED, 0)
                .apply();
    }

    public static void onResetCreditsUpdated(Context context, ResetCreditsSnapshot snapshot) {
        if (context == null || snapshot == null) return;
        SharedPreferences state = state(context);
        int current = snapshot.availableCount;
        if (!state.contains(KEY_CREDIT_COUNT)) {
            state.edit().putInt(KEY_CREDIT_COUNT, current).apply();
            return;
        }
        int previous = state.getInt(KEY_CREDIT_COUNT, current);
        state.edit().putInt(KEY_CREDIT_COUNT, current).apply();
        if (!ResetAlertPreferences.enabled(context) || current <= previous) return;
        int added = current - previous;
        String text = added == 1
                ? "A new Codex reset is available. You now have " + current + "."
                : added + " new Codex resets are available. You now have " + current + ".";
        post(context, NOTIFICATION_NEW_CREDIT, "New Codex reset available", text,
                NOTIFICATION_NEW_CREDIT);
    }

    public static void showResetNotification(Context context, String metric) {
        boolean weekly = ResetAlertPreferences.METRIC_WEEKLY.equals(metric);
        String label = weekly ? "Weekly" : "5-hour";
        post(context, weekly ? NOTIFICATION_RESET_WEEKLY : NOTIFICATION_RESET_FIVE_HOUR,
                "Codex " + label + " usage reset",
                "Your " + label + " allowance should be available again. Refreshing usage now.",
                weekly ? NOTIFICATION_RESET_WEEKLY : NOTIFICATION_RESET_FIVE_HOUR);
    }

    public static boolean sendTestNotification(Context context) {
        if (context == null || !ResetAlertPreferences.enabled(context) || !canPost(context)) {
            return false;
        }
        return post(context, NOTIFICATION_TEST, "Codex Meter notifications are working",
                "You’ll be notified for low usage, usage-window resets, surprise resets, and new reset credits.",
                NOTIFICATION_TEST);
    }

    public static boolean sendExternalResetTestNotification(Context context) {
        return context != null && ResetAlertPreferences.enabled(context)
                && ResetAlertPreferences.externalResetEnabled(context)
                && postExternalReset(context,
                        ExternalResetDetector.FIVE_HOUR | ExternalResetDetector.WEEKLY,
                        NOTIFICATION_EXTERNAL_RESET_TEST);
    }

    public static void ensureChannel(Context context) {
        if (context == null) return;
        NotificationManager manager = manager(context);
        if (manager != null) {
            createChannel(manager, ResetAlertPreferences.getStyle(context));
            if (ResetAlertPreferences.externalResetEnabled(context)) {
                createExternalChannel(manager, ResetAlertPreferences.getExternalResetStyle(context));
            }
        }
    }

    public static void clearState(Context context) {
        if (context != null) state(context).edit().clear().apply();
    }

    private static void notifyLowWindow(Context context, UsageWindow window, long fetchedAt,
            String label, String stateKey, int notificationId) {
        if (window == null || window.remainingPercent() > ResetAlertPreferences.getThreshold(context)) return;
        long windowId = window.resetAtMillis();
        if (windowId <= 0L && window.resetAfterSeconds > 0L) {
            windowId = fetchedAt + window.resetAfterSeconds * 1000L;
        }
        if (windowId <= 0L) return;
        SharedPreferences state = state(context);
        if (state.getLong(stateKey, 0L) == windowId) return;
        int remaining = window.remainingPercent();
        if (post(context, notificationId, label + " Codex usage is low",
                remaining + "% remaining in the current " + label.toLowerCase() + " window.",
                notificationId)) {
            state.edit().putLong(stateKey, windowId).apply();
        }
    }

    private static int detectUnexpectedResets(Context context, UsageSnapshot snapshot) {
        SharedPreferences state = state(context);
        long observedAt = snapshot.fetchedAtMillis > 0L
                ? snapshot.fetchedAtMillis : System.currentTimeMillis();
        boolean manualResetPending = state.getLong(KEY_MANUAL_RESET_PENDING_UNTIL, 0L)
                >= System.currentTimeMillis();
        int result = detectUnexpectedReset(state, snapshot.fiveHour, observedAt,
                manualResetPending, KEY_FIVE_HOUR_LAST_USED, KEY_FIVE_HOUR_LAST_RESET_AT)
                ? ExternalResetDetector.FIVE_HOUR : ExternalResetDetector.NONE;
        if (detectUnexpectedReset(state, snapshot.weekly, observedAt, manualResetPending,
                KEY_WEEKLY_LAST_USED, KEY_WEEKLY_LAST_RESET_AT)) {
            result |= ExternalResetDetector.WEEKLY;
        }

        SharedPreferences.Editor editor = state.edit();
        saveWindowState(editor, snapshot.fiveHour, snapshot.fetchedAtMillis,
                KEY_FIVE_HOUR_LAST_USED, KEY_FIVE_HOUR_LAST_RESET_AT);
        saveWindowState(editor, snapshot.weekly, snapshot.fetchedAtMillis,
                KEY_WEEKLY_LAST_USED, KEY_WEEKLY_LAST_RESET_AT);
        editor.apply();
        return result;
    }

    private static boolean detectUnexpectedReset(SharedPreferences state, UsageWindow current,
            long observedAt, boolean manualResetPending, String usedKey, String resetAtKey) {
        return current != null && state.contains(usedKey)
                && ExternalResetDetector.isUnexpectedReset(state.getInt(usedKey, 0),
                        state.getLong(resetAtKey, 0L), current, observedAt, manualResetPending);
    }

    private static void saveWindowState(SharedPreferences.Editor editor, UsageWindow window,
            long fetchedAt, String usedKey, String resetAtKey) {
        if (window == null) {
            editor.remove(usedKey).remove(resetAtKey);
            return;
        }
        long observedAt = fetchedAt > 0L ? fetchedAt : System.currentTimeMillis();
        editor.putInt(usedKey, window.usedPercent)
                .putLong(resetAtKey, ExternalResetDetector.expectedResetAt(window, observedAt));
    }

    private static boolean postExternalReset(Context context, int resets, int notificationId) {
        String label;
        if (resets == (ExternalResetDetector.FIVE_HOUR | ExternalResetDetector.WEEKLY)) {
            label = "5-hour and weekly allowances";
        } else if ((resets & ExternalResetDetector.WEEKLY) != 0) {
            label = "weekly allowance";
        } else {
            label = "5-hour allowance";
        }
        return postWithChannel(context, notificationId, "Surprise! Codex limits restored",
                "Your " + label + " jumped back to 100% before the countdown ended. "
                        + "Looks like someone reset your Codex limits for you!",
                notificationId, true);
    }

    private static boolean post(Context context, int id, String title, String text, int requestCode) {
        return postWithChannel(context, id, title, text, requestCode, false);
    }

    private static boolean postWithChannel(Context context, int id, String title, String text,
            int requestCode, boolean externalReset) {
        if (!canPost(context)) return false;
        NotificationManager manager = manager(context);
        if (manager == null) return false;
        String channel = externalReset
                ? createExternalChannel(manager, ResetAlertPreferences.getExternalResetStyle(context))
                : createChannel(manager, ResetAlertPreferences.getStyle(context));
        PendingIntent contentIntent = PendingIntent.getActivity(context, requestCode,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_oui_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .build();
        manager.notify(id, notification);
        return true;
    }

    private static boolean canPost(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String createChannel(NotificationManager manager, String style) {
        if (ResetAlertPreferences.STYLE_SILENT.equals(style)) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_SILENT,
                    "Codex usage alerts", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Low usage, reset times, and new reset credits");
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
            return CHANNEL_SILENT;
        }
        if (ResetAlertPreferences.STYLE_ALARM.equals(style)) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ALARM,
                    "Codex usage alarms", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Low usage, reset times, and new reset credits");
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
            return CHANNEL_ALARM;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_NOTIFY,
                "Codex usage alerts", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Low usage, reset times, and new reset credits");
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        manager.createNotificationChannel(channel);
        return CHANNEL_NOTIFY;
    }

    private static String createExternalChannel(NotificationManager manager, String style) {
        String channelId;
        String channelName;
        int importance;
        if (ResetAlertPreferences.EXTERNAL_STYLE_SILENT.equals(style)) {
            channelId = CHANNEL_EXTERNAL_SILENT;
            channelName = "Silent surprise Codex resets";
            importance = NotificationManager.IMPORTANCE_LOW;
        } else if (ResetAlertPreferences.EXTERNAL_STYLE_NOTIFICATION.equals(style)) {
            channelId = CHANNEL_EXTERNAL_NOTIFY;
            channelName = "Surprise Codex resets";
            importance = NotificationManager.IMPORTANCE_DEFAULT;
        } else {
            channelId = CHANNEL_EXTERNAL_CELEBRATION;
            channelName = "Surprise Codex reset celebrations";
            importance = NotificationManager.IMPORTANCE_HIGH;
        }
        NotificationChannel channel = new NotificationChannel(channelId, channelName, importance);
        channel.setDescription("Celebrations when an unexplained reset restores your allowance");
        if (ResetAlertPreferences.EXTERNAL_STYLE_SILENT.equals(style)) {
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
            return channelId;
        }
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        if (ResetAlertPreferences.EXTERNAL_STYLE_CELEBRATION.equals(style)) {
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0L, 180L, 90L, 180L, 90L, 360L});
        }
        manager.createNotificationChannel(channel);
        return channelId;
    }
}
