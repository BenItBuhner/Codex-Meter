using CodexMeter.App.Assets;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Core.Updates;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Views;

/// <summary>
/// Settings as libadwaita preference groups, with the same options and names as the Windows app
/// and the phone apps: Usage (Providers, Dashboard, Reset credits), Alerts &amp; live usage
/// (Notifications, Live usage), App (Appearance, Updates, Privacy &amp; data) and About.
/// </summary>
public sealed class SettingsPage : IPage
{
    private static readonly LowUsageAutoUseMetric[] AlertFor = [LowUsageAutoUseMetric.Both, LowUsageAutoUseMetric.FiveHour, LowUsageAutoUseMetric.Weekly];
    private const string SlowdownNote = "On the Priority page only when a Codex, Claude Code or Grok model runs much slower than usual, from public OpenRouter figures for each maker's newest models. Not your subscription's own speed.";
    private const string NewModelsNote = "When OpenRouter lists a new model from a maker you're signed in to (OpenAI for Codex, Anthropic for Claude Code, ...). Tools that offer many makers' models, like Cursor or Copilot, add none.";
    private readonly AppState state;
    private readonly Adw.PreferencesPage preferences;
    private readonly Adw.PreferencesGroup providersGroup;
    private readonly Adw.ActionRow providersRow;
    private readonly Adw.SwitchRow closeToTray;
    private readonly MainWindow window;
    private readonly Adw.PreferencesGroup appsGroup = Adw.PreferencesGroup.New();
    private readonly List<Gtk.Widget> appRows = [];
    private bool loading;

