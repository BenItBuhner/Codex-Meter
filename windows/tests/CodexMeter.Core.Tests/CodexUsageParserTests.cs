using System.Text.Json;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

/// <summary>Mirrors iOS <c>UsageParserTests</c> against the shared fixtures.</summary>
public class CodexUsageParserTests
{
    private static readonly DateTimeOffset At = Fixtures.FetchedAt;

    [Fact]
    public void StandardSnapshot()
    {
        var s = CodexUsageParser.Parse(Fixtures.Read("usage-standard"), At);
        Assert.Equal("plus", s.PlanType);
        Assert.True(s.Allowed);
        Assert.False(s.LimitReached);
        Assert.Equal(37, s.FiveHour!.UsedPercent);
        Assert.Equal(63, s.FiveHour.RemainingPercent);
        Assert.Equal(18_000, s.FiveHour.WindowSeconds);
        Assert.Equal(62, s.Weekly!.UsedPercent);
        Assert.Equal(3, s.ResetCreditsAvailable);
        Assert.Equal(At, s.FetchedAt);
    }

    [Fact]
    public void IdentifiesReversedWindowsByDuration()
    {
        var s = CodexUsageParser.Parse(Fixtures.Read("usage-reversed"), At);
        Assert.Equal(88, s.FiveHour!.UsedPercent);
        Assert.Equal(12, s.Weekly!.UsedPercent);
    }

    [Fact]
    public void AdditionalWindowsClampValuesAndNegativeResetFields()
    {
        var s = CodexUsageParser.Parse(Fixtures.Read("usage-additional"), At);
        Assert.Null(s.FiveHour);
        Assert.Null(s.Weekly);
        var limit = s.AdditionalLimits[0];
        Assert.Equal("GPT-5.3-Codex-Spark", limit.DisplayName);
        Assert.Equal("codex_bengalfox", limit.MeteredFeature);
        Assert.False(limit.Allowed);
        Assert.True(limit.LimitReached);
        Assert.Equal(100, limit.Primary!.UsedPercent);
        Assert.Equal(0, limit.Primary.RemainingPercent);
        Assert.Equal(0, limit.Primary.ResetAfterSeconds);
        Assert.Equal(0, limit.Secondary!.UsedPercent);
        Assert.Equal("2500.5", s.UsageCredits!.Balance);
        Assert.True(s.UsageCredits.ShouldDisplay);
    }

    [Fact]
    public void MainRateLimitTakesPrecedenceOverCloserAdditionalWindow()
    {
        var s = CodexUsageParser.Parse(Fixtures.Read("usage-primary-precedence"), At);
        Assert.Equal(10, s.FiveHour!.UsedPercent);
        Assert.Equal(21_600, s.FiveHour.WindowSeconds);
        Assert.Equal(20, s.Weekly!.UsedPercent);
    }

    [Fact]
    public void MalformedWindowsAreIgnoredAndDirectAdditionalShapeIsAccepted()
    {
        var s = CodexUsageParser.Parse(Fixtures.Read("usage-malformed"), At);
        Assert.False(s.Allowed);
        Assert.True(s.LimitReached);
        Assert.Null(s.FiveHour);
        Assert.Null(s.Weekly);
        Assert.Equal(45, s.AdditionalLimits[0].Primary!.UsedPercent);
        Assert.Null(s.AdditionalLimits[0].Primary!.ResetAt);
        Assert.Null(s.ResetCreditsAvailable);
    }

    [Fact]
    public void AdditionalLimitsRemainIndependentFromMissingPrimarySlots()
    {
        var s = CodexUsageParser.Parse(Fixtures.Read("usage-fill-missing"), At);
        Assert.Null(s.FiveHour);
        Assert.Equal(70, s.Weekly!.UsedPercent);
        Assert.Equal(30, s.AdditionalLimits[0].Primary!.UsedPercent);
        Assert.Equal(99, s.AdditionalLimits[0].Secondary!.UsedPercent);
    }

    [Fact]
    public void DefaultsWhenRateLimitIsMissing()
    {
        var s = CodexUsageParser.Parse("{\"plan_type\":\"free\"}", At);
        Assert.True(s.Allowed);
        Assert.Null(s.FiveHour);
        Assert.Null(s.Monthly);
        Assert.False(s.HasDisplayableData);
    }

