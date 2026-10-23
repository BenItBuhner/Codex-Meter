using CodexMeter.Core.Models;

namespace CodexMeter.Core.Formatting;

public sealed record UsagePaceAssessment(bool IsAvailable, bool IsAccelerated, TimeSpan EstimatedRemaining,
    TimeSpan ActualRemaining, DateTimeOffset? EstimatedExhaustionAt)
{
    public static readonly UsagePaceAssessment Unavailable = new(false, false, TimeSpan.Zero, TimeSpan.Zero, null);
}

/// <summary>
/// Estimates when a window runs out at the current average pace (the iOS/Android baseline
/// assessment): "accelerated" means it would run out well before it resets.
/// </summary>
public static class UsagePace
{
    public static UsagePaceAssessment Assess(UsageWindow? window, DateTimeOffset observedAt, DateTimeOffset now)
    {
        if (window is null || window.WindowSeconds <= 0 || window.EffectiveResetDate(observedAt) is not { } resetAt
            || resetAt <= observedAt || resetAt <= now)
            return UsagePaceAssessment.Unavailable;
        var duration = (double)window.WindowSeconds;
        var windowStart = resetAt.AddSeconds(-duration);
        var elapsed = (observedAt - windowStart).TotalSeconds;
        if (elapsed <= 0 || elapsed > duration || window.UsedPercent <= 0) return UsagePaceAssessment.Unavailable;
        if (elapsed < Math.Max(5 * 60, duration / 200) || window.UsedPercent < 5) return UsagePaceAssessment.Unavailable;

        var remainingAtObservation = elapsed * (100 - window.UsedPercent) / window.UsedPercent;
        var exhaustion = observedAt.AddSeconds(remainingAtObservation);
        var actualRemaining = (resetAt - observedAt).TotalSeconds;
        var accelerated = remainingAtObservation * 100 <= actualRemaining * 75;
        return new UsagePaceAssessment(true, accelerated,
            TimeSpan.FromSeconds(Math.Max(0, (exhaustion - now).TotalSeconds)),
            TimeSpan.FromSeconds(Math.Max(0, (resetAt - now).TotalSeconds)), exhaustion);
    }

    /// <summary>Short pace line for a card, or empty when there's nothing useful to say.</summary>
    public static string Describe(UsageWindow? window, DateTimeOffset observedAt, DateTimeOffset now)
    {
        var pace = Assess(window, observedAt, now);
        if (!pace.IsAvailable) return "";
        if (window!.UsedPercent >= 100) return "";
        return pace.IsAccelerated
            ? $"At this pace, runs out in {UsageFormat.Duration(pace.EstimatedRemaining)}"
            : "On pace to last until reset";
    }
}