    public SettingsPage(AppState state, MainWindow window)
    {
        this.state = state;
        this.window = window;
        preferences = Adw.PreferencesPage.New();
        preferences.SetName("SettingsPage");
        var view = Adw.ToolbarView.New();
        view.AddTopBar(Adw.HeaderBar.New());
        view.SetContent(preferences);
        Page = Adw.NavigationPage.New(view, "Settings");
        Page.SetTag("settings");
        loading = true;
        var s = state.Settings.Current;

        // Usage: providers and their accounts, each dashboard, reset credits.
        Heading("Usage");
        providersGroup = Group("Providers");
        providersRow = Adw.ActionRow.New();
        providersRow.SetTitle("Providers");
        providersRow.SetActivatable(true);
        providersRow.SetName("ProvidersRow");
        providersRow.AddSuffix(Gtk.Image.NewFromIconName("go-next-symbolic"));
        providersRow.OnActivated += (_, _) => window.Navigate("accounts");
        providersGroup.Add(providersRow);
        providersGroup.Add(Switch("Priority page", "First in the sidebar: what needs you across every connected service.",
            s.ShowPriority, on => state.Settings.Update(x => x with { ShowPriority = on }), "PrioritySwitch"));

        var dashboard = Group("Dashboard");
        dashboard.Add(Combo("Check usage every", "While Codex Meter runs. Some services allow fewer checks.",
            ["5 minutes", "15 minutes", "30 minutes", "1 hour"],
            (uint)Math.Max(0, Array.IndexOf(AppSettings.RefreshChoices, s.RefreshMinutes)),
            i => state.Settings.Update(x => x with { RefreshMinutes = AppSettings.RefreshChoices[i] }), "RefreshRow"));
        dashboard.Add(Combo("Show usage as", "The number in each ring.", ["Remaining", "Used"],
            s.DisplayMode == UsageDisplayMode.Remaining ? 0u : 1u,
            i => state.Settings.Update(x => x with { DisplayMode = i == 1 ? UsageDisplayMode.Used : UsageDisplayMode.Remaining }), "DisplayRow"));
        dashboard.Add(Combo("Reset times", "How each card says when its window resets.", ["Countdown", "Date and time", "Both"],
            (uint)s.ResetDisplay, i => state.Settings.Update(x => x with { ResetDisplay = (ResetDisplayMode)i })));
        dashboard.Add(Switch("24-hour clock", null, s.Use24HourClock, on => state.Settings.Update(x => x with { Use24HourClock = on })));
        dashboard.Add(Switch("Usage estimates", "How long your current allowance may last at your pace.", s.ShowPace,
            on => state.Settings.Update(x => x with { ShowPace = on }), "PaceSwitch"));
        dashboard.Add(Combo("Fast-usage warnings", "How readily a card warns that a window would run out before it resets.",
            UsagePace.Choices.Select(UsagePace.Label).ToArray(), (uint)Math.Max(0, Array.IndexOf(UsagePace.Choices, s.PaceSensitivity)),
            i => state.Settings.Update(x => x with { PaceSensitivity = UsagePace.Choices[i] }), "PaceSensitivityRow"));
        dashboard.Add(Switch("Service status", "Official outages and maintenance for Codex, Claude Code, Cursor and Grok, from each status page.",
            s.ShowServiceStatus, on => state.Settings.Update(x => x with { ShowServiceStatus = on }), "ServiceStatusSwitch"));
        dashboard.Add(Switch("Model slowdown warnings", SlowdownNote,
            s.ShowSlowdowns, on => state.Settings.Update(x => x with { ShowSlowdowns = on }), "SlowdownSwitch"));
        var highlights = Adw.ExpanderRow.New();
        highlights.SetTitle("Chart highlights");
        highlights.SetSubtitle("What appears under the usage history chart.");
        highlights.SetName("HistoryHighlights");
        foreach (var key in HistorySections.All)
        {
            highlights.AddRow(Switch(HistorySections.Label(key), null, HistorySections.IsVisible(s.HistoryOverrides, key),
                on => state.Settings.Update(x => x with { HistoryOverrides = HistorySections.SetVisible(x.HistoryOverrides, key, on) }),
                "Highlight_" + key));
        }
        dashboard.Add(highlights);
        var recorded = Adw.ActionRow.New();
        recorded.SetTitle("Usage history");
        recorded.SetSubtitle("Saved on this computer each time usage refreshes, and cleared when you sign out. Use Edit Dashboard on a service's page to hide or reorder its cards.");
        var clear = Gtk.Button.NewWithLabel("Clear All…");
        clear.SetValign(Gtk.Align.Center);
        clear.SetName("ClearAllHistory");
        clear.OnClicked += (_, _) =>
        {
            var confirm = Adw.AlertDialog.New("Clear all usage history?",
                "Every chart starts over from the next refresh. Accounts and current usage aren't affected.");
            confirm.AddResponse("cancel", "_Cancel");
            confirm.AddResponse("clear", "_Clear History");
            confirm.SetResponseAppearance("clear", Adw.ResponseAppearance.Destructive);
            confirm.SetDefaultResponse("cancel");
            confirm.SetCloseResponse("cancel");
            confirm.OnResponse += async (_, args) =>
            {
                if (args.Response == "clear") await state.ClearHistoryAsync();
            };
            confirm.Present(window.Window);
        };
        recorded.AddSuffix(clear);
        dashboard.Add(recorded);

        // Reset credits: one section per connected service that has them. Auto-use watches both
        // the 5-hour and weekly limits, so there is no picker for which one.
        var leads = CreditExpiryAutoApply.LeadMinuteOptions;
        var thresholds = LowUsageAutoUse.ThresholdOptions;
        string[] leadLabels = [.. leads.Select(CreditExpiryAutoApply.LeadLabel)];
        string[] thresholdLabels = [.. thresholds.Select(t => $"{t}% left")];
        var codexResets = state.Supports(UsageProvider.Codex) && state[UsageProvider.Codex].SignedIn;
        var claudeResets = state.Supports(UsageProvider.ClaudeCode) && state[UsageProvider.ClaudeCode].SignedIn;
        if (codexResets || claudeResets)
            Group("Reset credits", "Spend resets automatically so they don't go to waste. Auto-use watches both the 5-hour and weekly limits.");
        if (codexResets)
        {
            var codex = Group("Codex");
            codex.Add(ResetsRow(UsageProvider.Codex));
            var expiring = Expander("Use before they expire", "Spend a reset shortly before it would expire unused.",
                s.AutoUseExpiringResets, on => state.Settings.Update(x => x with { AutoUseExpiringResets = on }), "AutoUseExpiringSwitch");
            expiring.AddRow(Combo("When to use it", null, leadLabels,
                (uint)Math.Max(0, leads.ToList().IndexOf(CreditExpiryAutoApply.NormalizedLeadMinutes(s.AutoUseLeadMinutes))),
                i => state.Settings.Update(x => x with { AutoUseLeadMinutes = leads[i] }), "AutoUseLeadRow"));
            codex.Add(expiring);
            var low = Expander("Use when running low", "Spend a reset when a 5-hour or weekly limit is nearly used up, unless it refills on its own soon anyway.",
                s.AutoUseWhenLow, on => state.Settings.Update(x => x with { AutoUseWhenLow = on }), "AutoUseLowSwitch");
            low.AddRow(Combo("Use when remaining reaches", null, thresholdLabels,
                (uint)Math.Max(0, thresholds.ToList().IndexOf(LowUsageAutoUse.NormalizedThreshold(s.AutoUseThreshold))),
                i => state.Settings.Update(x => x with { AutoUseThreshold = thresholds[i] }), "AutoUseThresholdRow"));
            low.AddRow(WaitCombo("Wait for a 5-hour refill", false, s.AutoUseFiveHourWaitMinutes,
                m => state.Settings.Update(x => x with { AutoUseFiveHourWaitMinutes = m }), "AutoUseFiveHourWaitRow"));
            low.AddRow(WaitCombo("Wait for a weekly refill", true, s.AutoUseWeeklyWaitMinutes,
                m => state.Settings.Update(x => x with { AutoUseWeeklyWaitMinutes = m }), "AutoUseWeeklyWaitRow"));
            codex.Add(low);
            if (s.AutoUseWhenLow && ResetCreditsCard.RefillWaitText(s.AutoUseRefillWait, s, DateTimeOffset.UtcNow) is { } codexWait)
                codex.Add(InfoRow("Waiting for a refill", codexWait, "AutoUseRefillWaitRow"));
        }
        if (claudeResets)
        {
            var rules = s.ClaudeResets;
            var claude = Group("Claude Code", "Saved resets Anthropic gives out. Each one refills a limit you've hit.");
            claude.Add(ResetsRow(UsageProvider.ClaudeCode));
            var expiring = Expander("Use before they expire", "Spend a saved reset shortly before it would expire, once you're at a limit it refills.",
                rules.AutoUseExpiring, on => state.Settings.Update(x => x with { ClaudeResets = x.ClaudeResets with { AutoUseExpiring = on } }), "ClaudeAutoUseExpiringSwitch");
            expiring.AddRow(Combo("When to use it", null, leadLabels,
                (uint)Math.Max(0, leads.ToList().IndexOf(CreditExpiryAutoApply.NormalizedLeadMinutes(rules.LeadMinutes))),
                i => state.Settings.Update(x => x with { ClaudeResets = x.ClaudeResets with { LeadMinutes = leads[i] } }), "ClaudeAutoUseLeadRow"));
            claude.Add(expiring);
            var low = Expander("Use when running low", "Spend a saved reset when a 5-hour or weekly limit is nearly used up, unless it refills on its own soon anyway.",
                rules.AutoUseWhenLow, on => state.Settings.Update(x => x with { ClaudeResets = x.ClaudeResets with { AutoUseWhenLow = on } }), "ClaudeAutoUseLowSwitch");
            low.AddRow(Combo("Use when remaining reaches", null, thresholdLabels,
                (uint)Math.Max(0, thresholds.ToList().IndexOf(LowUsageAutoUse.NormalizedThreshold(rules.Threshold))),
                i => state.Settings.Update(x => x with { ClaudeResets = x.ClaudeResets with { Threshold = thresholds[i] } }), "ClaudeAutoUseThresholdRow"));
            low.AddRow(WaitCombo("Wait for a 5-hour refill", false, rules.FiveHourWaitMinutes,
                m => state.Settings.Update(x => x with { ClaudeResets = x.ClaudeResets with { FiveHourWaitMinutes = m } }), "ClaudeAutoUseFiveHourWaitRow"));
            low.AddRow(WaitCombo("Wait for a weekly refill", true, rules.WeeklyWaitMinutes,
                m => state.Settings.Update(x => x with { ClaudeResets = x.ClaudeResets with { WeeklyWaitMinutes = m } }), "ClaudeAutoUseWeeklyWaitRow"));
            claude.Add(low);
            if (rules.AutoUseWhenLow && ResetCreditsCard.RefillWaitText(rules.RefillWait, s, DateTimeOffset.UtcNow) is { } claudeWait)
                claude.Add(InfoRow("Waiting for a refill", claudeWait, "ClaudeAutoUseRefillWaitRow"));
            var check = Adw.ActionRow.New();
            check.SetTitle("Check with Anthropic");
            check.SetSubtitle("Asks Anthropic which saved resets this account has, and why one can't be used yet.");
            var checkButton = Gtk.Button.NewWithLabel("Check");
            checkButton.SetValign(Gtk.Align.Center);
            checkButton.SetName("ClaudeCheckResetsButton");
            checkButton.OnClicked += async (_, _) =>
            {
                checkButton.SetSensitive(false);
                string report;
                try { report = await state.CheckClaudeResetsAsync(); }
                catch (Exception error) { report = "The check didn't finish: " + error.Message; }
                checkButton.SetSensitive(true);
                var dialog = Adw.AlertDialog.New("Claude Code saved resets", report);
                dialog.AddResponse("close", "_Close");
                dialog.SetDefaultResponse("close");
                dialog.SetCloseResponse("close");
                dialog.Present(window.Window);
            };
            check.AddSuffix(checkButton);
            claude.Add(check);
        }

        // Alerts & live usage
        Heading("Alerts & live usage");
        var alerts = Group("Notifications");
        var lowAlert = Expander("Low allowance", "When a limit drops to this much or runs out.",
            s.NotifyLow, on => state.Settings.Update(x => x with { NotifyLow = on }), "LowAlertSwitch");
        lowAlert.AddRow(Combo("Alert for", null, ["Both", "5-hour", "Weekly"], (uint)Array.IndexOf(AlertFor, s.LowAlertMetric) is var f && f < 3 ? f : 0,
            i => state.Settings.Update(x => x with { LowAlertMetric = AlertFor[i] }), "LowAlertForRow"));
        var lowChoices = AppSettings.LowThresholdChoices;
        lowAlert.AddRow(Combo("Alert when remaining reaches", null, lowChoices.Select(t => $"{t}% left").ToArray(),
            (uint)Math.Max(0, Array.IndexOf(lowChoices, s.LowThreshold) is var t && t >= 0 ? t : 1),
            i => state.Settings.Update(x => x with { LowThreshold = lowChoices[i] }), "LowThresholdRow"));
        alerts.Add(lowAlert);
        alerts.Add(Switch("Surprise limit refills", "When a limit refills well before its reset time.",
            s.NotifySurpriseRefills, on => state.Settings.Update(x => x with { NotifySurpriseRefills = on }), "SurpriseRefillSwitch"));
        alerts.Add(Switch("New reset credits", "When a connected account gets a new reset credit.",
            s.NotifyNewResets, on => state.Settings.Update(x => x with { NotifyNewResets = on }), "NewResetsSwitch"));
        alerts.Add(Switch("Expiring reset credits", "A day before an unused reset credit expires.",
            s.NotifyExpiringResets, on => state.Settings.Update(x => x with { NotifyExpiringResets = on }), "ExpiringResetsSwitch"));
        alerts.Add(Switch("Limit resets", "When a 5-hour, weekly or monthly window starts over.",
            s.NotifyResets, on => state.Settings.Update(x => x with { NotifyResets = on }), "ResetAlertSwitch"));
        alerts.Add(Switch("Usage is back", "When a limit you used up resets, so you know you can work again.",
            s.NotifyUsageBack, on => state.Settings.Update(x => x with { NotifyUsageBack = on }), "UsageBackSwitch"));
        alerts.Add(Switch("New models", NewModelsNote,
            s.NotifyNewModels, on => state.Settings.Update(x => x with { NotifyNewModels = on }), "NewModelsSwitch"));

        var live = Group("Live usage");
        closeToTray = Switch("Keep running when the window is closed", "Closing hides the window; quit from the tray icon or the main menu.",
            s.CloseToTray, on => state.Settings.Update(x => x with { CloseToTray = on }));
        live.Add(Switch("Live usage in the system tray",
            "A ring or number that follows your usage, like the live notification on Android. Needs a tray: KDE Plasma, Cinnamon, Xfce, Budgie, or GNOME with the AppIndicator extension.",
            s.ShowTrayIcon, on =>
            {
                state.Settings.Update(x => x with { ShowTrayIcon = on });
                closeToTray.SetSensitive(on);
            }, "TraySwitch"));
        var liveProviders = state.EnabledProviders.Where(p => state[p].SignedIn || p.Id == s.LiveProvider).ToList();
        live.Add(Combo("Service", "Automatic shows the first connected service in the sidebar.",
            ["Automatic", .. liveProviders.Select(p => p.DisplayName)], (uint)(liveProviders.FindIndex(p => p.Id == s.LiveProvider) + 1),
            i => state.Settings.Update(x => x with { LiveProvider = i == 0 ? LiveUsage.AutomaticProvider : liveProviders[i - 1].Id }), "LiveProviderBox"));
        live.Add(Switch("While using AI apps",
            "Show a service's usage while its app is in front. Add any app, or change which service an app shows, below.",
            s.LiveFollowApps, on =>
            {
                state.Settings.Update(x => x with { LiveFollowApps = on });
                appsGroup.SetSensitive(on);
            }, "LiveFollowAppsSwitch"));
        live.Add(Combo("Percentage shown", "Auto shows whichever of the 5-hour and weekly limits has less left.",
            ["Auto", "5-hour usage", "Weekly usage"], (uint)s.LiveWindow,
            i => state.Settings.Update(x => x with { LiveWindow = (LiveWindowChoice)i }), "LiveWindowBox"));
        live.Add(Combo("Icon style", "A used-up limit shows the time until it resets.", ["Ring", "Number"], (uint)s.LiveIconStyle,
            i => state.Settings.Update(x => x with { LiveIconStyle = (LiveIconStyle)i }), "LiveStyleBox"));
        closeToTray.SetSensitive(s.ShowTrayIcon);
        live.Add(closeToTray);
        live.Add(Switch("Start when you log in", "Opens in the tray when you sign in to your desktop.",
            state.IsDemo ? s.StartWithWindows : Autostart.IsEnabled, on =>
            {
                state.Settings.Update(x => x with { StartWithWindows = on });
                if (state.IsDemo) return;
                try { Autostart.Set(on); }
                catch (Exception error) { AppLog.Write("Autostart change failed: " + error.Message); window.ShowToast("Couldn't change the login item: " + error.Message); }
            }, "StartupSwitch"));

        // While using AI apps: the app list, as on Android.
        appsGroup.SetTitle("AI apps");
        appsGroup.SetDescription("While one of these is in front, the tray shows its service. Apps are matched by process name or window class; on Wayland, apps running through XWayland are seen.");
        preferences.Add(appsGroup);
        appsGroup.SetName("LiveAppsGroup");
        var addApp = Gtk.Button.NewWithLabel("Add App");
        addApp.AddCssClass("flat");
        addApp.SetValign(Gtk.Align.Center);
        addApp.SetName("LiveAppAddButton");
        addApp.OnClicked += (_, _) => EditLiveApp(null);
        appsGroup.SetHeaderSuffix(addApp);
        appsGroup.SetSensitive(s.LiveFollowApps);
        BuildLiveApps();

        // App
        Heading("App");
        var appearance = Group("Appearance");
        appearance.Add(Combo("Style", "Follow the system, or always use light or dark.",
            ["Follow System", "Light", "Dark"], (uint)s.Theme, i => state.Settings.Update(x => x with { Theme = (AppTheme)i }), "ThemeRow"));
        if (state.Updater is { } updater) BuildUpdates(updater, window, s.Updates);
        BuildPrivacy(window);

        // About
        Heading("About");
        var about = Group("");
        var aboutRow = Adw.ActionRow.New();
        aboutRow.SetTitle("Codex Meter for Linux");
        aboutRow.SetSubtitle("Unofficial and open source. No analytics, no ads, no relay server: the app talks to each provider directly.");
        var appIcon = Gtk.Image.NewFromIconName(AppPaths.AppId);
        appIcon.SetPixelSize(32);
        aboutRow.AddPrefix(appIcon);
        var version = Gtk.Label.New(VersionText());
        version.AddCssClass("dim-label");
        version.AddCssClass("caption");
        version.SetName("VersionText");
        aboutRow.AddSuffix(version);
        about.Add(aboutRow);
        var whatsNew = Adw.ActionRow.New();
        whatsNew.SetTitle("What's new in " + WhatsNew.Release);
        whatsNew.SetSubtitle("Every feature that's new in this version.");
        whatsNew.SetActivatable(true);
        whatsNew.SetName("WhatsNewShow");
        whatsNew.AddSuffix(Gtk.Image.NewFromIconName("go-next-symbolic"));
        whatsNew.OnActivated += (_, _) => window.ShowWhatsNew();
        about.Add(whatsNew);
        var source = Adw.ActionRow.New();
        source.SetTitle("Source code and releases");
        source.SetSubtitle("github.com/BenItBuhner/Codex-Meter");
        source.SetActivatable(true);
        source.AddSuffix(Gtk.Image.NewFromIconName("adw-external-link-symbolic"));
        source.OnActivated += (_, _) => _ = Browser.OpenAsync(new Uri("https://github.com/BenItBuhner/Codex-Meter"));
        about.Add(source);

        scroller = AccountsPage.FindScroller(preferences) ?? Gtk.ScrolledWindow.New();
        loading = false;
    }

