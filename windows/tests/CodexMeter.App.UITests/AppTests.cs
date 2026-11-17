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
        foreach (var id in new[] { "Nav_codex", "Nav_claude_code", "Nav_cursor", "Nav_copilot", "Nav_kimi", "Nav_zai", "Nav_priority", "Nav_add", "Nav_accounts", "AppTitleBar" })
            Assert.NotNull(app.Find(id));
        Assert.Equal("Codex", app.TextOf("DashboardTitle"));
    }

    [Fact]
    public void TitleBarIsAsTallAsTheCaptionButtons()
    {
        using var app = new AppSession();
        var scale = GetDpiForWindow(app.Window.Properties.NativeWindowHandle.Value) / 96.0;
        // The window uses the tall (48px) caption buttons, so the title bar must be 48px too.
        Assert.InRange(app.Find("AppTitleBar").BoundingRectangle.Height, 47 * scale, 49 * scale);
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    [Fact]
    public void CodexDashboardShowsMetersAndResets()
    {
        using var app = new AppSession("--page", "codex");
        Assert.NotNull(app.Find("Meter_fiveHour"));
        Assert.NotNull(app.Find("Meter_weekly"));
        Assert.NotNull(app.Find("ResetCreditsCard"));
        Assert.Equal("2 resets available", app.TextOf("ResetCreditCount"));
        Assert.False(string.IsNullOrWhiteSpace(app.TextOf("PlanBadge")));
        Assert.NotNull(app.Find("HistoryCard"));
        Assert.NotNull(app.Find("RefreshButton"));
    }

    [Fact]
    public void EditDashboardHidesACard()
    {
        using var app = new AppSession("--page", "codex");
        Assert.NotNull(app.Find("Meter_weekly"));
        app.Find("EditDashboardButton").AsButton().Invoke();
        app.Find("Section_weekly").AsCheckBox().Toggle();
        app.Find("PrimaryButton").AsButton().Invoke();
        app.WaitUntil(() => app.TryFind("Meter_weekly", TimeSpan.FromMilliseconds(300)) is null, "The weekly card stayed after hiding it.");
        Assert.NotNull(app.Find("Meter_fiveHour"));
    }

    [Fact]
    public void AddAccountSearchesAndStartsADeviceCodeSignIn()
    {
        using var app = new AppSession("--page", "accounts");
        app.Find("AddAccountButton").AsButton().Invoke();
        app.Find("AddAccountSearch").AsTextBox().Enter("factory");
        app.WaitUntil(() => app.TryFind("Pick_cursor", TimeSpan.FromMilliseconds(300)) is null, "Search did not filter the list.");
        app.Find("Pick_factory").Click();
        app.WaitUntil(() => app.TextOf("DeviceCode") == "WDJB-MJHT", "The sign-in code never appeared.");
        Assert.NotNull(app.Find("OpenDevicePage"));
    }

    [Fact]
    public void FirstRunShowsWelcome()
    {
        using var app = new AppSession("--demo-signed-out");
        Assert.Equal("Welcome to Codex Meter", app.TextOf("WelcomeTitle"));
        Assert.NotNull(app.Find("Welcome_codex"));
        Assert.NotNull(app.Find("WelcomeMore"));
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
        app.WaitUntil(() => app.TryFind("Account_cursor", TimeSpan.FromMilliseconds(300)) is null, "Cursor stayed connected.");
        app.WaitUntil(() => app.TryFind("Nav_cursor", TimeSpan.FromMilliseconds(300)) is null, "Cursor stayed in the sidebar.");
        app.Find("AddAccountButton").AsButton().Invoke();
        Assert.NotNull(app.Find("Pick_cursor"));
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

    [Fact]
    public void LiveUsageSettingsMirrorTheLiveNotification()
    {
        using var app = new AppSession("--page", "settings");
        var tray = app.Find("TraySwitch");
        Assert.Equal(ToggleState.On, tray.AsToggleButton().ToggleState);
        Assert.Equal(ToggleState.On, app.Find("LiveFollowAppsSwitch").AsToggleButton().ToggleState);
        var provider = app.Find("LiveProviderBox").AsComboBox();
        Assert.Equal("Automatic", provider.SelectedItem?.Name);
        provider.Select("Claude Code");
        var window = app.Find("LiveWindowBox").AsComboBox();
        Assert.Equal("Auto", window.SelectedItem?.Name);
        window.Select("Weekly usage");
        var style = app.Find("LiveStyleBox").AsComboBox();
        Assert.Equal("Ring", style.SelectedItem?.Name);
        style.Select("Number");
        app.WaitUntil(() => app.Find("LiveStyleBox").AsComboBox().SelectedItem?.Name == "Number", "The icon style did not change.");
    }

    [Fact]
    public void AddedAiAppCanBeSwitchedOffAndRemoved()
    {
        using var app = new AppSession("--page", "settings");
        Assert.NotNull(app.Find("LiveApp_grokbot"));
        app.Find("LiveAppAddButton").AsButton().Invoke();
        var name = app.Find("LiveAppNameBox");
        var text = name.ControlType == ControlType.Edit ? name : name.FindFirstDescendant(cf => cf.ByControlType(ControlType.Edit))
            ?? throw new InvalidOperationException("The app name box has no text field.");
        text.AsTextBox().Text = "Zed";
        app.Find("LiveAppServiceBox").AsComboBox().Select("Cursor");
        // Save turns on once the name box reports the text, which it does asynchronously.
        app.WaitUntil(() => app.Find("PrimaryButton").IsEnabled, "Save stayed off after typing a name.");
        app.Find("PrimaryButton").AsButton().Invoke();
        var toggle = app.Find("LiveAppToggle_zed").AsToggleButton();
        Assert.Equal(ToggleState.On, toggle.ToggleState);
        Assert.NotNull(app.FindByName("Cursor · Added by you"));
        toggle.Toggle();
        app.WaitUntil(() => app.Find("LiveAppToggle_zed").AsToggleButton().ToggleState == ToggleState.Off, "Zed stayed on.");
        Assert.True(app.Find("LiveAppsRestoreButton").IsEnabled);
        app.Find("LiveAppRemove_zed").AsButton().Invoke();
        app.WaitUntil(() => app.TryFind("LiveApp_zed", TimeSpan.FromMilliseconds(300)) is null, "Zed stayed in the list.");
    }
}
