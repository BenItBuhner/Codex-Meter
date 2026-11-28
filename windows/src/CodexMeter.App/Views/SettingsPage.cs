using System.Diagnostics;
using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.App.Services;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CommunityToolkit.WinUI.Controls;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;

namespace CodexMeter.App.Views;

/// <summary>
/// Settings in the Windows 11 Settings app style: grouped setting cards and expanders, with the
/// same options and names as the phone apps and Linux: Usage (Providers, Dashboard,
/// Reset credits), Alerts &amp; live usage (Notifications, Live usage), App (Appearance, Updates,
/// Privacy &amp; data) and About, plus the Windows-only tray and startup choices.
/// </summary>
public sealed class SettingsPage : PageBase
{
    private static readonly LowUsageAutoUseMetric[] AlertFor = [LowUsageAutoUseMetric.Both, LowUsageAutoUseMetric.FiveHour, LowUsageAutoUseMetric.Weekly];
    private const string SlowdownNote = "On the Priority page only when a Codex, Claude Code or Grok model runs much slower than usual, from public OpenRouter figures for each maker's newest models. Not your subscription's own speed.";
    private const string NewModelsNote = "When OpenRouter lists a new model from a maker you're signed in to (OpenAI for Codex, Anthropic for Claude Code, ...). Tools that offer many makers' models, like Cursor or Copilot, add none.";
    private readonly UpdatesSection? updates;
    /// <summary>"While using AI apps": one card per app, then Add an app. Plain cards so UI tests reach them.</summary>
    private readonly StackPanel liveApps = new() { Spacing = 4, Margin = new Thickness(32, 0, 0, 0) };

