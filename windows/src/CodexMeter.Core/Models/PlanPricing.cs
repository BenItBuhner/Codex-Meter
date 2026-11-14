using System.Globalization;

namespace CodexMeter.Core.Models;

/// <summary>
/// Rough value estimates for subscription allowances, per provider, so the Estimated value card
/// only ever describes the plan the user is actually on. A port of the Android
/// <c>PlanPricing</c> as changed for the tester report (PR #227): Claude's "Pro" is not
/// ChatGPT Pro 20x, Cursor reports its own figure, and anything else gets no card.
/// </summary>
/// <remarks>
/// Codex anchors: Pro 20x ($200/month) is commonly measured at roughly $14,000 of equivalent API
/// usage per month and Pro 5x ($100/month) at roughly $3,500; Plus ($20/month) back-solves to
/// roughly $700. Claude anchors: SemiAnalysis (June 2026) put Ultra ($200/month) at roughly $8,000
/// and Pro ($20/month) near $400, with Max ($100/month, 5x Pro) at roughly $2,000. Cursor's
/// <c>planUsage.limit</c> is the usage the plan includes per cycle at API prices. Weekly figures
/// divide the month into four windows and the 5-hour figure is one sixth of a week. Every number
/// is an estimate, not a billing statement.
/// </remarks>
public sealed record PlanPricing(string PlanKey, string PlanLabel, double MonthlyPriceUsd, double MonthlyValueUsd,
    string Basis, bool Reported)
{
    private const double FiveHourShareOfWeekly = 1d / 6d;

    public const string CommunityBasis = "Rough community estimates comparing plan allowances with API pricing; not a billing statement.";
    public const string ClaudeBasis = "Rough estimates from SemiAnalysis's June 2026 study of Claude plans at API prices; not a billing statement.";
    public const string CursorBasis = "Cursor's own figure for the usage your plan includes at API prices; not a billing statement.";

    /// <summary>Pricing for the plan a provider's snapshot reports, or null when there is no figure for it.</summary>
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

    /// <summary>Codex (ChatGPT) pricing for a raw plan type.</summary>
    public static PlanPricing? ForCodexPlan(string? planType) =>
        planType?.Trim().ToLowerInvariant().Replace("_", "").Replace("-", "").Replace(" ", "") switch
        {
            "plus" => new("plus", "Plus", 20, 700, CommunityBasis, false),
            "prolite" or "pro5x" => new("pro5x", "Pro 5x", 100, 3500, CommunityBasis, false),
            "pro" or "pro20x" => new("pro20x", "Pro 20x", 200, 14000, CommunityBasis, false),
            _ => null,
        };

    /// <summary>Claude plan badge ("Pro", "Max", "Ultra", or a saved "Max 5x"/"Max 20x").</summary>
    public static PlanPricing? ForClaudePlan(string? planLabel) =>
        planLabel?.Trim().ToLowerInvariant().Replace("claude", "").Replace(" ", "").Replace("_", "").Replace("-", "") switch
        {
            "pro" => new("claude_pro", "Claude Pro", 20, 400, ClaudeBasis, false),
            "max" or "max5x" => new("claude_max", "Claude Max", 100, 2000, ClaudeBasis, false),
            "ultra" or "max20x" => new("claude_ultra", "Claude Ultra", 200, 8000, ClaudeBasis, false),
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
            CursorMonthlyPrice(label), weekly ? included * 4 : included, CursorBasis, true);
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
