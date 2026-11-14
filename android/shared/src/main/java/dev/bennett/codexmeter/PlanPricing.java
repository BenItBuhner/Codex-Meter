package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rough value estimates for subscription allowances, per provider, so the Estimated value card
 * only ever describes the plan the user is actually on.
 *
 * <p>Codex anchors: Pro 20x ($200/month) is commonly measured at roughly $14,000 of
 * equivalent API usage per month and Pro 5x ($100/month) at roughly $3,500.
 * Because OpenAI documents Pro 5x as 5x Plus usage and Pro 20x as 20x Plus,
 * Plus ($20/month) back-solves to roughly $700 of monthly usage value.
 *
 * <p>Claude anchors: SemiAnalysis (June 2026) put Claude Max 20x, now called Ultra
 * ($200/month), at roughly $8,000 of API-priced usage per month and Pro ($20/month) near $400.
 * Anthropic sells Max as 5x Pro usage and Ultra as 20x, so Max ($100/month) sits at roughly
 * $2,000, which also matches 20 x $400 for Ultra.
 *
 * <p>Cursor reports its own figure: {@code planUsage.limit} is the usage the plan includes per
 * cycle at API prices ({@link UsageSnapshot#includedValueUsd}), so no estimate is needed.
 *
 * <p>Weekly figures divide the month into four allowance windows, and the 5-hour figure uses a
 * rough one-sixth-of-weekly burst heuristic. Every number here is an estimate, not a billing
 * statement. Providers and plans without a figure get no card at all.
 */
public final class PlanPricing {
    /** Rough share of a weekly allowance available inside one 5-hour burst window. */
    private static final double FIVE_HOUR_SHARE_OF_WEEKLY = 1d / 6d;
    /** Provider ids, matching UsageProvider.id in the app. */
    public static final String CODEX = "codex";
    public static final String CLAUDE_CODE = "claude_code";
    public static final String CURSOR = "cursor";

    static final String COMMUNITY_BASIS = "Rough community estimates comparing plan allowances "
            + "with API pricing; not a billing statement.";
    static final String CLAUDE_BASIS = "Rough estimates from SemiAnalysis's June 2026 study of "
            + "Claude plans at API prices; not a billing statement.";
    static final String CURSOR_BASIS = "Cursor's own figure for the usage your plan includes "
            + "at API prices; not a billing statement.";

    public final String planKey;
    public final String planLabel;
    public final double monthlyPriceUsd;
    public final double monthlyValueUsd;
    /** One line under the card saying where the figures come from. */
    public final String basis;
    /** Whether {@link #monthlyValueUsd} is the provider's own number rather than an estimate. */
    public final boolean reported;

    private PlanPricing(String planKey, String planLabel, double monthlyPriceUsd,
            double monthlyValueUsd) {
        this(planKey, planLabel, monthlyPriceUsd, monthlyValueUsd, COMMUNITY_BASIS, false);
    }

    private PlanPricing(String planKey, String planLabel, double monthlyPriceUsd,
            double monthlyValueUsd, String basis, boolean reported) {
        this.planKey = planKey;
        this.planLabel = planLabel;
        this.monthlyPriceUsd = monthlyPriceUsd;
        this.monthlyValueUsd = monthlyValueUsd;
        this.basis = basis;
        this.reported = reported;
    }

    /**
     * Pricing for the plan a provider's snapshot reports, or null when there is no figure for
     * it. Only Codex plans map to the Codex table: Claude's "Pro" is not ChatGPT Pro 20x.
     */
    public static PlanPricing forProvider(String providerId, UsageSnapshot snapshot) {
        if (providerId == null || snapshot == null) return null;
        switch (providerId) {
            case CODEX:
                return forPlan(snapshot.planType);
            case CLAUDE_CODE:
                return forClaudePlan(snapshot.planType);
            case CURSOR:
                return forCursor(snapshot);
            default:
                return null;
        }
    }

    /** Codex (ChatGPT) pricing for a raw plan type, or null when no researched estimate exists. */
    public static PlanPricing forPlan(String planType) {
        if (planType == null) return null;
        String normalized = planType.trim().toLowerCase(Locale.US)
                .replace("_", "").replace("-", "");
        switch (normalized) {
            case "plus":
                return new PlanPricing("plus", "Plus", 20d, 700d);
            case "prolite":
            case "pro5x":
                return new PlanPricing("pro5x", "Pro 5x", 100d, 3500d);
            case "pro":
            case "pro20x":
                return new PlanPricing("pro20x", "Pro 20x", 200d, 14000d);
            default:
                return null;
        }
    }

    /** Claude plan badge ("Pro", "Max", "Ultra", or a saved "Max 5x"/"Max 20x") to pricing. */
    public static PlanPricing forClaudePlan(String planLabel) {
        if (planLabel == null) return null;
        String normalized = planLabel.trim().toLowerCase(Locale.US)
                .replace("claude", "").replace(" ", "").replace("_", "").replace("-", "");
        switch (normalized) {
            case "pro":
                return new PlanPricing("claude_pro", "Claude Pro", 20d, 400d, CLAUDE_BASIS, false);
            case "max":
            case "max5x":
                return new PlanPricing("claude_max", "Claude Max", 100d, 2000d, CLAUDE_BASIS,
                        false);
            case "ultra":
            case "max20x":
                return new PlanPricing("claude_ultra", "Claude Ultra", 200d, 8000d,
                        CLAUDE_BASIS, false);
            default:
                // Free has no allowance worth pricing; Team and Enterprise are priced per seat.
                return null;
        }
    }

    /**
     * Cursor pricing from the included usage Cursor reports. Current plans refresh that pool
     * weekly and legacy plans monthly; the month is four weeks here, as for every provider.
     */
    public static PlanPricing forCursor(UsageSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasIncludedValue()) return null;
        boolean weekly = snapshot.weekly != null && snapshot.monthly == null;
        double monthlyValue = weekly ? snapshot.includedValueUsd * 4d : snapshot.includedValueUsd;
        String label = snapshot.planType == null ? "" : snapshot.planType.trim();
        return new PlanPricing("cursor_" + label.toLowerCase(Locale.US).replace(" ", "_"),
                label.isEmpty() ? "Cursor" : "Cursor " + label, cursorMonthlyPrice(label),
                monthlyValue, CURSOR_BASIS, true);
    }

    /** List prices of Cursor's individual plans; 0 when unknown (the multiple is then hidden). */
    static double cursorMonthlyPrice(String planLabel) {
        switch (planLabel == null ? "" : planLabel.trim().toLowerCase(Locale.US)) {
            case "plus": return 8d;
            case "pro": return 20d;
            case "premium": return 30d;
            case "pro+": return 60d;
            case "super": return 100d;
            case "ultra": return 200d;
            default: return 0d;
        }
    }

    /** Estimated usage value covered by one week of the allowance (month / 4). */
    public double weeklyValueUsd() {
        return monthlyValueUsd / 4d;
    }

    /** Rough usage value available inside a single 5-hour burst window. */
    public double fiveHourValueUsd() {
        return weeklyValueUsd() * FIVE_HOUR_SHARE_OF_WEEKLY;
    }

    /** Estimated allowance value for a usage-history window kind. */
    public double windowValueUsd(String historyKind) {
        if (UsageHistory.MONTHLY.equals(historyKind)) return monthlyValueUsd;
        return UsageHistory.WEEKLY.equals(historyKind) ? weeklyValueUsd() : fiveHourValueUsd();
    }

    /** Estimated dollars burned for a used percentage of the given window kind. */
    public double estimatedValueUsd(String historyKind, double usedPercent) {
        double clamped = Math.max(0d, Math.min(100d, usedPercent));
        return windowValueUsd(historyKind) * clamped / 100d;
    }

    /** How many times the subscription price the estimated monthly value represents. */
    public double valueMultiplier() {
        if (monthlyPriceUsd <= 0d) return 0d;
        return monthlyValueUsd / monthlyPriceUsd;
    }

    /** Card title: "Claude Pro · $20/month", or just the plan when its price is unknown. */
    public String title() {
        return monthlyPriceUsd > 0d ? planLabel + " · " + formatUsd(monthlyPriceUsd) + "/month"
                : planLabel;
    }

    /**
     * Label and value rows for the Estimated value card, only for the windows this snapshot
     * has: no 5-hour row for a plan without a 5-hour limit, no multiple without a price.
     */
    public List<String[]> summaryRows(UsageSnapshot snapshot) {
        List<String[]> rows = new ArrayList<>();
        boolean weeklyCycle = snapshot != null && snapshot.weekly != null
                && snapshot.monthly == null;
        if (reported && weeklyCycle) {
            rows.add(new String[] {"Included usage", formatUsd(weeklyValueUsd()) + "/week"});
        } else {
            rows.add(new String[] {reported ? "Included usage" : "Est. included usage",
                    (reported ? "" : "≈ ") + formatUsd(monthlyValueUsd) + "/month"});
            if (snapshot != null && snapshot.weekly != null) {
                rows.add(new String[] {"Weekly allowance", "≈ " + formatUsd(weeklyValueUsd())});
            }
        }
        if (snapshot != null && snapshot.fiveHour != null) {
            rows.add(new String[] {"5-hour allowance", "≈ " + formatUsd(fiveHourValueUsd())});
        }
        if (monthlyPriceUsd > 0d) {
            rows.add(new String[] {"Vs. subscription price",
                    "≈ " + Math.round(valueMultiplier()) + "x the monthly cost"});
        }
        if (snapshot == null) return rows;
        UsageWindow longWindow = snapshot.longWindow();
        if (longWindow != null) {
            String kind = snapshot.longWindowIsMonthly() ? UsageHistory.MONTHLY : UsageHistory.WEEKLY;
            String period = snapshot.longWindowIsMonthly() ? "month" : "week";
            rows.add(new String[] {"Value used this " + period, "≈ " + formatUsd(
                    estimatedValueUsd(kind, longWindow.usedPercent))});
            rows.add(new String[] {(snapshot.longWindowIsMonthly() ? "Monthly" : "Weekly")
                    + " value remaining", "≈ " + formatUsd(
                    estimatedValueUsd(kind, longWindow.remainingPercent()))});
        }
        return rows;
    }

    /** Compact USD formatting: $3,500 · $29 · $4.20 · $0.35. */
    public static String formatUsd(double value) {
        double amount = Math.max(0d, value);
        if (amount >= 10d) {
            return String.format(Locale.US, "$%,d", Math.round(amount));
        }
        return String.format(Locale.US, "$%.2f", amount);
    }
}
