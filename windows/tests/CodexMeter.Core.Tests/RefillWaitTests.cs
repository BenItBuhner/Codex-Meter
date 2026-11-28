using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

/// <summary>
/// Issue #214: how long "Use when running low" waits for a limit to refill on its own is a
/// setting, separate for the 5-hour and weekly limits, and a held-back use is shown.
/// </summary>
public class RefillWaitTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-16T12:00:00Z", System.Globalization.CultureInfo.InvariantCulture);

    private static UsageSnapshot Usage(int fiveUsed, TimeSpan fiveReset, int weeklyUsed, TimeSpan weeklyReset) =>
        new("pro", true, false, new UsageWindow(fiveUsed, 18_000, 0, Now + fiveReset),
            new UsageWindow(weeklyUsed, 604_800, 0, Now + weeklyReset), null, 2, [], null, null, Now);

    private static readonly UsageSnapshot WeeklyOutTomorrow = Usage(30, TimeSpan.FromHours(2), 100, TimeSpan.FromHours(24));
    private static readonly UsageSnapshot FiveHourOutIn90 = Usage(100, TimeSpan.FromMinutes(90), 40, TimeSpan.FromDays(3));

    private static LowUsageAutoUse Evaluate(UsageSnapshot usage, int fiveWait, int weeklyWait) =>
        LowUsageAutoUse.Evaluate(usage, LowUsageAutoUseMetric.Both, 1, 1, null, Now, fiveWait, weeklyWait);

    [Fact]
    public void TheDefaultWaitStillSpendsAResetOnAWeekThatRefillsTomorrow()
    {
        Assert.True(LowUsageAutoUse.Evaluate(WeeklyOutTomorrow, LowUsageAutoUseMetric.Both, 1, 1, null, Now).Fires);
        Assert.Equal(LowUsageOutcome.SkipResetImminent,
            LowUsageAutoUse.Evaluate(Usage(100, TimeSpan.FromMinutes(10), 40, TimeSpan.FromDays(3)), LowUsageAutoUseMetric.Both, 1, 1, null, Now).Outcome);
    }

    [Fact]
    public void AOneDayWeeklyWaitKeepsTheResetUpToAndIncludingTheBoundary()
    {
        var held = Evaluate(WeeklyOutTomorrow, 15, 1440);
        Assert.Equal(LowUsageOutcome.SkipResetImminent, held.Outcome);
        Assert.Equal("weekly", held.WindowLabel);
        Assert.Equal(Now.AddHours(24), held.ResetAt);
        Assert.True(Evaluate(WeeklyOutTomorrow, 15, 720).Fires);
    }

    [Fact]
    public void EachLimitUsesItsOwnWait()
    {
        // A long 5-hour wait doesn't hold back the weekly limit, and the reverse.
        Assert.True(Evaluate(WeeklyOutTomorrow, 240, 15).Fires);
        Assert.True(Evaluate(FiveHourOutIn90, 60, 4320).Fires);
        Assert.Equal(LowUsageOutcome.SkipResetImminent, Evaluate(FiveHourOutIn90, 120, 0).Outcome);
        Assert.Equal("5-hour", Evaluate(FiveHourOutIn90, 120, 0).WindowLabel);
    }

    [Fact]
    public void DontWaitSpendsAResetEvenAMinuteBeforeTheRefill()
    {
        Assert.True(Evaluate(Usage(100, TimeSpan.FromMinutes(1), 40, TimeSpan.FromDays(3)), 0, 0).Fires);
    }

    [Fact]
    public void AMonthlyWindowUsesTheWeeklyWait()
    {
        var monthly = new UsageSnapshot("free", true, false, null, null, new UsageWindow(100, 2_592_000, 0, Now.AddDays(2)), 1, [], null, null, Now);
        var decision = LowUsageAutoUse.Evaluate(monthly, LowUsageAutoUseMetric.Weekly, 0, 1, null, Now, 15, 2880);
        Assert.Equal("monthly", decision.WindowLabel);
        Assert.Equal(LowUsageOutcome.SkipResetImminent, decision.Outcome);
    }

    [Fact]
    public void WaitsOffTheMenuFallBackToFifteenMinutes()
    {
        Assert.Equal(1440, LowUsageAutoUse.NormalizedWeeklyWait(1440));
        Assert.Equal(15, LowUsageAutoUse.NormalizedWeeklyWait(999));
        Assert.Equal(15, LowUsageAutoUse.NormalizedFiveHourWait(1440));
        Assert.Equal(0, LowUsageAutoUse.NormalizedFiveHourWait(0));
        Assert.Equal(["Don't wait", "5 minutes", "15 minutes", "30 minutes", "1 hour", "2 hours", "3 hours", "4 hours"],
            LowUsageAutoUse.FiveHourWaitOptions.Select(LowUsageAutoUse.WaitLabel));
        Assert.Equal("12 hours", LowUsageAutoUse.WaitLabel(720));
        Assert.Equal("1 day", LowUsageAutoUse.WaitLabel(1440));
        Assert.Equal("3 days", LowUsageAutoUse.WaitLabel(4320));
    }

    [Fact]
    public void AHeldBackUseIsRememberedUntilTheLimitRefillsOrAResetIsUsed()
    {
        var wait = RefillWait.After(null, Evaluate(WeeklyOutTomorrow, 15, 1440), Now);
        Assert.Equal(new RefillWait("weekly", Now.AddHours(24), Now), wait);
        // A later refresh that just waits keeps it; one after the refill drops it.
        var waiting = LowUsageAutoUse.Evaluate(null, LowUsageAutoUseMetric.Both, 1, 1, null, Now);
        Assert.Same(wait, RefillWait.After(wait, waiting, Now.AddHours(1)));
        Assert.Null(RefillWait.After(wait, waiting, Now.AddHours(24)));
        // Spending a reset clears it.
        Assert.Null(RefillWait.After(wait, Evaluate(WeeklyOutTomorrow, 15, 15), Now));
        Assert.Equal("Your weekly limit refills on its own tomorrow at 12:00 PM, so no reset was used.",
            wait!.Summary(at => Formatting.UsageFormat.Absolute(at, Now, zone: TimeZoneInfo.Utc)));
    }

    [Fact]
    public void ClaudeHoldsASavedResetBackForARefillAndSaysSo()
    {
        var saved = ClaudeSavedResets.FromUsageBody(DesktopParityTests.SavedResetsBody, Now)!;
        var usage = new UsageSnapshot("Pro", false, true, new UsageWindow(40, 18000, 7200, null), new UsageWindow(100, 604800, 20 * 3600, null),
            null, null, null, null, null, Now);
        Assert.Equal(ClaudeAutoUseOutcome.FireLowUsage,
            ClaudeResetAutoUse.Evaluate(saved, usage, false, 30, true, 1, LowUsageAutoUseMetric.Both, null, null, Now).Outcome);
        var held = ClaudeResetAutoUse.Evaluate(saved, usage, false, 30, true, 1, LowUsageAutoUseMetric.Both, null, null, Now, 15, 1440);
        Assert.Equal(ClaudeAutoUseOutcome.SkipRefillsSoon, held.Outcome);
        Assert.False(held.Fires);
        Assert.Equal("weekly", held.WindowLabel);
        Assert.Equal(Now.AddHours(20), held.ResetAt);
        Assert.Equal(new RefillWait("weekly", Now.AddHours(20), Now), RefillWait.After(null, held, Now));
        // Waiting for a refill never blocks using a reset that is about to expire.
        Assert.Equal(ClaudeAutoUseOutcome.FireExpiring,
            ClaudeResetAutoUse.Evaluate(saved, usage, true, 30, true, 1, LowUsageAutoUseMetric.Both, null, null, Now, 15, 1440).Outcome);
    }

    [Fact]
    public void TheResetsCardSaysWhyNoResetWasUsed()
    {
        var settings = new AppSettings
        {
            AutoUseWhenLow = true,
            AutoUseThreshold = 1,
            AutoUseRefillWait = new RefillWait("weekly", Now.AddHours(20), Now),
        };
        var card = ResetCreditsCard.For(UsageProvider.Codex, null, 2, settings, Now);
        Assert.StartsWith("Auto-use is on: when a limit falls to 1% left.", card.AutoUse);
        Assert.Contains("Your weekly limit refills on its own ", card.AutoUse);
        Assert.EndsWith(", so no reset was used.", card.AutoUse);
        // Once the week has refilled, or with the rule off, it's gone.
        Assert.Equal("Auto-use is on: when a limit falls to 1% left.", ResetCreditsCard.For(UsageProvider.Codex, null, 2, settings, Now.AddHours(21)).AutoUse);
        Assert.Null(ResetCreditsCard.For(UsageProvider.Codex, null, 2, settings with { AutoUseWhenLow = false }, Now).AutoUse);
    }

    [Fact]
    public void WaitsTravelWithExportedSettingsButAHeldBackUseStaysLocal()
    {
        var settings = new AppSettings
        {
            AutoUseFiveHourWaitMinutes = 60,
            AutoUseWeeklyWaitMinutes = 4320,
            AutoUseRefillWait = new RefillWait("weekly", Now.AddHours(20), Now),
            ClaudeResets = new ClaudeResetRules { WeeklyWaitMinutes = 1440, RefillWait = new RefillWait("5-hour", Now.AddHours(1), Now) },
        };
        var file = SettingsTransfer.Export(settings, "linux", Now);
        var imported = SettingsTransfer.Import(file, new AppSettings());
        Assert.Equal(60, imported.AutoUseFiveHourWaitMinutes);
        Assert.Equal(4320, imported.AutoUseWeeklyWaitMinutes);
        Assert.Equal(1440, imported.ClaudeResets.WeeklyWaitMinutes);
        Assert.Equal(15, imported.ClaudeResets.FiveHourWaitMinutes);
        Assert.Null(imported.AutoUseRefillWait);
        Assert.Null(imported.ClaudeResets.RefillWait);
        // A hand-edited file with a wait the menus don't offer gets the default.
        var edited = file.Replace("4320", "4321", StringComparison.Ordinal);
        Assert.Equal(15, SettingsTransfer.Import(edited, new AppSettings()).AutoUseWeeklyWaitMinutes);
    }

    [Fact]
    public void SavedSettingsKeepTheWaitsAndTheHeldBackUse()
    {
        var path = Path.Combine(Path.GetTempPath(), $"codex-meter-refill-wait-{Guid.NewGuid():N}.json");
        try
        {
            var store = new SettingsStore(path);
            store.Update(s => s with
            {
                AutoUseWeeklyWaitMinutes = 1440,
                AutoUseRefillWait = new RefillWait("weekly", Now.AddHours(20), Now),
            });
            var reloaded = new SettingsStore(path).Current;
            Assert.Equal(1440, reloaded.AutoUseWeeklyWaitMinutes);
            Assert.Equal(15, reloaded.AutoUseFiveHourWaitMinutes);
            Assert.Equal(new RefillWait("weekly", Now.AddHours(20), Now), reloaded.AutoUseRefillWait);
        }
        finally
        {
            File.Delete(path);
        }
    }
}
