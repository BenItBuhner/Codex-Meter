package dev.bennett.codexmeter;

import android.content.Context;

/** Serializes samples through the same refresh lock used by UsageApi. */
final class UsageHistoryRecorder {
    private UsageHistoryRecorder() {
    }

    static void record(Context context, UsageSnapshot snapshot) {
        if (context == null || snapshot == null) return;
        recordWindow(context, UsageHistory.FIVE_HOUR, snapshot.fiveHour,
                snapshot.fetchedAtMillis);
        recordWindow(context, UsageHistory.WEEKLY, snapshot.weekly,
                snapshot.fetchedAtMillis);
    }

    private static void recordWindow(Context context, String kind, UsageWindow window,
            long observedAtMillis) {
        if (window == null) return;
        UsageHistory current = AppPreferences.loadUsageHistory(context, kind);
        AppPreferences.saveUsageHistory(context, current.append(window, observedAtMillis));
    }
}