    public SettingsPage() : base(1000)
    {
        var s = State.Settings.Current;
        Body.Children.Add(PageTitle("Settings", "SettingsTitle"));

        // Usage: providers and their accounts, each dashboard, reset credits.
        Body.Children.Add(GroupHeader("Usage"));
        Body.Children.Add(SectionHeader("Providers"));
        var manage = new Button { Content = "Manage providers" };
        AutomationProperties.SetAutomationId(manage, "ManageAccountsButton");
        manage.Click += (_, _) => App.Current?.Window?.Navigate("accounts");
        var providersCard = Card("Providers", null, "", manage);
        AutomationProperties.SetAutomationId(providersCard, "ProvidersCard");
        Body.Children.Add(providersCard);
        Body.Children.Add(Card("Priority page", "First in the sidebar: what needs you across every connected service.", "",
            Toggle("PrioritySwitch", s.ShowPriority, on => Update(x => x with { ShowPriority = on }))));

        Body.Children.Add(SectionHeader("Dashboard"));
        Body.Children.Add(Card("Check usage every", "While Codex Meter runs. Some services are checked less often because they limit polling.", "",
            Combo("RefreshBox", ["5 minutes", "15 minutes", "30 minutes", "1 hour"], Math.Max(0, Array.IndexOf(AppSettings.RefreshChoices, s.RefreshMinutes)),
                i => Update(x => x with { RefreshMinutes = AppSettings.RefreshChoices[i] }))));
        Body.Children.Add(Card("Show usage as", "The big number on each card.", "",
            Combo("DisplayBox", ["Remaining", "Used"], s.DisplayMode == UsageDisplayMode.Remaining ? 0 : 1,
                i => Update(x => x with { DisplayMode = i == 1 ? UsageDisplayMode.Used : UsageDisplayMode.Remaining }))));
        Body.Children.Add(Card("Reset times", "How each card says when its window resets.", "",
            Combo("ResetBox", ["Countdown", "Date and time", "Both"], (int)s.ResetDisplay, i => Update(x => x with { ResetDisplay = (ResetDisplayMode)i }))));
        Body.Children.Add(Card("24-hour clock", null, "", Toggle("ClockSwitch", s.Use24HourClock, on => Update(x => x with { Use24HourClock = on }))));
        Body.Children.Add(Card("Usage estimates", "How long your current allowance may last at your pace.", "",
            Toggle("PaceSwitch", s.ShowPace, on => Update(x => x with { ShowPace = on }))));
        Body.Children.Add(Card("Fast-usage warnings", "How readily a card warns that a window would run out before it resets.", "",
            Combo("PaceSensitivityBox", UsagePace.Choices.Select(UsagePace.Label).ToArray(), Math.Max(0, Array.IndexOf(UsagePace.Choices, s.PaceSensitivity)),
                i => Update(x => x with { PaceSensitivity = UsagePace.Choices[i] }))));
        Body.Children.Add(Card("Service status", "Official outages and maintenance for Codex, Claude Code, Cursor and Grok, from each status page.", "",
            Toggle("ServiceStatusSwitch", s.ShowServiceStatus, on => Update(x => x with { ShowServiceStatus = on }))));
        Body.Children.Add(Card("Model slowdown warnings", SlowdownNote, "",
            Toggle("SlowdownSwitch", s.ShowSlowdowns, on => Update(x => x with { ShowSlowdowns = on }))));
        var highlights = new SettingsExpander
        {
            Header = "Chart highlights",
            Description = "What appears under the usage history chart.",
            HeaderIcon = new FontIcon { Glyph = "" },
        };
        AutomationProperties.SetAutomationId(highlights, "HistoryHighlights");
        foreach (var key in HistorySections.All)
        {
            highlights.Items.Add(new SettingsCard
            {
                Header = HistorySections.Label(key),
                Content = Toggle("Highlight_" + key, HistorySections.IsVisible(s.HistoryOverrides, key),
                    on => Update(x => x with { HistoryOverrides = HistorySections.SetVisible(x.HistoryOverrides, key, on) })),
            });
        }
        Body.Children.Add(highlights);
        var clear = new Button { Content = "Clear all history" };
        AutomationProperties.SetAutomationId(clear, "ClearAllHistory");
        clear.Click += async (_, _) =>
        {
            var dialog = SignInFlow.Dialog(XamlRoot, "Clear all usage history?",
                Cards.Text("Every chart starts over from the next refresh. Accounts and current usage aren't affected.", "BodyTextBlockStyle"), "ClearAllHistoryDialog");
            dialog.PrimaryButtonText = "Clear history";
            dialog.DefaultButton = ContentDialogButton.Close;
            if (await dialog.ShowAsync() == ContentDialogResult.Primary) await State.ClearHistoryAsync();
        };
        Body.Children.Add(Card("Usage history", "Saved on this PC each time usage refreshes, and cleared when you sign out. Use Edit dashboard on a service's page to hide or reorder its cards.", "", clear));

        // Reset credits: one section per connected service that has them. Auto-use watches both
        // the 5-hour and weekly limits, so there is no picker for which one.
        var codexResets = State.Supports(UsageProvider.Codex) && State[UsageProvider.Codex].SignedIn;
        var claudeResets = State.Supports(UsageProvider.ClaudeCode) && State[UsageProvider.ClaudeCode].SignedIn;
        if (codexResets || claudeResets)
        {
            Body.Children.Add(SectionHeader("Reset credits"));
            Body.Children.Add(Cards.Text("Spend resets automatically so they don't go to waste. Auto-use watches both the 5-hour and weekly limits.", "CaptionSecondaryTextStyle"));
        }
        var leads = CreditExpiryAutoApply.LeadMinuteOptions;
        var thresholds = LowUsageAutoUse.ThresholdOptions;
        string[] leadLabels = [.. leads.Select(CreditExpiryAutoApply.LeadLabel)];
        string[] thresholdLabels = [.. thresholds.Select(t => $"{t}% left")];
        if (codexResets)
        {
            Body.Children.Add(ProviderHeader(UsageProvider.Codex));
            Body.Children.Add(ResetsCard(UsageProvider.Codex));
            Body.Children.Add(RuleExpander("Use before they expire", "Spend a reset shortly before it would expire unused.", "",
                "AutoUseExpiringSwitch", s.AutoUseExpiringResets, on => Update(x => x with { AutoUseExpiringResets = on }),
                "When to use it", Combo("AutoUseLeadBox", leadLabels,
                    Math.Max(0, leads.ToList().IndexOf(CreditExpiryAutoApply.NormalizedLeadMinutes(s.AutoUseLeadMinutes))),
                    i => Update(x => x with { AutoUseLeadMinutes = leads[i] }))));
            Body.Children.Add(RuleExpander("Use when running low", "Spend a reset when a 5-hour or weekly limit is nearly used up, unless it refills on its own soon anyway.", "",
                "AutoUseLowSwitch", s.AutoUseWhenLow, on => Update(x => x with { AutoUseWhenLow = on }),
                "Use when remaining reaches", Combo("AutoUseThresholdBox", thresholdLabels,
                    Math.Max(0, thresholds.ToList().IndexOf(LowUsageAutoUse.NormalizedThreshold(s.AutoUseThreshold))),
                    i => Update(x => x with { AutoUseThreshold = thresholds[i] })),
                ("Wait if the 5-hour limit refills within", WaitCombo("AutoUseFiveHourWaitBox", false, s.AutoUseFiveHourWaitMinutes,
                    m => Update(x => x with { AutoUseFiveHourWaitMinutes = m }))),
                ("Wait if the weekly limit refills within", WaitCombo("AutoUseWeeklyWaitBox", true, s.AutoUseWeeklyWaitMinutes,
                    m => Update(x => x with { AutoUseWeeklyWaitMinutes = m })))));
            if (s.AutoUseWhenLow && ResetCreditsCard.RefillWaitText(s.AutoUseRefillWait, s, DateTimeOffset.UtcNow) is { } codexWait)
                Body.Children.Add(Card("Waiting for a refill", codexWait, "\uE916", null));
        }
        if (claudeResets)
        {
            var rules = s.ClaudeResets;
            Body.Children.Add(ProviderHeader(UsageProvider.ClaudeCode));
            Body.Children.Add(ResetsCard(UsageProvider.ClaudeCode));
            Body.Children.Add(RuleExpander("Use before they expire", "Spend a saved reset shortly before it would expire, once you're at a limit it refills.", "",
                "ClaudeAutoUseExpiringSwitch", rules.AutoUseExpiring, on => Update(x => x with { ClaudeResets = x.ClaudeResets with { AutoUseExpiring = on } }),
                "When to use it", Combo("ClaudeAutoUseLeadBox", leadLabels,
                    Math.Max(0, leads.ToList().IndexOf(CreditExpiryAutoApply.NormalizedLeadMinutes(rules.LeadMinutes))),
                    i => Update(x => x with { ClaudeResets = x.ClaudeResets with { LeadMinutes = leads[i] } }))));
            Body.Children.Add(RuleExpander("Use when running low", "Spend a saved reset when a 5-hour or weekly limit is nearly used up, unless it refills on its own soon anyway.", "",
                "ClaudeAutoUseLowSwitch", rules.AutoUseWhenLow, on => Update(x => x with { ClaudeResets = x.ClaudeResets with { AutoUseWhenLow = on } }),
                "Use when remaining reaches", Combo("ClaudeAutoUseThresholdBox", thresholdLabels,
                    Math.Max(0, thresholds.ToList().IndexOf(LowUsageAutoUse.NormalizedThreshold(rules.Threshold))),
                    i => Update(x => x with { ClaudeResets = x.ClaudeResets with { Threshold = thresholds[i] } })),
                ("Wait if the 5-hour limit refills within", WaitCombo("ClaudeAutoUseFiveHourWaitBox", false, rules.FiveHourWaitMinutes,
                    m => Update(x => x with { ClaudeResets = x.ClaudeResets with { FiveHourWaitMinutes = m } }))),
                ("Wait if the weekly limit refills within", WaitCombo("ClaudeAutoUseWeeklyWaitBox", true, rules.WeeklyWaitMinutes,
                    m => Update(x => x with { ClaudeResets = x.ClaudeResets with { WeeklyWaitMinutes = m } })))));
            if (rules.AutoUseWhenLow && ResetCreditsCard.RefillWaitText(rules.RefillWait, s, DateTimeOffset.UtcNow) is { } claudeWait)
                Body.Children.Add(Card("Waiting for a refill", claudeWait, "\uE916", null));
            var check = new Button { Content = "Check" };
            AutomationProperties.SetAutomationId(check, "ClaudeCheckResetsButton");
            check.Click += async (_, _) =>
            {
                check.IsEnabled = false;
                string report;
                try { report = await State.CheckClaudeResetsAsync(); }
                catch (Exception error) { report = "The check didn't finish: " + error.Message; }
                check.IsEnabled = true;
                var dialog = SignInFlow.Dialog(XamlRoot, "Claude Code saved resets", Cards.Text(report, "BodyTextBlockStyle"), "ClaudeCheckDialog");
                await dialog.ShowAsync();
            };
            Body.Children.Add(Card("Check with Anthropic", "Asks Anthropic which saved resets this account has, and why one can't be used yet.", "", check));
        }

        // Alerts & live usage
        Body.Children.Add(GroupHeader("Alerts & live usage"));
        Body.Children.Add(SectionHeader("Notifications"));
        var lowAlert = new SettingsExpander
        {
            Header = "Low allowance",
            Description = "When a limit drops to this much or runs out.",
            HeaderIcon = new FontIcon { Glyph = "" },
            Content = Toggle("LowAlertSwitch", s.NotifyLow, on => Update(x => x with { NotifyLow = on })),
        };
        lowAlert.Items.Add(new SettingsCard
        {
            Header = "Alert for",
            Content = Combo("LowAlertForBox", ["Both", "5-hour", "Weekly"], Math.Max(0, Array.IndexOf(AlertFor, s.LowAlertMetric)),
                i => Update(x => x with { LowAlertMetric = AlertFor[i] })),
        });
        var lowChoices = AppSettings.LowThresholdChoices;
        lowAlert.Items.Add(new SettingsCard
        {
            Header = "Alert when remaining reaches",
            Content = Combo("ThresholdBox", lowChoices.Select(t => $"{t}% left").ToArray(),
                Array.IndexOf(lowChoices, s.LowThreshold) is var t && t >= 0 ? t : 1,
                i => Update(x => x with { LowThreshold = lowChoices[i] })),
        });
        Body.Children.Add(lowAlert);
        Body.Children.Add(Card("Surprise limit refills", "When a limit refills well before its reset time.", "",
            Toggle("SurpriseRefillSwitch", s.NotifySurpriseRefills, on => Update(x => x with { NotifySurpriseRefills = on }))));
        Body.Children.Add(Card("New reset credits", "When a connected account gets a new reset credit.", "",
            Toggle("NewResetsSwitch", s.NotifyNewResets, on => Update(x => x with { NotifyNewResets = on }))));
        Body.Children.Add(Card("Expiring reset credits", "A day before an unused reset credit expires.", "",
            Toggle("ExpiringResetsSwitch", s.NotifyExpiringResets, on => Update(x => x with { NotifyExpiringResets = on }))));
        Body.Children.Add(Card("Limit resets", "When a 5-hour, weekly or monthly window starts over.", "",
            Toggle("ResetAlertSwitch", s.NotifyResets, on => Update(x => x with { NotifyResets = on }))));
        Body.Children.Add(Card("Usage is back", "When a limit you used up resets, so you know you can work again.", "",
            Toggle("UsageBackSwitch", s.NotifyUsageBack, on => Update(x => x with { NotifyUsageBack = on }))));
        Body.Children.Add(Card("New models", NewModelsNote, "",
            Toggle("NewModelsSwitch", s.NotifyNewModels, on => Update(x => x with { NotifyNewModels = on }))));

        Body.Children.Add(SectionHeader("Live usage"));
        ToggleSwitch closeToTray = null!;
        // Plain cards rather than an expander: its rows are created lazily, out of reach of UI tests.
        var live = Card("Live usage in the notification area",
            "A ring or number next to the clock that follows your usage, like the live notification on Android. Hover for every service; click for a summary.",
            "",
            Toggle("TraySwitch", s.ShowTrayIcon, on =>
            {
                Update(x => x with { ShowTrayIcon = on });
                closeToTray.IsEnabled = on;
            }));
        AutomationProperties.SetAutomationId(live, "LiveUsageCard");
        Body.Children.Add(live);
        var liveProviders = State.EnabledProviders.Where(p => State[p].SignedIn || p.Id == s.LiveProvider).ToList();
        Body.Children.Add(Card("Service", "Automatic shows the first connected service in your sidebar.", "",
            Combo("LiveProviderBox", ["Automatic", .. liveProviders.Select(p => p.DisplayName)],
                liveProviders.FindIndex(p => p.Id == s.LiveProvider) + 1,
                i => Update(x => x with { LiveProvider = i == 0 ? LiveUsage.AutomaticProvider : liveProviders[i - 1].Id }))));
        var followApps = Card("While using AI apps",
            "Show a service's usage while its app is in front. Add any app, or change which service an app shows.", "",
            Toggle("LiveFollowAppsSwitch", s.LiveFollowApps, on =>
            {
                Update(x => x with { LiveFollowApps = on });
                foreach (var card in liveApps.Children.OfType<Control>()) card.IsEnabled = on;
            }));
        AutomationProperties.SetAutomationId(followApps, "LiveFollowAppsCard");
        Body.Children.Add(followApps);
        AutomationProperties.SetAutomationId(liveApps, "LiveAppsList");
        BuildLiveApps();
        Body.Children.Add(liveApps);
        Body.Children.Add(Card("Percentage shown", "Auto shows whichever of the 5-hour and weekly limits has less left.", "",
            Combo("LiveWindowBox", ["Auto", "5-hour usage", "Weekly usage"], (int)s.LiveWindow,
                i => Update(x => x with { LiveWindow = (LiveWindowChoice)i }))));
        Body.Children.Add(Card("Icon style", "A used-up limit shows the time until it resets.", "",
            Combo("LiveStyleBox", ["Ring", "Number"], (int)s.LiveIconStyle,
                i => Update(x => x with { LiveIconStyle = (LiveIconStyle)i }))));
        closeToTray = Toggle("CloseToTraySwitch", s.CloseToTray, on => Update(x => x with { CloseToTray = on }));
        closeToTray.IsEnabled = s.ShowTrayIcon;
        Body.Children.Add(Card("Keep running when the window is closed", "Closing hides the window; quit from the tray icon.", "", closeToTray));
        Body.Children.Add(Card("Start with Windows", "Opens in the notification area when you sign in to Windows.", "",
            Toggle("StartupSwitch", State.IsDemo ? s.StartWithWindows : StartupRegistration.IsEnabled, on =>
            {
                Update(x => x with { StartWithWindows = on });
                if (State.IsDemo) return;
                try { StartupRegistration.Set(on); }
                catch (Exception error) { Debug.WriteLine("Startup registration failed: " + error.Message); }
            })));

        // App
        Body.Children.Add(GroupHeader("App"));
        Body.Children.Add(SectionHeader("Appearance"));
        Body.Children.Add(Card("App theme", "Follow Windows, or always use light or dark.", "",
            Combo("ThemeBox", ["Use system setting", "Light", "Dark"], (int)s.Theme, i => Update(x => x with { Theme = (AppTheme)i }))));
        Body.Children.Add(Card("Window material", "Mica tints the window with your desktop wallpaper.", "",
            Combo("BackdropBox", ["Mica", "Mica Alt", "Acrylic"], (int)s.Backdrop, i => Update(x => x with { Backdrop = (BackdropKind)i }))));
        if (State.Updater is { } updater)
        {
            Body.Children.Add(SectionHeader("Updates"));
            updates = new UpdatesSection(updater, () => XamlRoot, f => Update(x => x with { Updates = f(x.Updates) }), s.Updates);
            foreach (var element in updates.Elements) Body.Children.Add(element);
        }
        Body.Children.Add(SectionHeader("Privacy & data"));
        foreach (var element in Privacy()) Body.Children.Add(element);

        Body.Children.Add(GroupHeader("About"));
        Body.Children.Add(About());
    }

