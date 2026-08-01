package dev.bennett.codexmeter;

import java.util.Locale;

/**
 * Rough, community-researched value estimates for Codex subscription allowances.
 *
 * <p>Anchors: Pro 20x ($200/month) is commonly measured at roughly $14,000 of
 * equivalent API usage per month and Pro 5x ($100/month) at roughly $3,500.
 * Because OpenAI documents Pro 5x as 5x Plus usage and Pro 20x as 20x Plus,
 * Plus ($20/month) back-solves to roughly $700 of monthly usage value. Weekly
 * figures divide the month into four allowance windows, and the 5-hour figure
 * uses a rough one-sixth-of-weekly burst heuristic. Every number here is an
 * estimate, not a billing statement.
 */
public final class PlanPricing {
    /** Rough share of a weekly allowance available inside one 5-hour burst window. */
    private static final double FIVE_HOUR_SHARE_OF_WEEKLY = 1d / 6d;

    public final String planKey;
    public final String planLabel;
    public final double monthlyPriceUsd;
    public final double monthlyValueUsd;

    private PlanPricing(String planKey, String planLabel, double monthlyPriceUsd,
            double monthlyValueUsd) {
        this.planKey = planKey;
        this.planLabel = planLabel;
        this.monthlyPriceUsd = monthlyPriceUsd;
        this.monthlyValueUsd = monthlyValueUsd;
    }

    /** Returns pricing for a raw plan type, or null when no researched estimate exists. */
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

    /** Compact USD formatting: $3,500 · $29 · $4.20 · $0.35. */
    public static String formatUsd(double value) {
        double amount = Math.max(0d, value);
        if (amount >= 10d) {
            return String.format(Locale.US, "$%,d", Math.round(amount));
        }
        return String.format(Locale.US, "$%.2f", amount);
    }
}