    private readonly Gtk.ScrolledWindow scroller;

    public string Tag => "settings";
    public Adw.NavigationPage Page { get; }
    public Gtk.ScrolledWindow Scroller => scroller;

    public void Detach()
    {
        if (state.Updater is { } updater && renderUpdates is not null) updater.Changed -= renderUpdates;
    }

    private Action? renderUpdates;
    private Adw.ActionRow? updatesRow;

    /// <summary>Scrolls to Updates (from Priority's update card or the notification): the page follows focus.</summary>
    public void ShowUpdates()
    {
        if (updatesRow is not { } row) return;
        GLib.Functions.IdleAdd(GLib.Constants.PRIORITY_DEFAULT_IDLE, () =>
        {
            row.GrabFocus();
            return false;
        });
    }

    /// <summary>
    /// Settings → Updates, as on Android: channel, automatic checks, notifications and Check for
    /// updates. An AppImage replaces itself; a .deb installs through the system's package tools.
    /// Every download is checked against the release's SHA256SUMS-linux file first.
    /// </summary>
    private void BuildUpdates(DesktopUpdater updater, MainWindow window, UpdatePreferences prefs)
    {
        void Save(Func<UpdatePreferences, UpdatePreferences> change) => state.Settings.Update(x => x with { Updates = change(x.Updates) });
        var group = Group("Updates");
        var status = Adw.ActionRow.New();
        updatesRow = status;
        status.SetTitle("Codex Meter updates");
        status.SetName("UpdateStatusRow");
        status.AddPrefix(Gtk.Image.NewFromIconName("software-update-available-symbolic"));
        var box = Gtk.Box.New(Gtk.Orientation.Vertical, 6);
        box.SetValign(Gtk.Align.Center);
        var action = Gtk.Button.NewWithLabel("Check for Updates");
        action.AddCssClass("suggested-action");
        action.SetName("UpdateActionButton");
        var bar = Gtk.ProgressBar.New();
        bar.SetName("UpdateProgress");
        box.Append(action);
        box.Append(bar);
        status.AddSuffix(box);
        group.Add(status);

        var notes = Adw.ActionRow.New();
        notes.SetTitle("Release notes");
        notes.SetActivatable(true);
        notes.AddSuffix(Gtk.Image.NewFromIconName("adw-external-link-symbolic"));
        notes.SetName("UpdateNotesRow");
        notes.OnActivated += (_, _) =>
        {
            if ((updater.Available ?? updater.Latest) is { } release) _ = Browser.OpenAsync(new Uri(release.PageUrl));
        };
        group.Add(notes);

        var syncing = false;
        var channel = Combo("Update channel", "Alpha gets new features first, with less testing.", ["Stable", "Alpha (prereleases)"],
            updater.Channel == UpdateChannel.Alpha ? 1u : 0u, i =>
            {
                if (syncing) return;
                var chosen = i == 1 ? UpdateChannel.Alpha : UpdateChannel.Stable;
                if (chosen == updater.Channel && updater.UserChoseChannel) return;
                updater.SetChannel(chosen);
                _ = updater.CheckAsync(manual: true);
            }, "UpdateChannelBox");
        group.Add(channel);
        var frequency = Combo("Check frequency", null, UpdateCheckFrequency.Hours.Select(UpdateCheckFrequency.Label).ToArray(),
            (uint)Math.Max(0, Array.IndexOf(UpdateCheckFrequency.Hours, UpdateCheckFrequency.Normalize(prefs.CheckHours))),
            i => Save(p => p with { CheckHours = UpdateCheckFrequency.Hours[i] }), "UpdateFrequencyBox");
        frequency.SetSensitive(prefs.AutoCheck);
        group.Add(Switch("Check automatically", "Ask GitHub for the newest release in the background. Each check reads at most two small responses.",
            prefs.AutoCheck, on =>
            {
                Save(p => p with { AutoCheck = on });
                frequency.SetSensitive(on);
            }, "UpdateAutoSwitch"));
        group.Add(frequency);
        group.Add(Switch("Notify when an update is available", "A desktop notification, once per version.", prefs.Notify,
            on => Save(p => p with { Notify = on }), "UpdateNotifySwitch"));

        action.OnClicked += async (_, _) =>
        {
            switch (updater.Status)
            {
                case UpdateStatus.Downloading:
                    updater.Cancel();
                    break;
                case UpdateStatus.Available when updater.CannotInstallReason is not null:
                    await Browser.OpenAsync(new Uri(updater.Available!.PageUrl));
                    break;
                case UpdateStatus.Available:
                    await updater.DownloadAndInstallAsync();
                    if (updater.Status == UpdateStatus.UpToDate) window.ShowToast("Update installed. It starts the next time Codex Meter opens.");
                    break;
                default:
                    await updater.CheckAsync(manual: true);
                    break;
            }
        };

        renderUpdates = () =>
        {
            syncing = true;
            channel.SetSelected(updater.Channel == UpdateChannel.Alpha ? 1u : 0u);
            syncing = false;
            status.SetSubtitle(updater.StatusText + (updater.Status is UpdateStatus.Idle or UpdateStatus.UpToDate ? "" : "\n" + updater.LastCheckText));
            bar.SetVisible(updater.Status is UpdateStatus.Downloading or UpdateStatus.Installing);
            if (updater.Status == UpdateStatus.Installing) bar.Pulse(); else bar.SetFraction(updater.Progress);
            action.SetSensitive(updater.Status is not (UpdateStatus.Installing or UpdateStatus.Checking));
            action.SetLabel(updater.Status switch
            {
                UpdateStatus.Checking => "Checking…",
                UpdateStatus.Downloading => "Cancel",
                UpdateStatus.Installing => "Installing…",
                UpdateStatus.Available when updater.CannotInstallReason is not null => "Open Release Page",
                UpdateStatus.Available when updater.IsReturnToStable => "Return to Stable",
                UpdateStatus.Available => "Download and Install",
                _ => "Check for Updates",
            });
            var release = updater.Available ?? updater.Latest;
            notes.SetVisible(release is not null);
            if (release is not null)
            {
                notes.SetTitle($"What's new in {release.Version.Normalized}");
                var first = string.Join(" ", release.Notes.Split('\n').Select(l => l.Trim().TrimStart('#', '-', '*', ' ').Replace("**", ""))
                    .Where(l => l.Length > 0).Take(2));
                notes.SetSubtitle(first.Length > 200 ? first[..200] + "…" : first);
            }
            if (updater.CannotInstallReason is { } reason && updater.Status == UpdateStatus.Available) status.SetSubtitle(updater.StatusText + "\n" + reason);
        };
        updater.Changed += renderUpdates;
        renderUpdates();
    }