    protected override void Attach()
    {
        updates?.Attach();
    }

    /// <summary>Scrolls to Settings → Updates (from Priority's update card or the notification).</summary>
    public void ShowUpdates()
    {
        if (updates?.Elements.FirstOrDefault() is not { } first) return;
        void Bring() => first.StartBringIntoView(new BringIntoViewOptions { VerticalAlignmentRatio = 0, AnimationDesired = false });
        if (first.IsLoaded) Bring();
        else first.Loaded += (_, _) => Bring();
    }

    protected override void Detach()
    {
        updates?.Detach();
    }

    private void Update(Func<AppSettings, AppSettings> change) => State.Settings.Update(change);

    private void BuildLiveApps()
    {
        liveApps.Children.Clear();
        var settings = State.Settings.Current;
        foreach (var rule in AppMappings.Shown(AppMappings.Resolve(settings), State.SignedIn))
        {
            var id = rule.Key;
            var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
            controls.Children.Add(IconButton("\uE70F", $"Edit {rule.App}", "LiveAppEdit_" + id, () => _ = EditLiveAppAsync(rule)));
            controls.Children.Add(IconButton("\uE74D", $"Remove {rule.App}", "LiveAppRemove_" + id, () =>
            {
                Update(x => AppMappings.Remove(x, rule.App));
                BuildLiveApps();
            }));
            var toggle = new ToggleSwitch { IsOn = rule.On, OnContent = "", OffContent = "", MinWidth = 0 };
            AutomationProperties.SetAutomationId(toggle, "LiveAppToggle_" + id);
            AutomationProperties.SetName(toggle, "Use " + rule.App);
            toggle.Toggled += (_, _) => Update(x => AppMappings.SetOn(x, rule.App, toggle.IsOn));
            controls.Children.Add(toggle);
            var card = new SettingsCard
            {
                Header = rule.App,
                Description = LiveAppDescription(rule),
                HeaderIcon = ProviderIcons.Create(rule.Provider.Id, 20),
                Content = controls,
            };
            AutomationProperties.SetAutomationId(card, "LiveApp_" + id);
            liveApps.Children.Add(card);
        }

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var restore = new Button { Content = "Restore defaults", IsEnabled = AppMappings.IsCustomized(settings) };
        AutomationProperties.SetAutomationId(restore, "LiveAppsRestoreButton");
        restore.Click += (_, _) =>
        {
            Update(AppMappings.RestoreDefaults);
            BuildLiveApps();
        };
        buttons.Children.Add(restore);
        var add = new Button { Content = "Add app", Style = Cards.Style("AccentButtonStyle") };
        AutomationProperties.SetAutomationId(add, "LiveAppAddButton");
        add.Click += (_, _) => _ = EditLiveAppAsync(null);
        buttons.Children.Add(add);
        var addCard = Card("Add an app", "Any app or process by name, like Grok Bot showing Cursor.", "", buttons);
        AutomationProperties.SetAutomationId(addCard, "LiveAppsAddCard");
        liveApps.Children.Add(addCard);
        foreach (var child in liveApps.Children.OfType<Control>()) child.IsEnabled = settings.LiveFollowApps;
    }

