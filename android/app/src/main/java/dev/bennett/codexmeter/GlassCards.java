package dev.bennett.codexmeter;

import android.content.Intent;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Card;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Liquid Glass dashboard's content: the same cards, in the same order and with the same rules
 * as {@link MainActivity}'s One UI dashboard, as data for {@link dev.bennett.codexmeter.glass.CodexGlass}.
 */
final class GlassCards {
    private GlassCards() {}

    static GlassModel.Dashboard build(MainActivity activity, UsageProvider provider) {
        GlassModel.Dashboard d = new GlassModel.Dashboard();
        d.title = provider.displayName;
        GitHubRelease update = UpdatePreferences.availableUpdate(activity);
        if (update != null) {
            d.cards.add(update(activity, update));
        }
        ProviderStatus status = ProviderStatusStore.load(activity, provider);
        if (status != null && status.shouldShowCard()) {
            // Official incidents only; "status unknown" and our own failed requests stay out.
            Card s = new Card(Card.Kind.NOTE);
            s.detail = "Service status";
            s.title = status.headline(provider.displayName);
            String checked = "From the official status page · " + UsageFormat.updated(
                    status.checkedAtMillis, System.currentTimeMillis()).replace("Updated", "checked");
            s.summary = status.detail().isEmpty() ? checked : status.detail() + "\n" + checked;
            d.cards.add(s);
        }
        boolean signedIn = SecureTokenStore.isSignedIn(activity, provider);
        if (!signedIn) {
            d.cards.add(signIn(activity, provider));
            return d;
        }
        UsageSnapshot snapshot = provider == UsageProvider.CODEX
                ? AppPreferences.loadSnapshot(activity)
                : ProviderPreferences.loadSnapshot(activity, provider);
        long now = System.currentTimeMillis();
        if (snapshot != null) {
            d.subtitle = UsageFormat.updated(snapshot.fetchedAtMillis, now);
        }
        String error = provider == UsageProvider.CODEX ? "" : ProviderPreferences.getLastError(activity, provider);
        if (error != null && !error.isEmpty()) {
            Card e = new Card(Card.Kind.ERROR);
            e.title = error;
            d.cards.add(e);
        }
        if (provider == UsageProvider.CODEX) {
            codex(activity, snapshot, now, d.cards);
        } else {
            other(activity, provider, snapshot, now, d.cards);
        }
        return d;
    }

    private static void codex(MainActivity a, UsageSnapshot snapshot, long now, List<Card> out) {
        Map<String, List<UsageLimit>> limitsByKey = new LinkedHashMap<>();
        List<String> available = new ArrayList<>();
        if (snapshot != null) {
            for (UsageLimit limit : snapshot.additionalLimits) {
                String key = DashboardSections.limitKey(limit);
                List<UsageLimit> group = limitsByKey.get(key);
                if (group == null) {
                    group = new ArrayList<>();
                    limitsByKey.put(key, group);
                }
                group.add(limit);
            }
            if (AppPreferences.showDashboardFiveHour(a) && snapshot.fiveHour != null) {
                available.add(DashboardSections.FIVE_HOUR);
            }
            if (AppPreferences.showDashboardWeekly(a) && snapshot.weekly != null) {
                available.add(DashboardSections.WEEKLY);
            }
            if (AppPreferences.showDashboardMonthly(a) && snapshot.monthly != null) {
                available.add(DashboardSections.MONTHLY);
            }
            if (AppPreferences.showDashboardSpendControl(a) && snapshot.spendControl != null) {
                available.add(DashboardSections.SPEND_CONTROL);
            }
            if (AppPreferences.showDashboardAdditionalLimits(a)) {
                for (String key : limitsByKey.keySet()) {
                    if (!AppPreferences.isDashboardSectionHidden(a, key)) {
                        available.add(key);
                    }
                }
            }
            if (AppPreferences.showDashboardUsageCredits(a) && snapshot.usageCredits != null
                    && snapshot.usageCredits.shouldDisplay()) {
                available.add(DashboardSections.USAGE_CREDITS);
            }
            if (AppPreferences.showDashboardUsageHistory(a) && snapshot.fetchedAtMillis > 0L
                    && (snapshot.fiveHour != null || snapshot.weekly != null || snapshot.monthly != null)) {
                available.add(DashboardSections.USAGE_HISTORY);
            }
        }
        if (AppPreferences.showDashboardResetCredits(a) && a.shouldShowResetCreditsCard(snapshot)) {
            available.add(DashboardSections.RESET_CREDITS);
        }
        for (String key : DashboardSections.resolveOrder(AppPreferences.getDashboardOrder(a), available)) {
            if (DashboardSections.FIVE_HOUR.equals(key)) {
                out.add(meter(a, UsageProvider.CODEX, "5-hour", snapshot, snapshot.fiveHour, now));
            } else if (DashboardSections.WEEKLY.equals(key)) {
                out.add(meter(a, UsageProvider.CODEX, "Weekly", snapshot, snapshot.weekly, now));
            } else if (DashboardSections.MONTHLY.equals(key)) {
                out.add(meter(a, UsageProvider.CODEX, "Monthly", snapshot, snapshot.monthly, now));
            } else if (DashboardSections.SPEND_CONTROL.equals(key)) {
                out.add(spendControl(a, snapshot, now));
            } else if (DashboardSections.USAGE_CREDITS.equals(key)) {
                out.add(credits(MainActivity.usageCreditBalance(snapshot.usageCredits),
                        MainActivity.usageCreditsSummary(snapshot.usageCredits), "Usage credits"));
            } else if (DashboardSections.USAGE_HISTORY.equals(key)) {
                out.add(history(a, UsageProvider.CODEX, snapshot));
            } else if (DashboardSections.RESET_CREDITS.equals(key)) {
                out.add(resetCredits(a, UsageProvider.CODEX, now));
            } else {
                List<UsageLimit> group = limitsByKey.get(key);
                if (group == null) {
                    continue;
                }
                for (UsageLimit limit : group) {
                    if (limit.primary != null) {
                        out.add(meter(a, UsageProvider.CODEX, MainActivity.limitTitle(limit) + " · "
                                + MainActivity.cadenceLabel(limit.primary), snapshot, limit.primary, now));
                    }
                    if (limit.secondary != null) {
                        out.add(meter(a, UsageProvider.CODEX, MainActivity.limitTitle(limit) + " · "
                                + MainActivity.cadenceLabel(limit.secondary), snapshot, limit.secondary, now));
                    }
                }
            }
        }
        if (out.isEmpty()) {
            Card empty = new Card(Card.Kind.MESSAGE);
            empty.title = "No dashboard items are available. Refresh usage, or choose items in "
                    + "Settings, Refresh & dashboard, Edit dashboard.";
            out.add(empty);
        }
    }

