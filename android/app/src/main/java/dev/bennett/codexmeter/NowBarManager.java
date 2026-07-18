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
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.annotation.RequiresApi;
import dev.bennett.codexmeter.wear.PhoneWearSync;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Posts a finite Live Update that Samsung may surface in the Now Bar.
 * Users can start it manually, or it can auto-start when remaining allowance hits a
 * configured threshold (same metric/threshold pattern as low-usage notifications).
 */
public final class NowBarManager {
    static final String ACTION_END = "dev.bennett.codexmeter.action.NOW_BAR_END";
    static final String ACTION_REFRESH = "dev.bennett.codexmeter.action.NOW_BAR_REFRESH";
    static final String ACTION_STOP = "dev.bennett.codexmeter.action.NOW_BAR_STOP";

    private static final String CHANNEL_ID = "codex_live_monitor_v2";
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_AUTO_TRIGGER_FOCUS = "auto_trigger_focus";
    private static final String KEY_FOCUS_METRIC = "focus_metric";
    private static final String KEY_POSTED_MODE = "posted_mode";
    private static final String KEY_POSTED_PROMOTION_ALLOWED = "posted_promotion_allowed";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_START_REASON = "start_reason";
    private static final String KEY_UNTIL = "until";
    private static final String START_ACCELERATED = "accelerated";
    private static final String START_LOW = "low";
    private static final String START_MANUAL = "manual";
    private static final String START_PREVIEW = "preview";
    private static final int NOTIFICATION_ID = 8610;
    private static final int REQUEST_END = 8611;
    private static final int REQUEST_REFRESH = 8612;
    private static final int REQUEST_STOP = 8613;
    private static final String PREFS = "codex_meter_now_bar_v1";
    private static final String SAMSUNG_ONGOING_PREFIX = "android.ongoingActivityNoti.";
    private static final String TAG = "CodexNowBar";

    private NowBarManager() {
    }

    public static synchronized boolean start(Context context) {
        return startInternal(context, START_MANUAL, null, 0L);
    }

