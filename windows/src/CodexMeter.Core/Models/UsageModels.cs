using System.Globalization;
using System.Text.Json.Serialization;

namespace CodexMeter.Core.Models;

/// <summary>One rate-limit window, e.g. the 5-hour or weekly allowance. Mirrors iOS <c>UsageWindow</c>.</summary>
public sealed record UsageWindow
{
    [JsonConstructor]
    public UsageWindow(int usedPercent, long windowSeconds, long resetAfterSeconds = 0, DateTimeOffset? resetAt = null)
    {
        UsedPercent = Math.Clamp(usedPercent, 0, 100);
        WindowSeconds = Math.Max(0, windowSeconds);
        ResetAfterSeconds = Math.Max(0, resetAfterSeconds);
        ResetAt = resetAt is { } date && date.ToUnixTimeSeconds() > 0 ? date : null;
    }

    public int UsedPercent { get; init; }
    public long WindowSeconds { get; init; }
    public long ResetAfterSeconds { get; init; }
    public DateTimeOffset? ResetAt { get; init; }

    [JsonIgnore] public int RemainingPercent => Math.Clamp(100 - UsedPercent, 0, 100);

    /// <summary>Whether the service reported a reset timeline for this window.</summary>
    [JsonIgnore] public bool ShowsResetCountdown => ResetAt is not null || ResetAfterSeconds > 0;

    public DateTimeOffset? EffectiveResetDate(DateTimeOffset reference) =>
        ResetAt ?? (ResetAfterSeconds > 0 ? reference.AddSeconds(ResetAfterSeconds) : null);

    /// <summary>Same window with <see cref="ResetAt"/> pinned so cached copies stay correct.</summary>
    public UsageWindow Resolved(DateTimeOffset fetchedAt) =>
        new(UsedPercent, WindowSeconds, ResetAfterSeconds, EffectiveResetDate(fetchedAt));
}

/// <summary>An extra metered limit: a model-only allocation, a Cursor pool, extra usage, ...</summary>
public sealed record UsageLimit
{
    [JsonConstructor]
    public UsageLimit(string id, string name = "", string meteredFeature = "", bool allowed = true,
        bool limitReached = false, UsageWindow? primary = null, UsageWindow? secondary = null)
    {
        Id = (id ?? "").Trim();
        Name = (name ?? "").Trim();
        MeteredFeature = (meteredFeature ?? "").Trim();
        Allowed = allowed;
        LimitReached = limitReached;
        Primary = primary;
        Secondary = secondary;
    }

    public string Id { get; init; }
    public string Name { get; init; }
    public string MeteredFeature { get; init; }
    public bool Allowed { get; init; }
    public bool LimitReached { get; init; }
    public UsageWindow? Primary { get; init; }
    public UsageWindow? Secondary { get; init; }

    [JsonIgnore]
    public string DisplayName
    {
        get
        {
            if (Name.Length > 0) return Name;
            if (MeteredFeature.Length > 0)
            {
                var spaced = MeteredFeature.Replace('_', ' ').Replace('-', ' ');
                return CultureInfo.InvariantCulture.TextInfo.ToTitleCase(spaced.ToLowerInvariant());
            }
            return "Additional usage";
        }
    }
}

/// <summary>Purchased Codex usage credits (<c>credits</c>).</summary>
public sealed record UsageCredits
{
    [JsonConstructor]
    public UsageCredits(bool hasCredits, bool unlimited, string? balance)
    {
        HasCredits = hasCredits;
        Unlimited = unlimited;
        var clean = balance?.Trim();
        Balance = (hasCredits || unlimited) && !string.IsNullOrEmpty(clean) ? clean : null;
    }

    public bool HasCredits { get; init; }
    public bool Unlimited { get; init; }
    public string? Balance { get; init; }

    [JsonIgnore]
    public decimal? NumericBalance => Amounts.Parse(Balance);

    /// <summary>Unlimited and unreadable balances show; zero and near-zero balances stay hidden.</summary>
    [JsonIgnore]
    public bool ShouldDisplay
    {
        get
        {
            if (Unlimited) return true;
            if (!HasCredits) return false;
            if (Balance is null) return true;
            return NumericBalance is not { } value || value >= 0.005m;
        }
    }
}

/// <summary>The member's monthly credit allocation under workspace spend controls.</summary>
public sealed record SpendControlLimit
{
    [JsonConstructor]
    public SpendControlLimit(string source, string? limit, string? used, string? remaining, int usedPercent,
        long resetAfterSeconds = 0, DateTimeOffset? resetAt = null)
    {
        Source = (source ?? "").Trim();
        Limit = Amounts.Clean(limit);
        Used = Amounts.Clean(used);
        Remaining = Amounts.Clean(remaining);
        UsedPercent = Math.Clamp(usedPercent, 0, 100);
        ResetAfterSeconds = Math.Max(0, resetAfterSeconds);
        ResetAt = resetAt is { } date && date.ToUnixTimeSeconds() > 0 ? date : null;
    }

