package dev.bennett.codexmeter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.wear.ongoing.OngoingActivity;
import androidx.wear.ongoing.Status;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSurfaceMode;
import dev.bennett.codexmeter.wear.WearSyncPaths;
import java.util.Collections;

public final class WearOngoingMonitor {
    public static final String ACTION_REFRESH = "dev.bennett.codexmeter.action.WEAR_REFRESH";
    public static final String ACTION_STOP = "dev.bennett.codexmeter.action.WEAR_STOP";

    private static final String CHANNEL_ID = "codex_wear_live_monitor_v1";
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";
    private static final int NOTIFICATION_ID = 8710;
    private static final int REQUEST_CONTENT = 8711;
    private static final int REQUEST_STOP = 8712;
    private static final int REQUEST_REFRESH = 8713;
    private static final String TAG = "CodexWearMonitor";

    private WearOngoingMonitor() {
    }

    public static synchronized boolean start(Context context) {
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        // Desire first; only mark posted-active after notify() succeeds inside post().
        // localChange=false: UI/settings callers already stamp desire when the user acts.
        WearPreferences.setMonitorDesired(context, true, false);
        if (snapshot == null || (snapshot.fiveHour == null && snapshot.weekly == null)) {
            return false;
        }
        if (post(context, snapshot)) {
            return true;
        }
        // Keep the desired flag so a later usage update or permission grant can retry
        // without pretending the monitor is already visible.
        Log.w(TAG, "Could not post Wear monitor; leaving desired state for retry");
        return false;
    }

    public static synchronized void stop(Context context, boolean notifyPhone) {
        WearPreferences.setMonitorActive(context, false, notifyPhone);
        NotificationManager manager = manager(context);
        if (manager != null) {
            try {
                manager.cancel(NOTIFICATION_ID);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not cancel Wear monitor", exception);
            }
        }
        if (notifyPhone) {
            WearPhoneSync.pushSettings(context);
            WearPhoneSync.sendMessageToPhone(context, WearSyncPaths.MSG_STOP_MONITOR);
        }
    }

    public static synchronized void updateFromSnapshot(Context context, UsageSnapshot snapshot) {
        if (snapshot == null) return;
        WearSettingsState settings = WearPreferences.settingsState(context, 0L,
                WearSettingsState.SOURCE_WEAR);
        UsageWindow fiveHour = UsageSnapshot.currentWindow(snapshot.fiveHour,
                System.currentTimeMillis());
        UsageWindow weekly = UsageSnapshot.currentWindow(snapshot.weekly, System.currentTimeMillis());
        if (isActive(context)) {
            post(context, snapshot);
        } else if (WearPreferences.isMonitorDesired(context)
                || NowBarAutoStart.shouldStart(settings.autoStartEnabled, settings.metric,
                settings.threshold, fiveHour, weekly)) {
            // Desired-from-phone can arrive before usage; start once the snapshot exists.
            start(context);
        }
    }

    public static synchronized void restore(Context context) {
        UsageSnapshot snapshot = WearPreferences.loadSnapshot(context);
        if (snapshot == null) {
            // Keep any desired active flag; a later usage/monitor payload will start posting.
            return;
        }
        if (!isActive(context) && !WearPreferences.isMonitorDesired(context)) {
            updateFromSnapshot(context, snapshot);
            return;
        }
        if (!post(context, snapshot)) {
            Log.w(TAG, "Could not restore Wear monitor; leaving desired state for retry");
        }
    }

    public static boolean isActive(Context context) {
        return WearPreferences.isMonitorActive(context);
    }

    public static boolean canUseLocalLiveUpdates(Context context) {
        if (Build.VERSION.SDK_INT < 36) return false;
        NotificationManager manager = manager(context);
        return manager != null && Api36.canPostPromotedNotifications(manager);
    }