    private static void other(MainActivity a, UsageProvider target, UsageSnapshot snapshot, long now,
            List<Card> out) {
        boolean anyUsage = false;
        if (snapshot != null) {
            Map<String, UsageLimit> limitsByKey = new LinkedHashMap<>();
            for (UsageLimit limit : snapshot.additionalLimits) {
                limitsByKey.put(DashboardSections.limitKey(limit), limit);
            }
            List<String> available = new ArrayList<>();
            List<String> sections = new ArrayList<>(DashboardSections.providerSections(snapshot, null));
            ClaudeSavedResets savedResets = target == UsageProvider.CLAUDE_CODE
                    ? ProviderPreferences.loadSavedResets(a, target) : null;
            if (savedResets != null && savedResets.shouldDisplay(now)) {
                sections.add(DashboardSections.RESET_CREDITS);
            }
            for (String key : sections) {
                if (DashboardSections.USAGE_HISTORY.equals(key)
                        && (snapshot.fetchedAtMillis <= 0L || (snapshot.fiveHour == null
                                && snapshot.weekly == null && snapshot.monthly == null))) {
                    continue;
                }
                if (ProviderPreferences.isDashboardSectionVisible(a, target, key)) {
                    available.add(key);
                }
            }
            anyUsage = snapshot.fiveHour != null || snapshot.weekly != null
                    || snapshot.monthly != null || !snapshot.additionalLimits.isEmpty()
                    || (snapshot.usageCredits != null && snapshot.usageCredits.shouldDisplay());
            for (String key : DashboardSections.resolveOrder(
                    ProviderPreferences.getDashboardOrder(a, target), available)) {
                if (DashboardSections.FIVE_HOUR.equals(key)) {
                    out.add(meter(a, target, MainActivity.cadenceLabel(snapshot.fiveHour), snapshot,
                            snapshot.fiveHour, now));
                } else if (DashboardSections.WEEKLY.equals(key)) {
                    out.add(meter(a, target, "Weekly", snapshot, snapshot.weekly, now));
                } else if (DashboardSections.MONTHLY.equals(key)) {
                    out.add(meter(a, target, "Monthly", snapshot, snapshot.monthly, now));
                } else if (DashboardSections.USAGE_HISTORY.equals(key)) {
                    out.add(history(a, target, snapshot));
                } else if (DashboardSections.RESET_CREDITS.equals(key)) {
                    out.add(resetCredits(a, target, now));
                } else {
                    UsageLimit limit = limitsByKey.get(key);
                    if (limit == null) {
                        continue;
                    }
                    boolean cadence = target != UsageProvider.CURSOR;
                    if (limit.primary != null) {
                        out.add(meter(a, target, cadence ? MainActivity.limitTitle(limit) + " · "
                                + MainActivity.cadenceLabel(limit.primary) : limit.displayName(),
                                snapshot, limit.primary, now));
                    }
                    if (limit.secondary != null) {
                        out.add(meter(a, target, cadence ? MainActivity.limitTitle(limit) + " · "
                                + MainActivity.cadenceLabel(limit.secondary) : limit.displayName(),
                                snapshot, limit.secondary, now));
                    }
                }
            }
        }
        if (!anyUsage) {
            Card empty = new Card(Card.Kind.MESSAGE);
            empty.title = "Refresh to load your " + target.displayName + " usage.";
            out.add(empty);
        }
        if (snapshot != null && snapshot.usageCredits != null && snapshot.usageCredits.shouldDisplay()) {
            UsageCredits credits = snapshot.usageCredits;
            out.add(credits(credits.unlimited ? "Unlimited" : credits.balance,
                    "Credit balance on your " + target.vendorName + " account", "Balance"));
        }
    }

