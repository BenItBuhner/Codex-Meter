package dev.bennett.codexmeter;

import java.util.List;
import java.util.function.Predicate;

/**
 * Which provider a manual, low-allowance or fast-usage live monitor follows, without Android
 * types so it can be tested. A Claude-only account (no Codex sign-in) must get Claude Code,
 * not a Codex monitor with nothing to show ("Refresh your signed-in usage before starting the
 * monitor", fixed in 2.9.0-alpha.24).
 */
final class MonitorProviderPicker {
    private MonitorProviderPicker() {
    }

    /**
     * Codex when it is signed in with limits to show, otherwise the selected dashboard tab,
     * otherwise the first provider in tab order that can be monitored; Codex when none can.
     */
    static UsageProvider pick(UsageProvider selected, List<UsageProvider> order,
            Predicate<UsageProvider> canMonitor) {
        if (canMonitor.test(UsageProvider.CODEX)) return UsageProvider.CODEX;
        if (selected != null && canMonitor.test(selected)) return selected;
        if (order != null) {
            for (UsageProvider provider : order) {
                if (provider != null && canMonitor.test(provider)) return provider;
            }
        }
        return UsageProvider.CODEX;
    }

    /** Whether a saved snapshot has a window to show. */
    static boolean hasWindows(UsageSnapshot snapshot) {
        return snapshot != null && (snapshot.fiveHour != null || snapshot.longWindow() != null);
    }

    /**
     * When a monitor started now would end: the next reset ahead in any window, 0 when there is
     * none. Claude's idle 5-hour session has no reset time, so its weekly reset is used.
     */
    static long until(UsageSnapshot snapshot, long now) {
        return hasWindows(snapshot) ? snapshot.nextResetMillis(now) : 0L;
    }
}
