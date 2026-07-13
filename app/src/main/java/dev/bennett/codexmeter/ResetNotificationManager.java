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
import java.util.Locale;

/** Posts and deduplicates Codex usage, reset-time, and reset-credit notifications. */
public final class ResetNotificationManager {
    private static final String CHANNEL_ALARM = "codex_reset_alarm";
    private static final String CHANNEL_NOTIFY = "codex_reset_notify";
    private static final String CHANNEL_SILENT = "codex_reset_silent";
    private static final String PREFS = "codex_meter_notification_state_v1";
    private static final String KEY_FIVE_HOUR_WINDOW = "low_five_hour_window";
    private static final String KEY_WEEKLY_WINDOW = "low_weekly_window";
    private static final String KEY_CREDIT_COUNT = "known_reset_credit_count";
    private static final String KEY_USER_RESET_FIVE_HOUR_UNTIL = "user_reset_five_hour_until";
    private static final String KEY_USER_RESET_WEEKLY_UNTIL = "user_reset_weekly_until";
    private static final long UNKNOWN_USER_RESET_SUPPRESSION_MS = 15 * 60 * 1000L;
    private static final int NOTIFICATION_TEST = 74400;
    private static final int NOTIFICATION_RESET_FIVE_HOUR = 74405;
    private static final int NOTIFICATION_RESET_WEEKLY = 74407;
    private static final int NOTIFICATION_LOW_FIVE_HOUR = 74505;
    private static final int NOTIFICATION_LOW_WEEKLY = 74507;
    private static final int NOTIFICATION_NEW_CREDIT = 74509;
    private static final int NOTIFICATION_REFILL_FIVE_HOUR = 74511;
    private static final int NOTIFICATION_REFILL_WEEKLY = 74512;
    private static final int NOTIFICATION_REFILL_BOTH = 74513;

    private ResetNotificationManager() {
    }

    public static void onUsageUpdated(Context context, UsageSnapshot snapshot) {
        onUsageUpdated(context, null, snapshot);
    }

    public static void onUsageUpdated(Context context, UsageSnapshot previous,
            UsageSnapshot snapshot) {
        if (context == null || snapshot == null) return;
        int unexpectedRefills = suppressUserResetRefills(context,
                CelebrationDetector.detectUnexpectedRefills(previous, snapshot),
                snapshot.fetchedAtMillis);
        if (snapshot.resetCreditsAvailable >= 0) {
            onResetCreditCountUpdated(context, snapshot.resetCreditsAvailable);
        }
        if (!ResetAlertPreferences.enabled(context)) return;
        String metric = ResetAlertPreferences.getMetric(context);
        if (!ResetAlertPreferences.METRIC_WEEKLY.equals(metric)) {
            notifyLowWindow(context, snapshot.fiveHour, snapshot.fetchedAtMillis,
                    "5-hour", KEY_FIVE_HOUR_WINDOW, NOTIFICATION_LOW_FIVE_HOUR);
        }
        if (!ResetAlertPreferences.METRIC_FIVE_HOUR.equals(metric)) {
            notifyLowWindow(context, snapshot.weekly, snapshot.fetchedAtMillis,
                    "Weekly", KEY_WEEKLY_WINDOW, NOTIFICATION_LOW_WEEKLY);
        }
        if (ResetAlertPreferences.unexpectedRefillsEnabled(context)) {
            notifyUnexpectedRefill(context, unexpectedRefills);
        }
    }

    public static void onResetCreditsUpdated(Context context, ResetCreditsSnapshot snapshot) {
        if (context == null || snapshot == null) return;
        onResetCreditCountUpdated(context, snapshot.availableCount);
    }

    private static void onResetCreditCountUpdated(Context context, int current) {
        SharedPreferences state = state(context);
        if (!state.contains(KEY_CREDIT_COUNT)) {
            state.edit().putInt(KEY_CREDIT_COUNT, current).apply();
            return;
        }
        int previous = state.getInt(KEY_CREDIT_COUNT, current);
        state.edit().putInt(KEY_CREDIT_COUNT, current).apply();
        int added = CelebrationDetector.resetCreditsAdded(previous, current);
        if (!ResetAlertPreferences.enabled(context)
                || !ResetAlertPreferences.resetCreditIncreasesEnabled(context)
                || added <= 0) return;
        String text = added == 1
                ? "One Codex reset credit was added. You now have " + current + "."
                : added + " Codex reset credits were added. You now have " + current + ".";
        post(context, NOTIFICATION_NEW_CREDIT,
                added == 1 ? "Codex reset credit added" : "Codex reset credits added", text,
                NOTIFICATION_NEW_CREDIT);
    }

