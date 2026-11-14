using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>
/// The "usage is back" notification, a port of the Android <c>UsageRefill</c>: a limit that is
/// used up (0% left) is remembered with the time it resets, and once it resets (the reset time
/// passes, or a refresh shows some left again) it is announced once. The remembered state is
/// saved in settings so a reset that happens while the app is closed is still announced.
/// </summary>
public static class UsageRefill
{
    /// <summary>A used-up limit whose provider did not say when it resets.</summary>
    public const long UnknownReset = -1;
    private const long ToleranceSeconds = 60;

    public static string Key(UsageProvider provider, UsageHistoryKind kind) => $"{provider.Id}:{KindId(kind)}";

    /// <summary>Folds a fresh snapshot into the remembered limits; returns the new state and any "is back" alerts.</summary>
    public static (Dictionary<string, long> Armed, List<UsageAlert> Alerts) Observe(UsageProvider provider, UsageSnapshot snapshot,
        IReadOnlyDictionary<string, long> armed, DateTimeOffset now)
    {
        var state = new Dictionary<string, long>(armed);
        var alerts = new List<UsageAlert>();
        foreach (var (kind, window) in Windows(snapshot))
        {
            var key = Key(provider, kind);
            if (window is null || window.WindowSeconds <= 0) continue;
            var remembered = state.GetValueOrDefault(key);
            if (window.RemainingPercent <= 0)
            {
                var resetAt = window.EffectiveResetDate(snapshot.FetchedAt);
                if (resetAt is { } at && at <= now) continue; // older than its own reset; the next refresh says what's true
                var target = resetAt?.ToUnixTimeSeconds() ?? UnknownReset;
                if (target == remembered || (target > 0 && remembered > 0 && Math.Abs(target - remembered) < ToleranceSeconds)) continue;
                state[key] = target;
            }
            else if (remembered != 0)
            {
                state.Remove(key);
                alerts.Add(Back(provider.DisplayName, kind, window.RemainingPercent, key));
            }
        }
        return (state, alerts);
    }

    /// <summary>Remembered limits whose reset time has passed: announced as reset before the next refresh confirms it.</summary>
    public static (Dictionary<string, long> Armed, List<UsageAlert> Alerts) Due(IReadOnlyDictionary<string, long> armed,
        Func<string, UsageProvider?> provider, DateTimeOffset now)
    {
        var state = new Dictionary<string, long>(armed);
        var alerts = new List<UsageAlert>();
        foreach (var (key, resetAt) in armed)
        {
            if (resetAt <= 0 || resetAt > now.ToUnixTimeSeconds()) continue;
            var parts = key.Split(':');
            if (parts.Length != 2 || provider(parts[0]) is not { } p || ParseKind(parts[1]) is not { } kind)
            {
                state.Remove(key);
                continue;
            }
            state.Remove(key);
            alerts.Add(Back(p.DisplayName, kind, -1, key));
        }
        return (state, alerts);
    }

    public static UsageAlert Back(string provider, UsageHistoryKind kind, int left, string key)
    {
        var limit = $"Your {Label(kind).ToLowerInvariant()} limit";
        var body = left >= 0
            ? $"{limit} refilled to {left}% left. You can use {provider} again."
            : $"{limit} has reset. You can use {provider} again.";
        return new UsageAlert(UsageAlertKind.Back, $"{provider} usage is back", body, key + ":back");
    }

    private static IEnumerable<(UsageHistoryKind, UsageWindow?)> Windows(UsageSnapshot snapshot)
    {
        yield return (UsageHistoryKind.FiveHour, snapshot.FiveHour);
        yield return (UsageHistoryKind.Weekly, snapshot.Weekly);
        yield return (UsageHistoryKind.Monthly, snapshot.Monthly);
    }

    private static string Label(UsageHistoryKind kind) => kind switch
    {
        UsageHistoryKind.FiveHour => "5-hour",
        UsageHistoryKind.Weekly => "Weekly",
        _ => "Monthly",
    };

    private static string KindId(UsageHistoryKind kind) => kind switch
    {
        UsageHistoryKind.FiveHour => "five_hour",
        UsageHistoryKind.Weekly => "weekly",
        _ => "monthly",
    };

    private static UsageHistoryKind? ParseKind(string id) => id switch
    {
        "five_hour" => UsageHistoryKind.FiveHour,
        "weekly" => UsageHistoryKind.Weekly,
        "monthly" => UsageHistoryKind.Monthly,
        _ => null,
    };
}
