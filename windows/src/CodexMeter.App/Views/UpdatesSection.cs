using CodexMeter.App.Controls;
using CodexMeter.Core.Updates;
using CommunityToolkit.WinUI.Controls;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Views;

/// <summary>
/// Settings → Updates, the same options as Android's Updates page: the update channel (stable or
/// alpha), automatic checks and how often, a notification when an update is out, and Check for
/// updates, which downloads the setup (or zip), checks its SHA-256 against the release's
/// SHA256SUMS-windows.txt, then installs it and reopens the app.
/// </summary>
public sealed class UpdatesSection
{
    private readonly DesktopUpdater updater;
    private readonly Func<XamlRoot?> xamlRoot;
    private readonly SettingsCard status;
    private readonly Button action = new() { Style = Cards.Style("AccentButtonStyle"), MinWidth = 150 };
    private readonly ProgressBar progress = new() { Width = 150, Minimum = 0, Maximum = 1, Visibility = Visibility.Collapsed };
    private readonly HyperlinkButton notes = new() { Visibility = Visibility.Collapsed, Padding = new Thickness(0) };
    private readonly ComboBox channel;
    private bool syncing;

    public UpdatesSection(DesktopUpdater updater, Func<XamlRoot?> xamlRoot, Action<Func<UpdatePreferences, UpdatePreferences>> save,
        UpdatePreferences prefs)
    {
        this.updater = updater;
        this.xamlRoot = xamlRoot;
        AutomationProperties.SetAutomationId(action, "UpdateActionButton");
        AutomationProperties.SetAutomationId(progress, "UpdateProgress");
        AutomationProperties.SetAutomationId(notes, "UpdateNotesLink");
        action.Click += async (_, _) => await ActAsync();

        var controls = new StackPanel { Spacing = 6, HorizontalAlignment = HorizontalAlignment.Right };
        controls.Children.Add(action);
        controls.Children.Add(progress);
        status = new SettingsCard
        {
            Header = "Codex Meter updates",
            HeaderIcon = new FontIcon { Glyph = "" },
            Content = controls,
        };
        AutomationProperties.SetAutomationId(status, "UpdateStatusCard");

        channel = new ComboBox { MinWidth = 180 };
        channel.Items.Add(new ComboBoxItem { Content = "Stable" });
        channel.Items.Add(new ComboBoxItem { Content = "Alpha (prereleases)" });
        AutomationProperties.SetAutomationId(channel, "UpdateChannelBox");
        channel.SelectionChanged += async (_, _) => await ChannelChangedAsync();

        var auto = new ToggleSwitch { IsOn = prefs.AutoCheck };
        AutomationProperties.SetAutomationId(auto, "UpdateAutoSwitch");
        var frequency = new ComboBox { MinWidth = 180 };
        foreach (var hours in UpdateCheckFrequency.Hours) frequency.Items.Add(new ComboBoxItem { Content = UpdateCheckFrequency.Label(hours) });
        frequency.SelectedIndex = Math.Max(0, Array.IndexOf(UpdateCheckFrequency.Hours, UpdateCheckFrequency.Normalize(prefs.CheckHours)));
        frequency.IsEnabled = prefs.AutoCheck;
        AutomationProperties.SetAutomationId(frequency, "UpdateFrequencyBox");
        auto.Toggled += (_, _) =>
        {
            save(p => p with { AutoCheck = auto.IsOn });
            frequency.IsEnabled = auto.IsOn;
        };
        frequency.SelectionChanged += (_, _) =>
        {
            if (frequency.SelectedIndex >= 0) save(p => p with { CheckHours = UpdateCheckFrequency.Hours[frequency.SelectedIndex] });
        };
        var notify = new ToggleSwitch { IsOn = prefs.Notify };
        AutomationProperties.SetAutomationId(notify, "UpdateNotifySwitch");
        notify.Toggled += (_, _) => save(p => p with { Notify = notify.IsOn });

        Elements =
        [
            status,
            new SettingsCard { Header = "Release notes", HeaderIcon = new FontIcon { Glyph = "" }, Content = notes, Visibility = Visibility.Collapsed, Tag = "notes" },
            new SettingsCard
            {
                Header = "Update channel",
                Description = "Alpha gets new features first, with less testing. Desktop builds ship on both channels from the same release.",
                HeaderIcon = new FontIcon { Glyph = "" },
                Content = channel,
            },
            new SettingsCard
            {
                Header = "Check automatically",
                Description = "Ask GitHub for the newest release in the background. Each check reads at most two small responses.",
                HeaderIcon = new FontIcon { Glyph = "" },
                Content = auto,
            },
            new SettingsCard { Header = "Check frequency", HeaderIcon = new FontIcon { Glyph = "" }, Content = frequency },
            new SettingsCard
            {
                Header = "Notify when an update is available",
                Description = "A Windows notification, once per version.",
                HeaderIcon = new FontIcon { Glyph = "" },
                Content = notify,
            },
        ];
        Render();
    }