    /** A usage window, as the One UI wave card shows it. */
    private static Card meter(MainActivity a, UsageProvider provider, String label,
            UsageSnapshot snapshot, UsageWindow window, long now) {
        Card c = new Card(Card.Kind.METER);
        int percent = window.remainingPercent();
        c.title = label;
        c.value = percent + "%";
        c.remaining = percent / 100.0;
        boolean daily = window.windowSeconds >= 86_400L;
        c.icon = daily ? "calendar" : "clock";
        c.color = daily ? "#5856d6" : "#007aff";
        String reset = UsageFormat.reset(a, window, WidgetOptions.RESET_RELATIVE,
                snapshot.fetchedAtMillis, now);
        c.detail = reset.isEmpty() ? null : "Resets " + reset;
        UsagePace.Assessment pace = UsagePacePreferences.assess(a, provider, snapshot, window, now);
        String estimate = UsageFormat.estimatedRemaining(pace);
        c.fast = pace != null && pace.accelerated;
        String left = estimate.replace("Est. ", "");
        if (c.fast) {
            c.paceLabel = "Running out early";
            c.pace = left.isEmpty() ? null : left + " left";
            if (c.pace == null) {
                c.summary = c.paceLabel + ".";
                c.paceLabel = null;
            }
        } else if (!left.isEmpty()) {
            c.paceLabel = "At this pace it lasts";
            c.pace = left;
        }
        return c;
    }

    private static Card spendControl(MainActivity a, UsageSnapshot snapshot, long now) {
        SpendControl control = snapshot.spendControl;
        Card c = new Card(Card.Kind.METER);
        c.title = "Monthly credit limit";
        int remaining = control.effectiveRemainingPercent();
        c.remaining = Math.max(0, remaining) / 100.0;
        c.value = remaining >= 0 ? remaining + "%" : UsageFormat.spendControlUsage(a, control);
        c.icon = "creditcard";
        c.color = "#34c759";
        String footer = control.sourceLabel();
        if (control.showsResetCountdown()) {
            footer += " · " + UsageFormat.resetAt(a, control.effectiveResetAtMillis(snapshot.fetchedAtMillis),
                    WidgetOptions.RESET_BOTH, now);
        }
        c.detail = UsageFormat.spendControlPercentUsed(control);
        if (c.detail.isEmpty()) {
            c.detail = null;
        }
        c.summary = UsageFormat.spendControlRemaining(a, control) + "\n" + footer;
        c.fast = control.reached;
        return c;
    }

    private static Card credits(String value, String summary, String title) {
        Card c = new Card(Card.Kind.BALANCE);
        c.title = title;
        c.value = value;
        c.summary = summary;
        c.icon = "creditcard";
        c.color = "#34c759";
        return c;
    }

