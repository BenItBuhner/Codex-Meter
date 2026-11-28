using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;

namespace CodexMeter.Linux.Tests;

/// <summary>
/// The Linux app's state machine against the real Codex service and scripted HTTP: when it asks the
/// network, what it keeps showing when a refresh fails or looks wrong, and what signing out undoes.
/// </summary>
public sealed class AppStateTests
{
    private static FakeHttp Codex(Func<(int, string)> usage) =>
        new FakeHttp().On("GET", CodexService.UsageUrl, usage).On("GET", CodexService.CreditsUrl, 200, "{}");

    [Fact]
    public async Task FreshDataIsKeptUnlessTheRefreshIsForced()
    {
        var http = Codex(() => (200, CodexReplies.Fixture("usage-standard")));
        var state = await CodexReplies.LiveStateAsync(http);
        var changes = 0;
        state.ProviderChanged += p => { if (p == UsageProvider.Codex) changes++; };

        await state.RefreshAsync(UsageProvider.Codex, force: false);
        Assert.Equal(1, http.Calls(CodexService.UsageUrl));
        Assert.Equal(37, state[UsageProvider.Codex].Usage!.FiveHour!.UsedPercent);
        Assert.False(state.IsStale(UsageProvider.Codex));
        // Opening the dashboard again within five minutes keeps what is on screen.
        await state.RefreshAsync(UsageProvider.Codex, force: false);
        Assert.Equal(1, http.Calls(CodexService.UsageUrl));
        // The Refresh button always asks.
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.Equal(2, http.Calls(CodexService.UsageUrl));
        // Refreshing, then done: two changes per refresh so the spinner shows and hides.
        Assert.Equal(4, changes);
        Assert.False(state[UsageProvider.Codex].Refreshing);
    }

    [Fact]
    public async Task AFailedRefreshKeepsTheLastReadingAndSaysSo()
    {
        var status = 200;
        var http = Codex(() => (status, status == 200 ? CodexReplies.Usage(40, 20) : "{\"error\":\"boom\"}"));
        var state = await CodexReplies.LiveStateAsync(http);
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        var entry = state[UsageProvider.Codex];
        Assert.False(entry.HasError);

        status = 500;
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.True(entry.HasError);
        Assert.Equal(40, entry.Usage!.FiveHour!.UsedPercent);
        Assert.True(entry.SignedIn);

        status = 200;
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.False(entry.HasError);
    }

    [Fact]
    public async Task RateLimitingPausesBackgroundChecksForAnHour()
    {
        var http = Codex(() => (429, "{}"));
        var state = await CodexReplies.LiveStateAsync(http);
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        var entry = state[UsageProvider.Codex];
        Assert.NotNull(entry.RateLimitedAt);
        var asked = http.Calls(CodexService.UsageUrl);

        await state.BackgroundTickAsync();
        Assert.Equal(asked, http.Calls(CodexService.UsageUrl));

        // An hour on, the background check asks again.
        var hourAgo = DateTimeOffset.UtcNow.AddHours(-1).AddMinutes(-1);
        entry.RateLimitedAt = hourAgo;
        entry.Cache = entry.Cache! with { LastErrorAt = hourAgo };
        await state.BackgroundTickAsync();
        Assert.True(http.Calls(CodexService.UsageUrl) > asked);
    }

    [Fact]
    public async Task BackgroundTickWaitsForTheRefreshInterval()
    {
        var http = Codex(() => (200, CodexReplies.Usage(10, 5)));
        var state = await CodexReplies.LiveStateAsync(http);
        state.Settings.Update(s => s with { RefreshMinutes = 15 });
        await state.BackgroundTickAsync();
        Assert.Equal(1, http.Calls(CodexService.UsageUrl));
        // A minute later nothing is due.
        await state.BackgroundTickAsync();
        Assert.Equal(1, http.Calls(CodexService.UsageUrl));
    }

