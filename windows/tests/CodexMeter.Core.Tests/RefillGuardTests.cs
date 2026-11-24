using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

/// <summary>Every provider: an early full refill is held until a second reading agrees; an on-time reset never is.</summary>
public class RefillGuardTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-24T12:00:00Z");

    private static UsageWindow FiveHour(int used, DateTimeOffset resetAt) => new(used, 18000, 0, resetAt);

    private static UsageSnapshot Snapshot(UsageWindow? fiveHour, DateTimeOffset at, IReadOnlyList<UsageLimit>? extra = null) =>
        new("", true, false, fiveHour, null, null, null, extra, null, null, at);

    [Fact]
    public void ResetAMinuteOrTwoEarlyIsTheScheduledReset()
    {
        var before = Snapshot(FiveHour(70, Now.AddMinutes(2)), Now.AddMinutes(-10));
        var after = Snapshot(FiveHour(0, Now.AddHours(5)), Now);
        Assert.False(RefillGuard.Suspect(before, after));
        Assert.False(RefillGuard.Check(before, after, null, Now).Hold);
        Assert.True(RefillGuard.Suspect(Snapshot(FiveHour(70, Now.AddMinutes(6)), Now.AddMinutes(-10)), after));
    }

    [Fact]
    public void EarlyFullRefillIsHeldThenConfirmed()
    {
        // Antigravity: 60% used with 3 hours to go, then one answer says nothing is used.
        var before = Snapshot(FiveHour(60, Now.AddHours(3)), Now.AddMinutes(-5));
        var full = Snapshot(FiveHour(0, Now.AddHours(5)), Now);
        var first = RefillGuard.Check(before, full, null, Now);
        Assert.True(first.Hold);
        Assert.Equal(Now, first.HeldSince);
        var soon = RefillGuard.Check(before, full, first.HeldSince, Now.AddSeconds(10));
        Assert.True(soon.Hold);
        Assert.Equal(Now, soon.HeldSince);
        var confirmed = RefillGuard.Check(before, full, first.HeldSince, Now + RefillGuard.ConfirmDelay);
        Assert.False(confirmed.Hold);
        Assert.Null(confirmed.HeldSince);
        // A forgotten hold starts over instead of confirming a much later reading.
        var stale = RefillGuard.Check(before, full, first.HeldSince, Now + RefillGuard.HoldExpires + TimeSpan.FromMinutes(1));
        Assert.True(stale.Hold);
        Assert.Equal(Now + RefillGuard.HoldExpires + TimeSpan.FromMinutes(1), stale.HeldSince);
    }

    [Fact]
    public void FlukeThatRevertsIsSavedWithNoAlert()
    {
        var before = Snapshot(FiveHour(60, Now.AddHours(3)), Now.AddMinutes(-5));
        var real = Snapshot(FiveHour(61, Now.AddHours(3)), Now.AddSeconds(45));
        var verdict = RefillGuard.Check(before, real, Now, Now.AddSeconds(45));
        Assert.False(verdict.Hold);
        Assert.Null(verdict.HeldSince);
        Assert.DoesNotContain(UsageAlerts.Evaluate(UsageProvider.Codex, before, real, 20, true, true, true, true),
            a => a.Kind == UsageAlertKind.Refill);
    }

    [Fact]
    public void UsedUpLimitBackEarlyAndModelLimitsAreHeld()
    {
        Assert.True(RefillGuard.Suspect(Snapshot(FiveHour(100, Now.AddHours(2)), Now.AddMinutes(-5)),
            Snapshot(FiveHour(30, Now.AddHours(2)), Now)));
        Assert.False(RefillGuard.Suspect(Snapshot(FiveHour(100, Now.AddMinutes(1)), Now.AddMinutes(-5)),
            Snapshot(FiveHour(30, Now.AddHours(5)), Now)));
        var model = (int used, DateTimeOffset reset) => new UsageLimit("m", "Fable only", primary: new UsageWindow(used, 604800, 0, reset));
        Assert.True(RefillGuard.Suspect(Snapshot(null, Now.AddHours(-1), [model(60, Now.AddDays(2))]),
            Snapshot(null, Now, [model(0, Now.AddDays(7))])));
        Assert.False(RefillGuard.Suspect(Snapshot(null, Now.AddHours(-1), [model(60, Now.AddMinutes(1))]),
            Snapshot(null, Now, [model(0, Now.AddDays(7))])));
    }

    [Fact]
    public void OrdinaryReadingsAreNeverHeld()
    {
        var before = Snapshot(FiveHour(60, Now.AddHours(3)), Now.AddMinutes(-5));
        Assert.False(RefillGuard.Check(before, Snapshot(FiveHour(65, Now.AddHours(3)), Now), null, Now).Hold);
        Assert.False(RefillGuard.Check(null, Snapshot(FiveHour(0, Now.AddHours(5)), Now), null, Now).Hold);
        Assert.False(RefillGuard.Check(Snapshot(FiveHour(60, Now.AddMinutes(-1)), Now.AddMinutes(-10)),
            Snapshot(FiveHour(0, Now.AddHours(5)), Now), null, Now).Hold);
    }
}
