using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

public class PlanPricingTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-14T12:00:00Z");

    private static UsageSnapshot Snapshot(string plan, UsageWindow? fiveHour = null, UsageWindow? weekly = null, UsageWindow? monthly = null) =>
        new(plan, true, false, fiveHour, weekly, monthly, null, null, null, null, Now);

    [Fact]
    public void ClaudeProIsPricedAsClaudeProNotChatGptPro()
    {
        var snapshot = Snapshot("Pro", new UsageWindow(20, 18000), new UsageWindow(40, 604800));
        var pricing = PlanPricing.ForProvider("claude_code", snapshot)!;
        Assert.Equal("Claude Pro · $20/month", pricing.Title);
        Assert.Equal(1200, pricing.MonthlyValueUsd);
        Assert.StartsWith("Estimate: Opus 5.5 at full use, from BenchLeader (data Nov 7, 2026).", pricing.Basis);
        Assert.Contains(("All on Haiku 5.5", "≈ $877/month"), pricing.SummaryRows(snapshot));
        // The same badge on Codex is ChatGPT Pro 200.
        Assert.Equal(5604, PlanPricing.ForProvider("codex", snapshot)!.MonthlyValueUsd);
        Assert.Equal(7224, PlanPricing.ForClaudePlan("Max 5x")!.MonthlyValueUsd);
        Assert.Equal(13200, PlanPricing.ForClaudePlan("Ultra")!.MonthlyValueUsd);
        Assert.Null(PlanPricing.ForClaudePlan("Team"));
    }

    [Fact]
    public void OtherProvidersGetNoCard()
    {
        Assert.Null(PlanPricing.ForProvider("copilot", Snapshot("Pro")));
        Assert.Null(PlanPricing.ForProvider("cursor", Snapshot("Pro")));
        Assert.Null(PlanPricing.ForProvider("codex", Snapshot("free")));
    }

    [Fact]
    public void CursorUsesItsOwnIncludedUsage()
    {
        var json = """{"billingCycleStart":"1762000000000","billingCycleEnd":"1762604800000","planUsage":{"limit":2000,"includedSpend":500}}""";
        var snapshot = CursorUsageParser.Parse(json, "Pro", Now);
        Assert.Equal(20, snapshot.IncludedValueUsd);
        var pricing = PlanPricing.ForProvider("cursor", snapshot)!;
        Assert.True(pricing.Reported);
        Assert.Equal(80, pricing.MonthlyValueUsd); // a weekly pool, four weeks a month
        Assert.Equal(("Included usage", "$20/week"), pricing.SummaryRows(snapshot)[0]);
    }

    [Fact]
    public void RowsOnlyForTheWindowsThePlanHas()
    {
        var weeklyOnly = Snapshot("plus", weekly: new UsageWindow(25, 604800));
        var rows = PlanPricing.ForProvider("codex", weeklyOnly)!.SummaryRows(weeklyOnly).Select(r => r.Label).ToList();
        Assert.DoesNotContain("5-hour allowance", rows);
        Assert.Contains("Value used this week", rows);
        Assert.Equal("$3,500", PlanPricing.FormatUsd(3500));
        Assert.Equal(299, PlanPricing.ForCodexPlan("plus")!.MonthlyValueUsd);
        Assert.Equal("$4.20", PlanPricing.FormatUsd(4.2));
    }
}

public class UnknownPlanValueTests
{
    [Fact]
    public void PlansWithNoPublishedFigureKeepTheCardButShowUnknown()
    {
        var snapshot = new UsageSnapshot("prolite", true, false, null, new UsageWindow(30, 604800), null, null, null, null, null,
            DateTimeOffset.Parse("2026-11-14T12:00:00Z"));
        var pricing = PlanPricing.ForProvider("codex", snapshot)!;
        Assert.Equal("Pro 100 · $100/month", pricing.Title);
        Assert.False(pricing.HasValue);
        Assert.Equal([("Est. included usage", "Unknown")], pricing.SummaryRows(snapshot));
        Assert.False(PlanPricing.ForCodexPlan("pro500")!.HasValue);
    }
}

