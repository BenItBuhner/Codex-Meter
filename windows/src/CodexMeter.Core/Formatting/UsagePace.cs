using CodexMeter.Core.Models;

namespace CodexMeter.Core.Formatting;

public sealed record UsagePaceAssessment(bool IsAvailable, bool IsAccelerated, TimeSpan EstimatedRemaining,
    TimeSpan ActualRemaining, DateTimeOffset? EstimatedExhaustionAt)
{
    public static readonly UsagePaceAssessment Unavailable = new(false, false, TimeSpan.Zero, TimeSpan.Zero, null);
}

/// <summary>
/// How readily "at this pace, runs out" warnings appear (Dashboard → Fast-usage warnings), as on
/// Android: Sensitive warns earlier on less data, Relaxed only when a window would run out in well
/// under half the time left, Off keeps the estimates without warnings.
/// </summary>
public enum PaceSensitivity { Balanced, Sensitive, Relaxed, Off }

/// <summary>
/// Estimates when a window runs out at the current average pace (the iOS/Android baseline
/// assessment): "accelerated" means it would run out well before it resets.
/// </summary>
public static class UsagePace
{
    /// <summary>The choices in the order Settings lists them.</summary>
    public static readonly PaceSensitivity[] Choices = [PaceSensitivity.Off, PaceSensitivity.Sensitive, PaceSensitivity.Balanced, PaceSensitivity.Relaxed];

    public static string Label(PaceSensitivity sensitivity) => sensitivity switch
    {
        PaceSensitivity.Off => "Off",
        PaceSensitivity.Sensitive => "Sensitive",
        PaceSensitivity.Relaxed => "Relaxed",
        _ => "Balanced",
    };

    /// <summary>Least used percent, least elapsed share of the window, and the most of the time left a run-out may cover and still warn.</summary>
    private static (int MinUsed, double MinElapsedSeconds, int MaxCoverage) Policy(PaceSensitivity sensitivity, double duration) => sensitivity switch
    {
        PaceSensitivity.Sensitive => (2, Math.Max(2 * 60, duration / 400), 100),
        PaceSensitivity.Relaxed => (10, Math.Max(10 * 60, duration / 100), 50),
        _ => (5, Math.Max(5 * 60, duration / 200), 75),
    };

    /// <param name="usageStart">When usage was reset partway through the window (a Claude Code saved
    /// reset, from <see cref="UsageHistory.UsageStart"/>): the average is measured from there instead of
    /// the window start. The reset time stays the same.</param>
    public static UsagePaceAssessment Assess(UsageWindow? window, DateTimeOffset observedAt, DateTimeOffset now,
        PaceSensitivity sensitivity = PaceSensitivity.Balanced, DateTimeOffset? usageStart = null)
    {
        if (window is null || window.WindowSeconds <= 0 || window.EffectiveResetDate(observedAt) is not { } resetAt
            || resetAt <= observedAt || resetAt <= now)
            return UsagePaceAssessment.Unavailable;
        var duration = (double)window.WindowSeconds;
        var windowStart = resetAt.AddSeconds(-duration);
        if (usageStart is { } start && start > windowStart) windowStart = start;
        var elapsed = (observedAt - windowStart).TotalSeconds;
        if (elapsed <= 0 || elapsed > duration || window.UsedPercent <= 0) return UsagePaceAssessment.Unavailable;
        var policy = Policy(sensitivity, duration);
        if (elapsed < policy.MinElapsedSeconds || window.UsedPercent < policy.MinUsed) return UsagePaceAssessment.Unavailable;

        var remainingAtObservation = elapsed * (100 - window.UsedPercent) / window.UsedPercent;
        var exhaustion = observedAt.AddSeconds(remainingAtObservation);
        var actualRemaining = (resetAt - observedAt).TotalSeconds;
        var accelerated = sensitivity != PaceSensitivity.Off && remainingAtObservation * 100 <= actualRemaining * policy.MaxCoverage;
        return new UsagePaceAssessment(true, accelerated,
            TimeSpan.FromSeconds(Math.Max(0, (exhaustion - now).TotalSeconds)),
            TimeSpan.FromSeconds(Math.Max(0, (resetAt - now).TotalSeconds)), exhaustion);
    }

    /// <summary>Short pace line for a card, or empty when there's nothing useful to say.</summary>
    public static string Describe(UsageWindow? window, DateTimeOffset observedAt, DateTimeOffset now,
        PaceSensitivity sensitivity = PaceSensitivity.Balanced, DateTimeOffset? usageStart = null)
    {
        var pace = Assess(window, observedAt, now, sensitivity, usageStart);
        if (!pace.IsAvailable) return "";
        if (window!.UsedPercent >= 100) return "";
        return pace.IsAccelerated
            ? $"At this pace, runs out in {UsageFormat.Duration(pace.EstimatedRemaining)}"
            : "On pace to last until reset";
    }
}