    private static boolean startInternal(Context context, String reason, String triggerFocus,
            long requestedUntil) {
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(context);
        if (snapshot == null || (snapshot.fiveHour == null && snapshot.weekly == null)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long until = requestedUntil > now ? requestedUntil : snapshot.nextResetMillis(now);
        if (until <= now) return false;
        NowBarPreferences.clearSuppression(context);
        boolean fromLowAutoStart = START_LOW.equals(reason);
        String focus = NowBarPercentMode.normalizeFocusMetric(triggerFocus);
        if (focus == null) {
            focus = computeInitialFocus(context, snapshot, fromLowAutoStart, now);
        }
        String autoTrigger = triggerFocus != null ? focus : fromLowAutoStart
                ? NowBarPercentMode.triggeredFocus(
                        NowBarPreferences.getMetric(context),
                        NowBarPreferences.getThreshold(context),
                        UsageSnapshot.currentWindow(snapshot.fiveHour,
                                snapshot.fetchedAtMillis, now),
                        UsageSnapshot.currentWindow(snapshot.weekly,
                                snapshot.fetchedAtMillis, now))
                : null;
        saveState(context, false, until, focus, true, autoTrigger, reason);
        if (post(context, snapshot, until, false)) {
            PhoneWearSync.pushSettings(context);
            return true;
        }
        stop(context, false);
        return false;
    }

    public static synchronized boolean startPreview(Context context) {
        long now = System.currentTimeMillis();
        long until = now + TimeUnit.MINUTES.toMillis(20);
        UsageSnapshot preview = new UsageSnapshot("plus", true, false, null,
                new UsageWindow(18, TimeUnit.DAYS.toSeconds(7),
                        TimeUnit.DAYS.toSeconds(4), (now + TimeUnit.DAYS.toMillis(4)) / 1000L),
                now);
        NowBarPreferences.clearSuppression(context);
        String focus = computeInitialFocus(context, preview, false, now);
        saveState(context, true, until, focus, true, null, START_PREVIEW);
        if (post(context, preview, until, true)) {
            PhoneWearSync.pushSettings(context);
            return true;
        }
        stop(context, false);
        return false;
    }

    public static synchronized void onUsageUpdated(Context context, UsageSnapshot snapshot) {
        if (isPreview(context)) return;
        if (hasStoredActiveState(context)) {
            if (snapshot == null || (snapshot.fiveHour == null && snapshot.weekly == null)) {
                stop(context, false);
                return;
            }
            long now = System.currentTimeMillis();
            if (START_ACCELERATED.equals(sessionStartReason(context))) {
                int acceleratedWindow = acceleratedWindow(context, snapshot, now);
                if (acceleratedWindow == UsagePace.WINDOW_NONE) {
                    stop(context, false);
                    maybeAutoStart(context, snapshot);
                    return;
                }
                String focus = focusForPaceWindow(acceleratedWindow);
                long acceleratedUntil = acceleratedUntil(context, snapshot, focus, now);
                if (acceleratedUntil <= now) {
                    stop(context, false);
                    maybeAutoStart(context, snapshot);
                    return;
                }
                saveState(context, false, acceleratedUntil, focus, true, focus,
                        START_ACCELERATED);
            }
            long until = activeUntil(context);
            if (until <= now) {
                stop(context, false);
                return;
            }
            if (!post(context, snapshot, until, false)) stop(context, false);
            return;
        }
        maybeAutoStart(context, snapshot);
    }

    /**
     * Starts the live monitor when auto-start is enabled, notifications are allowed,
     * the user has not dismissed the current window, and remaining usage meets the threshold.
     */
    public static synchronized boolean maybeAutoStart(Context context, UsageSnapshot snapshot) {
        if (context == null || isActive(context) || isPreview(context)) return false;
        boolean lowEnabled = NowBarPreferences.isAutoStartEnabled(context);
        boolean paceEnabled = UsagePacePreferences.isEnabled(context)
                && NowBarPreferences.isAcceleratedStartEnabled(context);
        if (!lowEnabled && !paceEnabled) return false;
        if (NowBarPreferences.isSuppressed(context)) return false;
        if (!canPostNotifications(context)) return false;
        if (lowEnabled && NowBarPreferences.meetsThreshold(context, snapshot)) {
            return startInternal(context, START_LOW, null, 0L);
        }
        long now = System.currentTimeMillis();
        int acceleratedWindow = acceleratedWindow(context, snapshot, now);
        if (!paceEnabled || acceleratedWindow == UsagePace.WINDOW_NONE) return false;
        String focus = focusForPaceWindow(acceleratedWindow);
        long until = acceleratedUntil(context, snapshot, focus, now);
        return until > now && startInternal(context, START_ACCELERATED, focus, until);
    }

    public static synchronized void onPaceSettingsChanged(Context context) {
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(context);
        if (START_ACCELERATED.equals(sessionStartReason(context))) {
            onUsageUpdated(context, snapshot);
        } else if (hasStoredActiveState(context)) {
            repostActive(context);
        } else {
            maybeAutoStart(context, snapshot);
        }
    }

    public static synchronized void restore(Context context) {
        if (hasStoredActiveState(context)) {
            long until = activeUntil(context);
            if (until <= System.currentTimeMillis()) {
                stop(context, false);
            } else if (isPreview(context)) {
                if (!startPreviewWithEnd(context, until)) stop(context, false);
                else return;
            } else {
                UsageSnapshot snapshot = AppPreferences.loadSnapshot(context);
                if (snapshot == null || (snapshot.fiveHour == null && snapshot.weekly == null)) {
                    stop(context, false);
                } else if (START_ACCELERATED.equals(sessionStartReason(context))) {
                    onUsageUpdated(context, snapshot);
                    if (isActive(context)) return;
                } else if (!post(context, snapshot, until, false)) {
                    stop(context, false);
                } else {
                    return;
                }
            }
        }
        maybeAutoStart(context, AppPreferences.loadSnapshot(context));
    }

    public static synchronized boolean repostActive(Context context) {
        if (!hasStoredActiveState(context)) return false;
        long until = activeUntil(context);
        if (until <= System.currentTimeMillis()) {
            stop(context, false);
            return false;
        }
        boolean posted;
        if (isPreview(context)) {
            posted = startPreviewWithEnd(context, until);
        } else {
            UsageSnapshot snapshot = AppPreferences.loadSnapshot(context);
            if (START_ACCELERATED.equals(sessionStartReason(context))) {
                onUsageUpdated(context, snapshot);
                return isActive(context);
            }
            posted = snapshot != null && (snapshot.fiveHour != null || snapshot.weekly != null)
                    && post(context, snapshot, until, false);
        }
        if (!posted) stop(context, false);
        return posted;
    }

    /**
     * Rebuilds an active notification when Android's Live Update permission changes. Without
     * this, a monitor posted through Samsung compatibility remains stuck on that contract after
     * the user grants promotion access, and an explicit Android Live Update is not promoted until
     * some unrelated usage refresh reposts it.
     */
    public static synchronized boolean refreshActiveNotificationContract(Context context) {
        if (!isActive(context) || !canPostNotifications(context)) return true;
        SharedPreferences postedState = state(context);
        String postedMode = postedState.getString(KEY_POSTED_MODE, null);
        boolean postedPromotionAllowed = postedState.getBoolean(
                KEY_POSTED_PROMOTION_ALLOWED, false);
        String resolvedMode = resolveDisplayMode(context);
        boolean promotionAllowedNow = canPostPromotedNotifications(context);
        if (!NowBarDisplayMode.notificationContractChanged(postedMode,
                postedPromotionAllowed, resolvedMode, promotionAllowedNow)) {
            return true;
        }
        return repostActive(context);
    }

    private static boolean startPreviewWithEnd(Context context, long until) {
        long now = System.currentTimeMillis();
        UsageSnapshot preview = new UsageSnapshot("plus", true, false, null,
                new UsageWindow(18, TimeUnit.DAYS.toSeconds(7), 0L, 0L), now);
        if (!hasStoredActiveState(context) || lockedFocusMetric(context) == null) {
            String focus = computeInitialFocus(context, preview, false, now);
            saveState(context, true, until, focus, true, null, START_PREVIEW);
        }
        return post(context, preview, until, true);
    }

    /**
     * Recomputes the progress focus from the current percent-mode setting and usage,
     * then reposts when a monitor is already active. Used when the user changes the
     * percentage preference in Settings. AUTO restores the session auto-start trigger
     * when one was recorded, so cycling modes does not drop that lock.
     */
    public static synchronized boolean applyPercentModeChange(Context context) {
        if (!hasStoredActiveState(context)) return false;
        long until = activeUntil(context);
        if (until <= System.currentTimeMillis()) {
            stop(context, false);
            return false;
        }
        boolean preview = isPreview(context);
        UsageSnapshot snapshot;
        long now = System.currentTimeMillis();
        if (preview) {
            snapshot = new UsageSnapshot("plus", true, false, null,
                    new UsageWindow(18, TimeUnit.DAYS.toSeconds(7), 0L, 0L), now);
        } else {
            snapshot = AppPreferences.loadSnapshot(context);
            if (snapshot == null || (snapshot.fiveHour == null && snapshot.weekly == null)) {
                stop(context, false);
                return false;
            }
        }
        UsageWindow fiveHour = UsageSnapshot.currentWindow(
                snapshot == null ? null : snapshot.fiveHour,
                snapshot == null ? 0L : snapshot.fetchedAtMillis, now);
        UsageWindow weekly = UsageSnapshot.currentWindow(
                snapshot == null ? null : snapshot.weekly,
                snapshot == null ? 0L : snapshot.fetchedAtMillis, now);
        String focus = NowBarPercentMode.focusForSettingsChange(
                NowBarPreferences.getPercentMode(context), fiveHour, weekly,
                sessionAutoTriggerFocus(context));
        // Preserve KEY_AUTO_TRIGGER_FOCUS across mode switches.
        saveState(context, preview, until, focus, false, null, sessionStartReason(context));
        if (post(context, snapshot, until, preview)) return true;
        stop(context, false);
        return false;
    }

    public static synchronized void stop(Context context) {
        stop(context, true);
    }

    public static synchronized void stop(Context context, boolean suppressAutoRestart) {
        long until = activeUntil(context);
        boolean wasActive = hasStoredActiveState(context);
        state(context).edit().clear().apply();
        if (suppressAutoRestart && wasActive && until > System.currentTimeMillis()) {
            // Respect an explicit stop/dismiss until the window would have ended anyway.
            NowBarPreferences.markSuppressedUntil(context, until);
        }
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
        PhoneWearSync.pushMonitorState(context);
        PhoneWearSync.pushSettings(context);
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

    public static String activeFocusMetric(Context context) {
        return lockedFocusMetric(context);
    }

    public static boolean canPostNotifications(Context context) {
        NotificationManager manager = manager(context);
        if (manager == null || !manager.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static boolean canPostPromotedNotifications(Context context) {
        NotificationManager manager = manager(context);
        return Build.VERSION.SDK_INT >= 36 && manager != null
                && Api36.canPostPromotedNotifications(manager);
    }

    public static boolean isPromoted(Context context) {
        NotificationManager manager = manager(context);
        return Build.VERSION.SDK_INT >= 36 && manager != null
                && Api36.isPostedNotificationPromoted(manager, NOTIFICATION_ID);
    }

    public static String postedDisplayMode(Context context) {
        String posted = state(context).getString(KEY_POSTED_MODE, null);
        return posted == null ? resolveDisplayMode(context) : NowBarDisplayMode.normalize(posted);
    }

    private static String resolveDisplayMode(Context context) {
        return NowBarDisplayMode.resolve(NowBarPreferences.getDisplayMode(context),
                isSamsungDevice(), Build.VERSION.SDK_INT,
                canPostPromotedNotifications(context));
    }

    private static boolean isSamsungDevice() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER)
                || "samsung".equalsIgnoreCase(Build.BRAND);
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
            fiveHour = UsageSnapshot.currentWindow(fiveHour,
                    snapshot == null ? 0L : snapshot.fetchedAtMillis, now);
            weekly = UsageSnapshot.currentWindow(weekly,
                    snapshot == null ? 0L : snapshot.fetchedAtMillis, now);
        }
        String percentMode = NowBarPreferences.getPercentMode(context);
        String lockedForAuto = null;
        if (NowBarPercentMode.AUTO.equals(NowBarPercentMode.normalize(percentMode))) {
            lockedForAuto = sessionAutoTriggerFocus(context);
            if (lockedForAuto == null) lockedForAuto = lockedFocusMetric(context);
        }
        String acceleratedTrigger = START_ACCELERATED.equals(sessionStartReason(context))
                ? sessionAutoTriggerFocus(context) : null;
        String focus = acceleratedTrigger == null
                ? NowBarPercentMode.resolveFocus(percentMode, fiveHour, weekly, lockedForAuto)
                : NowBarPercentMode.resolveFocus(NowBarPercentMode.AUTO, fiveHour, weekly,
                        acceleratedTrigger);
        UsageWindow progressWindow = NowBarPercentMode.selectWindow(focus, fiveHour, weekly);
        UsagePace.Assessment pace = UsagePacePreferences.assess(
                context, snapshot, progressWindow, now);
        boolean accelerated = !preview && pace.accelerated;
        int remaining = progressWindow == null ? 0 : progressWindow.remainingPercent();
        int used = progressWindow == null ? 0 : progressWindow.usedPercent;
        boolean weeklyFocus = NowBarPercentMode.isWeeklyFocus(focus);
        String fiveHourText = limitText("5-hour", fiveHour);
        String weeklyText = limitText("Weekly", weekly);
        String title = "Codex usage";
        String estimate = UsageFormat.estimatedRemaining(pace);
        String text = fiveHourText + " · " + weeklyText
                + (estimate.isEmpty() ? "" : " · " + estimate);
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
        Icon stopActionIcon = Icon.createWithResource(context, R.drawable.ic_notification);
        Icon refreshActionIcon = Icon.createWithResource(context, R.drawable.ic_refresh);
        String displayMode = resolveDisplayMode(context);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                // Official Codex mark (white, no opaque square) — system tints status-bar icons.
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setDeleteIntent(stopIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(accelerated ? Ui.warning(false) : Color.rgb(3, 129, 254))
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        stopActionIcon, "Stop", stopIntent).build());
        if (!preview) {
            builder.addAction(new Notification.Action.Builder(
                    refreshActionIcon, "Refresh", refreshIntent).build());
        }
        if (NowBarDisplayMode.SAMSUNG_COMPATIBILITY.equals(displayMode)) {
            applySamsungCompatibility(context, builder, fiveHour, weekly, used, remaining,
                    weeklyFocus, until, now, preview, accelerated, estimate);
        } else {
            Bundle promotionExtras = new Bundle();
            promotionExtras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
            builder.addExtras(promotionExtras);
            if (Build.VERSION.SDK_INT >= 36) {
                String criticalPrefix = weeklyFocus ? "W " : "";
                Api36.applyLiveUpdateStyle(context, builder, used,
                        criticalPrefix + remaining + "%", accelerated);
            }
        }
        Notification builtNotification;
        boolean legacyColorizedFallback = false;
        try {
            builtNotification = builder.build();
            // Early Android 16 releases require colorization for promotability, while newer
            // releases reject colorized Live Updates. Trust the running framework's predicate:
            // use the modern uncolorized contract first and retry only where it is required.
            if (Build.VERSION.SDK_INT >= 36
                    && NowBarDisplayMode.ANDROID_LIVE_UPDATE.equals(displayMode)
                    && !Api36.hasPromotableCharacteristics(builtNotification)) {
                builder.setColorized(true);
                Notification colorizedCandidate = builder.build();
                if (Api36.hasPromotableCharacteristics(colorizedCandidate)) {
                    builtNotification = colorizedCandidate;
                    legacyColorizedFallback = true;
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not build live monitor notification", exception);
            return false;
        }
        final Notification notification = builtNotification;
        boolean promotable = Build.VERSION.SDK_INT >= 36
                && Api36.hasPromotableCharacteristics(notification);
        Log.i(TAG, "Posting live monitor: mode=" + displayMode + " promotable=" + promotable
                + " allowed=" + canPostPromotedNotifications(context)
                + " legacyColorizedFallback=" + legacyColorizedFallback
                + " preview=" + preview + " remaining=" + remaining);
        try {
            manager.notify(NOTIFICATION_ID, notification);
            state(context).edit()
                    .putString(KEY_POSTED_MODE, displayMode)
                    .putBoolean(KEY_POSTED_PROMOTION_ALLOWED,
                            canPostPromotedNotifications(context))
                    .apply();
            if (Build.VERSION.SDK_INT >= 36
                    && NowBarDisplayMode.ANDROID_LIVE_UPDATE.equals(displayMode)) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> Api36.logPostedPromotionState(manager, NOTIFICATION_ID), 1000L);
            }
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
            Log.w(TAG, "Could not schedule live monitor expiry", exception);
        }
        PhoneWearSync.pushMonitorState(context);
        return true;
    }

