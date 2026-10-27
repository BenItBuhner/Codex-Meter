using FlaUI.Core.AutomationElements;
using FlaUI.Core.Definitions;
using Xunit;

namespace CodexMeter.App.UITests;

public class AppTests
{
    [Fact]
    public void OpensWithAPageForEveryProvider()
    {
        using var app = new AppSession();
        Assert.Equal("Codex Meter", app.Window.Title);
        foreach (var id in new[] { "Nav_codex", "Nav_claude_code", "Nav_cursor", "Nav_accounts" })
            Assert.NotNull(app.Find(id));
        Assert.Equal("Codex", app.TextOf("DashboardTitle"));
    }

    [Fact]
    public void CodexDashboardShowsMetersAndResets()
    {
        using var app = new AppSession("--page", "codex");
        Assert.NotNull(app.Find("Meter_fiveHour"));
        Assert.NotNull(app.Find("Meter_weekly"));
        Assert.NotNull(app.Find("ResetCreditsCard"));
        Assert.Equal("2 resets available", app.TextOf("ResetCreditCount"));
        Assert.False(string.IsNullOrWhiteSpace(app.TextOf("PlanBadge")));
    }

    [Fact]
    public void UsingAResetAsksFirstThenUpdatesTheCount()
    {
        using var app = new AppSession("--page", "codex");
        app.Find("UseResetButton").AsButton().Invoke();
        app.Find("PrimaryButton").AsButton().Invoke();
        app.WaitUntil(() => app.TextOf("ResetCreditCount") == "1 reset available", "The reset count never went down.");
        Assert.NotNull(app.Find("StatusBar"));
    }

    [Fact]
    public void EachProviderHasItsOwnDashboard()
    {
        using var app = new AppSession();
        app.Open("Nav_claude_code");
        app.WaitUntil(() => app.TextOf("DashboardTitle") == "Claude Code", "Claude Code page did not open.");
        Assert.NotNull(app.Find("Meter_fiveHour"));
        app.Open("Nav_cursor");
        app.WaitUntil(() => app.TextOf("DashboardTitle") == "Cursor", "Cursor page did not open.");
        Assert.NotNull(app.Find("Meter_weekly"));
    }

    [Fact]
    public void SigningOutMovesTheAccountAndHidesItsPage()
    {
        using var app = new AppSession("--page", "accounts");
        Assert.Equal("Accounts", app.TextOf("AccountsTitle"));
        app.Find("SignOut_cursor").AsButton().Invoke();
        app.Find("PrimaryButton").AsButton().Invoke();
        Assert.NotNull(app.Find("SignIn_cursor"));
        app.WaitUntil(() => app.TryFind("Nav_cursor", TimeSpan.FromMilliseconds(300)) is null, "Cursor stayed in the sidebar.");
    }

    [Fact]
    public void SignedOutDashboardOffersSignIn()
    {
        using var app = new AppSession("--demo-signed-out", "--page", "codex");
        Assert.NotNull(app.Find("SignInButton"));
        Assert.Null(app.TryFind("Meter_fiveHour", TimeSpan.FromSeconds(1)));
    }

    [Fact]
    public void SettingsChangeTheDashboard()
    {
        using var app = new AppSession("--page", "settings");
        Assert.Equal("Settings", app.TextOf("SettingsTitle"));
        var display = app.Find("DisplayBox").AsComboBox();
        display.Select("Used");
        var hide = app.Find("ProviderToggle_claude_code").AsToggleButton();
        Assert.Equal(ToggleState.On, hide.ToggleState);
        hide.Toggle();
        app.WaitUntil(() => app.TryFind("Nav_claude_code", TimeSpan.FromMilliseconds(300)) is null, "Claude Code stayed in the sidebar.");
        app.Open("Nav_codex");
        app.WaitUntil(() => app.TextOf("DashboardTitle") == "Codex", "Codex page did not open.");
        app.WaitUntil(() => app.Find("Meter_fiveHour").Name.Contains("used", StringComparison.OrdinalIgnoreCase),
            "The meter did not switch to showing usage.");
    }
}
