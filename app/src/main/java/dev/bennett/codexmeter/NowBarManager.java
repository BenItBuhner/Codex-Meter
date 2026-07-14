package dev.bennett.codexmeter;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Posts a finite, user-initiated Live Update that Samsung may surface in the Now Bar. */
public final class NowBarManager {
    static final String ACTION_END = "dev.bennett.codexmeter.action.NOW_BAR_END";
    static final String ACTION_REFRESH = "dev.bennett.codexmeter.action.NOW_BAR_REFRESH";
    static final String ACTION_STOP = "dev.bennett.codexmeter.action.NOW_BAR_STOP";

    private static final String CHANNEL_ID = "codex_live_monitor";
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";
    private static final String SAMSUNG_ONGOING_PREFIX = "android.ongoingActivityNoti.";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_UNTIL = "until";
    private static final int NOTIFICATION_ID = 8610;
    private static final int REQUEST_END = 8611;
    private static final int REQUEST_REFRESH = 8612;
    private static final int REQUEST_STOP = 8613;
    private static final String PREFS = "codex_meter_now_bar_v1";
    private static final String TAG = "CodexNowBar";

    private NowBarManager() {
    }

    public static synchronized boolean start(Context context) {
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(context);
        if (snapshot == null || (snapshot.fiveHour == null && snapshot.weekly == null)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long until = snapshot.nextResetMillis(now);
        if (until <= now) return false;
        saveState(context, false, until);
        if (post(context, snapshot, until, false)) return true;
        stop(context);
        return false;
    }

    public static synchronized boolean startPreview(Context context) {
        long now = System.currentTimeMillis();
        long until = now + TimeUnit.MINUTES.toMillis(20);
        UsageSnapshot preview = new UsageSnapshot("plus", true, false, null,
                new UsageWindow(18, TimeUnit.DAYS.toSeconds(7),
                        TimeUnit.DAYS.toSeconds(4), (now + TimeUnit.DAYS.toMillis(4)) / 1000L),
                now);
        saveState(context, true, until);
        if (post(context, preview, until, true)) return true;
        stop(context);
        return false;
    }

    public static synchronized void onUsageUpdated(Context context, UsageSnapshot snapshot) {
        if (!hasStoredActiveState(context) || isPreview(context)) return;
        if (snapshot == null || (snapshot.fiveHour == null && snapshot.weekly == null)) {
            stop(context);
            return;
        }
        long now = System.currentTimeMillis();
        long until = activeUntil(context);
        if (until <= now) {
            stop(context);
            return;
        }
        if (!post(context, snapshot, until, false)) stop(context);
    }

    public static synchronized void restore(Context context) {
        if (!hasStoredActiveState(context)) return;
        long until = activeUntil(context);
        if (until <= System.currentTimeMillis()) {
            stop(context);
            return;
        }
        if (isPreview(context)) {
            if (!startPreviewWithEnd(context, until)) stop(context);
            return;
        }
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(context);
        if (snapshot == null || (snapshot.fiveHour == null && snapshot.weekly == null)) {
            stop(context);
        } else {
            if (!post(context, snapshot, until, false)) stop(context);
        }
    }

    private static boolean startPreviewWithEnd(Context context, long until) {
        long now = System.currentTimeMillis();
        UsageSnapshot preview = new UsageSnapshot("plus", true, false, null,
                new UsageWindow(18, TimeUnit.DAYS.toSeconds(7), 0L, 0L), now);
        return post(context, preview, until, true);
    }

    public static synchronized void stop(Context context) {
        state(context).edit().clear().apply();
        NotificationManager manager = manager(context);
        if (manager != null) {
            try {
                manager.cancel(NOTIFICATION_ID);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not cancel live monitor notification", exception);
            }
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            try {
                alarms.cancel(endIntent(context));
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not cancel live monitor expiry", exception);
            }
        }
    }

    public static boolean isActive(Context context) {
        return state(context).getBoolean(KEY_ACTIVE, false)
                && activeUntil(context) > System.currentTimeMillis();
    }

    public static boolean isPreview(Context context) {
        return state(context).getBoolean(KEY_PREVIEW, false);
    }

    public static long activeUntil(Context context) {
        return state(context).getLong(KEY_UNTIL, 0L);
    }

    public static boolean canPostNotifications(Context context) {
        NotificationManager manager = manager(context);
        return manager != null && manager.areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED);
    }