    private static void applySamsungCompatibility(Context context, Notification.Builder builder,
            UsageWindow fiveHour, UsageWindow weekly, int used, int focusRemaining,
            boolean weeklyFocus, long until, long now, boolean preview, boolean accelerated,
            String estimate) {
        String fiveHourText = limitText("5-hour", fiveHour);
        String weeklyText = limitText("Weekly", weekly);
        String focusLabel = weeklyFocus ? "Weekly " : "5-hour ";
        String availableWindows = fiveHour != null && weekly != null
                ? "Both usage windows"
                : fiveHour != null ? "5-hour window"
                : weekly != null ? "Weekly window" : "Usage window unavailable";
        // Chip sits on the accent fill → always-light Codex mark.
        // Expanded Now Bar: pick an explicit light/dark resource at post time. Night-qualified
        // drawables can resolve wrong inside Samsung SystemUI (separate process / config).
        Icon chipIcon = Icon.createWithResource(context, R.drawable.ic_codex_logo_on_accent);
        Icon nowBarIcon = themeAdaptiveCodexLogo(context);
        Icon progressDot = Icon.createWithResource(context, R.drawable.ic_now_bar_progress_dot);
        Bundle extras = new Bundle();
        extras.putInt(SAMSUNG_ONGOING_PREFIX + "style", 1);
        extras.putParcelable(SAMSUNG_ONGOING_PREFIX + "chipIcon", chipIcon);
        extras.putInt(SAMSUNG_ONGOING_PREFIX + "chipBgColor",
                accelerated ? Ui.warning(false) : Color.rgb(3, 129, 254));
        extras.putCharSequence(SAMSUNG_ONGOING_PREFIX + "chipExpandedText",
                "Codex · " + focusLabel + focusRemaining + "%");
        extras.putCharSequence(SAMSUNG_ONGOING_PREFIX + "primaryInfo",
                fiveHourText + " · " + weeklyText);
        extras.putCharSequence(SAMSUNG_ONGOING_PREFIX + "secondaryInfo",
                accelerated && !estimate.isEmpty() ? estimate : availableWindows);
        extras.putString(SAMSUNG_ONGOING_PREFIX + "description", "Codex usage limits");
        extras.putInt(SAMSUNG_ONGOING_PREFIX + "progress", used);
        extras.putInt(SAMSUNG_ONGOING_PREFIX + "progressMax", 100);
        extras.putParcelable(SAMSUNG_ONGOING_PREFIX + "progressSegments.icon", progressDot);
        extras.putParcelable(SAMSUNG_ONGOING_PREFIX + "nowbarIcon", nowBarIcon);
        extras.putString(SAMSUNG_ONGOING_PREFIX + "nowbarPrimaryInfo", fiveHourText);
        extras.putString(SAMSUNG_ONGOING_PREFIX + "nowbarSecondaryInfo", weeklyText);
        extras.putString(SAMSUNG_ONGOING_PREFIX + "nowbarIconType", "progress");
        builder.addExtras(extras)
                .setSubText(preview ? "Now Bar preview" : "Until the next usage reset")
                .setProgress(100, used, false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setShowWhen(true)
                .setWhen(until)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(Math.max(1L, until - now));
    }

    private static String limitText(String label, UsageWindow window) {
        return label + ": " + (window == null ? "unavailable"
                : window.remainingPercent() + "% left");
    }

    /**
     * Picks a fixed (non night-qualified) Codex logo resource from the posting process's
     * current UI mode so SystemUI loads the intended contrast without re-resolving
     * {@code drawable-night}.
     */
    private static Icon themeAdaptiveCodexLogo(Context context) {
        return Icon.createWithResource(context, themeAdaptiveCodexLogoRes(context));
    }

    static int themeAdaptiveCodexLogoRes(Context context) {
        int night = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES
                ? R.drawable.ic_codex_logo_dark
                : R.drawable.ic_codex_logo;
    }

    private static void createChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Codex live monitor", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(
                "Codex allowance monitor that ends at the next reset; may start from Settings or when remaining usage hits your threshold");
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

