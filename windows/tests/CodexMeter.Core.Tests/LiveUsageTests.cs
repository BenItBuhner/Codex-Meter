using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

public class LiveUsageTests
{
    private static readonly UsageProvider Codex = UsageProvider.Codex, Claude = UsageProvider.ClaudeCode, Cursor = UsageProvider.Cursor;
    private static readonly DateTimeOffset Now = new(2026, 11, 3, 12, 0, 0, TimeSpan.Zero);

    private static UsageWindow Window(int used, long seconds, TimeSpan? resetIn = null) =>
        new(used, seconds, resetAt: Now + (resetIn ?? TimeSpan.FromHours(2)));

    private static UsageSnapshot Snapshot(UsageWindow? five, UsageWindow? weekly, UsageWindow? monthly = null,
        IReadOnlyList<UsageLimit>? limits = null) =>
        new("plus", true, false, five, weekly, monthly, null, limits, null, null, Now);

    [Fact]
    public void ProviderFollowsTheAppInFrontThenTheChoiceThenTheSidebar()
    {
        IReadOnlyList<UsageProvider> candidates = [Cursor, Codex, Claude];
        Assert.Equal(Cursor, LiveUsage.ChooseProvider(LiveUsage.AutomaticProvider, null, candidates));
        Assert.Equal(Codex, LiveUsage.ChooseProvider("codex", null, candidates));
        Assert.Equal(Claude, LiveUsage.ChooseProvider("codex", Claude, candidates));
        // A chosen or front provider without usage to show is skipped.
        Assert.Equal(Cursor, LiveUsage.ChooseProvider("copilot", UsageProvider.Copilot, candidates));
        Assert.Null(LiveUsage.ChooseProvider("codex", Claude, []));
    }

