using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Views;

namespace CodexMeter.Linux.Tests;

/// <summary>
/// The real dashboard page, built and rendered by GTK: what a person reads on it after each kind of
/// refresh (fresh numbers, a failure, a reached limit, no account).
/// </summary>
[Collection(UiCollection.Name)]
public sealed class DashboardPageTests(UiHost host)
{
    private static FakeHttp Codex(Func<(int, string)> usage) =>
        new FakeHttp().On("GET", CodexService.UsageUrl, usage).On("GET", CodexService.CreditsUrl, 200, "{}");

    private static async Task<(MainWindow Window, DashboardPage Page)> OpenAsync(UiHost host, Services.AppState state, string tag)
    {
        var window = new MainWindow(host.Application, state);
        window.Navigate(tag);
        window.Window.Present();
        await UiHost.Settle(100);
        return (window, Assert.IsType<DashboardPage>(window.CurrentPage));
    }

    /// <summary>The ring's number and the line under it: "60" over "% left", then "40% used".</summary>
    private static string Meter(DashboardPage page, string name)
    {
        var texts = Tree.Texts(Tree.Require(page.Page, name));
        return $"{texts[0]}{texts[1]} · {texts[3]}";
    }

    [Fact]
    public Task MetersShowWhatTheServiceReturnedAndRefreshReplacesIt() => host.RunAsync(async () =>
    {
        var used = 40.0;
        var state = await CodexReplies.LiveStateAsync(Codex(() => (200, CodexReplies.Usage(used, 75))));
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        var (window, page) = await OpenAsync(host, state, "codex");
        try
        {
            Assert.Equal("60% left · 40% used", Meter(page, "Meter_fiveHour"));
            Assert.Equal("25% left · 75% used", Meter(page, "Meter_weekly"));
            Assert.StartsWith("dev@example.com", Tree.Text(Tree.Require(page.Page, "DashboardSubtitle")));
            Assert.Null(Tree.Named(page.Page, "StatusNotice"));
            // The sidebar row carries the same 5-hour number.
            Assert.Equal("60%", Tree.Texts(Tree.Require(window.Window, "Nav_codex"))[^1]);

            // Refresh asks again and every surface shows the new numbers, not the old ones.
            used = 70;
            Tree.Require(page.Page, "RefreshButton").Activate();
            await UiHost.Until(() => Meter(page, "Meter_fiveHour").StartsWith("30%", StringComparison.Ordinal));
            Assert.Equal("30% left · 70% used", Meter(page, "Meter_fiveHour"));
            Assert.Equal("30%", Tree.Texts(Tree.Require(window.Window, "Nav_codex"))[^1]);
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task AFailedRefreshKeepsTheNumbersUnderAWarning() => host.RunAsync(async () =>
    {
        var status = 200;
        var state = await CodexReplies.LiveStateAsync(Codex(() => (status, status == 200 ? CodexReplies.Usage(40, 75) : "{}")));
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        var (window, page) = await OpenAsync(host, state, "codex");
        try
        {
            status = 503;
            Tree.Require(page.Page, "RefreshButton").Activate();
            await UiHost.Until(() => Tree.Named(page.Page, "StatusNotice") is not null);
            var notice = Tree.Text(Tree.Require(page.Page, "StatusNotice"));
            Assert.StartsWith("Showing the last update", notice);
            Assert.Equal("60% left · 40% used", Meter(page, "Meter_fiveHour"));
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task NoDataAndAFailureSaysUsageDidNotLoad() => host.RunAsync(async () =>
    {
        var state = await CodexReplies.LiveStateAsync(Codex(() => (500, "{}")));
        var (window, page) = await OpenAsync(host, state, "codex");
        try
        {
            // Opening the page asks once on its own.
            await UiHost.Until(() => Tree.Named(page.Page, "StatusNotice") is not null);
            Assert.StartsWith("Couldn't load usage", Tree.Text(Tree.Require(page.Page, "StatusNotice")));
            Assert.Null(Tree.Named(page.Page, "MeterCards"));
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task AReachedLimitSaysUsageIsPaused() => host.RunAsync(async () =>
    {
        var state = await CodexReplies.LiveStateAsync(Codex(() => (200, CodexReplies.Usage(100, 80, limitReached: true))));
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        var (window, page) = await OpenAsync(host, state, "codex");
        try
        {
            var notice = Tree.Texts(Tree.Require(page.Page, "StatusNotice"));
            Assert.Equal("Limit reached", notice[0]);
            Assert.StartsWith("Codex usage is paused until a window resets (in ", notice[1]);
            Assert.StartsWith("0% left", Meter(page, "Meter_fiveHour"));
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task ASignedOutServiceOffersSignInAndNoRefresh() => host.RunAsync(async () =>
    {
        var state = await CodexReplies.LiveStateAsync(Codex(() => (200, CodexReplies.Usage(1, 1))), signedIn: false);
        var (window, page) = await OpenAsync(host, state, "codex");
        try
        {
            Tree.Require(page.Page, "SignedOutPanel");
            Assert.True(Tree.Require(page.Page, "SignInButton").GetSensitive());
            Assert.False(Tree.Require(page.Page, "RefreshButton").GetVisible());
            Assert.False(Tree.Require(page.Page, "EditDashboardButton").GetVisible());
            Assert.Contains("Not connected", Tree.Text(Tree.Require(page.Page, "DashboardSubtitle")));
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task HidingACardInEditDashboardRemovesItAtOnce() => host.RunAsync(async () =>
    {
        var state = await States.DemoAsync(enabled: ["codex", "claude_code"]);
        var (window, page) = await OpenAsync(host, state, "codex");
        try
        {
            Tree.Require(page.Page, "Meter_weekly");
            state.Settings.Update(s => s.WithSections("codex", s.SectionOrderFor("codex"), [DashboardSections.Weekly]));
            await UiHost.Settle();
            Assert.Null(Tree.Named(page.Page, "Meter_weekly"));
            Tree.Require(page.Page, "Meter_fiveHour");
        }
        finally { window.Window.Destroy(); }
    });
}