    private static String lockedFocusMetric(Context context) {
        return NowBarPercentMode.normalizeFocusMetric(
                state(context).getString(KEY_FOCUS_METRIC, null));
    }

    private static String sessionAutoTriggerFocus(Context context) {
        return NowBarPercentMode.normalizeFocusMetric(
                state(context).getString(KEY_AUTO_TRIGGER_FOCUS, null));
    }

    private static String sessionStartReason(Context context) {
        String reason = state(context).getString(KEY_START_REASON, START_MANUAL);
        if (START_ACCELERATED.equals(reason) || START_LOW.equals(reason)
                || START_PREVIEW.equals(reason)) {
            return reason;
        }
        return START_MANUAL;
    }

    private static int acceleratedWindow(Context context, UsageSnapshot snapshot, long now) {
        if (!UsagePacePreferences.isEnabled(context)
                || !NowBarPreferences.isAcceleratedStartEnabled(context)) {
            return UsagePace.WINDOW_NONE;
        }
        return UsagePace.mostAcceleratedWindow(snapshot, now,
                UsagePacePreferences.getSensitivity(context));
    }

    private static String focusForPaceWindow(int window) {
        return window == UsagePace.WINDOW_WEEKLY
                ? NowBarPercentMode.WEEKLY : NowBarPercentMode.FIVE_HOUR;
    }