    public IReadOnlyList<FrameworkElement> Elements { get; }

    public void Attach() => updater.Changed += Render;

    public void Detach() => updater.Changed -= Render;

    private void Render()
    {
        syncing = true;
        channel.SelectedIndex = updater.Channel == UpdateChannel.Alpha ? 1 : 0;
        syncing = false;
        status.Description = updater.StatusText + (updater.Status is UpdateStatus.Idle or UpdateStatus.UpToDate ? "" : "\n" + updater.LastCheckText);
        progress.Visibility = updater.Status == UpdateStatus.Downloading ? Visibility.Visible : Visibility.Collapsed;
        progress.IsIndeterminate = updater.Status == UpdateStatus.Installing;
        progress.Value = updater.Progress;
        if (updater.Status == UpdateStatus.Installing) progress.Visibility = Visibility.Visible;
        action.IsEnabled = updater.Status != UpdateStatus.Installing && updater.Status != UpdateStatus.Checking;
        action.Content = updater.Status switch
        {
            UpdateStatus.Checking => "Checking…",
            UpdateStatus.Downloading => "Cancel",
            UpdateStatus.Installing => "Installing…",
            UpdateStatus.Available when updater.CannotInstallReason is not null => "Open release page",
            UpdateStatus.Available when updater.IsReturnToStable => "Return to stable",
            UpdateStatus.Available => "Download and install",
            _ => "Check for updates",
        };
        var release = updater.Available ?? updater.Latest;
        var notesCard = (SettingsCard)Elements[1];
        notesCard.Visibility = release is null ? Visibility.Collapsed : Visibility.Visible;
        if (release is not null)
        {
            notes.Content = $"What's new in {release.Version.Normalized}";
            notes.NavigateUri = new Uri(release.PageUrl);
            notes.Visibility = Visibility.Visible;
            notesCard.Description = FirstLines(release.Notes);
        }
    }

    private static string FirstLines(string notes)
    {
        var lines = notes.Split('\n').Select(l => l.Trim().TrimStart('#', '-', '*', ' ').Replace("**", "")).Where(l => l.Length > 0).Take(3);
        var text = string.Join("\n", lines);
        return text.Length > 300 ? text[..300] + "…" : text;
    }

    private async Task ActAsync()
    {
        switch (updater.Status)
        {
            case UpdateStatus.Downloading:
                updater.Cancel();
                break;
            case UpdateStatus.Available when updater.CannotInstallReason is not null:
                await Services.Browser.OpenAsync(new Uri(updater.Available!.PageUrl));
                break;
            case UpdateStatus.Available:
                if (updater.IsReturnToStable && !await ConfirmAsync("Return to stable?",
                        $"This installs stable {updater.Available!.Version.Normalized}, an older version than this alpha. Your accounts and settings stay.", "Install stable"))
                    return;
                await updater.DownloadAndInstallAsync();
                break;
            default:
                await updater.CheckAsync(manual: true);
                break;
        }
    }

    private async Task ChannelChangedAsync()
    {
        if (syncing || channel.SelectedIndex < 0) return;
        var chosen = channel.SelectedIndex == 1 ? UpdateChannel.Alpha : UpdateChannel.Stable;
        if (chosen == updater.Channel && updater.UserChoseChannel) return;
        if (chosen == UpdateChannel.Alpha && updater.Channel != UpdateChannel.Alpha && !await ConfirmAsync("Switch to the alpha channel?",
                "Alpha builds ship faster with less testing and may be unstable. Switching back to stable later is one install, and your accounts and settings stay.",
                "Use alpha"))
        {
            Render();
            return;
        }
        updater.SetChannel(chosen);
        await updater.CheckAsync(manual: true);
    }

    private async Task<bool> ConfirmAsync(string title, string body, string primary)
    {
        if (xamlRoot() is not { } root) return true;
        var dialog = SignInFlow.Dialog(root, title, Cards.Text(body, "BodyTextBlockStyle"), "UpdateConfirmDialog");
        dialog.PrimaryButtonText = primary;
        dialog.DefaultButton = ContentDialogButton.Primary;
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }
}
