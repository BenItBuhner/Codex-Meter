package dev.bennett.codexmeter;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Priority tab's content, worked out once from what is already on the device and then drawn by
 * every design (One UI, One UI 9, Material 3 Expressive and Liquid Glass): the summary, the limits
 * to watch with their pace, everything else that has a limit, each provider's official status,
 * model performance warnings, new models and a tip.
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
        final boolean exhausted;
        /** Running out before it resets, at the pace since the window started. */
        final boolean fast;

        Limit(UsageProvider provider, String label, UsageSnapshot snapshot, UsageWindow window, int left,
                boolean exhausted, boolean fast) {
            this.provider = provider;
            this.label = label;
            this.snapshot = snapshot;
            this.window = window;
            this.left = left;
            this.exhausted = exhausted;
            this.fast = fast;
        }

        boolean watch() {
            return exhausted || fast;
        }

        /** "Codex · Weekly". */
        String title() {
            return provider.displayName + " · " + label;
        }
    }

    /** A provider's official status; {@code status} is null when it hasn't been checked yet. */
    static final class Status {
        final UsageProvider provider;
        final ProviderStatus status;

        Status(UsageProvider provider, ProviderStatus status) {
            this.provider = provider;
            this.status = status;
        }

        /** The value in the status list ("Operational", "Partial outage"). */
        String label() {
            return stateLabel(status == null ? null : status.state);
        }

        boolean problem() {
            return status != null && status.state.isProblem();
        }
    }

    /** Signed-in providers that take part, in tab order. */
    final List<UsageProvider> providers = new ArrayList<>();
    /** Used up or running out early, most urgent first. */
    final List<Limit> watch = new ArrayList<>();
    /** Every other limit, least left first. */
    final List<Limit> others = new ArrayList<>();
    final List<Status> statuses = new ArrayList<>();
    /** Official incidents and performance warnings, most severe first. */
    final List<PriorityView.Item> alerts = new ArrayList<>();
    String summary = "";
    String tip = "";

    boolean signedInAnywhere() {
        return !providers.isEmpty();
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
            ProviderStatus status = ProviderStatusSources.forProvider(p) == null ? null
                    : ProviderStatusStore.load(a, p);
            if (status != null && now - status.checkedAtMillis > StatusPollPolicy.MAX_BACKOFF_MS) {
                status = null; // too old to state as current
            }
            if (ProviderStatusSources.forProvider(p) != null) {
                page.statuses.add(new Status(p, status));
            }
        }
        split(limits, page.watch, page.others);
        List<PriorityView.Item> items = a.priorityItems();
        for (PriorityView.Item item : items) {
            if (item.kind == PriorityView.Kind.DOWNTIME || item.kind == PriorityView.Kind.PERFORMANCE_WARNING) {
                page.alerts.add(item);
            }
        }
        PriorityView.Prose prose = PriorityView.needsAttention(items) ? PrioritySummary.cached(a, items) : null;
        page.summary = PriorityPrefs.summaryLine(a, items, prose);
        page.tip = PriorityPrefs.tipLine(items, prose);
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
        out.add(new Limit(p, label, snapshot, w, left, left <= 0, left > 0 && pace != null && pace.accelerated));
    }

    /**
     * Splits limits into the ones to watch (used up first, then running out early) and the rest,
     * each with the least left first. Ties keep tab order. Pure, for the self-test.
     */
    static void split(List<Limit> all, List<Limit> watch, List<Limit> others) {
        for (Limit l : all) {
            (l.watch() ? watch : others).add(l);
        }
        Collections.sort(watch, (x, y) -> x.exhausted != y.exhausted ? (x.exhausted ? -1 : 1)
                : Integer.compare(x.left, y.left));
        Collections.sort(others, (x, y) -> Integer.compare(x.left, y.left));
    }

    static String stateLabel(ProviderStatus.State state) {
        if (state == null) {
            return "Not checked yet";
        }
        switch (state) {
            case OPERATIONAL:
                return "Operational";
            case NO_ACTIVE_INCIDENTS:
                return "No incidents";
            case MAINTENANCE:
                return "Maintenance";
            case DEGRADED:
                return "Degraded";
            case PARTIAL_OUTAGE:
                return "Partial outage";
            case MAJOR_OUTAGE:
                return "Major outage";
            case UNKNOWN:
            default:
                return "Unknown";
        }
    }
}