    public string Source { get; init; }
    public string? Limit { get; init; }
    public string? Used { get; init; }
    public string? Remaining { get; init; }
    public int UsedPercent { get; init; }
    public long ResetAfterSeconds { get; init; }
    public DateTimeOffset? ResetAt { get; init; }

    [JsonIgnore] public int RemainingPercent => Math.Clamp(100 - UsedPercent, 0, 100);
    [JsonIgnore] public decimal? NumericLimit => Amounts.Parse(Limit);
    [JsonIgnore] public decimal? NumericUsed => Amounts.Parse(Used);

    /// <summary>Reported remaining credits, or limit - used. Never negative.</summary>
    [JsonIgnore]
    public decimal? NumericRemaining
    {
        get
        {
            if (Amounts.Parse(Remaining) is { } remaining) return Math.Max(0, remaining);
            if (NumericLimit is { } limit && NumericUsed is { } used) return Math.Max(0, limit - used);
            return null;
        }
    }

    [JsonIgnore] public bool ShowsResetCountdown => ResetAt is not null || ResetAfterSeconds > 0;

    public DateTimeOffset? EffectiveResetDate(DateTimeOffset reference) =>
        ResetAt ?? (ResetAfterSeconds > 0 ? reference.AddSeconds(ResetAfterSeconds) : null);
}

public sealed record SpendControl([property: JsonPropertyName("reached")] bool Reached,
    [property: JsonPropertyName("individualLimit")] SpendControlLimit? IndividualLimit);

/// <summary>Everything one refresh learned about a provider's usage. Mirrors iOS <c>UsageSnapshot</c>.</summary>
public sealed record UsageSnapshot
{
    [JsonConstructor]
    public UsageSnapshot(string planType, bool allowed, bool limitReached, UsageWindow? fiveHour, UsageWindow? weekly,
        UsageWindow? monthly, int? resetCreditsAvailable, IReadOnlyList<UsageLimit>? additionalLimits,
        UsageCredits? usageCredits, SpendControl? spendControl, DateTimeOffset fetchedAt)
    {
        PlanType = planType ?? "";
        Allowed = allowed;
        LimitReached = limitReached;
        FiveHour = fiveHour;
        Weekly = weekly;
        Monthly = monthly;
        ResetCreditsAvailable = resetCreditsAvailable is { } count ? Math.Max(0, count) : null;
        AdditionalLimits = (additionalLimits ?? []).Where(l => l.Primary is not null || l.Secondary is not null).ToList();
        UsageCredits = usageCredits;
        SpendControl = spendControl;
        FetchedAt = fetchedAt;
    }

    public string PlanType { get; init; }
    public bool Allowed { get; init; }
    public bool LimitReached { get; init; }
    public UsageWindow? FiveHour { get; init; }
    public UsageWindow? Weekly { get; init; }
    /// <summary>~30-day window; reported instead of 5-hour/weekly on the Codex Free tier.</summary>
    public UsageWindow? Monthly { get; init; }
    public int? ResetCreditsAvailable { get; init; }
    public IReadOnlyList<UsageLimit> AdditionalLimits { get; init; }
    public UsageCredits? UsageCredits { get; init; }
    public SpendControl? SpendControl { get; init; }
    public DateTimeOffset FetchedAt { get; init; }

    [JsonIgnore] public SpendControlLimit? SpendControlLimit => SpendControl?.IndividualLimit;

    /// <summary>Weekly when present, otherwise the monthly window.</summary>
    [JsonIgnore] public UsageWindow? LongWindow => Weekly ?? Monthly;

    [JsonIgnore] public bool LongWindowIsMonthly => Weekly is null && Monthly is not null;

    [JsonIgnore]
    public bool HasDisplayableData =>
        FiveHour is not null || Weekly is not null || Monthly is not null || AdditionalLimits.Count > 0
        || UsageCredits?.ShouldDisplay == true || ResetCreditsAvailable is not null || SpendControlLimit is not null;

    /// <summary>The next usage-window reset. The spend-control billing boundary is excluded.</summary>
    public DateTimeOffset? NextReset(DateTimeOffset after)
    {
        var windows = new[] { FiveHour, Weekly, Monthly }
            .Concat(AdditionalLimits.SelectMany(l => new[] { l.Primary, l.Secondary }));
        DateTimeOffset? best = null;
        foreach (var window in windows)
        {
            if (window?.EffectiveResetDate(FetchedAt) is { } reset && reset > after && (best is null || reset < best))
                best = reset;
        }
        return best;
    }

