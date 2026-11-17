using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

public class HistoryAndSectionsTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.FromUnixTimeSeconds(1_790_000_000);

    [Fact]
    public void HistoryAppendsMergesAndSplitsWindows()
    {
        var reset = Now.AddHours(3);
        var history = UsageHistory.Empty(UsageHistoryKind.FiveHour)
            .Appending(new UsageWindow(10, 18_000, 0, reset), Now)
            .Appending(new UsageWindow(10, 18_000, 0, reset.AddSeconds(30)), Now.AddMinutes(2)) // same value, too soon: replaces
            .Appending(new UsageWindow(20, 18_000, 0, reset), Now.AddMinutes(30))
            .Appending(new UsageWindow(5, 18_000, 0, reset.AddHours(5)), reset.AddMinutes(10));
        Assert.Equal(3, history.Samples.Count);
        Assert.Equal(1, history.CompletedWindowCount);
        Assert.Single(history.CurrentWindowSamples);
        Assert.Equal(2, history.RecentWindows(5).Count);
        // Older observations are ignored.
        Assert.Same(history, history.Appending(new UsageWindow(50, 18_000, 0, reset), Now));
    }

    [Fact]
    public void SavedResetRestartsPaceAndKeepsEarlierBurn()
    {
        // Claude Code saved resets drop usage to zero without moving the weekly reset.
        const long week = 7 * 24 * 3600;
        var start = Now;
        var weeklyReset = start.AddDays(7);
        var history = UsageHistory.Empty(UsageHistoryKind.Weekly);
        for (var h = 6; h <= 66; h += 6)
            history = history.Appending(new UsageWindow(h * 70 / 66, week, 0, weeklyReset), start.AddHours(h));
        var resetSampleAt = start.AddHours(67);
        history = history.Appending(new UsageWindow(0, week, 0, weeklyReset), resetSampleAt);
        Assert.Equal(resetSampleAt, history.InWindowResetAt);
        history = history.Appending(new UsageWindow(5, week, 0, weeklyReset), resetSampleAt.AddHours(1));
        var now = resetSampleAt.AddHours(2);
        var afterReset = new UsageWindow(10, week, 0, weeklyReset);
        history = history.Appending(afterReset, now);

        var wholeWeek = UsagePace.Assess(afterReset, now, now);
        Assert.True(wholeWeek.EstimatedRemaining > TimeSpan.FromDays(20));
        var sinceReset = UsagePace.Assess(afterReset, now, now, PaceSensitivity.Balanced, history.UsageStart(afterReset, now));
        Assert.True(sinceReset.IsAvailable);
        Assert.True(sinceReset.IsAccelerated);
        Assert.InRange(sinceReset.EstimatedRemaining, TimeSpan.FromHours(17), TimeSpan.FromHours(19));
        // A sample from another window gives no restart point.
        Assert.Null(history.UsageStart(new UsageWindow(10, week, 0, weeklyReset.AddDays(7)), now));

        // The drop does not cancel out the burn before it.
        var stats = UsageStats.Breakdown(history, 1).Single();
        // 6% to 70%, then 0% to 10%: 74 points over 63 hours, not the 4-point net change.
        Assert.Equal(74 / 63.0, stats.AverageBurnPercentPerHour, 3);
    }

    [Fact]
    public void StatsAndInterpolation()
    {
        var reset = Now.AddHours(5);
        UsageSample S(double hours, int percent, DateTimeOffset r) => new(Now.AddHours(hours), percent, r, 18_000);
        var history = new UsageHistory(UsageHistoryKind.FiveHour, [
            S(-10, 0, reset.AddHours(-10)), S(-7.5, 50, reset.AddHours(-10)), S(-5.5, 80, reset.AddHours(-10)),
            S(-5, 0, reset.AddHours(-5)), S(-2.5, 30, reset.AddHours(-5)), S(-0.5, 60, reset.AddHours(-5)),
            S(0, 0, reset), S(1, 20, reset),
        ]);
        var breakdown = UsageStats.Breakdown(history, 10);
        Assert.Equal(3, breakdown.Count);
        Assert.True(breakdown[0].Complete);
        Assert.False(breakdown[^1].Complete);
        Assert.Equal(20, breakdown[0].PeakBurnPercentPerHour, 3);
        Assert.Equal(70, UsageStats.AverageFinalPercent(history));
        Assert.Equal(40, UsageStats.TypicalUsedPercent(history, 0.5)!.Value, 3);
        Assert.Equal(25, UsageStats.UsedPercentAt(Now.AddHours(-8.75), history.Samples)!.Value, 3);
        Assert.Null(UsageStats.UsedPercentAt(Now.AddHours(2), history.CurrentWindowSamples));
        Assert.Equal("20%/h", UsageStats.FormatRate(20));
        Assert.Equal("2.5%/h", UsageStats.FormatRate(2.5));
    }

    [Fact]
    public async Task HistoryStoreRoundTrips()
    {
        var directory = Path.Combine(Path.GetTempPath(), "cm-history-" + Guid.NewGuid());
        try
        {
            var store = new UsageHistoryStore(directory);
            await store.RecordAsync("codex", DemoData.Snapshot(UsageProvider.Codex, Now));
            var reloaded = await new UsageHistoryStore(directory).LoadAsync("codex");
            Assert.Single(reloaded.FiveHour.Samples);
            Assert.Single(reloaded.Weekly.Samples);
            await store.ClearAsync("codex");
            Assert.True((await new UsageHistoryStore(directory).LoadAsync("codex")).IsEmpty);
        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }

    [Fact]
    public void DemoHistoryCoversEveryWindow()
    {
        foreach (var provider in UsageProvider.All.Where(p => p.IsAvailable))
        {
            var snapshot = DemoData.Snapshot(provider, Now);
            var history = DemoData.History(provider, Now);
            Assert.Equal(snapshot.FiveHour is not null, history.FiveHour.Samples.Count > 0);
            Assert.Equal(snapshot.Weekly is not null, history.Weekly.Samples.Count > 0);
            if (snapshot.FiveHour is { } five)
            {
                Assert.Equal(five.UsedPercent, history.FiveHour.Samples[^1].UsedPercent);
                Assert.True(history.FiveHour.CompletedWindowCount >= 3, provider.Id);
            }
        }
    }

    [Fact]
    public void DashboardSectionsOrder()
    {
        var snapshot = DemoData.Snapshot(UsageProvider.Codex, Now);
        var available = DashboardSections.Available(snapshot, hasResetCredits: true);
        Assert.Equal(["five_hour", "weekly", "limit:codex_bengalfox-0", "usage_credits", "usage_history", "reset_credits"], available);
        // A saved order keeps its order; a new card lands after its natural predecessor.
        Assert.Equal(["weekly", "five_hour", "limit:codex_bengalfox-0", "usage_history", "usage_credits", "reset_credits"],
            DashboardSections.ResolveOrder(["weekly", "five_hour", "usage_history", "usage_credits", "gone"], available));
        Assert.Equal(["weekly", "limit:codex_bengalfox-0", "usage_credits", "reset_credits", "five_hour"],
            DashboardSections.Visible(snapshot, true, ["weekly", "limit:codex_bengalfox-0", "usage_credits", "usage_history", "reset_credits", "five_hour"], ["usage_history"]));
        Assert.Equal("5-hour limit", DashboardSections.Label("five_hour", snapshot));
        Assert.Equal("GPT-5.3-Codex-Spark", DashboardSections.Label("limit:codex_bengalfox-0", snapshot));
    }

    [Fact]
    public void HistoryHighlightOverrides()
    {
        Assert.False(HistorySections.IsVisible([], HistorySections.Guide));
        Assert.True(HistorySections.IsVisible([], HistorySections.InsightPeak));
        var overrides = HistorySections.SetVisible([], HistorySections.Guide, true);
        overrides = HistorySections.SetVisible(overrides, HistorySections.InsightPeak, false);
        Assert.Equal(["guide", "insight_peak"], overrides);
        Assert.Empty(HistorySections.SetVisible(HistorySections.SetVisible(overrides, "guide", false), "insight_peak", true));
    }

    [Fact]
    public void AutoUseRules()
    {
        var credits = new[]
        {
            new RateLimitResetCredit("a", "full", "available", Now.AddDays(-3), Now.AddMinutes(20)),
            new RateLimitResetCredit("b", "full", "available", Now.AddDays(-3), Now.AddDays(4)),
            new RateLimitResetCredit("c", "full", "redeemed", Now.AddDays(-3), Now.AddMinutes(5)),
        };
        Assert.Equal(["a"], CreditExpiryAutoApply.Due(credits, Now, 30).Select(p => p.CreditId));
        Assert.Equal(["a", "b"], CreditExpiryAutoApply.Plan(credits, Now, 30).Select(p => p.CreditId));
        Assert.Equal(60, CreditExpiryAutoApply.NormalizedLeadMinutes(50));
        Assert.Equal("24 hours", CreditExpiryAutoApply.LeadLabel(1440));

        var low = new UsageSnapshot("pro", true, false, new UsageWindow(99, 18_000, 0, Now.AddHours(2)),
            new UsageWindow(40, 604_800, 0, Now.AddDays(2)), null, 2, [], null, null, Now);
        var fire = LowUsageAutoUse.Evaluate(low, LowUsageAutoUseMetric.Both, 1, 2, null, Now);
        Assert.True(fire.Fires);
        Assert.Equal("5-hour", fire.WindowLabel);
        Assert.Equal(LowUsageOutcome.SkipAlreadyUsed, LowUsageAutoUse.Evaluate(low, LowUsageAutoUseMetric.Both, 1, 2, fire.WindowToken, Now).Outcome);
        Assert.Equal(LowUsageOutcome.Wait, LowUsageAutoUse.Evaluate(low, LowUsageAutoUseMetric.Weekly, 1, 2, null, Now).Outcome);
        Assert.Equal(LowUsageOutcome.SkipNoCredit, LowUsageAutoUse.Evaluate(low, LowUsageAutoUseMetric.Both, 1, 0, null, Now).Outcome);
        Assert.Equal(LowUsageOutcome.Wait, LowUsageAutoUse.Evaluate(low, LowUsageAutoUseMetric.Both, 1, 2, null, Now.AddMinutes(5)).Outcome);
    }
}
