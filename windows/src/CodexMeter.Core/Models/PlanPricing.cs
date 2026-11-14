using System.Globalization;

namespace CodexMeter.Core.Models;

/// <summary>One sourced figure: a month of the plan's allowance spent on one model at API prices.</summary>
public sealed record PlanEstimate(string Model, double MonthlyValueUsd, string Source, string SourceUrl, string DataDate);

/// <summary>
/// Value estimates for subscription allowances, per provider, so the Estimated value card only
/// ever describes the plan the user is actually on. A port of the Android <c>PlanPricing</c>
/// (PR #229; sources and alternatives in docs/pricing-sources.md).
/// </summary>
/// <remarks>
/// Every figure is "monthly API value at full use": what a whole month of the plan's allowance
/// would cost at API list prices if it all went to one model. Where sources disagree, the newest
/// dated figure wins. A plan with no published or estimated figure keeps its card but shows the
/// value as unknown (<see cref="Unknown"/>) instead of a guess. Cursor reports its own figure
/// (<c>planUsage.limit</c>). Weekly figures divide the month into four windows and the 5-hour
/// figure is one sixth of a week. Every number is an estimate, not a billing statement.
/// </remarks>
public sealed record PlanPricing(string PlanKey, string PlanLabel, double MonthlyPriceUsd, double MonthlyValueUsd,
    string Basis, bool Reported, IReadOnlyList<PlanEstimate> ByModel)
{
    private const double FiveHourShareOfWeekly = 1d / 6d;

    /// <summary>Value unknown: no published or estimated figure for the plan.</summary>
    public const double Unknown = double.NaN;

    public const string BenchLeader = "BenchLeader";
    public const string BenchLeaderUrl = "https://www.benchleader.com/coding-plans";
    public const string Holter = "Adam Holter";
    public const string HolterUrl = "https://x.com/AdamHoltererer/status/2115827734223974762";
    public const string CursorBasis = "Cursor's own figure for the usage your plan includes at API prices; not a billing statement.";

    private static PlanEstimate Bench(string model, double value, string date) => new(model, value, BenchLeader, BenchLeaderUrl, date);

    /// <summary>Holter gives dollars of API usage per $1 of plan; this is that ratio times the price.</summary>
    private static PlanEstimate HolterRatio(string model, double perDollar, double price) =>
        new(model, perDollar * price, Holter, HolterUrl, "2026-10-29");

    /// <summary>A plan priced from sourced estimates; the first one is the headline.</summary>
    private static PlanPricing Estimated(string key, string label, double price, params PlanEstimate[] estimates)
    {
        var head = estimates[0];
        var basis = $"Estimate: {head.Model} at full use, from {head.Source} (data {DisplayDate(head.DataDate)}). "
            + "Model rows assume all usage on one model. Not a billing statement.";
        return new PlanPricing(key, label, price, head.MonthlyValueUsd, basis, false, estimates);
    }

    /// <summary>A plan with no published or estimated figure: the card says so instead of guessing.</summary>
    private static PlanPricing UnknownPlan(string key, string label, double price, string why) =>
        new(key, label, price, Unknown, why + " Not a billing statement.", false, []);

    /// <summary>Whether there is a dollar figure to show; false for plans whose value is unknown.</summary>
    public bool HasValue => !double.IsNaN(MonthlyValueUsd) && MonthlyValueUsd > 0;

    /// <summary>Pricing for the plan a provider's snapshot reports, or null when there is no plan to describe.</summary>
    public static PlanPricing? ForProvider(string? providerId, UsageSnapshot? snapshot)
    {
        if (providerId is null || snapshot is null) return null;
        return providerId switch
        {
            "codex" => ForCodexPlan(snapshot.PlanType),
            "claude_code" => ForClaudePlan(snapshot.PlanType),
            "cursor" => ForCursor(snapshot),
            _ => null,
        };
    }

    /// <summary>
    /// Codex (ChatGPT) pricing for a raw plan type, or null for plans with no allowance to price.
    /// OpenAI sells Pro at $100, $200 and $500; "prolite" (the former Pro 5x) is Pro 100 and "pro"
    /// (the former Pro 20x) is Pro 200.
    /// </summary>
    public static PlanPricing? ForCodexPlan(string? planType) =>
        planType?.Trim().ToLowerInvariant().Replace("_", "").Replace("-", "").Replace(" ", "") switch
        {
            "plus" => Estimated("plus", "Plus", 20,
                Bench("GPT-6.1 Sol", 299, "2026-11-09"),
                HolterRatio("GPT-6 Astra", 32, 20),
                HolterRatio("GPT-6 Luna", 19, 20),
                HolterRatio("GPT-6.1 Terra", 18, 20),
                Bench("GPT-6 Sol", 323, "2026-09-24")),
            "prolite" or "pro5x" or "pro100" => UnknownPlan("pro100", "Pro 100", 100,
                "No published estimate for Pro 100 yet (checked Nov 14, 2026)."),
            "pro" or "pro20x" or "pro200" => Estimated("pro200", "Pro 200", 200,
                Bench("GPT-6 Astra", 5604, "2026-09-21")),
            "pro500" => UnknownPlan("pro500", "Pro 500", 500,
                "No published estimate for Pro 500 yet (checked Nov 14, 2026)."),
            _ => null,
        };

    /// <summary>Claude plan badge ("Pro", "Max", "Ultra", or a saved "Max 5x"/"Max 20x").</summary>
    public static PlanPricing? ForClaudePlan(string? planLabel) =>
        planLabel?.Trim().ToLowerInvariant().Replace("claude", "").Replace(" ", "").Replace("_", "").Replace("-", "") switch
        {
            "pro" => Estimated("claude_pro", "Claude Pro", 20,
                Bench("Opus 5.5", 1200, "2026-11-07"),
                Bench("Sonnet 5.5", 1203, "2026-09-27"),
                Bench("Haiku 5.5", 877, "2026-09-27")),
            "max" or "max5x" => Estimated("claude_max", "Claude Max", 100,
                Bench("Opus 5.5", 7224, "2026-10-03"),
                Bench("Sonnet 5.5", 7224, "2026-10-03"),
                Bench("Haiku 5.5", 5263, "2026-10-03")),
            // Holter 2026-10-29 (on Max 20x, now Ultra) is newer than BenchLeader's Ultra rows.
            "ultra" or "max20x" => Estimated("claude_ultra", "Claude Ultra", 200,
                HolterRatio("Opus 5.5", 66, 200),
                HolterRatio("Sonnet 5.5", 66, 200),
                HolterRatio("Haiku 5.5", 47, 200),
                HolterRatio("Fable 5.1", 6.4, 200)),
            // Free has no allowance worth pricing; Team and Enterprise are priced per seat.
            _ => null,
        };

    /// <summary>Cursor pricing from the included usage Cursor reports (weekly pools count four weeks a month).</summary>
    public static PlanPricing? ForCursor(UsageSnapshot? snapshot)
    {
        if (snapshot?.IncludedValueUsd is not > 0 || snapshot.IncludedValueUsd is not { } included) return null;
        var weekly = snapshot.Weekly is not null && snapshot.Monthly is null;
        var label = snapshot.PlanType.Trim();
        return new PlanPricing("cursor_" + label.ToLowerInvariant().Replace(' ', '_'), label.Length == 0 ? "Cursor" : "Cursor " + label,
            CursorMonthlyPrice(label), weekly ? included * 4 : included, CursorBasis, true, []);
    }

    /// <summary>List prices of Cursor's individual plans; 0 when unknown (the multiple is then hidden).</summary>
    public static double CursorMonthlyPrice(string? planLabel) => planLabel?.Trim().ToLowerInvariant() switch
    {
        "plus" => 8,
        "pro" => 20,
        "premium" => 30,
        "pro+" => 60,
        "super" => 100,
        "ultra" => 200,
        _ => 0,
    };

    /// <summary>"2026-11-07" to "Nov 7, 2026".</summary>
    public static string DisplayDate(string iso) =>
        DateTime.TryParseExact(iso, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date)
            ? date.ToString("MMM d, yyyy", CultureInfo.InvariantCulture)
            : iso;

    public double WeeklyValueUsd => MonthlyValueUsd / 4;
    public double FiveHourValueUsd => WeeklyValueUsd * FiveHourShareOfWeekly;
    public double ValueMultiplier => MonthlyPriceUsd > 0 ? MonthlyValueUsd / MonthlyPriceUsd : 0;

    public double WindowValueUsd(UsageHistoryKind kind) => kind switch
    {
        UsageHistoryKind.Monthly => MonthlyValueUsd,
        UsageHistoryKind.Weekly => WeeklyValueUsd,
        _ => FiveHourValueUsd,
    };

    public double EstimatedValueUsd(UsageHistoryKind kind, double usedPercent) =>
        WindowValueUsd(kind) * Math.Clamp(usedPercent, 0, 100) / 100;

    /// <summary>"Claude Pro · $20/month", or just the plan when its price is unknown.</summary>
    public string Title => MonthlyPriceUsd > 0 ? $"{PlanLabel} · {FormatUsd(MonthlyPriceUsd)}/month" : PlanLabel;

    /// <summary>Label and value rows for the card, only for the windows this snapshot has.</summary>
    public List<(string Label, string Value)> SummaryRows(UsageSnapshot? snapshot)
    {
        var rows = new List<(string, string)>();
        if (!HasValue)
        {
            rows.Add(("Est. included usage", "Unknown"));
            return rows;
        }
        var weeklyCycle = snapshot is { Weekly: not null, Monthly: null };
        if (Reported && weeklyCycle)
        {
            rows.Add(("Included usage", FormatUsd(WeeklyValueUsd) + "/week"));
        }
        else
        {
            rows.Add((Reported ? "Included usage" : "Est. included usage", (Reported ? "" : "≈ ") + FormatUsd(MonthlyValueUsd) + "/month"));
            if (snapshot?.Weekly is not null) rows.Add(("Weekly allowance", "≈ " + FormatUsd(WeeklyValueUsd)));
        }
        if (snapshot?.FiveHour is not null) rows.Add(("5-hour allowance", "≈ " + FormatUsd(FiveHourValueUsd)));
        if (MonthlyPriceUsd > 0) rows.Add(("Vs. subscription price", $"≈ {Math.Round(ValueMultiplier):0}x the monthly cost"));
        if (snapshot?.LongWindow is { } window)
        {
            var monthly = snapshot.LongWindowIsMonthly;
            var kind = monthly ? UsageHistoryKind.Monthly : UsageHistoryKind.Weekly;
            rows.Add(($"Value used this {(monthly ? "month" : "week")}", "≈ " + FormatUsd(EstimatedValueUsd(kind, window.UsedPercent))));
            rows.Add(($"{(monthly ? "Monthly" : "Weekly")} value remaining", "≈ " + FormatUsd(EstimatedValueUsd(kind, window.RemainingPercent))));
        }
        if (ByModel.Count > 1)
            foreach (var estimate in ByModel) rows.Add(($"All on {estimate.Model}", "≈ " + FormatUsd(estimate.MonthlyValueUsd) + "/month"));
        return rows;
    }

    /// <summary>Compact USD: $3,500 · $29 · $4.20 · $0.35.</summary>
    public static string FormatUsd(double value)
    {
        var amount = Math.Max(0, value);
        return amount >= 10
            ? "$" + Math.Round(amount).ToString("#,0", CultureInfo.InvariantCulture)
            : "$" + amount.ToString("0.00", CultureInfo.InvariantCulture);
    }
}
