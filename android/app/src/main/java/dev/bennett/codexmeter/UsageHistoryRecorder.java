package dev.bennett.codexmeter;

import android.content.Context;

/** Serializes samples through the same refresh lock used by UsageApi. */
final class UsageHistoryRecorder {
    private UsageHistoryRecorder() {
    }

    static void record(Context context, UsageSnapshot snapshot) {
        record(context, UsageProvider.CODEX, snapshot);
    }

    /** Each provider keeps its own burn history so charts and pace never mix accounts. */
    static void record(Context context, UsageProvider provider, UsageSnapshot snapshot) {
        if (context == null || snapshot == null) return;
        recordWindow(context, provider, UsageHistory.FIVE_HOUR, snapshot.fiveHour,
                snapshot.fetchedAtMillis);
        recordWindow(context, provider, UsageHistory.WEEKLY, snapshot.weekly,
                snapshot.fetchedAtMillis);
        recordWindow(context, provider, UsageHistory.MONTHLY, snapshot.monthly,
                snapshot.fetchedAtMillis);
    }

    private static void recordWindow(Context context, UsageProvider provider, String kind,
            UsageWindow window, long observedAtMillis) {
        if (window == null) return;
        UsageHistory current = ProviderPreferences.loadUsageHistory(context, provider, kind);
        ProviderPreferences.saveUsageHistory(context, provider,
                current.append(window, observedAtMillis));
    }
}
