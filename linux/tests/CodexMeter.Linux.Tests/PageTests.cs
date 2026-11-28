using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;
using CodexMeter.Linux.Views;

namespace CodexMeter.Linux.Tests;

/// <summary>
/// The sidebar, Providers, Settings and Priority pages as GTK builds them, including the rules that
/// came back as bugs before: lists show only signed-in services, signing out takes the service off
/// every surface, and a used-up limit shows on Priority.
/// </summary>
[Collection(UiCollection.Name)]
public sealed class PageTests(UiHost host)
{
    private static async Task<MainWindow> OpenAsync(UiHost host, AppState state, string tag)
    {
        var window = new MainWindow(host.Application, state);
        window.Navigate(tag);
        window.Window.Present();
        await UiHost.Settle(100);
        Assert.Equal(tag, window.CurrentTag);
        return window;
    }

    private static List<string> Items(Gtk.Widget combo)
    {
        var model = ((Adw.ComboRow)combo).GetModel()!;
        return Enumerable.Range(0, (int)model.GetNItems()).Select(i => ((Gtk.StringObject)model.GetObject((uint)i)!).GetString()).ToList();
    }

    private static List<string> NavTags(MainWindow window) =>
        Tree.All(window.Window).Select(w => w.GetName()).Where(n => n.StartsWith("Nav_", StringComparison.Ordinal)).Select(n => n[4..]).ToList();