    private static boolean post(Context context, UsageSnapshot snapshot) {
        if (context == null || snapshot == null) return false;
        if (!canPostNotifications(context)) {
            // Previously posted state must not linger after notifications become unavailable.
            WearPreferences.clearMonitorPosted(context);
            return false;
        }
        NotificationManager manager = manager(context);
        if (manager == null) {
            WearPreferences.clearMonitorPosted(context);
            return false;
        }
        createChannel(manager);

        long now = System.currentTimeMillis();
        long until = snapshot.nextResetMillis(now);
        if (until <= now) {
            WearPreferences.clearMonitorPosted(context);
            return false;
        }

        UsageWindow fiveHour = UsageSnapshot.currentWindow(snapshot.fiveHour, now);
        UsageWindow weekly = UsageSnapshot.currentWindow(snapshot.weekly, now);
        WearSettingsState settings = WearPreferences.settingsState(context, 0L,
                WearSettingsState.SOURCE_WEAR);
        String focus = NowBarPercentMode.resolveFocus(settings.percentMode, fiveHour, weekly,
                null);
        UsageWindow progressWindow = NowBarPercentMode.selectWindow(focus, fiveHour, weekly);
        int used = progressWindow == null ? 0 : progressWindow.usedPercent;
        int remaining = progressWindow == null ? 0 : progressWindow.remainingPercent();
        boolean weeklyFocus = NowBarPercentMode.isWeeklyFocus(focus);
        String criticalText = (weeklyFocus ? "W " : "") + remaining + "%";
        String contentText = limitText("5h", fiveHour) + " · " + limitText("Week", weekly);
        PendingIntent contentIntent = PendingIntent.getActivity(context, REQUEST_CONTENT,
                new Intent(context, WearMainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getBroadcast(context, REQUEST_STOP,
                new Intent(context, WearMonitorActionReceiver.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent refreshIntent = PendingIntent.getBroadcast(context, REQUEST_REFRESH,
                new Intent(context, WearMonitorActionReceiver.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Codex usage")
                .setContentText(contentText)
                .setContentIntent(contentIntent)
                .setDeleteIntent(stopIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(Color.rgb(3, 129, 254))
                .setShowWhen(false)
                .setProgress(100, used, false)
                .addAction(new NotificationCompat.Action.Builder(
                        R.drawable.ic_notification, "Stop", stopIntent).build())
                .addAction(new NotificationCompat.Action.Builder(
                        R.drawable.ic_refresh, "Refresh", refreshIntent).build());

        Status status = new Status.Builder()
                .addTemplate("Codex #remaining# · #timer#")
                .addPart("remaining", new Status.TextPart(criticalText))
                .addPart("timer", new Status.TimerPart(elapsedTimeZero(until, now)))
                .build();
        OngoingActivity activity = new OngoingActivity.Builder(
                context.getApplicationContext(), NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_notification)
                .setTouchIntent(contentIntent)
                .setStatus(status)
                .build();
        activity.apply(context.getApplicationContext());

        WearSurfaceMode surfaceMode = WearSurfaceMode.resolve(settings.displayMode,
                Build.VERSION.SDK_INT, canUseLocalLiveUpdates(context));
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 36 && surfaceMode == WearSurfaceMode.LIVE_UPDATE) {
            notification = Api36.withLiveUpdateStyle(context, notification, used, criticalText);
        }
        try {
            manager.notify(NOTIFICATION_ID, notification);
            WearPreferences.markMonitorPosted(context, until);
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not post Wear monitor", exception);
            WearPreferences.clearMonitorPosted(context);
            return false;
        }
    }

    private static boolean canPostNotifications(Context context) {
        NotificationManager manager = manager(context);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static long elapsedTimeZero(long untilWallMillis, long nowWallMillis) {
        return SystemClock.elapsedRealtime() + Math.max(1L, untilWallMillis - nowWallMillis);
    }

    private static String limitText(String label, UsageWindow window) {
        return label + " " + (window == null ? "--" : window.remainingPercent() + "%");
    }

    private static void createChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Codex Wear live monitor", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Watch-face and Recents monitor for Codex usage.");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @RequiresApi(36)
    private static final class Api36 {
        static boolean canPostPromotedNotifications(NotificationManager manager) {
            return manager.canPostPromotedNotifications();
        }

        static Notification withLiveUpdateStyle(Context context, Notification base, int used,
                String criticalText) {
            // Android 16 phone Live Updates and Samsung private Now Bar extras do not bridge to
            // Wear. On Wear OS 7+ we only request a local promoted ongoing notification; the
            // Wear-native surface remains OngoingActivity for watch-face chips and Recents.
            Bundle extras = new Bundle(base.extras);
            extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
            Notification.Builder builder = Notification.Builder.recoverBuilder(context, base)
                    .addExtras(extras)
                    .setShortCriticalText(criticalText);
            Notification.ProgressStyle style = new Notification.ProgressStyle()
                    .setProgress(used)
                    .setStyledByProgress(true)
                    .setProgressTrackerIcon(
                            Icon.createWithResource(context, R.drawable.ic_notification))
                    .setProgressSegments(Collections.singletonList(
                            new Notification.ProgressStyle.Segment(100)
                                    .setColor(Color.rgb(3, 129, 254))));
            return builder.setStyle(style).build();
        }

        static boolean isPromoted(NotificationManager manager) {
            try {
                for (StatusBarNotification active : manager.getActiveNotifications()) {
                    if (active.getId() == NOTIFICATION_ID) {
                        return (active.getNotification().flags
                                & Notification.FLAG_PROMOTED_ONGOING) != 0;
                    }
                }
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not read Wear promotion state", exception);
            }
            return false;
        }
    }
}