    [Theory]
    [InlineData("Claude", "claude_code")]
    [InlineData("ChatGPT.exe", "codex")]
    [InlineData("Codex", "codex")]
    [InlineData("cursor", "cursor")]
    [InlineData(" Windsurf ", "windsurf")]
    [InlineData("claude.exe", "claude_code")]
    [InlineData("Cursor.exe", "cursor")]
    // The SpaceXSI Grok app's process is "Grok Bot", with a space.
    [InlineData("Grok Bot", "grok")]
    [InlineData("Grok Bot.exe", "grok")]
    [InlineData("grokbot", "grok")]
    [InlineData("GrokBot", "grok")]
    [InlineData("grok-bot", "grok")]
    [InlineData("Grok", "grok")]
    [InlineData("Grok Desktop", "grok")]
    [InlineData("Cursor Helper", "cursor")]
    [InlineData("explorer", null)]
    [InlineData(".exe", null)]
    [InlineData("Claudette", null)]
    [InlineData("Precursor", null)]
    [InlineData("groker", null)]
    [InlineData("MyCursor App", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void DesktopAppsMapToProviders(string? app, string? provider) =>
        Assert.Equal(provider, LiveUsage.ProviderForApp(app)?.Id);

    [Theory]
    // The SpaceXSI desktop family signs its apps "SpaceXAI".
    [InlineData("SomeHost", "Grok Bot", "SpaceXAI", "grok")]
    [InlineData("SomeHost", null, "SpaceXAI", "grok")]
    [InlineData("SomeHost", null, "SpaceXSI", "grok")]
    [InlineData("SomeHost", null, "X.AI Corp", "grok")]
    [InlineData("SomeHost", null, "Anysphere, Inc.", "cursor")]
    [InlineData("SomeHost", null, "Anthropic, PBC", "claude_code")]
    [InlineData("SomeHost", "Claude", null, "claude_code")]
    // The process name wins over the publisher.
    [InlineData("Cursor", "Something", "Anthropic", "cursor")]
    [InlineData("notepad", "Notepad", "Microsoft Corporation", null)]
    [InlineData("SomeHost", null, "Anthropic Fans Club", null)]
    [InlineData(null, null, null, null)]
    public void DesktopAppsMapToProvidersByProductAndCompany(string? process, string? product, string? company, string? provider) =>
        Assert.Equal(provider, LiveUsage.ProviderForApp(process, product, company)?.Id);

    [Fact]
    public void FocusedGrokBotLeadsTheTrayOverClaudeCodeAndCursor()
    {
        IReadOnlyList<UsageProvider> candidates = [Claude, Cursor, UsageProvider.Grok];
        var front = LiveUsage.ProviderForApp("Grok Bot", "Grok Bot", "SpaceXAI");
        Assert.Equal(UsageProvider.Grok, LiveUsage.ChooseProvider(LiveUsage.AutomaticProvider, front, candidates));
    }

    [Fact]
    public void AutoTakesTheTighterWindowAndPrefersFiveHourOnATie()
    {
        var five = Window(40, 18_000);
        var weekly = Window(70, 604_800);
        Assert.Equal((weekly, "Weekly"), LiveUsage.ChooseWindow(Snapshot(five, weekly), LiveWindowChoice.Auto));
        Assert.Equal((five, "5-hour"), LiveUsage.ChooseWindow(Snapshot(five, Window(40, 604_800)), LiveWindowChoice.Auto));
        Assert.Equal((five, "5-hour"), LiveUsage.ChooseWindow(Snapshot(five, weekly), LiveWindowChoice.FiveHour));
        Assert.Equal((weekly, "Weekly"), LiveUsage.ChooseWindow(Snapshot(five, weekly), LiveWindowChoice.Weekly));
    }

    [Fact]
    public void ExplicitChoiceFallsBackToTheWindowTheServiceReports()
    {
        var five = Window(10, 18_000);
        var monthly = Window(30, 2_592_000);
        Assert.Equal((five, "5-hour"), LiveUsage.ChooseWindow(Snapshot(five, null), LiveWindowChoice.Weekly));
        Assert.Equal((monthly, "Monthly"), LiveUsage.ChooseWindow(Snapshot(null, null, monthly), LiveWindowChoice.FiveHour));
        var pool = new UsageLimit("pool", "Included usage", primary: Window(55, 2_592_000));
        Assert.Equal((pool.Primary, "Included usage"), LiveUsage.ChooseWindow(Snapshot(null, null, limits: [pool]), LiveWindowChoice.Auto));
        Assert.Equal((null, ""), LiveUsage.ChooseWindow(null, LiveWindowChoice.Auto));
    }

    [Fact]
    public void ReadingTextMatchesTheLiveNotification()
    {
        var reading = LiveUsage.Read(Codex, Snapshot(Window(38, 18_000), Window(10, 604_800)), LiveWindowChoice.Auto);
        Assert.Equal("62", reading.IconText(UsageDisplayMode.Remaining, Now));
        Assert.Equal("38", reading.IconText(UsageDisplayMode.Used, Now));
        Assert.Equal("62%", reading.CompactText(UsageDisplayMode.Remaining, Now));
        Assert.Equal("Codex · 5-hour 62% left", reading.Headline(UsageDisplayMode.Remaining, Now));
        Assert.Equal(0.62, reading.Fraction(UsageDisplayMode.Remaining), 3);
        Assert.Equal(LiveLevel.Healthy, reading.Level);
    }

    [Fact]
    public void UsedUpWindowShowsTimeUntilItResets()
    {
        var weekly = Window(100, 604_800, TimeSpan.FromHours(52) + TimeSpan.FromMinutes(10));
        var reading = LiveUsage.Read(Claude, Snapshot(Window(20, 18_000), weekly), LiveWindowChoice.Auto);
        Assert.True(reading.Exhausted);
        Assert.Equal(LiveLevel.Critical, reading.Level);
        Assert.Equal("2d", reading.IconText(UsageDisplayMode.Remaining, Now));
        Assert.Equal("2d 4h", reading.CompactText(UsageDisplayMode.Remaining, Now));
        Assert.Equal("Claude Code · Weekly resets in 2d 4h", reading.Headline(UsageDisplayMode.Remaining, Now));
    }

    [Fact]
    public void LevelsAndDurations()
    {
        Assert.Equal(LiveLevel.Low, LiveUsage.Read(Codex, Snapshot(Window(65, 18_000), null), LiveWindowChoice.Auto).Level);
        Assert.Equal(LiveLevel.Critical, LiveUsage.Read(Codex, Snapshot(Window(85, 18_000), null), LiveWindowChoice.Auto).Level);
        Assert.Equal(LiveLevel.None, LiveUsage.Read(Codex, null, LiveWindowChoice.Auto).Level);
        Assert.Equal("–", LiveUsage.Read(Codex, null, LiveWindowChoice.Auto).IconText(UsageDisplayMode.Remaining, Now));
        Assert.Equal("45m", LiveUsage.TinyDuration(TimeSpan.FromMinutes(44.2)));
        Assert.Equal("3h", LiveUsage.TinyDuration(TimeSpan.FromMinutes(121)));
        Assert.Equal("24h", LiveUsage.TinyDuration(TimeSpan.FromHours(24)));
        Assert.Equal("3d", LiveUsage.TinyDuration(TimeSpan.FromHours(61)));
        Assert.Equal("3h 12m", LiveUsage.ShortDuration(TimeSpan.FromMinutes(192)));
        Assert.Equal("2d", LiveUsage.ShortDuration(TimeSpan.FromDays(2)));
        Assert.Equal("1m", LiveUsage.ShortDuration(TimeSpan.Zero));
    }

    [Fact]
    public void SettingsDefaultToAutomaticRingAndRoundTrip()
    {
        var settings = new AppSettings();
        Assert.Equal("", settings.LiveProvider);
        Assert.True(settings.LiveFollowApps);
        Assert.Equal(LiveWindowChoice.Auto, settings.LiveWindow);
        Assert.Equal(LiveIconStyle.Ring, settings.LiveIconStyle);
        var path = Path.Combine(Path.GetTempPath(), $"live-{Guid.NewGuid():N}.json");
        try
        {
            new SettingsStore(path).Update(s => s with { LiveProvider = "cursor", LiveWindow = LiveWindowChoice.Weekly, LiveIconStyle = LiveIconStyle.Number, LiveFollowApps = false });
            var loaded = new SettingsStore(path).Current;
            Assert.Equal("cursor", loaded.LiveProvider);
            Assert.Equal(LiveWindowChoice.Weekly, loaded.LiveWindow);
            Assert.Equal(LiveIconStyle.Number, loaded.LiveIconStyle);
            Assert.False(loaded.LiveFollowApps);
        }
        finally
        {
            File.Delete(path);
        }
    }
}
