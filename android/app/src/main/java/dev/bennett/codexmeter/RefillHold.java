package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Keeps {@link RefillGuard}'s state per provider and asks for the re-check. Every provider's
 * refresh calls {@link #shouldHold} before it saves a reading; a held reading is not saved, so the
 * dashboard, history, widgets and alerts keep the last real numbers until the refill is confirmed.
 */
final class RefillHold {
    private RefillHold() {}

    private static final String PREFS = "codex_meter_refill_hold_v1";
    private static final String SUFFIX_SINCE = ".held_since";
    private static final String SUFFIX_SKIP_UNTIL = ".skip_until";
    /** After the person spends a reset, a refill is expected and shows at once. */
    private static final long USER_RESET_SKIP_MS = 15L * 60L * 1000L;

    /**
     * True when {@code current} should not be saved yet: it shows a refill well before its reset
     * time that a second reading has not confirmed. A re-check is scheduled.
     */
    static boolean shouldHold(Context context, UsageProvider provider, UsageSnapshot previous,
            UsageSnapshot current) {
        if (context == null || provider == null || previous == null || current == null) return false;
        Context app = app(context);
        long now = System.currentTimeMillis();
        SharedPreferences s = state(app);
        String since = provider.id + SUFFIX_SINCE;
        long heldSince = s.getLong(since, 0L);
        if (s.getLong(provider.id + SUFFIX_SKIP_UNTIL, 0L) > now || !RefillGuard.suspect(previous, current)) {
            if (heldSince > 0L) {
                s.edit().remove(since).apply();
                DiagnosticLog.info(app, "refresh", "refill_hold_dropped", "provider", provider.id,
                        "held_ms", now - heldSince);
            }
            return false;
        }
        if (RefillGuard.confirms(heldSince, now)) {
            s.edit().remove(since).apply();
            DiagnosticLog.info(app, "refresh", "refill_confirmed", "provider", provider.id,
                    "held_ms", now - heldSince);
            return false;
        }
        if (heldSince <= 0L || heldSince > now || now - heldSince > RefillGuard.HOLD_EXPIRES_MS) {
            s.edit().putLong(since, now).apply();
        }
        DiagnosticLog.info(app, "refresh", "refill_held", "provider", provider.id);
        RefreshScheduler.scheduleRefillCheck(app);
        return true;
    }

    /** True while a reading of this provider is held. */
    static boolean isHeld(Context context, UsageProvider provider) {
        if (context == null || provider == null) return false;
        return state(app(context)).getLong(provider.id + SUFFIX_SINCE, 0L) > 0L;
    }

    /**
     * Asks again every provider other than Codex that holds a reading. Codex is asked on every
     * refresh job anyway. Each answer goes through {@link #shouldHold}, which confirms or drops it.
     */
    static void recheckHeld(Context context) {
        Context app = app(context);
        for (UsageProvider provider : UsageProvider.values()) {
            if (provider == UsageProvider.CODEX || !isHeld(app, provider)) continue;
            try {
                if (!SecureTokenStore.isSignedIn(app, provider)) {
                    clear(app, provider);
                } else if (provider == UsageProvider.CLAUDE_CODE) {
                    ClaudeUsageApi.refreshAndCache(app);
                } else if (provider == UsageProvider.CURSOR) {
                    CursorUsageApi.refreshAndCache(app);
                } else if (ProviderUsageApi.handles(provider)) {
                    ProviderUsageApi.refreshAndCache(app, provider);
                }
            } catch (Exception e) {
                DiagnosticLog.warn(app, "refresh", "refill_recheck_failed", "provider", provider.id,
                        "error", e.getClass().getSimpleName());
            }
        }
    }

    /** The person just spent a reset on this provider: its refill is theirs and shows at once. */
    static void skipForUserReset(Context context, UsageProvider provider) {
        if (context == null || provider == null) return;
        state(app(context)).edit().putLong(provider.id + SUFFIX_SKIP_UNTIL,
                System.currentTimeMillis() + USER_RESET_SKIP_MS).remove(provider.id + SUFFIX_SINCE).apply();
    }

    /** Forgets a provider's hold, for sign-out. */
    static void clear(Context context, UsageProvider provider) {
        if (context == null || provider == null) return;
        state(app(context)).edit().remove(provider.id + SUFFIX_SINCE)
                .remove(provider.id + SUFFIX_SKIP_UNTIL).apply();
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Context app(Context context) {
        Context a = context.getApplicationContext();
        return a != null ? a : context;
    }
}