    public static boolean canPostPromotedNotifications(Context context) {
        NotificationManager manager = manager(context);
        return Build.VERSION.SDK_INT >= 36 && manager != null
                && Api36.canPostPromotedNotifications(manager);
    }

    private static boolean post(Context context, UsageSnapshot snapshot, long until,
            boolean preview) {
        NotificationManager manager = manager(context);
        if (manager == null || !canPostNotifications(context)) return false;
        try {
            createChannel(manager);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not create live monitor notification channel", exception);
            return false;
        }

        long now = System.currentTimeMillis();
        UsageWindow fiveHour = snapshot == null ? null : snapshot.fiveHour;
        UsageWindow weekly = snapshot == null ? null : snapshot.weekly;
        if (!preview) {
            fiveHour = UsageSnapshot.currentWindow(fiveHour, now);
            weekly = UsageSnapshot.currentWindow(weekly, now);
        }
        UsageWindow progressWindow = fiveHour != null ? fiveHour : weekly;
        int remaining = progressWindow == null ? 0 : progressWindow.remainingPercent();
        int used = progressWindow == null ? 0 : progressWindow.usedPercent;
        String fiveHourText = limitText("5-hour", fiveHour);
        String weeklyText = limitText("Weekly", weekly);
        String title = "Codex usage";
        String text = fiveHourText + " · " + weeklyText;
        String subText = preview ? "Now Bar preview" : "Until the next usage reset";

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 8614, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getBroadcast(context, REQUEST_STOP,
                new Intent(context, NowBarActionReceiver.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent refreshIntent = PendingIntent.getBroadcast(context, REQUEST_REFRESH,
                new Intent(context, NowBarActionReceiver.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_oui_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(subText)
                .setProgress(100, used, false)
                .setContentIntent(contentIntent)
                .setDeleteIntent(stopIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .setWhen(until)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(Math.max(1L, until - now))
                .addAction(new Notification.Action.Builder(null, "Stop", stopIntent).build());
        if (!preview) {
            builder.addAction(new Notification.Action.Builder(null, "Refresh", refreshIntent).build());
        }
        builder.getExtras().putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
        if (Build.VERSION.SDK_INT >= 36) {
            Api36.applyLiveUpdateStyle(builder, used,
                    (fiveHour == null ? "W " : "") + remaining + "%");
        }
        addSamsungOngoingActivityExtras(context, builder, fiveHour, weekly, used);
        final Notification notification;
        try {
            notification = builder.build();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not build live monitor notification", exception);
            return false;
        }
        boolean promotable = Build.VERSION.SDK_INT >= 36
                && Api36.hasPromotableCharacteristics(notification);
        Log.i(TAG, "Posting live monitor: promotable=" + promotable
                + " allowed=" + canPostPromotedNotifications(context)
                + " preview=" + preview + " remaining=" + remaining);
        try {
            manager.notify(NOTIFICATION_ID, notification);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not post live monitor notification", exception);
            try {
                manager.cancel(NOTIFICATION_ID);
            } catch (RuntimeException ignored) {
            }
            return false;
        }
        try {
            scheduleEnd(context, until);
        } catch (RuntimeException exception) {
            // setTimeoutAfter() remains the primary expiry path if an OEM rejects the backup alarm.
            Log.w(TAG, "Could not schedule live monitor backup expiry", exception);
        }
        return true;
    }

    private static void addSamsungOngoingActivityExtras(Context context,
            Notification.Builder builder, UsageWindow fiveHour, UsageWindow weekly, int used) {
        Icon icon = Icon.createWithResource(context, R.drawable.ic_oui_alarm);
        String fiveHourText = limitText("5-hour", fiveHour);
        String weeklyText = limitText("Weekly", weekly);
        int focusRemaining = fiveHour != null ? fiveHour.remainingPercent()
                : weekly == null ? 0 : weekly.remainingPercent();

        builder.getExtras().putInt(SAMSUNG_ONGOING_PREFIX + "style", 1);
        builder.getExtras().putParcelable(SAMSUNG_ONGOING_PREFIX + "chipIcon", icon);
        builder.getExtras().putInt(SAMSUNG_ONGOING_PREFIX + "chipBgColor",
                Color.rgb(56, 122, 255));
        builder.getExtras().putCharSequence(SAMSUNG_ONGOING_PREFIX + "chipExpandedText",
                "Codex · " + (fiveHour == null ? "Weekly " : "5-hour ")
                        + focusRemaining + "%");
        builder.getExtras().putCharSequence(SAMSUNG_ONGOING_PREFIX + "primaryInfo",
                fiveHourText + " · " + weeklyText);
        builder.getExtras().putCharSequence(SAMSUNG_ONGOING_PREFIX + "secondaryInfo",
                "Both usage windows");
        builder.getExtras().putString(SAMSUNG_ONGOING_PREFIX + "description",
                "Codex usage limits");
        builder.getExtras().putInt(SAMSUNG_ONGOING_PREFIX + "progress", used);
        builder.getExtras().putInt(SAMSUNG_ONGOING_PREFIX + "progressMax", 100);
        builder.getExtras().putParcelable(SAMSUNG_ONGOING_PREFIX + "nowbarIcon", icon);
        builder.getExtras().putString(SAMSUNG_ONGOING_PREFIX + "nowbarPrimaryInfo",
                fiveHourText);
        builder.getExtras().putString(SAMSUNG_ONGOING_PREFIX + "nowbarSecondaryInfo",
                weeklyText);
        builder.getExtras().putString(SAMSUNG_ONGOING_PREFIX + "nowbarIconType", "progress");
    }

    private static String limitText(String label, UsageWindow window) {
        return label + ": " + (window == null ? "unavailable"
                : window.remainingPercent() + "% left");
    }

    private static void createChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Codex live monitor", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("A user-started Codex allowance monitor that ends at the next reset");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private static void scheduleEnd(Context context, long until) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until,
                endIntent(context));
    }

    private static PendingIntent endIntent(Context context) {
        return PendingIntent.getBroadcast(context, REQUEST_END,
                new Intent(context, NowBarActionReceiver.class).setAction(ACTION_END),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void saveState(Context context, boolean preview, long until) {
        state(context).edit().putBoolean(KEY_ACTIVE, true).putBoolean(KEY_PREVIEW, preview)
                .putLong(KEY_UNTIL, until).apply();
    }

    private static boolean hasStoredActiveState(Context context) {
        return state(context).getBoolean(KEY_ACTIVE, false);
    }

    /** Keeps API 36 class references out of code paths verified on older Android releases. */
    @RequiresApi(36)
    private static final class Api36 {
        static void applyLiveUpdateStyle(Notification.Builder builder, int used,
                String criticalText) {
            Notification.ProgressStyle style = new Notification.ProgressStyle()
                    .setProgress(used)
                    .setStyledByProgress(true)
                    .setProgressSegments(Collections.singletonList(
                            new Notification.ProgressStyle.Segment(100)
                                    .setColor(Color.rgb(56, 122, 255))));
            builder.setStyle(style).setShortCriticalText(criticalText);
        }

        static boolean canPostPromotedNotifications(NotificationManager manager) {
            return manager.canPostPromotedNotifications();
        }

        static boolean hasPromotableCharacteristics(Notification notification) {
            return notification.hasPromotableCharacteristics();
        }
    }
}
