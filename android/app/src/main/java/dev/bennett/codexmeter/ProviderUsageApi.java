package dev.bennett.codexmeter;

import android.content.Context;
import android.os.SystemClock;

/**
 * Usage refresh for every provider served by {@link ProviderClients}: load the stored
 * credential, renew it when it is about to expire, fetch, cache, and record history. Mirrors
 * {@link CursorUsageApi} so the dashboard, background job and widgets treat them the same.
 */
public final class ProviderUsageApi {
    /** Opening a tab only re-fetches when the cached data is older than this. */
    public static final long STALE_AFTER_MILLIS = 5L * 60L * 1000L;
    /** Background spacing; none of these meters moves faster than this matters. */
    public static final long BACKGROUND_INTERVAL_MILLIS = 15L * 60L * 1000L;
    /** Renew tokens that expire within this margin. */
    private static final long REFRESH_MARGIN_MILLIS = 5L * 60L * 1000L;
    private static final Object LOCK = new Object();

    private ProviderUsageApi() {
    }

    /** Whether {@code provider} refreshes through this class. */
    public static boolean handles(UsageProvider provider) {
        return ProviderClients.get(provider) != null;
    }

    public static boolean isStale(Context context, UsageProvider provider) {
        UsageSnapshot snapshot = ProviderPreferences.loadSnapshot(context, provider);
        return snapshot == null
                || System.currentTimeMillis() - snapshot.fetchedAtMillis > STALE_AFTER_MILLIS;
    }

    /** Called from the shared refresh job. Never throws, so other providers always refresh. */
    public static void refreshAllInBackgroundIfDue(Context context, boolean userRequested) {
        for (UsageProvider provider : UsageProvider.values()) {
            if (!handles(provider)) continue;
            try {
                if (!SecureTokenStore.isSignedIn(context, provider)) continue;
                UsageSnapshot cached = ProviderPreferences.loadSnapshot(context, provider);
                if (!CursorApi.isRefreshDue(System.currentTimeMillis(),
                        cached == null ? 0L : cached.fetchedAtMillis,
                        userRequested ? STALE_AFTER_MILLIS : BACKGROUND_INTERVAL_MILLIS)) {
                    continue;
                }
                refreshAndCache(context, provider);
            } catch (Exception exception) {
                DiagnosticLog.warn(context, "scheduler", "provider_background_refresh_failed",
                        "provider", provider.id, "error", exception.getClass().getSimpleName());
            }
        }
    }

    public static UsageSnapshot refreshAndCache(Context context, UsageProvider provider)
            throws Exception {
        ProviderClient client = ProviderClients.get(provider);
        if (client == null) throw new IllegalArgumentException(provider.id);
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "refresh", "provider_usage_refresh_started",
                "provider", provider.id);
        try {
            synchronized (LOCK) {
                AuthTokens tokens = usableTokens(context, client);
                UsageSnapshot snapshot;
                try {
                    snapshot = client.fetch(context, tokens);
                } catch (ProviderClient.Unauthorized rejected) {
                    if (!client.canRefresh(tokens)) {
                        throw new Exception(rejected.getMessage() + " Sign in again from "
                                + "Settings → Accounts.");
                    }
                    DiagnosticLog.warn(context, "auth", "provider_token_rejected_refreshing",
                            "provider", provider.id);
                    tokens = client.refresh(context, tokens);
                    SecureTokenStore.save(context, provider, tokens);
                    snapshot = client.fetch(context, tokens);
                }
                if (snapshot.planType.isEmpty()) {
                    UsageSnapshot previous = ProviderPreferences.loadSnapshot(context, provider);
                    if (previous != null && !previous.planType.isEmpty()) {
                        snapshot = withPlan(snapshot, previous.planType);
                    }
                }
                if (!tokens.email.isEmpty()
                        && ProviderPreferences.getEmail(context, provider).isEmpty()) {
                    ProviderPreferences.setEmail(context, provider, tokens.email);
                }
                if (!ProviderPreferences.saveSnapshot(context, provider, snapshot)) {
                    throw new Exception("Usage was received, but it could not be saved on this "
                            + "device.");
                }
                UsageHistoryRecorder.record(context, provider, snapshot);
                DiagnosticLog.info(context, "refresh", "provider_usage_refresh_succeeded",
                        "provider", provider.id,
                        "duration_ms", SystemClock.elapsedRealtime() - started,
                        "short_window", snapshot.fiveHour != null,
                        "weekly", snapshot.weekly != null,
                        "monthly", snapshot.monthly != null,
                        "additional_limits", snapshot.additionalLimits.size());
                return snapshot;
            }
        } catch (Exception exception) {
            DiagnosticLog.error(context, "refresh", "provider_usage_refresh_failed", exception,
                    "provider", provider.id,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            ProviderPreferences.setLastError(context, provider,
                    MainActivity.safeMessage(exception));
            throw exception;
        }
    }

    /**
     * Fetches usage for a saved (not active) account without touching the active account's
     * cache. {@code tokens[0]} is replaced when it is renewed.
     */
    static UsageSnapshot fetchDetached(Context context, UsageProvider provider,
            AuthTokens[] tokens) throws Exception {
        ProviderClient client = ProviderClients.get(provider);
        if (client == null) throw new IllegalArgumentException(provider.id);
        synchronized (LOCK) {
            if (client.canRefresh(tokens[0]) && tokens[0].expiresAtMillis > 0L
                    && tokens[0].expiresAtMillis
                            <= System.currentTimeMillis() + REFRESH_MARGIN_MILLIS) {
                tokens[0] = client.refresh(context, tokens[0]);
            }
            try {
                return client.fetch(context, tokens[0]);
            } catch (ProviderClient.Unauthorized rejected) {
                if (!client.canRefresh(tokens[0])) throw rejected;
                tokens[0] = client.refresh(context, tokens[0]);
                return client.fetch(context, tokens[0]);
            }
        }
    }

    private static AuthTokens usableTokens(Context context, ProviderClient client)
            throws Exception {
        AuthTokens tokens = SecureTokenStore.load(context, client.provider);
        if (tokens == null) {
            throw new Exception("Connect " + client.provider.displayName
                    + " from Settings → Accounts first.");
        }
        if (client.canRefresh(tokens) && tokens.expiresAtMillis > 0L
                && tokens.expiresAtMillis <= System.currentTimeMillis() + REFRESH_MARGIN_MILLIS) {
            DiagnosticLog.info(context, "auth", "provider_token_refresh_due",
                    "provider", client.provider.id);
            try {
                tokens = client.refresh(context, tokens);
                SecureTokenStore.save(context, client.provider, tokens);
            } catch (Exception refreshError) {
                if (tokens.expiresAtMillis <= System.currentTimeMillis()) throw refreshError;
                DiagnosticLog.warn(context, "auth", "provider_early_refresh_failed",
                        "provider", client.provider.id,
                        "error", refreshError.getClass().getSimpleName());
            }
        }
        return tokens;
    }

    private static UsageSnapshot withPlan(UsageSnapshot snapshot, String plan) {
        return new UsageSnapshot(plan, snapshot.allowed, snapshot.limitReached,
                snapshot.fiveHour, snapshot.weekly, snapshot.monthly, snapshot.additionalLimits,
                snapshot.usageCredits, snapshot.resetCreditsAvailable, snapshot.fetchedAtMillis);
    }
}
