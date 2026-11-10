package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.Map;

/**
 * Surprise refills and newly saved resets for every provider, not only Codex: each refresh is
 * compared with the one before ({@link ProviderEvents}), and what it finds is sent as a
 * notification and kept for a day for the Priority tab.
 *
 * <p>The notifications follow Settings, Notifications: "Surprise limit refills" and "New reset
 * credits". The Priority tab shows the events either way.
 */
final class ProviderAlerts {
    private ProviderAlerts() {}

    private static final String PREFS = "codex_meter_provider_events_v1";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_CODEX_CREDITS = "codex.credits_seen";
    private static final String SUFFIX_RESETS_SEEN = ".saved_resets_seen";
    private static final String SUFFIX_RESETS_LAST = ".saved_resets_last";
    private static final String SUFFIX_USER_RESET_UNTIL = ".user_reset_until";
    /** After the person spends a reset, a full limit is their doing, not a surprise. */
    private static final long USER_RESET_QUIET_MS = 15L * 60L * 1000L;
    private static final Object LOCK = new Object();

    /** Call with the snapshot a provider had before, just after its new one is saved. */
    static void onUsageSaved(Context context, UsageProvider provider, UsageSnapshot previous,
            UsageSnapshot current) {
        if (context == null || provider == null || previous == null || current == null) return;
        Context app = app(context);
        try {
            if (current.fetchedAtMillis < state(app).getLong(provider.id + SUFFIX_USER_RESET_UNTIL, 0L)) {
                return;
            }
            String shortLabel = provider == UsageProvider.CODEX || current.fiveHour == null ? "5-hour"
                    : MainActivity.cadenceLabel(current.fiveHour);
            ProviderEvents.Event event = ProviderEvents.surpriseRefill(provider,
                    ProviderEvents.refilledEarly(previous, current, shortLabel), current.fetchedAtMillis);
            if (event == null) return;
            remember(app, event);
            if (ResetAlertPreferences.unexpectedRefillsEnabled(app)) {
                ResetNotificationManager.postAccountActivity(app, provider, 0, event.title, event.detail);
            }
        } catch (RuntimeException e) {
            DiagnosticLog.error(app, "notifications", "provider_refill_alert_failed", e);
        }
    }

    /**
     * Codex finds its own surprise refills ({@link ResetNotificationManager}, which also knows
     * about resets the person spent); this only keeps them for the Priority tab.
     */
    static void onCodexRefill(Context context, int mask, long at) {
        if (context == null || mask == 0) return;
        java.util.List<String> labels = new java.util.ArrayList<>();
        if ((mask & CelebrationDetector.FIVE_HOUR) != 0) labels.add("5-hour");
        if ((mask & CelebrationDetector.WEEKLY) != 0) labels.add("Weekly");
        if ((mask & CelebrationDetector.MONTHLY) != 0) labels.add("Monthly");
        remember(app(context), ProviderEvents.surpriseRefill(UsageProvider.CODEX, labels, at));
    }

    /** Keeps a rise in Codex's reset-credit count for the Priority tab. */
    static void onCodexCreditCount(Context context, int current) {
        if (context == null || current < 0) return;
        Context app = app(context);
        SharedPreferences s = state(app);
        if (s.contains(KEY_CODEX_CREDITS)) {
            remember(app, ProviderEvents.newResetCredits(UsageProvider.CODEX,
                    s.getInt(KEY_CODEX_CREDITS, current), current, System.currentTimeMillis()));
        }
        s.edit().putInt(KEY_CODEX_CREDITS, current).apply();
    }

