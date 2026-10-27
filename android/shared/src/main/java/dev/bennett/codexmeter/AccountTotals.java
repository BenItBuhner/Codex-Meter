package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Combines several accounts of one provider into a single snapshot, so the dashboard and widgets
 * can show "how much is left across all my accounts" with the same cards and meters they use for
 * one account. Pure Java so the self-tests cover it.
 *
 * <p>Each account counts equally: three accounts at 100%, 40% and 0% left combine to 47% left.
 * A window whose reset has already passed counts as fully available, since that account's
 * allowance is back even if it has not been refreshed yet. The combined window resets at the
 * earliest upcoming reset, which is when more usage next becomes available.
 */
public final class AccountTotals {
    private AccountTotals() {
    }

    /** Label used as the combined snapshot's plan, e.g. "3 accounts". */
    public static String label(int accounts) {
        return accounts == 1 ? "1 account" : accounts + " accounts";
    }

    /** Null when no account has usage yet. */
    public static UsageSnapshot combine(List<UsageSnapshot> snapshots, long now) {
        List<UsageSnapshot> present = new ArrayList<>();
        if (snapshots != null) {
            for (UsageSnapshot snapshot : snapshots) {
                if (snapshot != null) present.add(snapshot);
            }
        }
        if (present.isEmpty()) return null;
        if (present.size() == 1) return present.get(0);
        UsageWindow fiveHour = combineWindow(present, Kind.FIVE_HOUR, now);
        UsageWindow weekly = combineWindow(present, Kind.WEEKLY, now);
        UsageWindow monthly = combineWindow(present, Kind.MONTHLY, now);
        boolean limitReached = true;
        boolean allowed = false;
        long oldest = Long.MAX_VALUE;
        for (UsageSnapshot snapshot : present) {
            limitReached &= snapshot.limitReached;
            allowed |= snapshot.allowed;
            if (snapshot.fetchedAtMillis > 0L) oldest = Math.min(oldest, snapshot.fetchedAtMillis);
        }
        return new UsageSnapshot(label(present.size()), allowed, limitReached, fiveHour, weekly,
                monthly, Collections.emptyList(), null, null, -1,
                oldest == Long.MAX_VALUE ? 0L : oldest);
    }

    private enum Kind { FIVE_HOUR, WEEKLY, MONTHLY }

    private static UsageWindow pick(UsageSnapshot snapshot, Kind kind) {
        switch (kind) {
            case FIVE_HOUR:
                return snapshot.fiveHour;
            case WEEKLY:
                return snapshot.weekly;
            default:
                return snapshot.monthly;
        }
    }

    private static UsageWindow combineWindow(List<UsageSnapshot> snapshots, Kind kind, long now) {
        int count = 0;
        int usedTotal = 0;
        long windowSeconds = 0L;
        long earliestReset = Long.MAX_VALUE;
        for (UsageSnapshot snapshot : snapshots) {
            UsageWindow window = pick(snapshot, kind);
            if (window == null) continue;
            count++;
            windowSeconds = Math.max(windowSeconds, window.windowSeconds);
            UsageWindow current = UsageSnapshot.currentWindow(window, snapshot.fetchedAtMillis,
                    now);
            if (current == null) continue; // Reset already happened: this account is full again.
            usedTotal += current.usedPercent;
            long reset = current.effectiveResetAtMillis(snapshot.fetchedAtMillis);
            if (reset > now) earliestReset = Math.min(earliestReset, reset);
        }
        if (count == 0) return null;
        int used = Math.round(usedTotal / (float) count);
        long resetAtSeconds = earliestReset == Long.MAX_VALUE ? 0L : earliestReset / 1000L;
        return new UsageWindow(used, windowSeconds, 0L, resetAtSeconds);
    }
}