    /**
     * Marks cached non-full windows as user-reset so delayed propagation cannot be mistaken for
     * an external refill on a later refresh.
     */
    public static void markUserReset(Context context, UsageSnapshot snapshot) {
        if (context == null || snapshot == null) return;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = state(context).edit();
        markUserResetWindow(editor, KEY_USER_RESET_FIVE_HOUR_UNTIL,
                snapshot, snapshot.fiveHour, now);
        markUserResetWindow(editor, KEY_USER_RESET_WEEKLY_UNTIL,
                snapshot, snapshot.weekly, now);
        editor.apply();
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
                "Low usage, scheduled resets, surprise refills, and reset-credit alerts are ready.",
                NOTIFICATION_TEST);
    }

    public static void ensureChannel(Context context) {
        if (context == null) return;
        NotificationManager manager = manager(context);
        if (manager != null) createChannel(manager, ResetAlertPreferences.getStyle(context));
    }

    public static void clearState(Context context) {
        if (context != null) state(context).edit().clear().apply();
    }

    public static void clearNotificationHistory(Context context) {
        if (context == null) return;
        state(context).edit()
                .remove(KEY_FIVE_HOUR_WINDOW)
                .remove(KEY_WEEKLY_WINDOW)
                .remove(KEY_CREDIT_COUNT)
                .apply();
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
                remaining + "% remaining in the current "
                        + label.toLowerCase(Locale.ROOT) + " window.",
                notificationId)) {
            state.edit().putLong(stateKey, windowId).apply();
        }
    }

    private static void notifyUnexpectedRefill(Context context, int refills) {
        if (refills == 0) return;
        boolean fiveHour = (refills & CelebrationDetector.FIVE_HOUR) != 0;
        boolean weekly = (refills & CelebrationDetector.WEEKLY) != 0;
        if (fiveHour && weekly) {
            post(context, NOTIFICATION_REFILL_BOTH, "Surprise Codex refill",
                    "Your 5-hour and weekly allowances jumped to 100% before their scheduled resets. Enjoy the bonus capacity.",
                    NOTIFICATION_REFILL_BOTH);
        } else if (weekly) {
            post(context, NOTIFICATION_REFILL_WEEKLY, "Surprise weekly Codex refill",
                    "Your weekly allowance jumped to 100% before its scheduled reset. Enjoy the bonus capacity.",
                    NOTIFICATION_REFILL_WEEKLY);
        } else {
            post(context, NOTIFICATION_REFILL_FIVE_HOUR, "Surprise 5-hour Codex refill",
                    "Your 5-hour allowance jumped to 100% before its scheduled reset. Enjoy the bonus capacity.",
                    NOTIFICATION_REFILL_FIVE_HOUR);
        }
    }

    private static int suppressUserResetRefills(Context context, int refills, long observedAt) {
        SharedPreferences preferences = state(context);
        SharedPreferences.Editor editor = preferences.edit();
        long fiveHourUntil = preferences.getLong(KEY_USER_RESET_FIVE_HOUR_UNTIL, 0L);
        long weeklyUntil = preferences.getLong(KEY_USER_RESET_WEEKLY_UNTIL, 0L);
        int filtered = CelebrationDetector.withoutUserResetRefills(refills, observedAt,
                fiveHourUntil, weeklyUntil);
        clearUsedSuppression(editor, KEY_USER_RESET_FIVE_HOUR_UNTIL,
                CelebrationDetector.FIVE_HOUR, refills, filtered, observedAt, fiveHourUntil);
        clearUsedSuppression(editor, KEY_USER_RESET_WEEKLY_UNTIL,
                CelebrationDetector.WEEKLY, refills, filtered, observedAt, weeklyUntil);
        editor.apply();
        return filtered;
    }

    private static void clearUsedSuppression(SharedPreferences.Editor editor, String key,
            int window, int before, int after, long observedAt, long suppressUntil) {
        if (suppressUntil > 0L && (observedAt >= suppressUntil
                || ((before & window) != 0 && (after & window) == 0))) {
            editor.remove(key);
        }
    }

    private static void markUserResetWindow(SharedPreferences.Editor editor, String key,
            UsageSnapshot snapshot, UsageWindow window, long now) {
        if (window == null || window.usedPercent <= 0) {
            editor.remove(key);
            return;
        }
        long suppressUntil = CelebrationDetector.expectedResetMillis(snapshot, window);
        if (suppressUntil <= now) {
            suppressUntil = now + UNKNOWN_USER_RESET_SUPPRESSION_MS;
        }
        editor.putLong(key, suppressUntil);
    }

    private static boolean post(Context context, int id, String title, String text, int requestCode) {
        if (!canPost(context)) return false;
        NotificationManager manager = manager(context);
        if (manager == null) return false;
        String channel = createChannel(manager, ResetAlertPreferences.getStyle(context));
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
            channel.setDescription("Low usage, scheduled resets, surprise refills, and reset credits");
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
            return CHANNEL_SILENT;
        }
        if (ResetAlertPreferences.STYLE_ALARM.equals(style)) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ALARM,
                    "Codex usage alarms", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Low usage, scheduled resets, surprise refills, and reset credits");
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
            return CHANNEL_ALARM;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_NOTIFY,
                "Codex usage alerts", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Low usage, scheduled resets, surprise refills, and reset credits");
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        manager.createNotificationChannel(channel);
        return CHANNEL_NOTIFY;
    }
}
