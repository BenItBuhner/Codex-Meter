using System.Diagnostics;
using CodexMeter.App.Assets;
using CodexMeter.App.Services;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CommunityToolkit.WinUI.Controls;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace CodexMeter.App.Views;

public sealed partial class SettingsPage : Page
{
    private static readonly int[] Thresholds = [10, 15, 25];
    private readonly AppState state = App.Current!.State;
    private bool loading;

    public SettingsPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        state.ProvidersChanged += BuildProviders;
        Load();
        BuildProviders();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => state.ProvidersChanged -= BuildProviders;

    private void Load()
    {
        loading = true;
        var s = state.Settings.Current;
        ThemeBox.SelectedIndex = (int)s.Theme;
        BackdropBox.SelectedIndex = (int)s.Backdrop;
        DisplayBox.SelectedIndex = s.DisplayMode == UsageDisplayMode.Remaining ? 0 : 1;
        ResetBox.SelectedIndex = (int)s.ResetDisplay;
        ClockSwitch.IsOn = s.Use24HourClock;
        PaceSwitch.IsOn = s.ShowPace;
        RefreshBox.SelectedIndex = Math.Max(0, Array.IndexOf(AppSettings.RefreshChoices, s.RefreshMinutes));
        ResetAlertSwitch.IsOn = s.NotifyResets;
        LowAlertSwitch.IsOn = s.NotifyLow;
        ThresholdBox.SelectedIndex = Math.Max(0, Array.IndexOf(Thresholds, s.LowThreshold) is var i && i >= 0 ? i : 1);
        TraySwitch.IsOn = s.ShowTrayIcon;
        CloseToTraySwitch.IsOn = s.CloseToTray;
        CloseToTraySwitch.IsEnabled = s.ShowTrayIcon;
        StartupSwitch.IsOn = state.IsDemo ? s.StartWithWindows : StartupRegistration.IsEnabled;
        var version = typeof(App).Assembly.GetName().Version;
        VersionText.Text = $"Version 2.9.0-alpha.4 ({(Environment.Is64BitProcess ? System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture.ToString().ToLowerInvariant() : "x86")})";
        loading = false;
    }

    private void OnChanged(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        state.Settings.Update(s => s with
        {
            Theme = (AppTheme)Math.Max(0, ThemeBox.SelectedIndex),
            Backdrop = (BackdropKind)Math.Max(0, BackdropBox.SelectedIndex),
            DisplayMode = DisplayBox.SelectedIndex == 1 ? UsageDisplayMode.Used : UsageDisplayMode.Remaining,
            ResetDisplay = (ResetDisplayMode)Math.Max(0, ResetBox.SelectedIndex),
            Use24HourClock = ClockSwitch.IsOn,
            ShowPace = PaceSwitch.IsOn,
            RefreshMinutes = AppSettings.RefreshChoices[Math.Max(0, RefreshBox.SelectedIndex)],
            NotifyResets = ResetAlertSwitch.IsOn,
            NotifyLow = LowAlertSwitch.IsOn,
            LowThreshold = Thresholds[Math.Max(0, ThresholdBox.SelectedIndex)],
            ShowTrayIcon = TraySwitch.IsOn,
            CloseToTray = CloseToTraySwitch.IsOn,
            StartWithWindows = StartupSwitch.IsOn,
        });
        CloseToTraySwitch.IsEnabled = TraySwitch.IsOn;
        if (ReferenceEquals(sender, StartupSwitch) && !state.IsDemo)
        {
            try { StartupRegistration.Set(StartupSwitch.IsOn); }
            catch (Exception error) { Debug.WriteLine("Startup registration failed: " + error.Message); }
        }
    }

    private void OnChanged(object sender, SelectionChangedEventArgs e) => OnChanged(sender, (RoutedEventArgs)e);

    private void BuildProviders()
    {
        ProvidersExpander.Items.Clear();
        var enabled = state.EnabledProviders;
        var order = UsageProvider.OrderFromIds(state.Settings.Current.ProviderOrder);
        for (var index = 0; index < order.Count; index++)
        {
            var provider = order[index];
            var entry = state[provider];
            var toggle = new ToggleSwitch { IsOn = enabled.Contains(provider), OnContent = "", OffContent = "", MinWidth = 0 };
            AutomationProperties.SetAutomationId(toggle, "ProviderToggle_" + provider.Id);
            AutomationProperties.SetName(toggle, "Show " + provider.DisplayName);
            toggle.IsEnabled = !(toggle.IsOn && enabled.Count == 1);
            toggle.Toggled += (_, _) => state.SetEnabled(provider, toggle.IsOn);
            var up = new Button { Content = new FontIcon { Glyph = "", FontSize = 12 }, IsEnabled = index > 0, Padding = new Thickness(8) };
            AutomationProperties.SetName(up, $"Move {provider.DisplayName} up");
            up.Click += (_, _) => state.Move(provider, -1);
            var down = new Button { Content = new FontIcon { Glyph = "", FontSize = 12 }, IsEnabled = index < order.Count - 1, Padding = new Thickness(8) };
            AutomationProperties.SetName(down, $"Move {provider.DisplayName} down");
            down.Click += (_, _) => state.Move(provider, 1);
            var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            controls.Children.Add(up);
            controls.Children.Add(down);
            controls.Children.Add(toggle);
            ProvidersExpander.Items.Add(new SettingsCard
            {
                Header = provider.DisplayName,
                Description = entry.SignedIn ? $"Connected · {provider.Meters}" : $"Not connected · {provider.Meters}",
                HeaderIcon = ProviderIcons.Create(provider.Id, 20),
                Content = controls,
            });
        }
    }

    private void OnOpenDataFolder(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(AppPaths.DataDirectory);
        Process.Start(new ProcessStartInfo(AppPaths.DataDirectory) { UseShellExecute = true });
    }
}