    public static string VersionText()
    {
        var version = typeof(SettingsPage).Assembly
            .GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
            .OfType<System.Reflection.AssemblyInformationalVersionAttribute>().FirstOrDefault()?.InformationalVersion ?? "";
        var plus = version.IndexOf('+');
        if (plus >= 0) version = version[..plus];
        var arch = System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture.ToString().ToLowerInvariant();
        return $"Version {version} ({arch})";
    }

    /// <summary>A top-level heading above a run of groups.</summary>
    private void Heading(string title)
    {
        var group = Adw.PreferencesGroup.New();
        var label = Gtk.Label.New(title);
        label.AddCssClass("title-2");
        label.SetXalign(0);
        label.SetMarginTop(12);
        group.Add(label);
        group.SetName("Heading_" + title.Replace(" ", "").Replace("&", "And"));
        preferences.Add(group);
    }

    /// <summary>"Your resets" with what the service has right now.</summary>
    private Adw.ActionRow ResetsRow(UsageProvider provider)
    {
        var entry = state[provider];
        var card = ResetCreditsCard.For(provider, entry.Cache, entry.Usage?.ResetCreditsAvailable ?? 0, state.Settings.Current, DateTimeOffset.UtcNow);
        var row = Adw.ActionRow.New();
        row.SetTitle("Your resets");
        row.SetSubtitle(card.Held is { } held ? card.Count + ". " + held : card.Count);
        row.SetName("ResetsRow_" + provider.Id);
        return row;
    }

