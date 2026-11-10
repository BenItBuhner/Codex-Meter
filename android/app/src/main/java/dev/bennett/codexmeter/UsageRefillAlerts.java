package dev.bennett.codexmeter;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * The "usage is back" notification: when any signed-in provider's 5-hour, weekly or monthly limit
 * hits 0%, the app waits for it to reset and then says so, once. It is always on and has no
 * setting; it only needs Android's notification permission.
 *
 * <p>It fires at the reset time the provider gave (an alarm, then a refresh), or sooner when a
 * refresh shows the limit has some left again. The rules live in {@link UsageRefill}.
 */
final class UsageRefillAlerts {
    private UsageRefillAlerts() {}

    static final String ACTION = "dev.bennett.codexmeter.action.USAGE_REFILL";
    private static final String CHANNEL = "usage_back";
    private static final String PREFS = "codex_meter_usage_refill_v1";
    private static final String KEY_ASKED_PERMISSION = "asked_permission";
    private static final String EXTRA_PROVIDER = "provider";
    private static final String EXTRA_WINDOW = "window";
    private static final String EXTRA_RESET_AT = "reset_at";
    private static final long DELIVERY_GRACE_MS = 3000L;
    private static final int ID_BASE = 75100;
    static final int PERMISSION_REQUEST = 8604;

    /** The limits this alert follows, in the order of {@link UsageSnapshot}'s fields. */
    private static final String[] WINDOWS = {"five_hour", "weekly", "monthly"};

    /** Call after a provider's usage is saved. Safe on any thread. */
    static void onUsageSaved(Context context, UsageProvider provider, UsageSnapshot snapshot) {
        if (context == null || provider == null || snapshot == null) return;
        Context app = app(context);
        try {
            long now = System.currentTimeMillis();
            for (int i = 0; i < WINDOWS.length; i++) {
                UsageWindow w = window(snapshot, i);
                String key = key(provider, i);
                long armed = state(app).getLong(key, 0L);
                UsageRefill.Decision d = UsageRefill.decide(armed, w, snapshot.fetchedAtMillis, now);
                if (d.action == UsageRefill.Action.ARM) {
                    state(app).edit().putLong(key, d.resetAt)
                            .putString(key + ".label", label(provider, snapshot, i)).apply();
                    schedule(app, provider, i, d.resetAt);
                } else if (d.action == UsageRefill.Action.BACK) {
                    String label = state(app).getString(key + ".label", label(provider, snapshot, i));
                    forget(app, provider, i, armed);
                    post(app, provider, i, label, d.left);
                }
            }
        } catch (RuntimeException e) {
            DiagnosticLog.error(app, "notifications", "usage_refill_failed", e);
        }
    }

    /** The alarm at a used-up limit's reset time. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null || intent == null || !ACTION.equals(intent.getAction())) return;
            Context app = app(context);
            UsageProvider provider = UsageProvider.fromId(intent.getStringExtra(EXTRA_PROVIDER));
            int index = intent.getIntExtra(EXTRA_WINDOW, -1);
            long resetAt = intent.getLongExtra(EXTRA_RESET_AT, 0L);
            if (provider == null || index < 0 || index >= WINDOWS.length) return;
            String key = key(provider, index);
            long armed = state(app).getLong(key, 0L);
            if (!UsageRefill.alarmStillDue(armed, resetAt) || !SecureTokenStore.isSignedIn(app, provider)) {
                return;
            }
            String label = state(app).getString(key + ".label", "");
            forget(app, provider, index, armed);
            post(app, provider, index, label, -1);
            RefreshScheduler.scheduleImmediate(app);
            WidgetRenderer.updateAll(app);
        }
    }

    /**
     * True when this alert covers the Codex reset the optional reset alarm is about to announce,
     * so the person gets one notification, not two.
     */
    static boolean coversCodex(Context context, String metric, long resetAt) {
        int index = "weekly".equals(metric) ? 1 : "monthly".equals(metric) ? 2 : 0;
        String key = key(UsageProvider.CODEX, index);
        SharedPreferences s = state(app(context));
        return UsageRefill.alarmStillDue(s.getLong(key, 0L), resetAt)
                || UsageRefill.alarmStillDue(s.getLong(key + ".announced", 0L), resetAt);
    }

