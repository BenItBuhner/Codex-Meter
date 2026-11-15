package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Value estimates for subscription allowances, per provider, so the Estimated value card only
 * ever describes the plan the user is actually on.
 *
 * <p>Every figure below is "monthly API value at full use": what a whole month of the plan's
 * allowance would cost at API list prices if it all went to one model. Each one carries its
 * source URL and data date, and docs/pricing-sources.md keeps the alternatives. Where sources
 * disagree, the newest dated figure wins. A plan with no published or estimated figure keeps its
 * card but shows the value as unknown instead of a guess.
 *
 * <p>Cursor reports its own figure: {@code planUsage.limit} is the usage the plan includes per
 * cycle at API prices ({@link UsageSnapshot#includedValueUsd}), so no estimate is needed.
 *
 * <p>Weekly figures divide the month into four allowance windows, and the 5-hour figure uses a
 * rough one-sixth-of-weekly burst heuristic. Every number here is an estimate, not a billing
 * statement.
 */
public final class PlanPricing {
    /** Rough share of a weekly allowance available inside one 5-hour burst window. */
    private static final double FIVE_HOUR_SHARE_OF_WEEKLY = 1d / 6d;
    /** Provider ids, matching UsageProvider.id in the app. */
    public static final String CODEX = "codex";
    public static final String CLAUDE_CODE = "claude_code";
    public static final String CURSOR = "cursor";

    /** Value unknown: no published or estimated figure for the plan. */
    public static final double UNKNOWN = Double.NaN;

    // Sources. Data dates are per figure, on each Estimate below.
    /** BenchLeader, "What an AI coding subscription is worth" (page data as of 2026-11-14). */
    static final String BENCHLEADER = "BenchLeader";
    static final String BENCHLEADER_URL = "https://www.benchleader.com/coding-plans";
    /** Adam Holter, "Subscription tier list: how much API usage $1 of plan buys" (2026-10-29). */
    static final String HOLTER = "Adam Holter";
    static final String HOLTER_URL = "https://x.com/AdamHoltererer/status/2115827734223974762";
    /** OpenAI's plan page: Pro tiers at $100, $200 and $500, weekly limits only (2026-11-14). */
    static final String OPENAI_PRICING_URL = "https://learn.chatgpt.com/docs/pricing";

    static final String CURSOR_BASIS = "Cursor's own figure for the usage your plan includes "
            + "at API prices; not a billing statement.";

    /** One sourced figure: a month of the plan's allowance spent on one model at API prices. */
    public static final class Estimate {
        public final String model;
        public final double monthlyValueUsd;
        public final String source;
        public final String sourceUrl;
        /** ISO date the figure's data is from. */
        public final String dataDate;

        Estimate(String model, double monthlyValueUsd, String source, String sourceUrl,
                String dataDate) {
            this.model = model;
            this.monthlyValueUsd = monthlyValueUsd;
            this.source = source;
            this.sourceUrl = sourceUrl;
            this.dataDate = dataDate;
        }
    }

    private static Estimate benchLeader(String model, double value, String date) {
        return new Estimate(model, value, BENCHLEADER, BENCHLEADER_URL, date);
    }

    /** Holter gives dollars of API usage per $1 of plan; this is that ratio times the price. */
    private static Estimate holter(String model, double perDollar, double price) {
        return new Estimate(model, perDollar * price, HOLTER, HOLTER_URL, "2026-10-29");
    }

    public final String planKey;
    public final String planLabel;
    public final double monthlyPriceUsd;
    /** The headline estimate, or {@link #UNKNOWN} (NaN) when there is no figure for the plan. */
    public final double monthlyValueUsd;
    /** One line under the card saying where the figures come from. */
    public final String basis;
    /** Whether {@link #monthlyValueUsd} is the provider's own number rather than an estimate. */
    public final boolean reported;
    /** Per-model figures, headline first; empty for reported or unknown plans. */
    public final List<Estimate> byModel;

    private PlanPricing(String planKey, String planLabel, double monthlyPriceUsd,
            double monthlyValueUsd, String basis, boolean reported, List<Estimate> byModel) {
        this.planKey = planKey;
        this.planLabel = planLabel;
        this.monthlyPriceUsd = monthlyPriceUsd;
        this.monthlyValueUsd = monthlyValueUsd;
        this.basis = basis;
        this.reported = reported;
        this.byModel = Collections.unmodifiableList(byModel);
    }

    /** A plan priced from sourced estimates; the first one is the headline. */
    private static PlanPricing estimated(String key, String label, double price,
            Estimate... estimates) {
        Estimate head = estimates[0];
        String basis = "Estimate: " + head.model + " at full use, from " + head.source
                + " (data " + displayDate(head.dataDate) + "). Model rows assume all usage on one "
                + "model. Not a billing statement.";
        return new PlanPricing(key, label, price, head.monthlyValueUsd, basis, false,
                Arrays.asList(estimates));
    }

    /** A plan with no published or estimated figure: the card says so instead of guessing. */
    private static PlanPricing unknown(String key, String label, double price, String why) {
        return new PlanPricing(key, label, price, UNKNOWN, why + " Not a billing statement.",
                false, new ArrayList<>());
    }

    /** Whether there is a dollar figure to show; false for plans whose value is unknown. */
    public boolean hasValue() {
        return !Double.isNaN(monthlyValueUsd) && monthlyValueUsd > 0d;
    }

    /**
     * Pricing for the plan a provider's snapshot reports, or null when there is no plan to
     * describe. Only Codex plans map to the Codex table: Claude's "Pro" is not ChatGPT Pro.
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

    /**
     * Codex (ChatGPT) pricing for a raw plan type, or null for plans with no allowance to price
     * (Free, Go, Business, Enterprise). OpenAI sells Pro at $100, $200 and $500
     * (https://learn.chatgpt.com/docs/pricing, read 2026-11-14), each with weekly limits only.
     * "prolite" (the former Pro 5x) is Pro 100 and "pro" (the former Pro 20x) is Pro 200.
     */
    public static PlanPricing forPlan(String planType) {
        if (planType == null) return null;
        String normalized = planType.trim().toLowerCase(Locale.US)
                .replace("_", "").replace("-", "").replace(" ", "");
        switch (normalized) {
            case "plus":
                // BenchLeader 2026-11-09 is the newest dated Plus figure (GPT-6.1 Sol). Holter's
                // 2026-10-29 ratios are newer for Astra, Luna and Terra; GPT-6 Sol is BenchLeader's.
                return estimated("plus", "Plus", 20d,
                        benchLeader("GPT-6.1 Sol", 299d, "2026-11-09"),
                        holter("GPT-6 Astra", 32d, 20d),
                        holter("GPT-6 Luna", 19d, 20d),
                        holter("GPT-6.1 Terra", 18d, 20d),
                        benchLeader("GPT-6 Sol", 323d, "2026-09-24"));
            case "prolite":
            case "pro5x":
            case "pro100":
                // BenchLeader: "No usable figure" (2026-11-14); OpenAI doubled the allowance on
                // 2026-10-29, so no older figure carries over either.
                return unknown("pro100", "Pro 100", 100d,
                        "No published estimate for Pro 100 yet (checked Nov 14, 2026).");
            case "pro":
            case "pro20x":
            case "pro200":
                // BenchLeader 2026-09-21, measured on the former Pro 20x tier, now Pro 200.
                return estimated("pro200", "Pro 200", 200d,
                        benchLeader("GPT-6 Astra", 5604d, "2026-09-21"));
            case "pro500":
                // BenchLeader: "No usable figure" (2026-11-14). Pro 500 adds Ultrafast.
                return unknown("pro500", "Pro 500", 500d,
                        "No published estimate for Pro 500 yet (checked Nov 14, 2026).");
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
                return estimated("claude_pro", "Claude Pro", 20d,
                        benchLeader("Opus 5.5", 1200d, "2026-11-07"),
                        benchLeader("Sonnet 5.5", 1203d, "2026-09-27"),
                        benchLeader("Haiku 5.5", 877d, "2026-09-27"));
            case "max":
            case "max5x":
                return estimated("claude_max", "Claude Max", 100d,
                        benchLeader("Opus 5.5", 7224d, "2026-10-03"),
                        benchLeader("Sonnet 5.5", 7224d, "2026-10-03"),
                        benchLeader("Haiku 5.5", 5263d, "2026-10-03"));
            case "ultra":
            case "max20x":
                // Holter 2026-10-29 (on Max 20x, now Ultra) is newer than BenchLeader's
                // 2026-09-27 Ultra rows ($12,426 Opus and Sonnet, $9,054 Haiku).
                return estimated("claude_ultra", "Claude Ultra", 200d,
                        holter("Opus 5.5", 66d, 200d),
                        holter("Sonnet 5.5", 66d, 200d),
                        holter("Haiku 5.5", 47d, 200d),
                        holter("Fable 5.1", 6.4d, 200d));
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
                monthlyValue, CURSOR_BASIS, true, new ArrayList<>());
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

    /** "2026-11-07" to "Nov 7, 2026". */
    static String displayDate(String iso) {
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
                "Nov", "Dec"};
        try {
            String[] parts = iso.split("-");
            return months[Integer.parseInt(parts[1]) - 1] + " " + Integer.parseInt(parts[2])
                    + ", " + parts[0];
        } catch (RuntimeException e) {
            return iso;
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
        if (!hasValue()) {
            rows.add(new String[] {"Est. included usage", "Unknown"});
            return rows;
        }
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
        UsageWindow longWindow = snapshot == null ? null : snapshot.longWindow();
        if (longWindow != null) {
            String kind = snapshot.longWindowIsMonthly() ? UsageHistory.MONTHLY : UsageHistory.WEEKLY;
            String period = snapshot.longWindowIsMonthly() ? "month" : "week";
            rows.add(new String[] {"Value used this " + period, "≈ " + formatUsd(
                    estimatedValueUsd(kind, longWindow.usedPercent))});
            rows.add(new String[] {(snapshot.longWindowIsMonthly() ? "Monthly" : "Weekly")
                    + " value remaining", "≈ " + formatUsd(
                    estimatedValueUsd(kind, longWindow.remainingPercent()))});
        }
        if (byModel.size() > 1) {
            for (Estimate estimate : byModel) {
                rows.add(new String[] {"All on " + estimate.model,
                        "≈ " + formatUsd(estimate.monthlyValueUsd) + "/month"});
            }
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