    [Fact]
    public Task TheSidebarFollowsTheUsersOrderWithPriorityFirst() => host.RunAsync(async () =>
    {
        var state = await States.DemoAsync(enabled: ["cursor", "codex", "claude_code"]);
        state.Settings.Update(s => s with { ProviderOrder = ["cursor", "codex", "claude_code"], ShowPriority = true });
        var window = await OpenAsync(host, state, "codex");
        try
        {
            Assert.Equal(["priority", "cursor", "codex", "claude_code", "add", "accounts", "settings"], NavTags(window));
            state.Move(UsageProvider.ClaudeCode, -2);
            await UiHost.Settle();
            Assert.Equal(["priority", "claude_code", "cursor", "codex"], NavTags(window).Take(4));
            // Turning Priority off drops its row.
            state.Settings.Update(s => s with { ShowPriority = false });
            await UiHost.Settle();
            Assert.DoesNotContain("priority", NavTags(window));
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task SigningOutFromProvidersTakesTheServiceOffEverySurface() => host.RunAsync(async () =>
    {
        var state = await States.DemoAsync(enabled: ["codex", "claude_code", "cursor"]);
        var window = await OpenAsync(host, state, "accounts");
        try
        {
            var page = Assert.IsType<AccountsPage>(window.CurrentPage);
            Tree.Require(page.Page, "Account_cursor");
            Assert.Contains("cursor", NavTags(window));

            var dialog = page.ConfirmSignOut(UsageProvider.Cursor);
            await UiHost.Settle();
            var signOut = Tree.All(dialog).OfType<Gtk.Button>().Single(b => b.GetLabel() == "_Sign Out");
            signOut.Activate();
            await UiHost.Until(() => !state[UsageProvider.Cursor].SignedIn && Tree.Named(page.Page, "Account_cursor") is null);

            Assert.False(state[UsageProvider.Cursor].SignedIn);
            Assert.Null(Tree.Named(page.Page, "SignOut_cursor"));
            Assert.DoesNotContain("cursor", NavTags(window));
            Assert.DoesNotContain(UsageProvider.Cursor, state.EnabledProviders);
            // The others stay connected.
            Tree.Require(page.Page, "Account_codex");
            Tree.Require(page.Page, "SignOut_claude_code");
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task TheLastShownServiceCannotBeSwitchedOff() => host.RunAsync(async () =>
    {
        var state = await States.DemoAsync(enabled: ["codex"]);
        var window = await OpenAsync(host, state, "accounts");
        try
        {
            var page = window.CurrentPage!;
            var codex = (Gtk.Switch)Tree.Require(page.Page, "ProviderToggle_codex");
            Assert.True(codex.GetActive());
            Assert.False(codex.GetSensitive());
            var cursor = (Gtk.Switch)Tree.Require(page.Page, "ProviderToggle_cursor");
            Assert.False(cursor.GetActive());
            Assert.True(cursor.GetSensitive());
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task SettingsListsOnlySignedInServices() => host.RunAsync(async () =>
    {
        // Codex and Claude Code connected; Cursor and every added service signed out.
        var signedOut = UsageProvider.All.Select(p => p.Id).Where(id => id is not ("codex" or "claude_code"));
        var state = await States.DemoAsync(signedOut, enabled: ["codex", "claude_code", "cursor"]);
        var window = await OpenAsync(host, state, "settings");
        try
        {
            var page = window.CurrentPage!;
            Assert.Equal(["Automatic", "Codex", "Claude Code"], Items(Tree.Require(page.Page, "LiveProviderBox")));
            Tree.Require(page.Page, "ResetsRow_codex");
            Tree.Require(page.Page, "ResetsRow_claude_code");
            // "While using AI apps" lists only apps of connected services.
            var apps = Tree.All(Tree.Require(page.Page, "LiveAppsGroup")).Select(w => w.GetName()).Where(n => n.StartsWith("LiveApp_", StringComparison.Ordinal)).ToList();
            Assert.NotEmpty(apps);
            var shown = AppMappings.Shown(AppMappings.Resolve(state.Settings.Current), state.SignedIn).ToList();
            Assert.All(shown, rule => Assert.Contains(rule.Provider, state.SignedIn));
            Assert.Equal(shown.Select(r => "LiveApp_" + r.Key), apps);
            Assert.DoesNotContain(AppMappings.Resolve(state.Settings.Current), r => r.Provider == UsageProvider.Cursor && apps.Contains("LiveApp_" + r.Key));

            // The Add/Edit app picker offers the same connected services.
            var settings = Assert.IsType<SettingsPage>(page);
            var dialog = settings.EditLiveApp(null);
            await UiHost.Settle();
            Assert.Equal(["Codex", "Claude Code"], Items(Tree.Require(dialog, "LiveAppServiceBox")));
            dialog.ForceClose();
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task ResetSettingsHideWithoutAServiceThatHasResets() => host.RunAsync(async () =>
    {
        var signedOut = UsageProvider.All.Select(p => p.Id).Where(id => id != "cursor");
        var state = await States.DemoAsync(signedOut, enabled: ["cursor"]);
        var window = await OpenAsync(host, state, "settings");
        try
        {
            var page = window.CurrentPage!;
            Assert.Null(Tree.Named(page.Page, "ResetsRow_codex"));
            Assert.Null(Tree.Named(page.Page, "ResetsRow_claude_code"));
            Assert.Null(Tree.Named(page.Page, "AutoUseExpiringSwitch"));
            Assert.Equal(["Automatic", "Cursor"], Items(Tree.Require(page.Page, "LiveProviderBox")));
        }
        finally { window.Window.Destroy(); }
    });

    [Fact]
    public Task AUsedUpLimitShowsOnPriority() => host.RunAsync(async () =>
    {
        var http = new FakeHttp()
            .On("GET", CodexService.UsageUrl, 200, CodexReplies.Usage(100, 60, limitReached: true))
            .On("GET", CodexService.CreditsUrl, 200, "{}");
        var state = await CodexReplies.LiveStateAsync(http);
        state.Settings.Update(s => s with { ShowPriority = true, ShowServiceStatus = false, ShowSlowdowns = false });
        await state.RefreshAsync(UsageProvider.Codex, force: true);
        var window = await OpenAsync(host, state, "priority");
        try
        {
            var page = window.CurrentPage!;
            var usedUp = Tree.Text(Tree.Require(page.Page, "PriorityUsedUp"));
            Assert.Contains("Codex", usedUp);
            Assert.Contains("5-hour", usedUp);
            Assert.Null(Tree.Named(page.Page, "PriorityService"));
            Assert.Equal(state.Priority(DateTimeOffset.UtcNow).Headline,
                Tree.Texts(Tree.Require(page.Page, "PriorityHeadline")).First(t => t != "Priority"));
        }
        finally { window.Window.Destroy(); }
    });
}