    /**
     * Call before a provider's saved resets are replaced. The first list ever seen is only
     * remembered, so signing in does not announce resets the person already had. The comparison
     * is with the last list the provider sent, so one answer without the list announces nothing
     * when it comes back.
     */
    static void onSavedResetsChanging(Context context, UsageProvider provider, ClaudeSavedResets stored,
            ClaudeSavedResets current) {
        if (context == null || provider == null || current == null) return;
        Context app = app(context);
        try {
            SharedPreferences s = state(app);
            ClaudeSavedResets previous = stored;
            String last = s.getString(provider.id + SUFFIX_RESETS_LAST, "");
            if (!last.isEmpty()) {
                try {
                    previous = ClaudeSavedResets.fromJson(new org.json.JSONObject(last));
                } catch (org.json.JSONException ignored) {
                    // Fall back to what the dashboard had stored.
                }
            }
            boolean known = previous != null || s.getBoolean(provider.id + SUFFIX_RESETS_SEEN, false);
            SharedPreferences.Editor editor = s.edit().putBoolean(provider.id + SUFFIX_RESETS_SEEN, true);
            try {
                editor.putString(provider.id + SUFFIX_RESETS_LAST, current.toJson().toString());
            } catch (org.json.JSONException ignored) {
                // Only non-finite numbers throw; there are none.
            }
            editor.apply();
            if (!known) return;
            long now = System.currentTimeMillis();
            Map<String, Integer> added = ProviderEvents.addedSavedResets(previous, current, now);
            if (added.isEmpty()) return;
            long expiry = current.nextExpiryMillis(now);
            ProviderEvents.Event event = ProviderEvents.newSavedResets(provider, added,
                    current.availableCount(now), expiry > 0L ? UsageFormat.absolute(app, expiry, now) : "", now);
            if (event == null) return;
            remember(app, event);
            if (ResetAlertPreferences.resetCreditIncreasesEnabled(app)) {
                ResetNotificationManager.postAccountActivity(app, provider, 1, event.title, event.detail);
            }
        } catch (RuntimeException e) {
            DiagnosticLog.error(app, "notifications", "provider_new_resets_alert_failed", e);
        }
    }

    /** The person just spent a reset on this provider. */
    static void markUserReset(Context context, UsageProvider provider) {
        if (context == null || provider == null) return;
        state(app(context)).edit().putLong(provider.id + SUFFIX_USER_RESET_UNTIL,
                System.currentTimeMillis() + USER_RESET_QUIET_MS).apply();
    }

    /** The last day's events, newest first. */
    static List<ProviderEvents.Event> recent(Context context) {
        if (context == null) return new java.util.ArrayList<>();
        return ProviderEvents.recent(ProviderEvents.fromJson(state(app(context)).getString(KEY_EVENTS, "")),
                System.currentTimeMillis());
    }

    /** Forgets a provider's events, for sign-out. */
    static void clear(Context context, UsageProvider provider) {
        if (context == null || provider == null) return;
        Context app = app(context);
        synchronized (LOCK) {
            List<ProviderEvents.Event> kept = new java.util.ArrayList<>();
            for (ProviderEvents.Event e : recent(app)) {
                if (!provider.id.equals(e.provider)) kept.add(e);
            }
            SharedPreferences.Editor editor = state(app).edit().putString(KEY_EVENTS, ProviderEvents.toJson(kept))
                    .remove(provider.id + SUFFIX_RESETS_SEEN).remove(provider.id + SUFFIX_RESETS_LAST)
                    .remove(provider.id + SUFFIX_USER_RESET_UNTIL);
            if (provider == UsageProvider.CODEX) editor.remove(KEY_CODEX_CREDITS);
            editor.apply();
        }
    }

    static void remember(Context app, ProviderEvents.Event event) {
        if (event == null) return;
        synchronized (LOCK) {
            SharedPreferences s = state(app);
            List<ProviderEvents.Event> events = ProviderEvents.remember(
                    ProviderEvents.fromJson(s.getString(KEY_EVENTS, "")), event, System.currentTimeMillis());
            s.edit().putString(KEY_EVENTS, ProviderEvents.toJson(events)).apply();
        }
        DiagnosticLog.info(app, "notifications", "provider_event", "kind", event.kind, "provider", event.provider);
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Context app(Context context) {
        Context a = context.getApplicationContext();
        return a != null ? a : context;
    }
}