    /** Sets the alarms again after a reboot or an update; a reset that passed meanwhile fires now. */
    static void rescheduleAll(Context context) {
        if (context == null) return;
        Context app = app(context);
        for (UsageProvider p : UsageProvider.values()) {
            for (int i = 0; i < WINDOWS.length; i++) {
                long armed = state(app).getLong(key(p, i), 0L);
                if (armed > 0L) {
                    schedule(app, p, i, armed);
                }
            }
        }
    }

    /** Forgets a provider's used-up limits, for sign-out. */
    static void clear(Context context, UsageProvider provider) {
        if (context == null || provider == null) return;
        Context app = app(context);
        for (int i = 0; i < WINDOWS.length; i++) {
            long armed = state(app).getLong(key(provider, i), 0L);
            forget(app, provider, i, armed);
            state(app).edit().remove(key(provider, i) + ".announced").apply();
        }
    }

    /**
     * Asks for Android's notification permission once, the first time a limit is used up while
     * the app is open, so the alert can reach the person when it resets.
     */
    static void askForPermissionIfNeeded(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences s = state(activity.getApplicationContext());
        if (s.getBoolean(KEY_ASKED_PERMISSION, false) || !anyWaiting(s)) return;
        s.edit().putBoolean(KEY_ASKED_PERMISSION, true).apply();
        activity.requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"},
                PERMISSION_REQUEST);
    }

    private static boolean anyWaiting(SharedPreferences s) {
        for (UsageProvider p : UsageProvider.values()) {
            for (int i = 0; i < WINDOWS.length; i++) {
                if (s.getLong(key(p, i), 0L) != 0L) return true;
            }
        }
        return false;
    }

    /** Posts the alert; used by the design gallery's debug scene too. */
    static boolean post(Context context, UsageProvider provider, int index, String label, int left) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Usage is back",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("When a limit you used up resets");
        manager.createNotificationChannel(channel);
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }
        int id = ID_BASE + provider.ordinal() * WINDOWS.length + index;
        PendingIntent open = PendingIntent.getActivity(context, id,
                new Intent(context, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_PROVIDER, provider.id)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = UsageRefill.text(provider.displayName, label, left);
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_reset_notification)
                .setContentTitle(UsageRefill.title(provider.displayName))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .build();
        manager.notify(id, n);
        return true;
    }

    private static void forget(Context app, UsageProvider provider, int index, long armed) {
        String key = key(provider, index);
        SharedPreferences.Editor e = state(app).edit().remove(key).remove(key + ".label");
        if (armed > 0L) e.putLong(key + ".announced", armed);
        e.apply();
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(pending(app, provider, index, 0L));
    }

    private static void schedule(Context app, UsageProvider provider, int index, long resetAt) {
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pi = pending(app, provider, index, resetAt);
        if (resetAt <= 0L) {
            alarms.cancel(pi); // unknown reset time: a refresh that shows some left announces it
            return;
        }
        long at = resetAt + DELIVERY_GRACE_MS;
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static PendingIntent pending(Context app, UsageProvider provider, int index, long resetAt) {
        Intent intent = new Intent(app, Receiver.class).setAction(ACTION)
                .putExtra(EXTRA_PROVIDER, provider.id)
                .putExtra(EXTRA_WINDOW, index)
                .putExtra(EXTRA_RESET_AT, resetAt);
        return PendingIntent.getBroadcast(app, ID_BASE + provider.ordinal() * WINDOWS.length + index,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static UsageWindow window(UsageSnapshot s, int index) {
        return index == 0 ? s.fiveHour : index == 1 ? s.weekly : s.monthly;
    }

    /** "5-hour", "Weekly" or "Monthly", named the way the dashboard names them. */
    private static String label(UsageProvider provider, UsageSnapshot s, int index) {
        if (index == 0) {
            return provider == UsageProvider.CODEX || s.fiveHour == null ? "5-hour"
                    : MainActivity.cadenceLabel(s.fiveHour);
        }
        return index == 1 ? "Weekly" : "Monthly";
    }

    private static String key(UsageProvider provider, int index) {
        return provider.id + "." + WINDOWS[index];
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Context app(Context context) {
        Context a = context.getApplicationContext();
        return a != null ? a : context;
    }
}