    /// <summary>Copy with every relative reset pinned to an absolute date.</summary>
    public UsageSnapshot Resolved() => this with
    {
        FiveHour = FiveHour?.Resolved(FetchedAt),
        Weekly = Weekly?.Resolved(FetchedAt),
        Monthly = Monthly?.Resolved(FetchedAt),
        AdditionalLimits = AdditionalLimits.Select(l => new UsageLimit(l.Id, l.Name, l.MeteredFeature, l.Allowed,
            l.LimitReached, l.Primary?.Resolved(FetchedAt), l.Secondary?.Resolved(FetchedAt))).ToList(),
        SpendControl = SpendControl is { IndividualLimit: { } limit } control
            ? control with
            {
                IndividualLimit = new SpendControlLimit(limit.Source, limit.Limit, limit.Used, limit.Remaining,
                    limit.UsedPercent, limit.ResetAfterSeconds, limit.EffectiveResetDate(FetchedAt)),
            }
            : SpendControl,
    };

    public UsageSnapshot WithResetCredits(int count) => this with { ResetCreditsAvailable = Math.Max(0, count) };
}

/// <summary>One banked Codex rate-limit reset.</summary>
public sealed record RateLimitResetCredit
{
    public const string AvailableStatus = "available";

    [JsonConstructor]
    public RateLimitResetCredit(string id, string resetType, string status, DateTimeOffset? grantedAt = null,
        DateTimeOffset? expiresAt = null, string title = "", string description = "")
    {
        Id = id ?? "";
        ResetType = resetType ?? "";
        Status = status ?? "";
        GrantedAt = grantedAt is { } g && g.ToUnixTimeSeconds() > 0 ? g : null;
        ExpiresAt = expiresAt is { } e && e.ToUnixTimeSeconds() > 0 ? e : null;
        Title = title ?? "";
        Description = description ?? "";
    }

    public string Id { get; init; }
    public string ResetType { get; init; }
    public string Status { get; init; }
    public DateTimeOffset? GrantedAt { get; init; }
    public DateTimeOffset? ExpiresAt { get; init; }
    public string Title { get; init; }
    public string Description { get; init; }

    [JsonIgnore]
    public bool IsAvailable => string.Equals(Status, AvailableStatus, StringComparison.OrdinalIgnoreCase);
}

public sealed record ResetCreditsSnapshot
{
    [JsonConstructor]
    public ResetCreditsSnapshot(int availableCount, IReadOnlyList<RateLimitResetCredit>? credits, DateTimeOffset fetchedAt)
    {
        AvailableCount = Math.Max(0, availableCount);
        Credits = credits ?? [];
        FetchedAt = fetchedAt;
    }

    public int AvailableCount { get; init; }
    public IReadOnlyList<RateLimitResetCredit> Credits { get; init; }
    public DateTimeOffset FetchedAt { get; init; }

    /// <summary>The available, unexpired credit that expires first (credits without expiry last).</summary>
    public RateLimitResetCredit? NextExpiringAvailable(DateTimeOffset at)
    {
        RateLimitResetCredit? best = null;
        foreach (var credit in Credits.Where(c => c.IsAvailable))
        {
            if (credit.ExpiresAt is { } expiry && expiry <= at) continue;
            if (best is null) { best = credit; continue; }
            if (credit.ExpiresAt is { } e && (best.ExpiresAt is null || e < best.ExpiresAt)) best = credit;
        }
        return best;
    }
}

public enum ResetConsumeOutcome { Reset, NothingToReset, NoCredit, AlreadyRedeemed, Unknown }

public sealed record ResetConsumeResult(ResetConsumeOutcome Outcome, int WindowsReset, string? RefreshWarning = null)
{
    public static ResetConsumeOutcome ParseOutcome(string? code) => code switch
    {
        "reset" => ResetConsumeOutcome.Reset,
        "nothing_to_reset" => ResetConsumeOutcome.NothingToReset,
        "no_credit" => ResetConsumeOutcome.NoCredit,
        "already_redeemed" => ResetConsumeOutcome.AlreadyRedeemed,
        _ => ResetConsumeOutcome.Unknown,
    };

    public bool Applied => Outcome == ResetConsumeOutcome.Reset;

    public string UserMessage => Outcome switch
    {
        ResetConsumeOutcome.Reset => (WindowsReset > 0
            ? $"Reset applied to {WindowsReset} usage window{(WindowsReset == 1 ? "." : "s.")}"
            : "Codex usage reset applied.") + (RefreshWarning is null ? "" : " " + RefreshWarning),
        ResetConsumeOutcome.NothingToReset => "There is no used Codex allowance to reset right now.",
        ResetConsumeOutcome.NoCredit => "No reset credit is currently available.",
        ResetConsumeOutcome.AlreadyRedeemed => "That reset request was already redeemed.",
        _ => "OpenAI returned an unrecognized reset result.",
    };
}

internal static class Amounts
{
    public static string? Clean(string? value)
    {
        var trimmed = value?.Trim();
        return string.IsNullOrEmpty(trimmed) ? null : trimmed;
    }

    public static decimal? Parse(string? value)
    {
        if (value is null) return null;
        return decimal.TryParse(value.Replace(",", ""), NumberStyles.Number | NumberStyles.AllowExponent,
            CultureInfo.InvariantCulture, out var parsed) ? parsed : null;
    }
}
