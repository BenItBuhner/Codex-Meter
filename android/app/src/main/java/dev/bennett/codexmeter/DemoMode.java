package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * "Explore demo": Codex, Claude Code and Cursor tabs filled with local sample usage, for someone
 * who wants to see the app before signing in (the Apple apps' demo mode). Only providers that are
 * signed out get demo data, so a real account is never overwritten. A demo provider holds marked
 * placeholder tokens, so every screen, widget and the watch treat it as signed in; its refreshes
 * re-date the sample data and never touch the network. Signing in for real, signing out, or
 * Leave demo removes the sample data.
 */
public final class DemoMode {
    /** The providers demo mode fills, in tab order. */
    public static final List<UsageProvider> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            UsageProvider.CODEX, UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR));
    static final String ACCESS_TOKEN = "codex-meter-demo";
    static final String EMAIL = "Demo account";
    static final String NO_NETWORK_MESSAGE =
            "This is demo data. Sign in to see your own usage.";

    private static final String PREFS = "codex_meter_demo";
    private static final String KEY_PROVIDERS = "providers";

    private DemoMode() {
    }

    /** Raised instead of a network request for a provider showing demo data. */
    public static final class DemoDataException extends Exception {
        private static final long serialVersionUID = 1L;

        DemoDataException() {
            super(NO_NETWORK_MESSAGE);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether this provider is showing demo data. */
    public static boolean isOn(Context context, UsageProvider provider) {
        return provider != null && active(context).contains(provider);
    }

    /** Whether any provider is showing demo data. */
    public static boolean isAnyOn(Context context) {
        return !active(context).isEmpty();
    }

    static boolean isDemoTokens(AuthTokens tokens) {
        return tokens != null && ACCESS_TOKEN.equals(tokens.accessToken);
    }

    /** Throws instead of letting a request go out with the demo's placeholder tokens. */
    static void requireRealAccount(Context context, UsageProvider provider)
            throws DemoDataException {
        if (isOn(context, provider)) throw new DemoDataException();
    }

    static List<UsageProvider> active(Context context) {
        String saved = prefs(context).getString(KEY_PROVIDERS, "");
        List<UsageProvider> providers = new ArrayList<>();
        if (saved == null || saved.isEmpty()) return providers;
        for (String id : saved.split(",")) {
            UsageProvider provider = UsageProvider.fromId(id);
            if (provider != null && PROVIDERS.contains(provider) && !providers.contains(provider)) {
                providers.add(provider);
            }
        }
        return providers;
    }

    private static void saveActive(Context context, List<UsageProvider> providers) {
        StringBuilder ids = new StringBuilder();
        for (UsageProvider provider : providers) {
            if (ids.length() > 0) ids.append(',');
            ids.append(provider.id);
        }
        prefs(context).edit().putString(KEY_PROVIDERS, ids.toString()).apply();
    }

    /**
     * Fills every signed-out demo provider with sample usage and shows their tabs. Returns the
     * providers now in demo mode (empty when all three already have real accounts).
     */
    public static List<UsageProvider> enter(Context context) throws Exception {
        Context app = context.getApplicationContext();
        List<UsageProvider> active = active(app);
        long now = System.currentTimeMillis();
        for (UsageProvider provider : PROVIDERS) {
            if (active.contains(provider) || SecureTokenStore.isSignedIn(app, provider)) continue;
            // Marked first, so the token save below knows these are demo tokens.
            active.add(provider);
            saveActive(app, active);
            SecureTokenStore.save(app, provider, new AuthTokens(ACCESS_TOKEN, ACCESS_TOKEN, "",
                    Long.MAX_VALUE, "demo", EMAIL));
            seed(app, provider, now);
        }
        if (!active.isEmpty()) {
            List<UsageProvider> enabled = new ArrayList<>(ProviderPreferences.getEnabled(app));
            for (UsageProvider provider : active) {
                if (!enabled.contains(provider)) enabled.add(provider);
            }
            ProviderPreferences.setEnabledProviders(app, enabled);
            ProviderPreferences.setSelected(app, active.get(0));
            DiagnosticLog.info(app, "user", "demo_entered", "providers", active.size());
            WidgetRenderer.updateAll(app);
        }
        return active;
    }

    /** Removes every provider's demo data, leaving real accounts as they are. */
    public static void leave(Context context) {
        for (UsageProvider provider : active(context)) {
            ProviderAccounts.signOut(context, provider);
        }
    }

    /**
     * Forgets that a provider is in demo mode and deletes its sample usage. Called when the demo
     * account is signed out or replaced by a real sign-in; the caller handles the tokens.
     */
    static void clear(Context context, UsageProvider provider) {
        Context app = context.getApplicationContext();
        List<UsageProvider> active = active(app);
        if (!active.remove(provider)) return;
        saveActive(app, active);
        if (provider == UsageProvider.CODEX) {
            // Also clears the Codex reset credits and usage history.
            AppPreferences.clearSnapshot(app);
        } else {
            ProviderPreferences.clearProvider(app, provider);
        }
        DiagnosticLog.info(app, "user", "demo_left", "provider", provider.id);
    }

    /** A demo provider's "refresh": the same sample usage, dated now. */
    static UsageSnapshot refresh(Context context, UsageProvider provider) {
        seed(context.getApplicationContext(), provider, System.currentTimeMillis());
        return ProviderPreferences.loadSnapshot(context, provider);
    }

    /** The sample usage for one provider, with resets and history relative to {@code now}. */
    static void seed(Context context, UsageProvider provider, long now) {
        long hour = TimeUnit.HOURS.toMillis(1);
        long day = TimeUnit.DAYS.toMillis(1);
        long fiveHours = TimeUnit.HOURS.toSeconds(5);
        long week = TimeUnit.DAYS.toSeconds(7);
        if (provider == UsageProvider.CODEX) {
            long fiveHourReset = now + 4 * hour;
            long weeklyReset = now + 6 * day;
            UsageSnapshot snapshot = new UsageSnapshot("plus", true, false,
                    new UsageWindow(37, fiveHours, 0L, fiveHourReset / 1000L),
                    new UsageWindow(61, week, 0L, weeklyReset / 1000L),
                    Collections.emptyList(),
                    new UsageCredits(true, false, "2500"),
                    3,
                    now);
            AppPreferences.saveSnapshot(context, snapshot);
            AppPreferences.saveResetCredits(context, new ResetCreditsSnapshot(2, Arrays.asList(
                    new RateLimitResetCredit("demo-1", "both", "available", now,
                            now + 2 * day, "Reset credit 1", ""),
                    new RateLimitResetCredit("demo-2", "both", "available", now,
                            now + 9 * day, "Reset credit 2", "")),
                    now));
            seedHistory(context, provider, UsageHistory.FIVE_HOUR, fiveHours, fiveHourReset,
                    new int[][] {{6, 14, 25, 33, 41}, {12, 30, 52, 74, 96}, {3, 7, 12, 18, 22}},
                    new int[] {8, 15, 22, 30, 37}, now);
            seedHistory(context, provider, UsageHistory.WEEKLY, week, weeklyReset,
                    new int[][] {{8, 19, 33, 47, 58, 71}, {11, 24, 39, 52, 66, 84}},
                    new int[] {12, 28, 41, 53, 61}, now);
        } else if (provider == UsageProvider.CLAUDE_CODE) {
            long fiveHourReset = now + 52 * TimeUnit.MINUTES.toMillis(1);
            long weeklyReset = now + 5 * day;
            ProviderPreferences.saveSnapshot(context, provider, new UsageSnapshot("Max", true,
                    false,
                    new UsageWindow(71, fiveHours, 0L, fiveHourReset / 1000L),
                    new UsageWindow(34, week, 0L, weeklyReset / 1000L),
                    Collections.emptyList(), null, 0, now));
            seedHistory(context, provider, UsageHistory.FIVE_HOUR, fiveHours, fiveHourReset,
                    new int[][] {{10, 26, 38, 61, 83}, {5, 12, 20, 31, 44}},
                    new int[] {18, 33, 47, 60, 71}, now);
            seedHistory(context, provider, UsageHistory.WEEKLY, week, weeklyReset,
                    new int[][] {{5, 9, 14, 22, 30, 38}, {15, 34, 52, 78, 95, 100}},
                    new int[] {9, 17, 24, 30, 34}, now);
        } else if (provider == UsageProvider.CURSOR) {
            long month = TimeUnit.DAYS.toSeconds(30);
            long monthlyReset = now + 11 * day;
            ProviderPreferences.saveSnapshot(context, provider, new UsageSnapshot("Pro", true,
                    false, null, null, new UsageWindow(27, month, 0L, monthlyReset / 1000L),
                    Collections.emptyList(), null, 0, now));
            seedHistory(context, provider, UsageHistory.MONTHLY, month, monthlyReset,
                    new int[][] {{9, 21, 35, 52, 70, 88}},
                    new int[] {6, 12, 18, 23, 27}, now);
        }
    }

    /**
     * Earlier windows' climbs (oldest first), then the current window climbing to its last value
     * over the past hour.
     */
    private static void seedHistory(Context context, UsageProvider provider, String kind,
            long windowSeconds, long resetAt, int[][] earlier, int[] current, long now) {
        long windowMillis = windowSeconds * 1000L;
        UsageHistory history = UsageHistory.empty(kind);
        for (int index = 0; index < earlier.length; index++) {
            long reset = resetAt - windowMillis * (earlier.length - index);
            int[] shape = earlier[index];
            for (int point = 0; point < shape.length; point++) {
                long observed = reset - windowMillis + windowMillis * (point + 1) / (shape.length + 1);
                history = history.append(new UsageWindow(shape[point], windowSeconds, 0L,
                        reset / 1000L), observed);
            }
        }
        for (int point = 0; point < current.length; point++) {
            history = history.append(new UsageWindow(current[point], windowSeconds, 0L,
                    resetAt / 1000L), now - TimeUnit.MINUTES.toMillis(48L - 12L * point));
        }
        ProviderPreferences.saveUsageHistory(context, provider, history);
    }
}
