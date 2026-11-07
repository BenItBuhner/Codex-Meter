package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The meters a watch surface shows for any provider, most urgent cadence first.
 *
 * <p>Codex reports a 5-hour and a weekly window, but other providers report a daily window,
 * only a monthly one, or named limits (credits, model pools). Tiles and complications have
 * room for one or two meters, so this picks them in a fixed order: the short window, the
 * weekly window, the monthly window, then each additional limit. For Codex that is exactly
 * the 5-hour and weekly pair the watch always showed.
 */
public final class WatchMeters {
    public static final String KIND_SHORT = "short";
    public static final String KIND_WEEKLY = "weekly";
    public static final String KIND_MONTHLY = "monthly";
    public static final String KIND_LIMIT = "limit";

    private static final int MAX_LABEL = 12;

    private WatchMeters() {
    }

    /** One meter: its window plus the labels a 2-line tile or a 3-character slot can fit. */
    public static final class Meter {
        public final String kind;
        /** "5h", "Day", "Week", "Month" or a short limit name. */
        public final String shortLabel;
        /** "5-hour", "Daily", "Weekly", "Monthly" or the limit name. */
        public final String label;
        public final UsageWindow window;

        Meter(String kind, String shortLabel, String label, UsageWindow window) {
            this.kind = kind;
            this.shortLabel = shortLabel;
            this.label = label;
            this.window = window;
        }

        /** Whether the dial shows the calendar glyph rather than the clock. */
        public boolean longCadence() {
            return KIND_WEEKLY.equals(kind) || KIND_MONTHLY.equals(kind)
                    || (window != null && window.windowSeconds >= TimeUnit.DAYS.toSeconds(2));
        }
    }

    /** Every meter {@code snapshot} reports, rolled forward to {@code nowMillis}. */
    public static List<Meter> all(UsageSnapshot snapshot, long nowMillis) {
        List<Meter> meters = new ArrayList<>();
        if (snapshot == null) return meters;
        long observed = snapshot.fetchedAtMillis;
        UsageWindow shortWindow = UsageSnapshot.currentWindow(snapshot.fiveHour, observed,
                nowMillis);
        if (shortWindow != null) {
            meters.add(new Meter(KIND_SHORT, shortLabel(shortWindow), longLabel(shortWindow),
                    shortWindow));
        }
        UsageWindow weekly = UsageSnapshot.currentWindow(snapshot.weekly, observed, nowMillis);
        if (weekly != null) meters.add(new Meter(KIND_WEEKLY, "Week", "Weekly", weekly));
        UsageWindow monthly = UsageSnapshot.currentWindow(snapshot.monthly, observed, nowMillis);
        if (monthly != null) meters.add(new Meter(KIND_MONTHLY, "Month", "Monthly", monthly));
        for (UsageLimit limit : snapshot.additionalLimits) {
            if (limit == null) continue;
            UsageWindow window = limit.primary != null ? limit.primary : limit.secondary;
            window = UsageSnapshot.currentWindow(window, observed, nowMillis);
            if (window == null) continue;
            String name = limit.displayName();
            meters.add(new Meter(KIND_LIMIT, clip(name), name, window));
        }
        return meters;
    }

    /** The first meter, or null when the provider has reported nothing yet. */
    public static Meter primary(UsageSnapshot snapshot, long nowMillis) {
        List<Meter> meters = all(snapshot, nowMillis);
        return meters.isEmpty() ? null : meters.get(0);
    }

    /** The second meter, or null when the provider reports only one. */
    public static Meter secondary(UsageSnapshot snapshot, long nowMillis) {
        List<Meter> meters = all(snapshot, nowMillis);
        return meters.size() < 2 ? null : meters.get(1);
    }

    public static UsageWindow window(Meter meter) {
        return meter == null ? null : meter.window;
    }

    /** "62·41"-style pair for a short complication. */
    public static String pairShortText(UsageSnapshot snapshot, long nowMillis) {
        Meter first = primary(snapshot, nowMillis);
        Meter second = secondary(snapshot, nowMillis);
        String left = WearGlanceFormat.remainingNumberText(window(first));
        return second == null ? left
                : left + "·" + WearGlanceFormat.remainingNumberText(second.window);
    }

    /** "5h 62% · Week 41%"-style pair for a long complication or the monitor. */
    public static String pairLongText(UsageSnapshot snapshot, long nowMillis) {
        Meter first = primary(snapshot, nowMillis);
        Meter second = secondary(snapshot, nowMillis);
        if (first == null) return "No usage yet";
        String text = first.shortLabel + " " + WearGlanceFormat.remainingPercentText(first.window);
        return second == null ? text : text + " · " + second.shortLabel + " "
                + WearGlanceFormat.remainingPercentText(second.window);
    }

    /** The lower of the first two meters' remaining percent; 0 with no data. */
    public static int constrainedRemaining(UsageSnapshot snapshot, long nowMillis) {
        Meter first = primary(snapshot, nowMillis);
        Meter second = secondary(snapshot, nowMillis);
        if (first == null) return 0;
        if (second == null) return first.window.remainingPercent();
        return Math.min(first.window.remainingPercent(), second.window.remainingPercent());
    }

    /** The meter whose window resets soonest, or null when none has a reset time. */
    public static Meter nextReset(UsageSnapshot snapshot, long nowMillis) {
        Meter best = null;
        long bestAt = Long.MAX_VALUE;
        if (snapshot == null) return null;
        for (Meter meter : all(snapshot, nowMillis)) {
            long at = meter.window.effectiveResetAtMillis(snapshot.fetchedAtMillis);
            if (at > nowMillis && at < bestAt) {
                best = meter;
                bestAt = at;
            }
        }
        return best;
    }

    static String shortLabel(UsageWindow window) {
        long seconds = window.windowSeconds;
        if (seconds <= 0L) return "Now";
        long hours = Math.round(seconds / 3600.0);
        if (seconds >= TimeUnit.HOURS.toSeconds(20)) return "Day";
        return Math.max(1L, hours) + "h";
    }

    static String longLabel(UsageWindow window) {
        long seconds = window.windowSeconds;
        if (seconds <= 0L) return "Current";
        if (seconds >= TimeUnit.HOURS.toSeconds(20)) return "Daily";
        long hours = Math.max(1L, Math.round(seconds / 3600.0));
        return hours + "-hour";
    }

    private static String clip(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return "Limit";
        if (clean.length() <= MAX_LABEL) return clean;
        return clean.substring(0, MAX_LABEL - 1).trim() + "…";
    }
}
