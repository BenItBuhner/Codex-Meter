namespace CodexMeter.Core.Models;

/// <summary>
/// Pure planner: an unused Codex reset credit is used automatically a chosen lead time before it
/// expires, instead of being lost. Port of iOS's <c>CreditExpiryAutoApply</c>.
/// </summary>
public sealed record CreditExpiryAutoApply(string CreditId, DateTimeOffset ExpiresAt, DateTimeOffset TriggerAt)
{
    public static IReadOnlyList<int> LeadMinuteOptions { get; } = [1, 15, 30, 60, 120, 360, 720, 1440];
    public const int DefaultLeadMinutes = 30;

    public string Token => $"{CreditId}:{ExpiresAt.ToUnixTimeSeconds()}";

    public static int NormalizedLeadMinutes(int minutes) =>
        minutes <= 0 ? DefaultLeadMinutes : LeadMinuteOptions.MinBy(option => Math.Abs(option - minutes));

    public static TimeSpan LeadInterval(int minutes) => TimeSpan.FromMinutes(NormalizedLeadMinutes(minutes));

    /// <summary>"1 minute", "30 minutes", "1 hour", "24 hours".</summary>
    public static string LeadLabel(int minutes)
    {
        var value = NormalizedLeadMinutes(minutes);
        if (value < 60) return value == 1 ? "1 minute" : $"{value} minutes";
        var hours = value / 60;
        return hours == 1 ? "1 hour" : $"{hours} hours";
    }

    public static List<CreditExpiryAutoApply> Plan(IEnumerable<RateLimitResetCredit> credits, DateTimeOffset now, int leadMinutes) =>
        credits.Where(c => c.IsAvailable && c.ExpiresAt is { } e && e > now)
            .Select(c => new CreditExpiryAutoApply(c.Id, c.ExpiresAt!.Value, c.ExpiresAt!.Value - LeadInterval(leadMinutes)))
            .OrderBy(p => p.TriggerAt).ThenBy(p => p.ExpiresAt).ThenBy(p => p.CreditId, StringComparer.Ordinal)
            .ToList();

    public static List<CreditExpiryAutoApply> Due(IEnumerable<RateLimitResetCredit> credits, DateTimeOffset now, int leadMinutes) =>
        Plan(credits, now, leadMinutes).Where(p => IsDue(p.ExpiresAt, now, leadMinutes)).ToList();

    public static bool IsDue(DateTimeOffset expiresAt, DateTimeOffset now, int leadMinutes) =>
        expiresAt > now && expiresAt - now <= LeadInterval(leadMinutes);
}

/// <summary>Which windows the low-usage rule watches.</summary>
public enum LowUsageAutoUseMetric { Both, Weekly, FiveHour }

public enum LowUsageOutcome { Fire, Wait, SkipNoCredit, SkipResetImminent, SkipAlreadyUsed }

/// <summary>
/// Pure decision: use one banked Codex reset when a window's remaining allowance drops to the
/// chosen threshold. Fires at most once per window, never from a stale snapshot, and never when
/// that window refills on its own within 15 minutes. Port of iOS's <c>LowUsageAutoUse</c>.
/// </summary>
public sealed record LowUsageAutoUse(LowUsageOutcome Outcome, string WindowLabel, int RemainingPercent, string WindowToken)
{
    public static IReadOnlyList<int> ThresholdOptions { get; } = [0, 1, 2, 5, 10];
    public const int DefaultThreshold = 1;
    public static readonly TimeSpan ImminentReset = TimeSpan.FromMinutes(15);
    public static readonly TimeSpan MaxSnapshotAge = TimeSpan.FromMinutes(2);

    public bool Fires => Outcome == LowUsageOutcome.Fire;

    public static int NormalizedThreshold(int value) => ThresholdOptions.Contains(value) ? value : DefaultThreshold;

    private static readonly LowUsageAutoUse Waiting = new(LowUsageOutcome.Wait, "", -1, "");

    public static LowUsageAutoUse Evaluate(UsageSnapshot? snapshot, LowUsageAutoUseMetric metric, int threshold, int? availableCredits,
        string? lastFiredToken, DateTimeOffset now)
    {
        if (snapshot is null || now - snapshot.FetchedAt > MaxSnapshotAge) return Waiting;
        var limit = NormalizedThreshold(threshold);
        var candidates = new List<(UsageWindow Window, string Label)>();
        if (metric != LowUsageAutoUseMetric.Weekly && Current(snapshot.FiveHour, snapshot, now) is { } five) candidates.Add((five, "5-hour"));
        if (metric != LowUsageAutoUseMetric.FiveHour && Current(snapshot.LongWindow, snapshot, now) is { } longWindow)
            candidates.Add((longWindow, snapshot.LongWindowIsMonthly ? "monthly" : "weekly"));
        var low = candidates.Where(c => c.Window.RemainingPercent <= limit).OrderBy(c => c.Window.RemainingPercent).ToList();
        if (low.Count == 0) return Waiting;
        var (window, label) = low[0];
        var resetAt = window.EffectiveResetDate(snapshot.FetchedAt);
        var token = $"{label}:{resetAt?.ToUnixTimeMilliseconds() ?? 0}";
        if (SameWindow(lastFiredToken, label, resetAt, window))
            return new(LowUsageOutcome.SkipAlreadyUsed, label, window.RemainingPercent, token);
        if (availableCredits == 0) return new(LowUsageOutcome.SkipNoCredit, label, window.RemainingPercent, token);
        if (resetAt is { } reset && reset > now && reset - now <= ImminentReset)
            return new(LowUsageOutcome.SkipResetImminent, label, window.RemainingPercent, token);
        return new(LowUsageOutcome.Fire, label, window.RemainingPercent, token);
    }

    private static UsageWindow? Current(UsageWindow? window, UsageSnapshot snapshot, DateTimeOffset now) =>
        window is null || window.EffectiveResetDate(snapshot.FetchedAt) is { } reset && reset <= now ? null : window;

    private static bool SameWindow(string? lastToken, string label, DateTimeOffset? resetAt, UsageWindow window)
    {
        if (lastToken is null) return false;
        var split = lastToken.LastIndexOf(':');
        if (split < 0 || lastToken[..split] != label || !long.TryParse(lastToken[(split + 1)..], out var millis)) return false;
        if (resetAt is null || millis <= 0) return resetAt is null && millis == 0;
        return (DateTimeOffset.FromUnixTimeMilliseconds(millis) - resetAt.Value).Duration() <= UsageHistory.ResetTolerance(window.WindowSeconds);
    }
}