    [Fact]
    public void MonthlyWindowClassifiesFreeTierAndCalendarDrift()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(2_000_000_000);
        var resetAt = now.AddDays(14).ToUnixTimeSeconds();
        var s = CodexUsageParser.Parse("""{"plan_type":"free","rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"used_percent":28,"limit_window_seconds":2592000,"reset_after_seconds":1209600,"reset_at":""" + resetAt + "}}}", now);
        Assert.Null(s.FiveHour);
        Assert.Null(s.Weekly);
        Assert.Equal(28, s.Monthly!.UsedPercent);
        Assert.True(s.LongWindowIsMonthly);
        Assert.Equal(s.Monthly, s.LongWindow);
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(resetAt), s.NextReset(now));

        var restored = JsonSerializer.Deserialize<UsageSnapshot>(JsonSerializer.Serialize(s, Storage.Options), Storage.Options)!;
        Assert.Equal(28, restored.Monthly!.UsedPercent);
        Assert.Equal(2_592_000, restored.Monthly.WindowSeconds);

        Assert.NotNull(CodexUsageParser.Parse("""{"rate_limit":{"primary_window":{"used_percent":5,"limit_window_seconds":2419200}}}""", now).Monthly);
        Assert.NotNull(CodexUsageParser.Parse("""{"rate_limit":{"primary_window":{"used_percent":5,"limit_window_seconds":2678400}}}""", now).Monthly);

        var pro = CodexUsageParser.Parse("""
            {"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":18000},
             "secondary_window":{"used_percent":72,"limit_window_seconds":604800}}}
            """, now);
        Assert.NotNull(pro.FiveHour);
        Assert.NotNull(pro.Weekly);
        Assert.Null(pro.Monthly);
        Assert.False(pro.LongWindowIsMonthly);
    }

    [Fact]
    public void UnrecognizedSingleWindowStillDisplaysForGoStylePlans()
    {
        var s = CodexUsageParser.Parse("""{"plan_type":"go","rate_limit":{"primary_window":{"used_percent":41,"limit_window_seconds":5184000}}}""", At);
        Assert.Null(s.FiveHour);
        Assert.Null(s.Weekly);
        Assert.Equal(41, s.Monthly!.UsedPercent);
        Assert.True(s.HasDisplayableData);
    }

    [Fact]
    public void WeeklyAndNumericCreditBalanceWithoutFiveHour()
    {
        var s = CodexUsageParser.Parse("""
            {"rate_limit":{"secondary_window":{"used_percent":25,"limit_window_seconds":604800}},
             "credits":{"has_credits":true,"unlimited":false,"balance":2500}}
            """, At);
        Assert.Null(s.FiveHour);
        Assert.Equal(25, s.Weekly!.UsedPercent);
        Assert.Equal("2500", s.UsageCredits!.Balance);
        Assert.True(s.HasDisplayableData);
    }

    [Fact]
    public void EmptyPurchasedCreditsAreNotStandaloneUsageData()
    {
        var s = CodexUsageParser.Parse("""{"credits":{"has_credits":false,"unlimited":false,"balance":"0"}}""", At);
        Assert.NotNull(s.UsageCredits);
        Assert.False(s.UsageCredits!.ShouldDisplay);
        Assert.False(s.HasDisplayableData);
    }

    [Fact]
    public void FiveHourTieChoosesFirstAndDoesNotInventWeeklyWindow()
    {
        var s = CodexUsageParser.Parse("""
            {"rate_limit":{"primary_window":{"used_percent":11,"limit_window_seconds":14400},
             "secondary_window":{"used_percent":22,"limit_window_seconds":21600}}}
            """, At);
        Assert.Equal(11, s.FiveHour!.UsedPercent);
        Assert.Null(s.Weekly);
    }

    [Fact]
    public void SpendControlParsesAlongsideWindowsAndCredits()
    {
        var s = CodexUsageParser.Parse(Fixtures.Read("usage-spend-control"), At);
        var control = s.SpendControl!;
        Assert.False(control.Reached);
        var limit = control.IndividualLimit!;
        Assert.Equal("workspace_spend_controls", limit.Source);
        Assert.Equal("25000", limit.Limit);
        Assert.Equal("8000", limit.Used);
        Assert.Equal("17000", limit.Remaining);
        Assert.Equal(32, limit.UsedPercent);
        Assert.Equal(68, limit.RemainingPercent);
        Assert.Equal(43_200, limit.ResetAfterSeconds);
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(2_000_043_200), limit.ResetAt);
        Assert.Equal(17_000m, limit.NumericRemaining);
        Assert.Equal("team", s.PlanType);
        Assert.Equal(42, s.FiveHour!.UsedPercent);
        Assert.Equal(5, s.Weekly!.UsedPercent);
        Assert.Equal(2, s.ResetCreditsAvailable);
        Assert.False(s.UsageCredits!.ShouldDisplay);
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(2_000_003_600), s.NextReset(At));

        var restored = JsonSerializer.Deserialize<UsageSnapshot>(JsonSerializer.Serialize(s, Storage.Options), Storage.Options)!;
        Assert.Equal(control, restored.SpendControl);
        Assert.Equal(s.FiveHour, restored.FiveHour);
        Assert.Equal(s.AdditionalLimits.Count, restored.AdditionalLimits.Count);
    }

    [Fact]
    public void AndroidLiveSpendControlFixtureParses()
    {
        var s = CodexUsageParser.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", "android", "usage-spend-control-live.json")), At);
        Assert.True(s.HasDisplayableData);
        Assert.NotNull(s.SpendControl);
    }

    [Fact]
    public void SpendControlAcceptsNumbersAndStringsInterchangeably()
    {
        var s = CodexUsageParser.Parse("""
            {"spend_control":{"reached":"true","individual_limit":{"limit":25000,"used":8000.5,"used_percent":"32.4",
             "reset_after_seconds":"43200","reset_at":"2000043200"}}}
            """, At);
        Assert.True(s.SpendControl!.Reached);
        var limit = s.SpendControlLimit!;
        Assert.Equal("", limit.Source);
        Assert.Equal("25000", limit.Limit);
        Assert.Equal("8000.5", limit.Used);
        Assert.Null(limit.Remaining);
        Assert.Equal(16999.5m, limit.NumericRemaining);
        Assert.Equal(32, limit.UsedPercent);
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(2_000_043_200), limit.ResetAt);
        Assert.True(s.HasDisplayableData);
        Assert.Null(s.NextReset(At));
    }

    [Fact]
    public void SpendControlPercentFallbacksAndClamping()
    {
        var remainingOnly = CodexUsageParser.Parse("""{"spend_control":{"reached":false,"individual_limit":{"limit":"1000","used":"250","remaining_percent":75}}}""", At);
        Assert.Equal(25, remainingOnly.SpendControlLimit!.UsedPercent);
        Assert.Equal(750m, remainingOnly.SpendControlLimit.NumericRemaining);
        Assert.False(remainingOnly.SpendControlLimit.ShowsResetCountdown);

        var derived = CodexUsageParser.Parse("""{"spend_control":{"reached":false,"individual_limit":{"limit":"1000","used":"333"}}}""", At);
        Assert.Equal(33, derived.SpendControlLimit!.UsedPercent);

        var overrun = CodexUsageParser.Parse("""{"spend_control":{"reached":true,"individual_limit":{"limit":"1000","used":"1200","remaining":"-200","used_percent":120}}}""", At);
        Assert.Equal(100, overrun.SpendControlLimit!.UsedPercent);
        Assert.Equal(0, overrun.SpendControlLimit.RemainingPercent);
        Assert.Equal(0m, overrun.SpendControlLimit.NumericRemaining);
    }

    [Fact]
    public void SpendControlWithoutUsableLimitStaysHidden()
    {
        var nullLimit = CodexUsageParser.Parse("""{"plan_type":"team","spend_control":{"reached":true,"individual_limit":null}}""", At);
        Assert.True(nullLimit.SpendControl!.Reached);
        Assert.Null(nullLimit.SpendControlLimit);
        Assert.False(nullLimit.HasDisplayableData);

        var unusable = CodexUsageParser.Parse("""{"spend_control":{"reached":false,"individual_limit":{"limit":true,"used":null,"used_percent":false}}}""", At);
        Assert.NotNull(unusable.SpendControl);
        Assert.Null(unusable.SpendControlLimit);
    }

    [Fact]
    public void InvalidRootThrows()
    {
        Assert.Throws<UsageParsingException>(() => CodexUsageParser.Parse("[]", At));
        Assert.Throws<UsageParsingException>(() => CodexUsageParser.Parse("not json", At));
    }

    [Fact]
    public void BooleanNumericFieldsAreMalformedRatherThanCoerced()
    {
        var s = CodexUsageParser.Parse("""
            {"rate_limit":{"allowed":1,"primary_window":{"used_percent":true,"limit_window_seconds":18000},
             "secondary_window":{"used_percent":2,"limit_window_seconds":false}}}
            """, At);
        Assert.True(s.Allowed);
        Assert.Null(s.FiveHour);
        Assert.Null(s.Weekly);
    }

    [Fact]
    public void ResolvedPinsRelativeResets()
    {
        var s = CodexUsageParser.Parse("""{"rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":18000,"reset_after_seconds":600}}}""", At);
        Assert.Null(s.FiveHour!.ResetAt);
        Assert.Equal(At.AddSeconds(600), s.Resolved().FiveHour!.ResetAt);
    }
}