    private static string LiveAppDescription(AppRule rule)
    {
        var text = rule.Provider.DisplayName;
        if (!rule.BuiltIn) return text + " · Added by you";
        if (rule.Edited && AppMappings.Defaults.FirstOrDefault(d => AppMappings.Key(d.App) == rule.Key) is { } builtIn
            && UsageProvider.Find(builtIn.ProviderId) is { } original)
            return $"{text} · Changed from {original.DisplayName}";
        return text;
    }

    /// <summary>Add an app, or edit <paramref name="rule"/>: its name and the service it shows.</summary>
    private async Task EditLiveAppAsync(AppRule? rule)
    {
        if (XamlRoot is not { } root) return;
        // Only services you're signed in to, plus the one this app already shows.
        var signedIn = State.SignedIn;
        var providers = UsageProvider.All.Where(p => (p.IsAvailable && signedIn.Contains(p)) || p == rule?.Provider).ToList();
        var name = new AutoSuggestBox
        {
            Header = "App or process name",
            PlaceholderText = "Grok Bot",
            Text = rule?.App ?? "",
        };
        AutomationProperties.SetAutomationId(name, "LiveAppNameBox");
        var running = RunningAppNames();
        name.TextChanged += (_, args) =>
        {
            if (args.Reason != AutoSuggestionBoxTextChangeReason.UserInput) return;
            var needle = name.Text.Trim();
            name.ItemsSource = needle.Length == 0 ? null
                : running.Where(n => n.Contains(needle, StringComparison.OrdinalIgnoreCase)).Take(8).ToList();
        };
        var service = new ComboBox { Header = "Shows usage for", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var provider in providers) service.Items.Add(new ComboBoxItem { Content = provider.DisplayName });
        var selected = rule is null ? -1 : providers.IndexOf(rule.Provider);
        service.SelectedIndex = providers.Count == 0 ? -1 : selected >= 0 ? selected : 0;
        AutomationProperties.SetAutomationId(service, "LiveAppServiceBox");
        var body = new StackPanel { Spacing = 12, Width = 380 };
        body.Children.Add(new TextBlock
        {
            Text = "While this app is in front, the notification area shows this service's usage.",
            Style = Cards.Style("SecondaryTextStyle"),
            TextWrapping = TextWrapping.WrapWholeWords,
        });
        body.Children.Add(name);
        body.Children.Add(service);

        var dialog = SignInFlow.Dialog(root, rule is null ? "Add an app" : "Edit app", body, "LiveAppDialog");
        dialog.PrimaryButtonText = "Save";
        dialog.DefaultButton = ContentDialogButton.Primary;
        dialog.IsPrimaryButtonEnabled = AppMappings.Key(name.Text).Length > 0;
        name.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = AppMappings.Key(name.Text).Length > 0;
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || service.SelectedIndex < 0) return;
        var chosen = providers[service.SelectedIndex];
        Update(x => AppMappings.Set(x, name.Text, chosen, replacing: rule?.App));
        BuildLiveApps();
    }

    /// <summary>Names of apps with a window open right now, for the name box's suggestions.</summary>
    private static List<string> RunningAppNames()
    {
        var names = new SortedSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var process in Process.GetProcesses())
        {
            using (process)
            {
                try
                {
                    if (process.MainWindowHandle != IntPtr.Zero && process.Id != Environment.ProcessId) names.Add(process.ProcessName);
                }
                catch (InvalidOperationException)
                {
                    // The process exited while we looked.
                }
            }
        }
        return [.. names];
    }

    private static Button IconButton(string glyph, string name, string id, Action click)
    {
        var button = new Button { Content = new FontIcon { Glyph = glyph, FontSize = 14 }, Padding = new Thickness(8) };
        AutomationProperties.SetName(button, name);
        AutomationProperties.SetAutomationId(button, id);
        ToolTipService.SetToolTip(button, name);
        button.Click += (_, _) => click();
        return button;
    }

    /// <summary>A top-level heading above a run of sections.</summary>
    private static TextBlock GroupHeader(string text)
    {
        var header = Cards.Text(text, "SubtitleTextBlockStyle", wrap: false);
        header.Margin = new Thickness(1, 32, 0, 0);
        AutomationProperties.SetHeadingLevel(header, AutomationHeadingLevel.Level2);
        AutomationProperties.SetAutomationId(header, "Group_" + text.Replace(" ", "").Replace("&", "And"));
        return header;
    }

    private static FrameworkElement ProviderHeader(UsageProvider provider)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(1, 12, 0, 4) };
        row.Children.Add(ProviderIcons.Create(provider.Id, 16));
        var name = Cards.Text(provider.DisplayName, "BodyStrongTextBlockStyle", wrap: false);
        AutomationProperties.SetHeadingLevel(name, AutomationHeadingLevel.Level3);
        row.Children.Add(name);
        return row;
    }

    /// <summary>"Your resets" with what the service has right now.</summary>
    private SettingsCard ResetsCard(UsageProvider provider)
    {
        var entry = State[provider];
        var card = Core.Models.ResetCreditsCard.For(provider, entry.Cache, entry.Usage?.ResetCreditsAvailable ?? 0, State.Settings.Current, DateTimeOffset.UtcNow);
        var result = Card("Your resets", card.Held is { } held ? card.Count + ". " + held : card.Count, "\uE777", null);
        AutomationProperties.SetAutomationId(result, "Resets_" + provider.Id);
        return result;
    }

    /// <summary>An expander whose switch turns an auto-use rule on, with its one option inside.</summary>
    private static SettingsExpander RuleExpander(string header, string description, string glyph, string id, bool on, Action<bool> changed,
        string optionHeader, FrameworkElement option)
    {
        var expander = new SettingsExpander
        {
            Header = header,
            Description = description,
            HeaderIcon = new FontIcon { Glyph = glyph },
            Content = Toggle(id, on, changed),
        };
        expander.Items.Add(new SettingsCard { Header = optionHeader, Content = option });
        return expander;
    }

    /// <summary>
    /// Privacy &amp; data: export and import settings (never sign-ins), opt-in diagnostic logs kept
    /// only on this PC, and where everything is stored.
    /// </summary>
    private IEnumerable<FrameworkElement> Privacy()
    {
        var export = new Button { Content = "Export" };
        AutomationProperties.SetAutomationId(export, "ExportSettingsButton");
        export.Click += async (_, _) =>
        {
            var text = SettingsTransfer.Export(State.Settings.Current, "windows", DateTimeOffset.UtcNow);
            if (await SaveFileAsync(SettingsTransfer.FileName, "Settings file", ".json", text)) ShowNote("Settings exported.");
        };
        yield return Card("Export settings", "Saves your settings to a file. No accounts, tokens or API keys are included.", "\uEDE1", export);

        var import = new Button { Content = "Import" };
        AutomationProperties.SetAutomationId(import, "ImportSettingsButton");
        import.Click += async (_, _) =>
        {
            try
            {
                if (await OpenFileAsync(".json") is not { } text) return;
                var imported = SettingsTransfer.Import(text, State.Settings.Current);
                State.Settings.Update(_ => imported);
                App.Current?.Window?.Reload();
            }
            catch (FormatException error) { ShowNote(error.Message); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { ShowNote("Couldn't read the file: " + error.Message); }
        };
        yield return Card("Import settings", "Replaces your settings with ones exported from Codex Meter on Windows or Linux. Your accounts stay as they are.", "\uE8B5", import);

        var log = State.Diagnostics;
        var exportLogs = new Button { Content = "Export" };
        AutomationProperties.SetAutomationId(exportLogs, "ExportLogsButton");
        var clearLogs = new Button { Content = "Clear" };
        AutomationProperties.SetAutomationId(clearLogs, "ClearLogsButton");
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        buttons.Children.Add(exportLogs);
        buttons.Children.Add(clearLogs);
        var size = new SettingsCard { Header = "Saved logs", Content = buttons };
        AutomationProperties.SetAutomationId(size, "DiagnosticLogSize");
        void RenderLogs()
        {
            var bytes = log?.Bytes ?? 0;
            size.Description = DiagnosticLog.SizeLabel(bytes);
            exportLogs.IsEnabled = bytes > 0;
            clearLogs.IsEnabled = bytes > 0;
        }
        var toggle = Toggle("DiagnosticLogSwitch", State.Settings.Current.DiagnosticLogging, on =>
        {
            Update(x => x with { DiagnosticLogging = on });
            if (on) log?.Write("app", "Diagnostic logging turned on: Codex Meter for Windows " + VersionText());
            RenderLogs();
        });
        toggle.IsEnabled = log is not null;
        var diagnostics = new SettingsExpander
        {
            Header = "Diagnostic logs",
            Description = "Keeps a privacy-filtered log on this PC to help with a bug report. Tokens, keys and email addresses are removed, and nothing is sent anywhere.",
            HeaderIcon = new FontIcon { Glyph = "\uEBE8" },
            Content = toggle,
        };
        AutomationProperties.SetAutomationId(diagnostics, "DiagnosticLogs");
        exportLogs.Click += async (_, _) =>
        {
            if (log is null) return;
            if (await SaveFileAsync("codex-meter-diagnostics.txt", "Text file", ".txt", log.Export("Codex Meter for Windows " + VersionText())))
                ShowNote("Diagnostic logs exported.");
        };
        clearLogs.Click += (_, _) =>
        {
            log?.Clear();
            RenderLogs();
        };
        diagnostics.Items.Add(size);
        RenderLogs();
        yield return diagnostics;

        var open = new Button { Content = "Open folder" };
        open.Click += (_, _) =>
        {
            Directory.CreateDirectory(AppPaths.DataDirectory);
            Process.Start(new ProcessStartInfo(AppPaths.DataDirectory) { UseShellExecute = true });
        };
        yield return Card("Your data",
            "Credentials are encrypted with Windows Data Protection for your user account. Cached usage, history and settings live in your local app data folder. Status pages and OpenRouter are checked without your account details.",
            "\uE72E", open);
    }

    private async void ShowNote(string message)
    {
        var dialog = SignInFlow.Dialog(XamlRoot, "Settings", Cards.Text(message, "BodyTextBlockStyle"), "SettingsNoteDialog");
        try
        {
            await dialog.ShowAsync();
        }
        catch (System.Runtime.InteropServices.COMException)
        {
            // WinUI allows one open ContentDialog; while another is up the note is dropped, not a crash.
        }
    }

    private static async Task<bool> SaveFileAsync(string name, string kind, string extension, string text)
    {
        if (App.Current?.Window is not { } window) return false;
        var picker = new Windows.Storage.Pickers.FileSavePicker { SuggestedFileName = Path.GetFileNameWithoutExtension(name) };
        picker.FileTypeChoices.Add(kind, [extension]);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, window.Handle);
        if (await picker.PickSaveFileAsync() is not { } file) return false;
        await File.WriteAllTextAsync(file.Path, text);
        return true;
    }

    private static async Task<string?> OpenFileAsync(string extension)
    {
        if (App.Current?.Window is not { } window) return null;
        var picker = new Windows.Storage.Pickers.FileOpenPicker();
        picker.FileTypeFilter.Add(extension);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, window.Handle);
        if (await picker.PickSingleFileAsync() is not { } file) return null;
        if (new FileInfo(file.Path).Length > SettingsTransfer.MaxBytes) throw new FormatException("The file is too large to be a Codex Meter settings file.");
        return await File.ReadAllTextAsync(file.Path);
    }

    private static string VersionText()
    {
        var version = typeof(App).Assembly
            .GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
            .OfType<System.Reflection.AssemblyInformationalVersionAttribute>().FirstOrDefault()?.InformationalVersion ?? "";
        var plus = version.IndexOf('+');
        return plus >= 0 ? version[..plus] : version;
    }

    private static SettingsCard Card(string header, string? description, string glyph, FrameworkElement? content)
    {
        var card = new SettingsCard
        {
            Header = header,
            HeaderIcon = new FontIcon { Glyph = glyph },
            Content = content,
        };
        if (description is not null)
        {
            card.Description = description;
        }
        return card;
    }

    /// <summary>The running-low rule's wait for one limit: "Don't wait", "15 minutes" … "3 days".</summary>
    private static ComboBox WaitCombo(string id, bool weekly, int minutes, Action<int> changed)
    {
        var options = weekly ? LowUsageAutoUse.WeeklyWaitOptions : LowUsageAutoUse.FiveHourWaitOptions;
        var current = weekly ? LowUsageAutoUse.NormalizedWeeklyWait(minutes) : LowUsageAutoUse.NormalizedFiveHourWait(minutes);
        return Combo(id, [.. options.Select(LowUsageAutoUse.WaitLabel)], Math.Max(0, options.ToList().IndexOf(current)), i => changed(options[i]));
    }

    private static ComboBox Combo(string id, string[] items, int selected, Action<int> changed)
    {
        var box = new ComboBox { MinWidth = 180 };
        foreach (var item in items) box.Items.Add(new ComboBoxItem { Content = item });
        box.SelectedIndex = Math.Clamp(selected, 0, items.Length - 1);
        AutomationProperties.SetAutomationId(box, id);
        box.SelectionChanged += (_, _) =>
        {
            if (box.SelectedIndex >= 0) changed(box.SelectedIndex);
        };
        return box;
    }

    private static ToggleSwitch Toggle(string id, bool on, Action<bool> changed)
    {
        var toggle = new ToggleSwitch { IsOn = on };
        AutomationProperties.SetAutomationId(toggle, id);
        toggle.Toggled += (_, _) => changed(toggle.IsOn);
        return toggle;
    }

    private SettingsExpander About()
    {
        var version = typeof(App).Assembly
            .GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
            .OfType<System.Reflection.AssemblyInformationalVersionAttribute>().FirstOrDefault()?.InformationalVersion ?? "";
        var plus = version.IndexOf('+');
        if (plus >= 0) version = version[..plus];
        var arch = System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture.ToString().ToLowerInvariant();
        var about = new SettingsExpander
        {
            Header = "Codex Meter for Windows",
            Description = "Unofficial and open source. No analytics, no ads, no relay server: the app talks to each service directly.",
            HeaderIcon = new BitmapIcon { UriSource = new Uri("ms-appx:///Assets/AppIcon.png"), ShowAsMonochrome = false },
            Content = Cards.Text($"Version {version} ({arch})", "CaptionSecondaryTextStyle", wrap: false),
        };
        var whatsNew = new Button { Content = "Show" };
        AutomationProperties.SetAutomationId(whatsNew, "WhatsNewShow");
        whatsNew.Click += (_, _) => App.Current?.Window?.ShowWhatsNew();
        about.Items.Add(new SettingsCard
        {
            Header = "What's new in " + WhatsNew.Release,
            Description = "Every feature that's new in this version.",
            Content = whatsNew,
        });
        about.Items.Add(new SettingsCard
        {
            Header = "Source code and releases",
            Description = "github.com/BenItBuhner/Codex-Meter",
            Content = new HyperlinkButton { Content = "Open on GitHub", NavigateUri = new Uri("https://github.com/BenItBuhner/Codex-Meter") },
        });
        return about;
    }
}
