package dev.bennett.codexmeter;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Priority tab's content, worked out once from what is already on the device and then drawn by
 * every design (One UI, One UI 9, Material 3 Expressive and Liquid Glass): official incidents and
 * significant slowdowns when there are any, the limits to watch, and new models when there are
 * any. Limits that are fine or already used up are not shown; nothing is shown just to fill it.
 *
 * <p>Only providers that are turned on in Settings, Providers, and signed in take part.
 */
final class PriorityPage {
    /** The tab's id in the designs that name tabs by string (Liquid Glass). */
    static final String TAB_ID = "priority";
    static final String TITLE = "Priority";

    /** One usage window of one provider. */
    static final class Limit {
        final UsageProvider provider;
        final String label;
        final UsageSnapshot snapshot;
        final UsageWindow window;
        /** 0 to 100. */
        final int left;
        /** Running out before it resets, at the pace since the window started. */
        final boolean fast;

        Limit(UsageProvider provider, String label, UsageSnapshot snapshot, UsageWindow window, int left,
                boolean fast) {
            this.provider = provider;
            this.label = label;
            this.snapshot = snapshot;
            this.window = window;
            this.left = left;
            this.fast = fast;
        }

        boolean watch() {
            return PriorityView.worthWatching(left, fast);
        }

        /** "Codex · Weekly". */
        String title() {
            return provider.displayName + " · " + label;
        }
    }

    /** Signed-in providers that take part, in tab order. */
    final List<UsageProvider> providers = new ArrayList<>();
    /** Running out early with some still left, least left first. */
    final List<Limit> watch = new ArrayList<>();
    /** Official incidents and performance warnings, most severe first. */
    final List<PriorityView.Item> alerts = new ArrayList<>();

    boolean signedInAnywhere() {
        return !providers.isEmpty();
    }

    /** Shown when no limit is running out early and nothing is wrong. */
    static final String ALL_CLEAR = "Nothing needs attention right now.";

    boolean allClear() {
        return watch.isEmpty() && alerts.isEmpty();
    }

    static PriorityPage build(MainActivity a) {
        PriorityPage page = new PriorityPage();
        long now = System.currentTimeMillis();
        for (UsageProvider p : ProviderPreferences.getEnabled(a)) {
            if (SecureTokenStore.isSignedIn(a, p)) {
                page.providers.add(p);
            }
        }
        List<Limit> limits = new ArrayList<>();
        for (UsageProvider p : page.providers) {
            UsageSnapshot snapshot = snapshot(a, p);
            if (snapshot != null) {
                add(a, limits, p, p == UsageProvider.CODEX || snapshot.fiveHour == null ? "5-hour"
                        : MainActivity.cadenceLabel(snapshot.fiveHour), snapshot, snapshot.fiveHour, now);
                add(a, limits, p, "Weekly", snapshot, snapshot.weekly, now);
                add(a, limits, p, "Monthly", snapshot, snapshot.monthly, now);
            }
        }
        for (Limit l : limits) {
            if (l.watch()) {
                page.watch.add(l);
            }
        }
        Collections.sort(page.watch, (x, y) -> Integer.compare(x.left, y.left));
        List<PriorityView.Item> items = a.priorityItems();
        for (PriorityView.Item item : items) {
            if (item.kind == PriorityView.Kind.DOWNTIME || item.kind == PriorityView.Kind.PERFORMANCE_WARNING) {
                page.alerts.add(item);
            }
        }
        return page;
    }

    static UsageSnapshot snapshot(Context context, UsageProvider p) {
        return p == UsageProvider.CODEX ? AppPreferences.loadSnapshot(context)
                : ProviderPreferences.loadSnapshot(context, p);
    }

    private static void add(MainActivity a, List<Limit> out, UsageProvider p, String label, UsageSnapshot snapshot,
            UsageWindow w, long now) {
        if (w == null || w.windowSeconds <= 0L) {
            return;
        }
        long resetAt = w.effectiveResetAtMillis(snapshot.fetchedAtMillis);
        if (w.showsResetCountdown() && resetAt > 0L && resetAt <= now) {
            return; // reset since the last refresh; the old number would mislead
        }
        int left = Math.max(0, Math.min(100, w.remainingPercent()));
        UsagePace.Assessment pace = UsagePacePreferences.assess(a, p, snapshot, w, now);
        out.add(new Limit(p, label, snapshot, w, left, pace != null && pace.accelerated));
    }
}
