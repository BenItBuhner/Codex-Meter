using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

public enum UsageAlertKind { Reset, Low, Exhausted }

public sealed record UsageAlert(UsageAlertKind Kind, string Title, string Body, string Key);

/// <summary>
/// Decides which desktop notifications a new snapshot deserves compared with the previous one:
/// a window that reset, a window that dropped below the low-allowance threshold, or one that ran out.
/// Pure so it can be unit tested; the app shows the alerts as Windows toasts.
/// </summary>
public static class UsageAlerts
{
    public static IReadOnlyList<UsageAlert> Evaluate(UsageProvider provider, UsageSnapshot? previous, UsageSnapshot current,
        int lowThreshold, bool notifyResets, bool notifyLow)
    {
        var alerts = new List<UsageAlert>();
        if (previous is null) return alerts;
        foreach (var (name, before, after) in Pairs(previous, current))
        {
            if (after is null || before is null) continue;
            var key = $"{provider.Id}:{name}";
            if (notifyResets && after.UsedPercent + 10 < before.UsedPercent && before.UsedPercent >= 50
                && (before.EffectiveResetDate(previous.FetchedAt) is not { } resetAt || resetAt <= current.FetchedAt.AddMinutes(15)))
            {
                alerts.Add(new UsageAlert(UsageAlertKind.Reset, $"{provider.DisplayName} {name.ToLowerInvariant()} limit reset",
                    $"You have {after.RemainingPercent}% of your {name.ToLowerInvariant()} allowance again.", key + ":reset"));
                continue;
            }
            if (!notifyLow) continue;
            if (after.RemainingPercent == 0 && before.RemainingPercent > 0)
            {
                var reset = after.EffectiveResetDate(current.FetchedAt) is { } r
                    ? " " + UsageFormat.ResetText(r, ResetDisplayMode.Relative, current.FetchedAt) + "."
                    : "";
                alerts.Add(new UsageAlert(UsageAlertKind.Exhausted, $"{provider.DisplayName} {name.ToLowerInvariant()} limit reached",
                    $"You've used all of this window.{reset}", key + ":exhausted"));
            }
            else if (after.RemainingPercent <= lowThreshold && before.RemainingPercent > lowThreshold)
            {
                alerts.Add(new UsageAlert(UsageAlertKind.Low, $"{provider.DisplayName} is running low",
                    $"{after.RemainingPercent}% of your {name.ToLowerInvariant()} allowance is left.", key + ":low"));
            }
        }
        return alerts;
    }

    private static IEnumerable<(string Name, UsageWindow? Before, UsageWindow? After)> Pairs(UsageSnapshot previous, UsageSnapshot current)
    {
        yield return ("5-hour", previous.FiveHour, current.FiveHour);
        yield return ("Weekly", previous.Weekly, current.Weekly);
        yield return ("Monthly", previous.Monthly, current.Monthly);
    }
}
