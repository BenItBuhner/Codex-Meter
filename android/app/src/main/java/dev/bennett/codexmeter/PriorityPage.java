package dev.bennett.codexmeter;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Priority tab's content, worked out once from what is already on the device and then drawn by
 * every design (One UI, One UI 9, Material 3 Expressive and Liquid Glass): official incidents and
 * significant slowdowns when there are any, the limits to watch, the used-up limits with when each
 * one resets, and new models when there are any. It also carries the last day's activity (a limit
 * that refilled early, saved resets added or used), which the person can dismiss, saved resets that can be used on a used-up
 * limit, and what is coming up in the next three days (a monthly limit renewing, saved resets
 * expiring). Limits that are fine are not shown; nothing is shown just to fill it.
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

        /** When it resets, in milliseconds; 0 when the provider did not say. */
        long resetAt() {
            long at = window.effectiveResetAtMillis(snapshot.fetchedAtMillis);
            return Math.max(0L, at);
        }

        /** "Resets 4:14 PM", "Resets tomorrow 9:00 AM", "Resets Nov 3", or "Reset time unknown". */
        String resetText(Context context, long now) {
            String text = NowBarCopy.resetClockText(window, snapshot.fetchedAtMillis, now,
                    android.text.format.DateFormat.is24HourFormat(context), null, null);
            return text != null ? text : "Reset time unknown";
        }

        /** "Codex · Weekly". */
        String title() {
            return provider.displayName + " · " + label;
        }
    }

    /**
     * Dismisses one row of a dismissible list (Recent activity or New models). Every design calls
     * this, so a swipe, the hold menu and the accessibility action all clear the same thing.
     */
    static void dismiss(Context context, PriorityNote note) {
        if (note != null) {
            apply(context, PriorityNote.toDismiss(java.util.Collections.singletonList(note)));
        }
    }

    /** Dismisses every dismissible row of one list, and only that list. */
    static void dismissAll(Context context, List<PriorityNote> notes) {
        apply(context, PriorityNote.toDismiss(notes));
    }

    private static void apply(Context context, PriorityNote.Dismissal d) {
        for (String key : d.eventKeys) {
            ProviderAlerts.dismiss(context, key);
        }
        if (!d.models.isEmpty()) {
            ModelDiscoveryStore.dismiss(context, d.models);
        }
    }

    /** Signed-in providers that take part, in tab order. */
    final List<UsageProvider> providers = new ArrayList<>();
    /** Running out early with some still left, least left first. */
    final List<Limit> watch = new ArrayList<>();
    /** Used up until they reset, soonest reset first. Never under {@link #watch}. */
    final List<Limit> usedUp = new ArrayList<>();
    /** Official incidents and performance warnings, most severe first. */
    final List<PriorityView.Item> alerts = new ArrayList<>();

    /** The last day's surprise refills and saved resets added or used, newest first. Dismissible. */
    final List<PriorityNote> news = new ArrayList<>();
    /** Saved resets of a provider that has a used-up limit they can refill. */
    final List<PriorityNote> ready = new ArrayList<>();
    /** Monthly limits renewing and saved resets expiring within three days, soonest first. */
    final List<PriorityNote> comingUp = new ArrayList<>();
    /** Models newly listed for the connected providers. Dismissible, one row at a time or all. */
    final List<PriorityNote> models = new ArrayList<>();

    static final String NEWS = "Recent activity";
    static final String NEW_MODELS = "New models";
    static final String READY = "Saved resets you can use";
    static final String COMING_UP = "Coming up";

    boolean signedInAnywhere() {
        return !providers.isEmpty();
    }

    /** Shown when no limit is running out early or used up, and nothing is wrong. */
    static final String ALL_CLEAR = "Nothing needs attention right now.";
    /** Shown instead when the filter menu hides everything there is. */
    static final String ALL_FILTERED = "Everything here is filtered out.";

    /** The line for an empty page: all clear, or all filtered out. */
    String emptyLine() {
        return filtered ? ALL_FILTERED : ALL_CLEAR;
    }
    /** The heading over {@link #usedUp}. */
    static final String USED_UP = "Used up";

    /**
     * Priority's sections the filter menu switches, in page order: {key, label}. The keys are
     * stored, so keep them when the labels change.
     */
    static final String[][] SECTIONS = {
            {"alerts", "Outages and slowdowns"},
            {"news", NEWS},
            {"watch", "Limits to watch"},
            {"used_up", USED_UP},
            {"ready", READY},
            {"coming_up", COMING_UP},
            {"models", NEW_MODELS},
    };

    /** True when the filter menu hides something that would show now. */
    boolean filtered;

    boolean allClear() {
        return watch.isEmpty() && usedUp.isEmpty() && alerts.isEmpty() && news.isEmpty() && ready.isEmpty()
                && comingUp.isEmpty() && models.isEmpty();
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
            } else if (l.left <= 0) {
                page.usedUp.add(l);
            }
        }
        Collections.sort(page.watch, (x, y) -> Integer.compare(x.left, y.left));
        Collections.sort(page.usedUp, (x, y) -> Long.compare(PriorityView.resetOrder(x.resetAt()),
                PriorityView.resetOrder(y.resetAt())));
        addNotes(a, page, limits, now);
        if (PriorityPrefs.isViewEnabled(a)) {
            addModels(page, ModelDiscoveryStore.cardItems(a));
        }
        List<PriorityView.Item> items = a.priorityItems();
        for (PriorityView.Item item : items) {
            if (item.kind == PriorityView.Kind.DOWNTIME || item.kind == PriorityView.Kind.PERFORMANCE_WARNING) {
                page.alerts.add(item);
            }
        }
        page.filter(a);
        return page;
    }

    /** Empties the sections the filter menu switched off. */
    private void filter(Context context) {
        hide(context, "alerts", alerts);
        hide(context, "news", news);
        hide(context, "watch", watch);
        hide(context, "used_up", usedUp);
        hide(context, "ready", ready);
        hide(context, "coming_up", comingUp);
        hide(context, "models", models);
    }

    private void hide(Context context, String key, List<?> list) {
        if (!list.isEmpty() && !PriorityPrefs.isSectionShown(context, key)) {
            list.clear();
            filtered = true;
        }
    }

    private static void addNotes(MainActivity a, PriorityPage page, List<Limit> limits, long now) {
        for (ProviderEvents.Event e : ProviderAlerts.recent(a)) {
            UsageProvider p = UsageProvider.fromId(e.provider);
            if (p != null && p.id.equals(e.provider) && page.providers.contains(p) && !e.line.isEmpty()) {
                page.news.add(new PriorityNote(p, p.displayName + " · " + e.line, ProviderEvents.ago(e.at, now), e.key()));
            }
        }
        // Soonest first: each entry is {when, note}.
        List<Object[]> soon = new ArrayList<>();
        java.util.Set<UsageProvider> renewing = new java.util.HashSet<>();
        for (Limit l : limits) {
            boolean monthly = "Monthly".equals(l.label) || "Monthly".equals(MainActivity.cadenceLabel(l.window));
            if (monthly && l.left > 0 && !renewing.contains(l.provider)
                    && ProviderEvents.renewsSoon(l.window, l.snapshot.fetchedAtMillis, now)) {
                renewing.add(l.provider);
                soon.add(new Object[] {l.resetAt(), new PriorityNote(l.provider, l.provider.displayName + " · Monthly limit",
                        "Renews in " + ProviderEvents.duration(l.resetAt() - now) + " · " + l.left + "% left")});
            }
        }
        for (UsageProvider p : page.providers) {
            ClaudeSavedResets saved = p == UsageProvider.CLAUDE_CODE
                    ? ProviderPreferences.loadSavedResets(a, p) : null;
            ResetCreditsSnapshot credits = ProviderPreferences.loadResetCredits(a, p, now);
            int available = credits == null ? 0 : credits.availableCount;
            if (available <= 0) {
                continue;
            }
            String what = saved != null ? saved.inventoryLabel(now)
                    : available + " reset credit" + (available == 1 ? "" : "s");
            String title = p.displayName + " · " + what;
            boolean usedUp = false;
            for (Limit l : page.usedUp) {
                usedUp |= l.provider == p;
            }
            if (usedUp && (saved == null || saved.usableGrant(now) != null)) {
                page.ready.add(new PriorityNote(p, title, "Ready to use"));
            }
            long expiry = credits.nextExpiryMillis(now);
            if (expiry > now && expiry - now <= ProviderEvents.EXPIRY_SOON_MS) {
                soon.add(new Object[] {expiry, new PriorityNote(p, title,
                        (available == 1 ? "Expires in " : "Next expires in ") + ProviderEvents.duration(expiry - now))});
            }
        }
        Collections.sort(soon, (x, y) -> Long.compare((Long) x[0], (Long) y[0]));
        for (Object[] entry : soon) {
            page.comingUp.add((PriorityNote) entry[1]);
        }
    }

    /** New models as dismissible rows: the model's name, then the provider it was matched for. */
    static void addModels(PriorityPage page, List<ModelDiscovery.Entry> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        page.models.addAll(PriorityNote.fromModels(items));
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