public class UsageBackTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-14T12:00:00Z");
    private static readonly UsageProvider Claude = UsageProvider.ClaudeCode;

    private static UsageSnapshot At(DateTimeOffset at, int fiveHourUsed, DateTimeOffset? resetAt) =>
        new("pro", true, fiveHourUsed >= 100, new UsageWindow(fiveHourUsed, 18000, 0, resetAt), null, null, null, null, null, null, at);

    [Fact]
    public void AnnouncesOnceWhenAUsedUpLimitComesBack()
    {
        var reset = Now.AddHours(2);
        var (armed, alerts) = UsageRefill.Observe(Claude, At(Now, 100, reset), new Dictionary<string, long>(), Now);
        Assert.Empty(alerts);
        Assert.Equal(reset.ToUnixTimeSeconds(), armed["claude_code:five_hour"]);
        (armed, alerts) = UsageRefill.Observe(Claude, At(Now.AddMinutes(15), 100, reset), armed, Now.AddMinutes(15));
        Assert.Empty(alerts);

        (armed, alerts) = UsageRefill.Observe(Claude, At(Now.AddHours(2.1), 3, Now.AddHours(7)), armed, Now.AddHours(2.1));
        var alert = Assert.Single(alerts);
        Assert.Equal("Claude Code usage is back", alert.Title);
        Assert.Equal("Your 5-hour limit refilled to 97% left. You can use Claude Code again.", alert.Body);
        Assert.Empty(armed);
        (_, alerts) = UsageRefill.Observe(Claude, At(Now.AddHours(2.2), 5, Now.AddHours(7)), armed, Now.AddHours(2.2));
        Assert.Empty(alerts);
    }

    [Fact]
    public void AnnouncesWhenTheResetTimePassesWithoutARefresh()
    {
        var (armed, _) = UsageRefill.Observe(Claude, At(Now, 100, Now.AddHours(1)), new Dictionary<string, long>(), Now);
        var (still, none) = UsageRefill.Due(armed, UsageProvider.Find, Now.AddMinutes(30));
        Assert.Empty(none);
        var (cleared, alerts) = UsageRefill.Due(still, UsageProvider.Find, Now.AddHours(1).AddSeconds(1));
        Assert.Equal("Your 5-hour limit has reset. You can use Claude Code again.", Assert.Single(alerts).Body);
        Assert.Empty(cleared);
    }

    [Fact]
    public void ResetAlertDefersToUsageIsBackAndSurpriseRefillsAreFlagged()
    {
        var before = At(Now, 100, Now.AddMinutes(5));
        var after = At(Now.AddMinutes(10), 0, Now.AddHours(5));
        Assert.Empty(UsageAlerts.Evaluate(Claude, before, after, 15, true, true, true, usageBackOn: true));
        Assert.Single(UsageAlerts.Evaluate(Claude, before, after, 15, true, true, true, usageBackOn: false));

        var early = UsageAlerts.Evaluate(Claude, At(Now, 80, Now.AddHours(3)), At(Now.AddMinutes(10), 10, Now.AddHours(3)), 15, true, true, true);
        var refill = Assert.Single(early);
        Assert.Equal(UsageAlertKind.Refill, refill.Kind);
        Assert.Equal("Claude Code refilled early", refill.Title);
    }
}

public class PriorityAndModelsTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-14T12:00:00Z");

    [Fact]
    public void ListsWhatNeedsAttention()
    {
        var codex = new UsageSnapshot("plus", true, false,
            new UsageWindow(90, 18000, 0, Now.AddHours(1)),          // 10% left: watch
            new UsageWindow(100, 604800, 0, Now.AddDays(2)),         // used up
            null, null, null, null, null, Now);
        var cursor = new UsageSnapshot("Pro", true, false, null, null,
            new UsageWindow(40, 2592000, 0, Now.AddDays(2)), null, null, null, null, Now); // renews in 2 days
        var credits = new ResetCreditsSnapshot(1, [new RateLimitResetCredit("c1", "full", "available", Now.AddDays(-20), Now.AddDays(1))], Now);
        var report = PriorityReport.Build([(UsageProvider.Codex, codex, credits), (UsageProvider.Cursor, cursor, null)], [], Now);
        Assert.Equal("Codex 5-hour", Assert.Single(report.Watch).Title);
        Assert.Equal("Codex weekly", Assert.Single(report.UsedUp).Title);
        Assert.Equal(["Cursor monthly renews", "Codex reset credit expires"], report.ComingUp.Select(i => i.Title).OrderBy(t => t.StartsWith("Codex")));
        Assert.Equal("1 limit needs watching.", report.Headline);
        Assert.True(PriorityReport.Build([], [], Now).IsEmpty);
    }

    private static (string, string, DateTimeOffset?)[] Catalog(params (string Id, DateTimeOffset? Created)[] extra) =>
        Enumerable.Range(0, 60).Select(i => ($"filler/model-{i}", $"Model {i}", (DateTimeOffset?)Now.AddYears(-1)))
            .Concat(extra.Select(e => (e.Id, e.Id, e.Created))).ToArray();

    [Fact]
    public void NewModelsNeedABaselineTwoSightingsAndAMatchingProvider()
    {
        var state = new ModelDiscoveryState();
        (state, var confirmed) = ModelDiscovery.Observe(state, Catalog(("anthropic/old", Now.AddYears(-1))), Now);
        Assert.Empty(confirmed); // baseline
        var catalog = Catalog(("anthropic/claude-fable-6", Now.AddDays(-1)), ("openai/gpt-6", Now.AddDays(-1)),
            ("anthropic/claude-fable-6:free", Now.AddDays(-1)), ("anthropic/claude-relisted", Now.AddYears(-2)));
        (state, confirmed) = ModelDiscovery.Observe(state, catalog, Now.AddHours(12));
        Assert.Empty(confirmed); // first sighting
        (state, confirmed) = ModelDiscovery.Observe(state, catalog, Now.AddHours(24));
        Assert.Equal(["anthropic/claude-fable-6", "openai/gpt-6"], confirmed.Select(m => m.Id).Order());
        // A partial response changes nothing.
        Assert.Same(state, ModelDiscovery.Observe(state, [("x/y", "y", Now)], Now.AddHours(36)).State);

        var items = ModelDiscovery.Items(state, [UsageProvider.ClaudeCode, UsageProvider.Cursor], Now.AddHours(25));
        var item = Assert.Single(items);
        Assert.Equal("Anthropic model, for your Claude Code", item.Detail);
        Assert.StartsWith("Shown for Claude Code. Tools that offer many makers' models, like Cursor or Copilot, add none.", ModelDiscovery.Note(items));
        Assert.Empty(ModelDiscovery.Items(state, [UsageProvider.Cursor, UsageProvider.Copilot], Now.AddHours(25)));
    }
}
