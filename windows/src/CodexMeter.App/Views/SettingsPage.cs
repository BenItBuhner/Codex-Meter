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
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;

namespace CodexMeter.App.Views;

/// <summary>
/// Settings in the Windows 11 Settings app style: grouped setting cards and expanders, with the
/// same options as the phone apps plus the Windows-only tray and startup choices.
/// </summary>
public sealed class SettingsPage : PageBase
{
    private static readonly int[] Thresholds = [10, 15, 25];
    private readonly SettingsExpander sidebar;
    // Rows go through an observable ItemsSource: the expander doesn't notice later changes to Items.
    private readonly System.Collections.ObjectModel.ObservableCollection<SettingsCard> sidebarRows = [];
    private bool sidebarDirty;
    private readonly UpdatesSection? updates;
    /// <summary>"While using AI apps": one card per app, then Add an app. Plain cards so UI tests reach them.</summary>
    private readonly StackPanel liveApps = new() { Spacing = 4, Margin = new Thickness(32, 0, 0, 0) };

    public SettingsPage() : base(1000)
    {
        var s = State.Settings.Current;
        Body.Children.Add(PageTitle("Settings", "SettingsTitle"));

        Body.Children.Add(SectionHeader("Appearance"));
        Body.Children.Add(Card("App theme", "Follow Windows, or always use light or dark.", "",
            Combo("ThemeBox", ["Use system setting", "Light", "Dark"], (int)s.Theme, i => Update(x => x with { Theme = (AppTheme)i }))));
        Body.Children.Add(Card("Window material", "Mica tints the window with your desktop wallpaper.", "",
            Combo("BackdropBox", ["Mica", "Mica Alt", "Acrylic"], (int)s.Backdrop, i => Update(x => x with { Backdrop = (BackdropKind)i }))));

        if (State.Updater is { } updater)
        {
            // First, so a pending update is the first thing Settings shows.
            Body.Children.Add(SectionHeader("Updates"));
            updates = new UpdatesSection(updater, () => XamlRoot, f => Update(x => x with { Updates = f(x.Updates) }), s.Updates);
            foreach (var element in updates.Elements) Body.Children.Add(element);
        }

        Body.Children.Add(SectionHeader("Sidebar"));
        sidebar = new SettingsExpander
        {
            Header = "Pages in the sidebar",
            Description = "Choose which services get a page, and their order.",
            HeaderIcon = new FontIcon { Glyph = "" },
            IsExpanded = true,
        };
        AutomationProperties.SetAutomationId(sidebar, "ProvidersExpander");
        BuildSidebar();
        sidebar.ItemsSource = sidebarRows;
        Body.Children.Add(sidebar);
        Body.Children.Add(Card("Priority page", "First in the sidebar: limits to watch, used-up limits, what's coming up and new models, across every connected service.", "\uE734",
            Toggle("PrioritySwitch", s.ShowPriority, on => Update(x => x with { ShowPriority = on }))));

        Body.Children.Add(SectionHeader("Dashboard"));
        Body.Children.Add(Card("Show usage as", "The big number on each card.", "",
            Combo("DisplayBox", ["Remaining", "Used"], s.DisplayMode == UsageDisplayMode.Remaining ? 0 : 1,
                i => Update(x => x with { DisplayMode = i == 1 ? UsageDisplayMode.Used : UsageDisplayMode.Remaining }))));
        Body.Children.Add(Card("Reset times", "How each card says when its window resets.", "",
            Combo("ResetBox", ["Countdown", "Date and time", "Both"], (int)s.ResetDisplay, i => Update(x => x with { ResetDisplay = (ResetDisplayMode)i }))));
        Body.Children.Add(Card("24-hour clock", null, "", Toggle("ClockSwitch", s.Use24HourClock, on => Update(x => x with { Use24HourClock = on }))));
        Body.Children.Add(Card("Pace estimates", "Warn when a window would run out before it resets at your current pace.", "",
            Toggle("PaceSwitch", s.ShowPace, on => Update(x => x with { ShowPace = on }))));
        Body.Children.Add(Card("Cards on each dashboard", "Use Edit dashboard on a service's page to hide or reorder its cards.", "", null));

        Body.Children.Add(SectionHeader("Usage history"));
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
        Body.Children.Add(Card("Recorded history", "Saved on this PC each time usage refreshes, and cleared when you sign out.", "", clear));

        // Only for a connected Codex account, as on Android: the rules do nothing without one.
        if (State.Supports(UsageProvider.Codex) && State[UsageProvider.Codex].SignedIn)
        {
            Body.Children.Add(SectionHeader("Codex reset credits"));
            var leads = CreditExpiryAutoApply.LeadMinuteOptions;
            var expiring = new SettingsExpander
            {
                Header = "Use resets before they expire",
                Description = "Spend a banked reset shortly before it would expire unused.",
                HeaderIcon = new FontIcon { Glyph = "" },
                Content = Toggle("AutoUseExpiringSwitch", s.AutoUseExpiringResets, on => Update(x => x with { AutoUseExpiringResets = on })),
            };
            expiring.Items.Add(new SettingsCard
            {
                Header = "How early",
                Content = Combo("AutoUseLeadBox", leads.Select(CreditExpiryAutoApply.LeadLabel).ToArray(),
                    Math.Max(0, leads.ToList().IndexOf(CreditExpiryAutoApply.NormalizedLeadMinutes(s.AutoUseLeadMinutes))),
                    i => Update(x => x with { AutoUseLeadMinutes = leads[i] })),
            });
            Body.Children.Add(expiring);

            var thresholds = LowUsageAutoUse.ThresholdOptions;
            var low = new SettingsExpander
            {
                Header = "Use a reset when running low",
                Description = "Spend a banked reset when a limit is nearly used up, unless it resets soon anyway.",
                HeaderIcon = new FontIcon { Glyph = "" },
                Content = Toggle("AutoUseLowSwitch", s.AutoUseWhenLow, on => Update(x => x with { AutoUseWhenLow = on })),
            };
            low.Items.Add(new SettingsCard
            {
                Header = "Watch",
                Content = Combo("AutoUseMetricBox", ["5-hour or weekly", "Weekly", "5-hour"], (int)s.AutoUseMetric,
                    i => Update(x => x with { AutoUseMetric = (LowUsageAutoUseMetric)i })),
            });
            low.Items.Add(new SettingsCard
            {
                Header = "When it falls to",
                Content = Combo("AutoUseThresholdBox", thresholds.Select(t => $"{t}% left").ToArray(),
                    Math.Max(0, thresholds.ToList().IndexOf(LowUsageAutoUse.NormalizedThreshold(s.AutoUseThreshold))),
                    i => Update(x => x with { AutoUseThreshold = thresholds[i] })),
            });
            Body.Children.Add(low);
        }

        Body.Children.Add(SectionHeader("Refresh and alerts"));
        Body.Children.Add(Card("Check usage every", "While Codex Meter runs. Some services are checked less often because they limit polling.", "",
            Combo("RefreshBox", ["5 minutes", "15 minutes", "30 minutes", "1 hour"], Math.Max(0, Array.IndexOf(AppSettings.RefreshChoices, s.RefreshMinutes)),
                i => Update(x => x with { RefreshMinutes = AppSettings.RefreshChoices[i] }))));
        Body.Children.Add(Card("Notify when a limit resets", null, "",
            Toggle("ResetAlertSwitch", s.NotifyResets, on => Update(x => x with { NotifyResets = on }))));
        var lowAlert = new SettingsExpander
        {
            Header = "Notify when running low",
            Description = "When a 5-hour, weekly or monthly window drops to this much or runs out.",
            HeaderIcon = new FontIcon { Glyph = "" },
            Content = Toggle("LowAlertSwitch", s.NotifyLow, on => Update(x => x with { NotifyLow = on })),
        };
        lowAlert.Items.Add(new SettingsCard
        {
            Header = "Threshold",
            Content = Combo("ThresholdBox", ["10% left", "15% left", "25% left"], Array.IndexOf(Thresholds, s.LowThreshold) is var t && t >= 0 ? t : 1,
                i => Update(x => x with { LowThreshold = Thresholds[i] })),
        });
        Body.Children.Add(lowAlert);
        Body.Children.Add(Card("Notify when usage is back", "When a limit you used up resets, so you know you can work again.", "\uE8FB",
            Toggle("UsageBackSwitch", s.NotifyUsageBack, on => Update(x => x with { NotifyUsageBack = on }))));
        Body.Children.Add(Card("Surprise limit refills", "When a limit refills well before its reset time.", "\uE945",
            Toggle("SurpriseRefillSwitch", s.NotifySurpriseRefills, on => Update(x => x with { NotifySurpriseRefills = on }))));
        Body.Children.Add(Card("New models", "When OpenRouter lists a new model from a maker you're signed in to (OpenAI for Codex, Anthropic for Claude Code, ...). Tools that offer many makers' models, like Cursor or Copilot, add none.", "\uE8D2",
            Toggle("NewModelsSwitch", s.NotifyNewModels, on => Update(x => x with { NotifyNewModels = on }))));

        Body.Children.Add(SectionHeader("Live usage"));
        ToggleSwitch closeToTray = null!;
        // Plain cards rather than an expander: its rows are created lazily, out of reach of UI tests.
        var live = Card("Live usage in the notification area",
            "A ring or number next to the clock that follows your usage, like the live notification on Android. Hover for every service; click for a summary.",
            "\uE9D9",
            Toggle("TraySwitch", s.ShowTrayIcon, on =>
            {
                Update(x => x with { ShowTrayIcon = on });
                closeToTray.IsEnabled = on;
            }));
        AutomationProperties.SetAutomationId(live, "LiveUsageCard");
        Body.Children.Add(live);
        var liveProviders = State.EnabledProviders.ToList();
        Body.Children.Add(Card("Service", "Automatic shows the first connected service in your sidebar.", "\uE77B",
            Combo("LiveProviderBox", ["Automatic", .. liveProviders.Select(p => p.DisplayName)],
                liveProviders.FindIndex(p => p.Id == s.LiveProvider) + 1,
                i => Update(x => x with { LiveProvider = i == 0 ? LiveUsage.AutomaticProvider : liveProviders[i - 1].Id }))));
        var followApps = Card("While using AI apps",
            "Show a service's usage while its app is in front. Add any app, or change which service an app shows.", "\uE7C4",
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
        Body.Children.Add(Card("Percentage shown", "Auto shows whichever of the 5-hour and weekly limits has less left.", "\uE9D2",
            Combo("LiveWindowBox", ["Auto", "5-hour usage", "Weekly usage"], (int)s.LiveWindow,
                i => Update(x => x with { LiveWindow = (LiveWindowChoice)i }))));
        Body.Children.Add(Card("Icon style", "A used-up limit shows the time until it resets.", "\uE790",
            Combo("LiveStyleBox", ["Ring", "Number"], (int)s.LiveIconStyle,
                i => Update(x => x with { LiveIconStyle = (LiveIconStyle)i }))));

        Body.Children.Add(SectionHeader("Windows"));
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

        Body.Children.Add(SectionHeader("About"));
        Body.Children.Add(About());
    }

    protected override void Attach()
    {
        updates?.Attach();
        State.ProvidersChanged += OnProvidersChanged;
        State.ProviderChanged += OnProviderChanged;
        BuildSidebar();
    }

    protected override void Detach()
    {
        updates?.Detach();
        State.ProvidersChanged -= OnProvidersChanged;
        State.ProviderChanged -= OnProviderChanged;
    }

    private void OnProvidersChanged() => QueueSidebar();

    private void OnProviderChanged(UsageProvider _) => QueueSidebar();

    /// <summary>Rebuilds once after a burst of changes, not inside the toggle that caused them.</summary>
    private void QueueSidebar()
    {
        if (sidebarDirty) return;
        sidebarDirty = true;
        DispatcherQueue.TryEnqueue(() =>
        {
            sidebarDirty = false;
            BuildSidebar();
        });
    }

    private void Update(Func<AppSettings, AppSettings> change) => State.Settings.Update(change);

    private void BuildSidebar()
    {
        sidebarRows.Clear();
        var enabled = State.EnabledProviders;
        var order = State.SidebarCandidates;
        for (var index = 0; index < order.Count; index++)
        {
            var provider = order[index];
            var entry = State[provider];
            var toggle = new ToggleSwitch { IsOn = enabled.Contains(provider), OnContent = "", OffContent = "", MinWidth = 0 };
            AutomationProperties.SetAutomationId(toggle, "ProviderToggle_" + provider.Id);
            AutomationProperties.SetName(toggle, "Show " + provider.DisplayName);
            toggle.IsEnabled = !(toggle.IsOn && enabled.Count == 1);
            toggle.Toggled += (_, _) => State.SetEnabled(provider, toggle.IsOn);
            var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
            controls.Children.Add(MoveButton("", $"Move {provider.DisplayName} up", index > 0, () => State.Move(provider, -1)));
            controls.Children.Add(MoveButton("", $"Move {provider.DisplayName} down", index < order.Count - 1, () => State.Move(provider, 1)));
            controls.Children.Add(toggle);
            sidebarRows.Add(new SettingsCard
            {
                Header = provider.DisplayName,
                Description = entry.SignedIn ? $"Connected · {provider.Meters}" : $"Not connected · {provider.Meters}",
                HeaderIcon = ProviderIcons.Create(provider.Id, 20),
                Content = controls,
            });
        }
    }

    private void BuildLiveApps()
    {
        liveApps.Children.Clear();
        var settings = State.Settings.Current;
        foreach (var rule in AppMappings.Resolve(settings))
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
        var addCard = Card("Add an app", "Any app or process by name, like Grok Bot showing Cursor.", "\uE710", buttons);
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
        var providers = UsageProvider.All.Where(p => p.IsAvailable || p == rule?.Provider).ToList();
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
        service.SelectedIndex = selected >= 0 ? selected : 0;
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

    private static Button MoveButton(string glyph, string name, bool enabled, Action move)
    {
        var button = new Button { Content = new FontIcon { Glyph = glyph, FontSize = 12 }, IsEnabled = enabled, Padding = new Thickness(8) };
        AutomationProperties.SetName(button, name);
        ToolTipService.SetToolTip(button, name);
        button.Click += (_, _) => move();
        return button;
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
        var open = new Button { Content = "Open folder" };
        open.Click += (_, _) =>
        {
            Directory.CreateDirectory(AppPaths.DataDirectory);
            Process.Start(new ProcessStartInfo(AppPaths.DataDirectory) { UseShellExecute = true });
        };
        about.Items.Add(new SettingsCard
        {
            Header = "Your data",
            Description = "Credentials are encrypted with Windows Data Protection for your user account. Cached usage, history and settings live in your local app data folder.",
            Content = open,
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
