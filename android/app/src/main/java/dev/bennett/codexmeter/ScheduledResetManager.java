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
import android.os.Build;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns the single armed {@link ScheduledReset}: persistence, the date/time alarm, the fire-time
 * evaluation that runs after every usage refresh, and the outcome notifications. Firing always
 * goes through {@link ResetCreditApi#consumeBestAvailable}, so the live and demo paths spend a
 * credit exactly the way the manual "Use 1 reset" button does.
 */
public final class ScheduledResetManager {
    static final String OUTCOME_FIRED = "fired";
    static final String OUTCOME_SKIPPED = "skipped";
    static final String OUTCOME_FAILED = "failed";
    private static final String PREFS = "codex_meter_scheduled_reset_v1";
    private static final String KEY_SCHEDULE = "schedule";
    private static final String KEY_LAST_OUTCOME = "last_outcome";
    private static final String KEY_FAILURE_NOTIFIED = "failure_notified";
    private static final String KEY_RETRIES = "retries";
    private static final String CHANNEL = "codex_scheduled_reset";
    private static final int NOTIFICATION_ID = 74700;
    private static final int REQUEST_DUE = 74210;
    private static final long RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_RETRIES = 3;
    private static final Object LOCK = new Object();

    private ScheduledResetManager() {
    }

    public static ScheduledReset load(Context context) {
        String stored = prefs(appContext(context)).getString(KEY_SCHEDULE, "");
        if (stored.isEmpty()) return null;
        try {
            return ScheduledReset.fromJson(new JSONObject(stored));
        } catch (JSONException exception) {
            return null;
        }
    }

    public static boolean isArmed(Context context) {
        return load(context) != null;
    }

    /** Replaces any existing schedule; the caller has already confirmed the irreversible step. */
    public static void arm(Context context, ScheduledReset schedule) {
        Context app = appContext(context);
        synchronized (LOCK) {
            cancelAlarm(app);
            try {
                prefs(app).edit()
                        .putString(KEY_SCHEDULE, schedule.toJson().toString())
                        .remove(KEY_LAST_OUTCOME)
                        .remove(KEY_FAILURE_NOTIFIED)
                        .remove(KEY_RETRIES)
                        .apply();
            } catch (JSONException exception) {
                DiagnosticLog.error(app, "scheduled_reset", "encode_failed", exception);
                return;
            }
            if (schedule.isDateTime()) {
                scheduleAlarm(app, schedule.fireAtMillis);
            }
        }
        DiagnosticLog.info(app, "scheduled_reset", "armed",
                "trigger", schedule.trigger,
                "threshold", schedule.thresholdPercent,
                "fire_at", schedule.fireAtMillis,
                "condition", schedule.conditionPercent,
                "exact_alarm", canScheduleExact(app));
        replanRefresh(app);
        notifyUpdated(app);
    }

    /** User cancellation; needs no confirmation and leaves no trace but the log line. */
    public static void cancel(Context context) {
        Context app = appContext(context);
        boolean hadSchedule;
        synchronized (LOCK) {
            hadSchedule = disarm(app);
        }
        if (hadSchedule) {
            DiagnosticLog.info(app, "scheduled_reset", "cancelled");
            replanRefresh(app);
            notifyUpdated(app);
        }
    }

    /** Sign-out and leaving the demo drop the schedule with the rest of the session state. */
    public static void clear(Context context) {
        Context app = appContext(context);
        synchronized (LOCK) {
            disarm(app);
            prefs(app).edit().remove(KEY_LAST_OUTCOME).apply();
        }
    }

    /** Re-registers the date/time alarm after a reboot or app update. */
    public static void rearm(Context context) {
        Context app = appContext(context);
        synchronized (LOCK) {
            ScheduledReset schedule = load(app);
            if (schedule != null && schedule.isDateTime()) {
                scheduleAlarm(app, schedule.fireAtMillis);
            }
        }
    }

    /**
     * Runs after every successful usage refresh with the snapshot that was just fetched, so the
     * decision never rests on cached data. Fires at most once, then disarms.
     */
    public static void onUsageRefreshed(Context context, UsageSnapshot snapshot) {
        Context app = appContext(context);
        ScheduledReset schedule;
        ScheduledReset.Decision decision;
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            schedule = load(app);
            if (schedule == null || snapshot == null) return;
            ResetCreditsSnapshot credits = AppPreferences.loadResetCredits(app);
            int available = credits != null ? credits.availableCount : snapshot.resetCreditsAvailable;
            decision = schedule.evaluate(snapshot, available, now);
            if (decision.waits()) return;
            disarm(app);
        }
        DiagnosticLog.info(app, "scheduled_reset", "decided",
                "trigger", schedule.trigger,
                "outcome", decision.outcome,
                "remaining", decision.remainingPercent);
        if (decision.skipped()) {
            record(app, OUTCOME_SKIPPED, "Scheduled reset skipped",
                    decision.skipReason(schedule, now) + " The schedule is off.");
        } else {
            fire(app, schedule, decision);
        }
        replanRefresh(app);
        notifyUpdated(app);
    }

    /**
     * A due date/time schedule whose refresh failed: say so once, then retry a few times on a
     * short alarm before falling back to the regular refresh cadence.
     */
    public static void onRefreshFailed(Context context, Exception exception) {
        Context app = appContext(context);
        synchronized (LOCK) {
            ScheduledReset schedule = load(app);
            long now = System.currentTimeMillis();
            if (schedule == null || !schedule.isDateTime() || now < schedule.fireAtMillis) return;
            SharedPreferences prefs = prefs(app);
            int retries = prefs.getInt(KEY_RETRIES, 0);
            DiagnosticLog.warn(app, "scheduled_reset", "refresh_failed",
                    "retries", retries, "error", UsageApi.safeMessage(exception));
            if (!prefs.getBoolean(KEY_FAILURE_NOTIFIED, false)) {
                String retry = retries < MAX_RETRIES
                        ? "Codex Meter retries in 5 minutes and at each refresh; the schedule stays on."
                        : "Codex Meter retries at each refresh; the schedule stays on.";
                post(app, "Scheduled reset delayed",
                        "Usage could not be refreshed, so no credit was used yet. " + retry);
                prefs.edit().putBoolean(KEY_FAILURE_NOTIFIED, true).apply();
            }
            if (retries < MAX_RETRIES) {
                prefs.edit().putInt(KEY_RETRIES, retries + 1).apply();
                scheduleAlarm(app, now + RETRY_DELAY_MS);
            }
        }
    }

    /** The most recent fired/skipped/failed outcome for the reset screen, or null. */
    public static Outcome lastOutcome(Context context) {
        String stored = prefs(appContext(context)).getString(KEY_LAST_OUTCOME, "");
        if (stored.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(stored);
            return new Outcome(json.optString("outcome", ""), json.optString("title", ""),
                    json.optString("text", ""), json.optLong("at", 0L));
        } catch (JSONException exception) {
            return null;
        }
    }

    public static boolean canScheduleExact(Context context) {
        return ResetAlertScheduler.canScheduleExact(appContext(context));
    }

    /** The realistic timing statement shown under an armed schedule on the reset screen. */
    public static String timingSummary(Context context, ScheduledReset schedule) {
        if (schedule.isThreshold()) {
            return "Checked at every refresh; automatic refresh tightens to every 5 minutes as "
                    + "the limit nears the threshold. Background timing follows Android battery policy.";
        }
        if (canScheduleExact(context)) {
            return "Fires within about a minute of the scheduled time, even while the phone is "
                    + "idle. Usage is refreshed first and the credit is only used if the guards pass.";
        }
        return "Alarms & reminders is off for Codex Meter, so this can run up to about 15 minutes "
                + "late, or later while the phone is in Doze.";
    }

    /** The one-line version for the dashboard card. */
    public static String timingShort(Context context, ScheduledReset schedule) {
        if (schedule.isThreshold()) return "Checked at every refresh";
        return canScheduleExact(context)
                ? "Fires within about a minute of the time"
                : "May run up to 15 minutes late";
    }

    /** "today at 4:00 PM" for labels, or "on Tue, Sep 30 at 4:00 PM" inside a sentence. */
    public static String dateTimeText(Context context, long atMillis, long nowMillis,
            boolean inSentence) {
        String absolute = UsageFormat.absolute(appContext(context), atMillis, nowMillis);
        if (inSentence && !absolute.startsWith("today") && !absolute.startsWith("tomorrow")) {
            return "on " + absolute;
        }
        return absolute;
    }

    private static void fire(Context app, ScheduledReset schedule, ScheduledReset.Decision decision) {
        try {
            ResetConsumeResult result = ResetCreditApi.consumeBestAvailable(app);
            if (result.applied()) {
                ResetCreditsSnapshot after = AppPreferences.loadResetCredits(app);
                int left = after == null ? -1 : after.availableCount;
                String remaining = left < 0 ? "" : left == 0 ? " No reset credits remain."
                        : left == 1 ? " 1 reset credit remains." : " " + left + " reset credits remain.";
                record(app, OUTCOME_FIRED, "Scheduled reset used 1 credit",
                        decision.firedReason(schedule) + remaining);
            } else if (ResetConsumeResult.NO_CREDIT.equals(result.outcome)) {
                record(app, OUTCOME_SKIPPED, "Scheduled reset skipped",
                        "No reset credit was available, so nothing was used. The schedule is off.");
            } else if (ResetConsumeResult.NOTHING_TO_RESET.equals(result.outcome)) {
                record(app, OUTCOME_SKIPPED, "Scheduled reset skipped",
                        "Nothing had been used, so the credit was kept. The schedule is off.");
            } else {
                record(app, OUTCOME_FAILED, "Scheduled reset failed", result.userMessage()
                        + " Codex Meter will not retry on its own; open Codex reset to try again.");
            }
        } catch (Exception exception) {
            DiagnosticLog.error(app, "scheduled_reset", "consume_failed", exception);
            record(app, OUTCOME_FAILED, "Scheduled reset failed",
                    ResetCreditActivity.safeMessage(exception) + " No credit was used as far as "
                            + "Codex Meter can tell, and it will not retry on its own; open Codex "
                            + "reset to try again.");
        }
    }

    private static void record(Context app, String outcome, String title, String text) {
        try {
            prefs(app).edit().putString(KEY_LAST_OUTCOME, new JSONObject()
                    .put("outcome", outcome)
                    .put("title", title)
                    .put("text", text)
                    .put("at", System.currentTimeMillis()).toString()).apply();
        } catch (JSONException ignored) {
            // The notification below still tells the user what happened.
        }
        DiagnosticLog.info(app, "scheduled_reset", outcome, "text", text);
        post(app, title, text);
    }

    private static boolean disarm(Context app) {
        boolean had = prefs(app).contains(KEY_SCHEDULE);
        cancelAlarm(app);
        prefs(app).edit().remove(KEY_SCHEDULE).remove(KEY_FAILURE_NOTIFIED).remove(KEY_RETRIES)
                .apply();
        return had;
    }

    private static void scheduleAlarm(Context app, long triggerAtMillis) {
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        long at = Math.max(System.currentTimeMillis() + 1000L, triggerAtMillis);
        PendingIntent pending = pending(app);
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            }
        } catch (SecurityException exception) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        }
    }

    private static void cancelAlarm(Context app) {
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.cancel(pending(app));
    }

    private static PendingIntent pending(Context app) {
        return PendingIntent.getBroadcast(app, REQUEST_DUE,
                new Intent(app, ScheduledResetReceiver.class)
                        .setAction(AppConstants.ACTION_SCHEDULED_RESET_DUE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void replanRefresh(Context app) {
        if (SecureTokenStore.isSignedIn(app) && AppPreferences.getAutomaticRefresh(app)) {
            RefreshScheduler.schedulePeriodic(app);
        }
    }

    private static void notifyUpdated(Context app) {
        try {
            app.sendBroadcast(new Intent(AppConstants.ACTION_RESET_CREDITS_UPDATED)
                    .setPackage(app.getPackageName()), AppConstants.INTERNAL_PERMISSION);
        } catch (RuntimeException ignored) {
            // The next rebuild picks the state up from the store.
        }
    }

    private static void post(Context app, String title, String text) {
        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED)) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Scheduled reset",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Outcome of a scheduled Codex reset: used, skipped, or failed");
        manager.createNotificationChannel(channel);
        NotificationChannel created = manager.getNotificationChannel(CHANNEL);
        if (created != null && created.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return;
        }
        PendingIntent open = PendingIntent.getActivity(app, NOTIFICATION_ID,
                new Intent(app, ResetCreditActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTIFICATION_ID, new Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_reset_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .build());
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Context appContext(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    /** A recorded run of a schedule. */
    public static final class Outcome {
        public final String outcome;
        public final String title;
        public final String text;
        public final long atMillis;

        Outcome(String outcome, String title, String text, long atMillis) {
            this.outcome = outcome;
            this.title = title;
            this.text = text;
            this.atMillis = atMillis;
        }
    }
}
