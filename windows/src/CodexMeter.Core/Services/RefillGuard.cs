using CodexMeter.Core.Models;

namespace CodexMeter.Core.Services;

/// <summary>
/// Guards every provider against one-off false refills: an answer that shows a limit full again (or
/// a used-up limit with some left) well before its reset time, which the next refresh takes back.
/// Antigravity does this now and then. Such a reading is held: the app keeps showing the last real
/// numbers, records nothing and alerts nothing, and asks again after <see cref="ConfirmDelay"/>.
/// A second reading at least <see cref="MinConfirmGap"/> later that still shows the refill
/// confirms it. A refill within <see cref="OnTimeTolerance"/> of its reset time, or after it, is the
/// normal reset and is never held. Same rules as Android's RefillGuard and Apple's RefillGuard.
/// </summary>
public static class RefillGuard
{
    /// <summary>A reset this close to its scheduled time is the scheduled reset, a little early.</summary>
    public static readonly TimeSpan OnTimeTolerance = TimeSpan.FromMinutes(5);
    /// <summary>How long after a held reading the provider is asked again.</summary>
    public static readonly TimeSpan ConfirmDelay = TimeSpan.FromSeconds(45);
    /// <summary>A second suspect reading this long after the first confirms the refill.</summary>
    public static readonly TimeSpan MinConfirmGap = TimeSpan.FromSeconds(30);
    /// <summary>A hold this old confirms nothing; the next suspect reading starts a new one.</summary>
    public static readonly TimeSpan HoldExpires = TimeSpan.FromMinutes(30);

    /// <summary>What to do with a fresh reading, and the hold to keep for the next one.</summary>
    public readonly record struct Verdict(bool Hold, DateTimeOffset? HeldSince);

    /// <summary>
    /// Whether to hold <paramref name="current"/>. <paramref name="heldSince"/> is when the first held
    /// reading of this run came in, or null.
    /// </summary>
    public static Verdict Check(UsageSnapshot? previous, UsageSnapshot current, DateTimeOffset? heldSince, DateTimeOffset now)
    {
        if (!Suspect(previous, current)) return new(false, null);
        if (Confirms(heldSince, now)) return new(false, null);
        var fresh = heldSince is not { } since || since > now || now - since > HoldExpires;
        return new(true, fresh ? now : heldSince);
    }

    public static bool Confirms(DateTimeOffset? heldSince, DateTimeOffset now) =>
        heldSince is { } since && since <= now && now - since >= MinConfirmGap && now - since <= HoldExpires;

    /// <summary>True when a limit refilled well before the reset time <paramref name="previous"/> gave.</summary>
    public static bool Suspect(UsageSnapshot? previous, UsageSnapshot current)
    {
        if (previous is null) return false;
        var at = current.FetchedAt;
        if (Suspect(previous, previous.FiveHour, current.FiveHour, at)
            || Suspect(previous, previous.Weekly, current.Weekly, at)
            || Suspect(previous, previous.Monthly, current.Monthly, at)) return true;
        foreach (var now in current.AdditionalLimits)
        {
            if (now.Id.Length == 0) continue;
            var before = previous.AdditionalLimits.FirstOrDefault(l => l.Id == now.Id);
            if (before is not null && Suspect(previous, before.Primary, now.Primary, at)) return true;
        }
        return false;
    }

    public static bool Suspect(UsageSnapshot previous, UsageWindow? before, UsageWindow? after, DateTimeOffset observedAt)
    {
        if (before is null || after is null) return false;
        var refilled = before.UsedPercent > 0 && after.UsedPercent == 0;
        var back = before.WindowSeconds > 0 && before.RemainingPercent <= 0 && after.RemainingPercent > 0;
        if (!refilled && !back) return false;
        if (before.EffectiveResetDate(previous.FetchedAt) is not { } due) return back;
        return WellBeforeReset(due, observedAt);
    }

    /// <summary>True when <paramref name="observedAt"/> is more than <see cref="OnTimeTolerance"/> before <paramref name="due"/>.</summary>
    public static bool WellBeforeReset(DateTimeOffset due, DateTimeOffset observedAt) => due - OnTimeTolerance > observedAt;
}