    private static Card resetCredits(MainActivity a, UsageProvider target, long now) {
        boolean claude = target == UsageProvider.CLAUDE_CODE;
        boolean signedIn = SecureTokenStore.isSignedIn(a, target);
        ClaudeSavedResets saved = claude ? ProviderPreferences.loadSavedResets(a, target) : null;
        ResetCreditsSnapshot credits = claude
                ? (saved == null ? null : saved.toResetCreditsSnapshot(now))
                : AppPreferences.loadResetCredits(a);
        int available = credits == null ? 0 : credits.availableCount;
        long nextExpiry = credits == null ? 0L : credits.nextExpiryMillis(now);
        Card c = new Card(Card.Kind.LINK);
        c.title = "Reset credits";
        c.value = !signedIn ? null : available == 1 ? "1 available" : available + " available";
        c.summary = a.resetCreditsSummary(signedIn, available, nextExpiry, now);
        c.icon = "bolt";
        c.color = "#ff9500";
        if (signedIn) {
            c.onPress = () -> a.startActivity(ResetCreditActivity.intent(a, target));
        }
        return c;
    }

    /** The current window's samples as bars: how much of the window was used over time. */
    private static Card history(MainActivity a, UsageProvider target, UsageSnapshot snapshot) {
        Card c = new Card(Card.Kind.HISTORY);
        c.title = "Usage history";
        String kind = snapshot.weekly != null ? UsageHistory.WEEKLY
                : snapshot.fiveHour != null ? UsageHistory.FIVE_HOUR : UsageHistory.MONTHLY;
        String label = UsageHistory.WEEKLY.equals(kind) ? "weekly"
                : UsageHistory.FIVE_HOUR.equals(kind) ? "5-hour" : "monthly";
        List<UsageSample> samples = ProviderPreferences.loadUsageHistory(a, target, kind)
                .currentWindowSamples();
        int n = Math.min(24, samples.size());
        double[] bars = new double[Math.max(1, n)];
        for (int i = 0; i < n; i++) {
            int index = samples.size() <= 24 ? i : (int) Math.round(i * (samples.size() - 1) / 23.0);
            bars[i] = Math.max(0.02, Math.min(1, samples.get(index).usedPercent / 100.0));
        }
        c.bars = bars;
        c.startLabel = n > 0 ? "Window start" : "";
        c.endLabel = n > 0 ? "Now" : "";
        c.value = label.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + label.substring(1);
        c.color = "#007aff";
        GlassApp glass = GlassApp.of(a);
        if (glass != null) {
            c.onPress = () -> glass.openPage(GlassScreens.PREFIX + "history:" + target.id, null);
        }
        c.summary = n > 0
                ? "How much of the " + label + " window was used, from its start until now."
                : "Charts appear once " + target.vendorName + " has reported a few refreshes.";
        return c;
    }

    private static Card update(MainActivity a, GitHubRelease release) {
        boolean returnToStable = UpdateChannel.isReturnToStable(release,
                UpdatePreferences.installedVersion(a));
        Card c = new Card(Card.Kind.UPDATE);
        c.title = returnToStable ? "Return to Codex Meter " + release.version
                : "Codex Meter " + release.version + " is ready";
        c.summary = returnToStable
                ? "You are back on the stable channel. The stable APK installs in place over this alpha build after checksum verification."
                : release.prerelease
                        ? "A signed alpha release is available. The APK will be checksum-verified before Android asks you to approve installation."
                        : "A signed GitHub release is available. The APK will be checksum-verified before Android asks you to approve installation.";
        c.button = "Review update";
        c.onPress = () -> a.startActivity(new Intent(a, UpdateActivity.class)
                .putExtra(UpdateActivity.EXTRA_VERSION, release.version));
        return c;
    }

    private static Card signIn(MainActivity a, UsageProvider target) {
        Card c = new Card(Card.Kind.SIGN_IN);
        c.title = target.displayName;
        c.icon = "provider_" + target.id;
        c.color = "#1c1c1e";
        if (target == UsageProvider.CODEX) {
            c.summary = "See your Codex 5-hour and weekly limits, reset credits and usage credits. "
                    + "Sign in with the ChatGPT account you use in Codex. Your tokens stay encrypted on this device.";
            c.button = ProviderPreferences.isSignInPending(a, target) ? "Continue sign-in" : "Sign in with ChatGPT";
        } else {
            String limits = target == UsageProvider.CURSOR
                    ? " usage and when it refreshes, plus any extra spend on top of your plan."
                    : target == UsageProvider.CLAUDE_CODE
                            ? " 5-hour and weekly limits, plus any model-only allocation your plan has."
                            : ": " + ProviderCatalog.get(target).meters.toLowerCase(Locale.ROOT) + ".";
            c.summary = "See your " + target.displayName + limits + " Sign in with the "
                    + target.accountName + " account you use in " + target.displayName
                    + ". Your tokens stay encrypted on this device.";
            c.button = ProviderPreferences.isSignInPending(a, target) ? "Continue sign-in" : target.signInLabel();
        }
        c.onPress = () -> a.startOrContinueSignIn(target);
        return c;
    }
}