    /// <summary>
    /// Privacy &amp; data: export and import settings (never sign-ins), opt-in diagnostic logs kept
    /// only on this computer, and where everything is stored.
    /// </summary>
    private void BuildPrivacy(MainWindow window)
    {
        var group = Group("Privacy & data");
        var export = Adw.ActionRow.New();
        export.SetTitle("Export settings");
        export.SetSubtitle("Saves your settings to a file. No accounts, tokens or API keys are included.");
        var exportButton = Gtk.Button.NewWithLabel("Export…");
        exportButton.SetValign(Gtk.Align.Center);
        exportButton.SetName("ExportSettingsButton");
        exportButton.OnClicked += async (_, _) =>
        {
            var dialog = Gtk.FileDialog.New();
            dialog.SetTitle("Export settings");
            dialog.SetInitialName(SettingsTransfer.FileName);
            try
            {
                if (await dialog.SaveAsync(window.Window) is not { } file || file.GetPath() is not { } path) return;
                await File.WriteAllTextAsync(path, SettingsTransfer.Export(state.Settings.Current, "linux", DateTimeOffset.UtcNow));
                window.ShowToast("Settings exported");
            }
            catch (GLib.GException) { }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { window.ShowToast("Couldn't save the file: " + error.Message); }
        };
        export.AddSuffix(exportButton);
        group.Add(export);

        var import = Adw.ActionRow.New();
        import.SetTitle("Import settings");
        import.SetSubtitle("Replaces your settings with ones exported from Codex Meter on Windows or Linux. Your accounts stay as they are.");
        var importButton = Gtk.Button.NewWithLabel("Import…");
        importButton.SetValign(Gtk.Align.Center);
        importButton.SetName("ImportSettingsButton");
        importButton.OnClicked += async (_, _) =>
        {
            var dialog = Gtk.FileDialog.New();
            dialog.SetTitle("Import settings");
            try
            {
                if (await dialog.OpenAsync(window.Window) is not { } file || file.GetPath() is not { } path) return;
                if (new FileInfo(path).Length > SettingsTransfer.MaxBytes) throw new FormatException("The file is too large to be a Codex Meter settings file.");
                var imported = SettingsTransfer.Import(await File.ReadAllTextAsync(path), state.Settings.Current);
                state.Settings.Update(_ => imported);
                window.ShowToast("Settings imported");
                window.Reload();
            }
            catch (GLib.GException) { }
            catch (FormatException error) { window.ShowToast(error.Message); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { window.ShowToast("Couldn't read the file: " + error.Message); }
        };
        import.AddSuffix(importButton);
        group.Add(import);

        var log = state.Diagnostics;
        var size = Adw.ActionRow.New();
        size.SetTitle("Saved logs");
        size.SetName("DiagnosticLogSize");
        var exportLogs = Gtk.Button.NewWithLabel("Export…");
        exportLogs.SetValign(Gtk.Align.Center);
        var clearLogs = Gtk.Button.NewWithLabel("Clear");
        clearLogs.SetValign(Gtk.Align.Center);
        void RenderLogs()
        {
            var bytes = log?.Bytes ?? 0;
            size.SetSubtitle(DiagnosticLog.SizeLabel(bytes));
            exportLogs.SetSensitive(bytes > 0);
            clearLogs.SetSensitive(bytes > 0);
        }
        var diagnostics = Expander("Diagnostic logs",
            "Keeps a privacy-filtered log on this computer to help with a bug report. Tokens, keys and email addresses are removed, and nothing is sent anywhere.",
            state.Settings.Current.DiagnosticLogging, on =>
            {
                state.Settings.Update(x => x with { DiagnosticLogging = on });
                if (on) log?.Write("app", "Diagnostic logging turned on: " + VersionText());
                RenderLogs();
            }, "DiagnosticLogSwitch");
        diagnostics.SetSensitive(log is not null);
        exportLogs.OnClicked += async (_, _) =>
        {
            if (log is null) return;
            var dialog = Gtk.FileDialog.New();
            dialog.SetTitle("Export diagnostic logs");
            dialog.SetInitialName("codex-meter-diagnostics.txt");
            try
            {
                if (await dialog.SaveAsync(window.Window) is not { } file || file.GetPath() is not { } path) return;
                await File.WriteAllTextAsync(path, log.Export("Codex Meter for Linux " + VersionText()));
                window.ShowToast("Diagnostic logs exported");
            }
            catch (GLib.GException) { }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { window.ShowToast("Couldn't save the file: " + error.Message); }
        };
        clearLogs.OnClicked += (_, _) =>
        {
            log?.Clear();
            RenderLogs();
        };
        size.AddSuffix(exportLogs);
        size.AddSuffix(clearLogs);
        diagnostics.AddRow(size);
        RenderLogs();
        group.Add(diagnostics);

        var data = Adw.ActionRow.New();
        data.SetTitle("Your data");
        data.SetSubtitle(SignInDialog.StorageDescription(App.Current?.KeyLocation)
            + " Cached usage, history and settings live in your home folder. Status pages and OpenRouter are checked without your account details.");
        var open = Gtk.Button.NewWithLabel("Open Folder");
        open.SetValign(Gtk.Align.Center);
        open.OnClicked += (_, _) =>
        {
            Directory.CreateDirectory(AppPaths.DataDirectory);
            _ = Browser.OpenAsync(new Uri(AppPaths.ConfigDirectory.EndsWith('/') ? AppPaths.ConfigDirectory : AppPaths.ConfigDirectory + "/"));
        };
        data.AddSuffix(open);
        group.Add(data);
    }

    private Adw.PreferencesGroup Group(string title, string? description = null)
    {
        var group = Adw.PreferencesGroup.New();
        // Group titles and descriptions are Pango markup.
        group.SetTitle(GLib.Functions.MarkupEscapeText(title, -1));
        if (description is not null) group.SetDescription(GLib.Functions.MarkupEscapeText(description, -1));
        preferences.Add(group);
        return group;
    }

    /// <summary>The running-low rule's wait for one limit: "Don't wait", "15 minutes" … "3 days".</summary>
    private Adw.ComboRow WaitCombo(string title, bool weekly, int minutes, Action<int> changed, string name)
    {
        var options = weekly ? LowUsageAutoUse.WeeklyWaitOptions : LowUsageAutoUse.FiveHourWaitOptions;
        var current = weekly ? LowUsageAutoUse.NormalizedWeeklyWait(minutes) : LowUsageAutoUse.NormalizedFiveHourWait(minutes);
        return Combo(title, null, [.. options.Select(LowUsageAutoUse.WaitLabel)], (uint)Math.Max(0, options.ToList().IndexOf(current)),
            i => changed(options[i]), name);
    }

    private static Adw.ActionRow InfoRow(string title, string subtitle, string name)
    {
        var row = Adw.ActionRow.New();
        row.SetTitle(title);
        row.SetSubtitle(subtitle);
        row.SetName(name);
        return row;
    }

    private Adw.ComboRow Combo(string title, string? subtitle, string[] items, uint selected, Action<int> changed, string? name = null)
    {
        var row = Adw.ComboRow.New();
        row.SetTitle(title);
        if (subtitle is not null) row.SetSubtitle(subtitle);
        row.SetModel(Gtk.StringList.New(items));
        row.SetSelected(selected);
        if (name is not null) row.SetName(name);
        row.OnNotify += (_, args) =>
        {
            if (loading || args.Pspec.GetName() != "selected") return;
            var index = (int)row.GetSelected();
            if (index >= 0 && index < items.Length) changed(index);
        };
        return row;
    }

    /// <summary>An expander whose switch turns the feature on; its options sit inside.</summary>
    private Adw.ExpanderRow Expander(string title, string subtitle, bool active, Action<bool> changed, string name)
    {
        var row = Adw.ExpanderRow.New();
        row.SetTitle(title);
        row.SetSubtitle(subtitle);
        row.SetShowEnableSwitch(true);
        row.SetEnableExpansion(active);
        row.SetExpanded(active);
        row.SetName(name);
        row.OnNotify += (_, args) =>
        {
            if (loading || args.Pspec.GetName() != "enable-expansion") return;
            changed(row.GetEnableExpansion());
        };
        return row;
    }

    private Adw.SwitchRow Switch(string title, string? subtitle, bool active, Action<bool> changed, string? name = null)
    {
        var row = Adw.SwitchRow.New();
        row.SetTitle(title);
        if (subtitle is not null) row.SetSubtitle(subtitle);
        row.SetActive(active);
        if (name is not null) row.SetName(name);
        row.OnNotify += (_, args) =>
        {
            if (loading || args.Pspec.GetName() != "active") return;
            changed(row.GetActive());
        };
        return row;
    }

    private void BuildLiveApps()
    {
        foreach (var row in appRows) appsGroup.Remove(row);
        appRows.Clear();
        var settings = state.Settings.Current;
        foreach (var rule in AppMappings.Shown(AppMappings.Resolve(settings), state.SignedIn))
        {
            var row = Adw.ActionRow.New();
            row.SetTitle(GLib.Markup.EscapeText(rule.App));
            row.SetSubtitle(LiveAppDescription(rule));
            row.SetName("LiveApp_" + rule.Key);
            var mark = ProviderIcons.Create(rule.Provider.Id, 20);
            mark.SetMarginEnd(4);
            row.AddPrefix(mark);
            var edit = FlatButton("document-edit-symbolic", $"Edit {rule.App}", "LiveAppEdit_" + rule.Key);
            edit.OnClicked += (_, _) => EditLiveApp(rule);
            var remove = FlatButton("user-trash-symbolic", $"Remove {rule.App}", "LiveAppRemove_" + rule.Key);
            remove.OnClicked += (_, _) => GLib.Functions.IdleAdd(GLib.Constants.PRIORITY_DEFAULT_IDLE, () =>
            {
                state.Settings.Update(x => AppMappings.Remove(x, rule.App));
                BuildLiveApps();
                return false;
            });
            var toggle = Gtk.Switch.New();
            toggle.SetValign(Gtk.Align.Center);
            toggle.SetActive(rule.On);
            toggle.SetName("LiveAppToggle_" + rule.Key);
            toggle.SetTooltipText("Use " + rule.App);
            toggle.OnStateSet += (_, args) =>
            {
                state.Settings.Update(x => AppMappings.SetOn(x, rule.App, args.State));
                return false;
            };
            row.AddSuffix(edit);
            row.AddSuffix(remove);
            row.AddSuffix(toggle);
            appsGroup.Add(row);
            appRows.Add(row);
        }

        var restoreRow = Adw.ActionRow.New();
        restoreRow.SetTitle("Built-in apps");
        restoreRow.SetSubtitle("Bring back the apps Codex Meter knows, and drop your changes.");
        var restore = Gtk.Button.NewWithLabel("Restore Defaults");
        restore.SetValign(Gtk.Align.Center);
        restore.SetName("LiveAppsRestoreButton");
        restore.SetSensitive(AppMappings.IsCustomized(settings));
        restore.OnClicked += (_, _) => GLib.Functions.IdleAdd(GLib.Constants.PRIORITY_DEFAULT_IDLE, () =>
        {
            state.Settings.Update(AppMappings.RestoreDefaults);
            BuildLiveApps();
            return false;
        });
        restoreRow.AddSuffix(restore);
        appsGroup.Add(restoreRow);
        appRows.Add(restoreRow);
    }

    private static Gtk.Button FlatButton(string icon, string tooltip, string name)
    {
        var button = Gtk.Button.NewFromIconName(icon);
        button.AddCssClass("flat");
        button.SetValign(Gtk.Align.Center);
        button.SetTooltipText(tooltip);
        button.SetName(name);
        return button;
    }

    internal static string LiveAppDescription(AppRule rule)
    {
        var text = rule.Provider.DisplayName;
        if (!rule.BuiltIn) return text + " · Added by you";
        if (rule.Edited && AppMappings.Defaults.FirstOrDefault(d => AppMappings.Key(d.App) == rule.Key) is { } builtIn
            && UsageProvider.Find(builtIn.ProviderId) is { } original)
            return $"{text} · Changed from {original.DisplayName}";
        return text;
    }

    /// <summary>Add an app, or edit <paramref name="rule"/>: its name and the service it shows.</summary>
    internal Adw.AlertDialog EditLiveApp(AppRule? rule, string? presetName = null, UsageProvider? presetProvider = null)
    {
        // Only services you're signed in to, plus the one this app already shows.
        var signedIn = state.SignedIn;
        var providers = UsageProvider.All
            .Where(p => (p.IsAvailable && signedIn.Contains(p)) || p == rule?.Provider || p == presetProvider).ToList();
        var dialog = Adw.AlertDialog.New(rule is null ? "Add an App" : "Edit App",
            "While this app is in front, the tray shows this service's usage.");
        var list = Gtk.ListBox.New();
        list.AddCssClass("boxed-list");
        list.SetSelectionMode(Gtk.SelectionMode.None);
        var name = Adw.EntryRow.New();
        name.SetTitle("App or process name, like Grok Bot");
        name.SetText(presetName ?? rule?.App ?? "");
        name.SetName("LiveAppNameBox");
        list.Append(name);
        var service = Adw.ComboRow.New();
        service.SetTitle("Shows usage for");
        service.SetModel(Gtk.StringList.New(providers.Select(p => p.DisplayName).ToArray()));
        var selected = (presetProvider ?? rule?.Provider) is { } current ? providers.IndexOf(current) : 0;
        service.SetSelected((uint)Math.Max(0, selected));
        service.SetName("LiveAppServiceBox");
        list.Append(service);
        dialog.SetExtraChild(list);
        dialog.AddResponse("cancel", "_Cancel");
        dialog.AddResponse("save", "_Save");
        dialog.SetResponseAppearance("save", Adw.ResponseAppearance.Suggested);
        dialog.SetDefaultResponse("save");
        dialog.SetCloseResponse("cancel");
        void Validate() => dialog.SetResponseEnabled("save", providers.Count > 0 && AppMappings.Key(name.GetText()).Length > 0);
        Validate();
        name.OnChanged += (_, _) => Validate();
        dialog.OnResponse += (_, args) =>
        {
            if (args.Response != "save") return;
            var index = (int)service.GetSelected();
            if (index < 0 || index >= providers.Count) return;
            var chosen = providers[index];
            var text = name.GetText();
            state.Settings.Update(x => AppMappings.Set(x, text, chosen, replacing: rule?.App));
            BuildLiveApps();
        };
        dialog.Present(window.Window);
        return dialog;
    }
}