    private static long acceleratedUntil(Context context, UsageSnapshot snapshot, String focus,
            long now) {
        if (snapshot == null) return 0L;
        UsageWindow window = NowBarPercentMode.selectWindow(focus,
                UsageSnapshot.currentWindow(snapshot.fiveHour, snapshot.fetchedAtMillis, now),
                UsageSnapshot.currentWindow(snapshot.weekly, snapshot.fetchedAtMillis, now));
        UsagePace.Assessment assessment = UsagePacePreferences.assess(
                context, snapshot, window, now);
        return assessment.accelerated ? assessment.resetAtMillis : 0L;
    }

    private static String computeInitialFocus(Context context, UsageSnapshot snapshot,
            boolean fromAutoStart, long now) {
        UsageWindow fiveHour = snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.fiveHour, snapshot.fetchedAtMillis, now);
        UsageWindow weekly = snapshot == null ? null
                : UsageSnapshot.currentWindow(snapshot.weekly, snapshot.fetchedAtMillis, now);
        String mode = NowBarPreferences.getPercentMode(context);
        if (!NowBarPercentMode.AUTO.equals(NowBarPercentMode.normalize(mode))) {
            return NowBarPercentMode.resolveFocus(mode, fiveHour, weekly, null);
        }
        if (fromAutoStart) {
            String triggered = NowBarPercentMode.triggeredFocus(
                    NowBarPreferences.getMetric(context),
                    NowBarPreferences.getThreshold(context),
                    fiveHour, weekly);
            if (triggered != null) return triggered;
        }
        return NowBarPercentMode.lowerRemainingFocus(fiveHour, weekly);
    }

    /**
     * @param updateAutoTrigger when true, writes or clears {@link #KEY_AUTO_TRIGGER_FOCUS};
     *        when false, leaves any existing session auto-start trigger untouched
     */
    private static void saveState(Context context, boolean preview, long until, String focus,
            boolean updateAutoTrigger, String autoTriggerFocus, String startReason) {
        SharedPreferences.Editor editor = state(context).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putBoolean(KEY_PREVIEW, preview)
                .putLong(KEY_UNTIL, until)
                .putString(KEY_START_REASON, startReason == null ? START_MANUAL : startReason);
        String normalizedFocus = NowBarPercentMode.normalizeFocusMetric(focus);
        if (normalizedFocus == null) {
            editor.remove(KEY_FOCUS_METRIC);
        } else {
            editor.putString(KEY_FOCUS_METRIC, normalizedFocus);
        }
        if (updateAutoTrigger) {
            String normalizedTrigger = NowBarPercentMode.normalizeFocusMetric(autoTriggerFocus);
            if (normalizedTrigger == null) {
                editor.remove(KEY_AUTO_TRIGGER_FOCUS);
            } else {
                editor.putString(KEY_AUTO_TRIGGER_FOCUS, normalizedTrigger);
            }
        }
        editor.apply();
    }

    private static boolean hasStoredActiveState(Context context) {
        return state(context).getBoolean(KEY_ACTIVE, false);
    }

    /** Keeps API 36 class references out of code paths verified on older Android releases. */
    @RequiresApi(36)
    private static final class Api36 {
        static void applyLiveUpdateStyle(Context context, Notification.Builder builder, int used,
                String criticalText, boolean accelerated) {
            Notification.ProgressStyle style = new Notification.ProgressStyle()
                    .setProgress(used)
                    .setStyledByProgress(true)
                    // Plain circle tracker — not the brand glyph (that belongs in setSmallIcon).
                    .setProgressTrackerIcon(
                            Icon.createWithResource(context, R.drawable.ic_now_bar_progress_dot))
                    .setProgressSegments(Collections.singletonList(
                            new Notification.ProgressStyle.Segment(100)
                                    .setColor(accelerated
                                            ? Ui.warning(false) : Color.rgb(3, 129, 254))));
            builder.setStyle(style).setShortCriticalText(criticalText);
        }

        static boolean canPostPromotedNotifications(NotificationManager manager) {
            return manager.canPostPromotedNotifications();
        }

        static boolean hasPromotableCharacteristics(Notification notification) {
            return notification.hasPromotableCharacteristics();
        }

        static boolean isPostedNotificationPromoted(NotificationManager manager,
                int notificationId) {
            try {
                for (StatusBarNotification active : manager.getActiveNotifications()) {
                    if (active.getId() == notificationId) {
                        return (active.getNotification().flags
                                & Notification.FLAG_PROMOTED_ONGOING) != 0;
                    }
                }
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not read live monitor promotion state", exception);
            }
            return false;
        }

        static void logPostedPromotionState(NotificationManager manager, int notificationId) {
            try {
                StatusBarNotification[] activeNotifications = manager.getActiveNotifications();
                for (StatusBarNotification active : activeNotifications) {
                    if (active.getId() != notificationId) continue;
                    Notification posted = active.getNotification();
                    boolean promoted = (posted.flags & Notification.FLAG_PROMOTED_ONGOING) != 0;
                    Log.i(TAG, "Posted live monitor state: promotedFlag=" + promoted
                            + " flags=0x" + Integer.toHexString(posted.flags)
                            + " requestExtra="
                            + posted.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false));
                    return;
                }
                Log.i(TAG, "Posted live monitor state: notification not found in active list");
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not read posted live monitor promotion state", exception);
            }
        }
    }
}
