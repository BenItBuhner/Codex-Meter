using System.Globalization;
using CodexMeter.Core.Models;

namespace CodexMeter.Core.Formatting;

public enum UsageDisplayMode { Remaining, Used }

public enum ResetDisplayMode { Relative, Absolute, Both }

/// <summary>User-facing text for meters. Mirrors iOS <c>UsageFormat</c>.</summary>
public static class UsageFormat
{
    /// <summary>ChatGPT plan ids to badges; other providers already send a label.</summary>
    public static string PlanLabel(string? planType)
    {
        var normalized = (planType ?? "").Trim().ToLowerInvariant().Replace("_", "").Replace("-", "");
        return normalized switch
        {
            "free" => "Free",
            "go" => "Go",
            "plus" => "Plus",
            "prolite" or "pro5x" => "Pro 5x",
            "pro" or "pro20x" => "Pro 20x",
            "team" => "Team",
            "business" => "Business",
            "enterprise" => "Enterprise",
            "edu" => "Edu",
            _ => (planType ?? "").Trim(),
        };
    }

    public static int Value(UsageWindow window, UsageDisplayMode mode) =>
        mode == UsageDisplayMode.Used ? window.UsedPercent : window.RemainingPercent;

    public static string Percent(UsageWindow? window, UsageDisplayMode mode, bool compact = false)
    {
        if (window is null) return compact ? "—" : "Unavailable";
        var number = $"{Value(window, mode)}%";
        return compact ? number : $"{number} {(mode == UsageDisplayMode.Used ? "used" : "left")}";
    }

    /// <summary>"38% used" when showing remaining, "62% left" when showing used: the other half.</summary>
    public static string Complement(UsageWindow window, UsageDisplayMode mode) =>
        mode == UsageDisplayMode.Used ? $"{window.RemainingPercent}% left" : $"{window.UsedPercent}% used";

    public static string Reset(UsageWindow? window, ResetDisplayMode mode, DateTimeOffset fetchedAt, DateTimeOffset now,
        bool use24Hour = false, TimeZoneInfo? zone = null)
    {
        if (window is null || !window.ShowsResetCountdown) return "";
        if (window.EffectiveResetDate(fetchedAt) is not { } resetAt) return "Reset time unavailable";
        return ResetText(resetAt, mode, now, use24Hour, zone);
    }

    public static string ResetText(DateTimeOffset resetAt, ResetDisplayMode mode, DateTimeOffset now, bool use24Hour = false,
        TimeZoneInfo? zone = null)
    {
        var relative = Relative(resetAt, now);
        if (resetAt <= now) return "Resets now";
        var absolute = Absolute(resetAt, now, use24Hour, zone);
        return mode switch
        {
            ResetDisplayMode.Absolute => $"Resets {absolute}",
            ResetDisplayMode.Both => $"Resets {absolute} ({relative})",
            _ => $"Resets {relative}",
        };
    }

    public static string Absolute(DateTimeOffset date, DateTimeOffset now, bool use24Hour = false, TimeZoneInfo? zone = null)
    {
        zone ??= TimeZoneInfo.Local;
        var local = TimeZoneInfo.ConvertTime(date, zone);
        var today = TimeZoneInfo.ConvertTime(now, zone).Date;
        var prefix = local.Date == today ? "today at"
            : local.Date == today.AddDays(1) ? "tomorrow at"
            : local.ToString("ddd, MMM d", CultureInfo.InvariantCulture) + " at";
        return $"{prefix} {local.ToString(use24Hour ? "HH:mm" : "h:mm tt", CultureInfo.InvariantCulture)}";
    }

    public static string Relative(DateTimeOffset date, DateTimeOffset now)
    {
        var totalMinutes = (long)(Math.Max(0, (date - now).TotalSeconds) / 60);
        var days = totalMinutes / 1440;
        var hours = totalMinutes % 1440 / 60;
        var minutes = totalMinutes % 60;
        if (days > 0) return $"in {days}d {hours}h";
        if (hours > 0) return $"in {hours}h {minutes}m";
        return totalMinutes > 0 ? $"in {totalMinutes}m" : "now";
    }

    /// <summary>"2h 15m" style duration for pace estimates.</summary>
    public static string Duration(TimeSpan span)
    {
        var totalMinutes = (long)Math.Max(0, span.TotalMinutes);
        var days = totalMinutes / 1440;
        var hours = totalMinutes % 1440 / 60;
        var minutes = totalMinutes % 60;
        if (days > 0) return $"{days}d {hours}h";
        if (hours > 0) return $"{hours}h {minutes}m";
        return $"{Math.Max(1, minutes)}m";
    }

    public static string Updated(DateTimeOffset? fetchedAt, DateTimeOffset now)
    {
        if (fetchedAt is not { } fetched) return "Not updated yet";
        var minutes = (long)(Math.Max(0, (now - fetched).TotalSeconds) / 60);
        if (minutes < 1) return "Updated just now";
        if (minutes < 60) return $"Updated {minutes}m ago";
        var hours = minutes / 60;
        return hours < 24 ? $"Updated {hours}h ago" : $"Updated {hours / 24}d ago";
    }

    /// <summary>"5-hour", "Weekly", "Monthly", or "30-day" for odd lengths.</summary>
    public static string WindowName(long seconds) => seconds switch
    {
        >= 10_800 and <= 28_800 => "5-hour",
        >= 432_000 and <= 864_000 => "Weekly",
        >= 864_000 => "Monthly",
        >= 86_400 => $"{seconds / 86_400}-day",
        _ => $"{Math.Max(1, seconds / 3600)}-hour",
    };

    public static string CreditAmount(decimal value) =>
        value.ToString("#,0.##", CultureInfo.InvariantCulture);

    public static string? SpendControlUsage(SpendControlLimit limit) =>
        limit.NumericUsed is { } used && limit.NumericLimit is { } total
            ? $"{CreditAmount(used)} of {CreditAmount(total)} credits used" : null;

    public static string? SpendControlRemaining(SpendControlLimit limit) =>
        limit.NumericRemaining is { } remaining
            ? $"{CreditAmount(remaining)} {(remaining == 1 ? "credit" : "credits")} remaining" : null;
}