    [Fact]
    public async Task AnEarlyRefillIsHeldUntilASecondReadingConfirmsIt()
    {
        var used = 80.0;
        var http = Codex(() => (200, CodexReplies.Usage(used, 50)));
        var state = await CodexReplies.LiveStateAsync(http);
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        var entry = state[UsageProvider.Codex];
        var recorded = entry.History.FiveHour.Samples.Count;

        // Hours before its reset, the 5-hour window reads empty: a fluke until confirmed.
        used = 0;
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.Equal(80, entry.Usage!.FiveHour!.UsedPercent);
        Assert.NotNull(entry.RefillHeldSince);
        Assert.Equal(recorded, entry.History.FiveHour.Samples.Count);

        // The second look, more than 30 s later, still shows the refill: now it is real.
        entry.RefillHeldSince = DateTimeOffset.UtcNow - RefillGuard.MinConfirmGap - TimeSpan.FromSeconds(5);
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.Equal(0, entry.Usage!.FiveHour!.UsedPercent);
        Assert.Null(entry.RefillHeldSince);
    }

    [Fact]
    public async Task ALowAlertFiresOncePerWindowNotOnEveryRefresh()
    {
        var used = 60.0;
        var http = Codex(() => (200, CodexReplies.Usage(used, 30)));
        var state = await CodexReplies.LiveStateAsync(http);
        var alerts = new List<UsageAlert>();
        state.Alerts += a => alerts.AddRange(a);
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.Empty(alerts);

        used = 95;
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        var low = Assert.Single(alerts);
        Assert.Equal(UsageAlertKind.Low, low.Kind);

        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.Single(alerts);
    }

    [Fact]
    public async Task SigningOutForgetsTheAccountAndHidesItsPage()
    {
        var http = Codex(() => (200, CodexReplies.Usage(10, 5)));
        var state = await CodexReplies.LiveStateAsync(http);
        state.Settings.Update(s => s with { EnabledProviders = ["codex", "claude_code"] });
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.NotEmpty(state[UsageProvider.Codex].History.FiveHour.Samples);
        Assert.Contains(UsageProvider.Codex, state.EnabledProviders);

        await state.SignOutAsync(UsageProvider.Codex);
        var entry = state[UsageProvider.Codex];
        Assert.False(entry.SignedIn);
        Assert.Null(entry.Usage);
        Assert.Empty(entry.History.FiveHour.Samples);
        Assert.DoesNotContain(UsageProvider.Codex, state.EnabledProviders);
        Assert.DoesNotContain(UsageProvider.Codex, state.SignedIn);
        // Nothing to refresh any more.
        var asked = http.Calls(CodexService.UsageUrl);
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        Assert.Equal(asked, http.Calls(CodexService.UsageUrl));
    }

    [Fact]
    public async Task TheLastPageStaysAfterSigningOut()
    {
        var http = Codex(() => (200, CodexReplies.Usage(10, 5)));
        var state = await CodexReplies.LiveStateAsync(http);
        state.Settings.Update(s => s with { EnabledProviders = ["codex"] });
        await state.SignOutAsync(UsageProvider.Codex);
        Assert.Equal([UsageProvider.Codex], state.EnabledProviders);
    }

    [Fact]
    public async Task MovingAProviderReordersTheSidebar()
    {
        var state = await States.DemoAsync(enabled: ["codex", "claude_code", "cursor"]);
        Assert.Equal([UsageProvider.Codex, UsageProvider.ClaudeCode, UsageProvider.Cursor], state.EnabledProviders);
        state.Move(UsageProvider.Cursor, -2);
        Assert.Equal([UsageProvider.Cursor, UsageProvider.Codex, UsageProvider.ClaudeCode], state.EnabledProviders);
        // Past the end stays at the end.
        state.Move(UsageProvider.Cursor, 99);
        Assert.Equal(UsageProvider.Cursor, state.SidebarCandidates[^1]);
    }

    [Fact]
    public async Task HiddenDashboardCardsStayHidden()
    {
        var state = await States.DemoAsync(enabled: ["codex"]);
        Assert.Contains(DashboardSections.Weekly, state.VisibleSections(UsageProvider.Codex));
        state.Settings.Update(s => s.WithSections("codex", s.SectionOrderFor("codex"), [DashboardSections.Weekly]));
        Assert.DoesNotContain(DashboardSections.Weekly, state.VisibleSections(UsageProvider.Codex));
        Assert.Contains(DashboardSections.FiveHour, state.VisibleSections(UsageProvider.Codex));
    }
}
