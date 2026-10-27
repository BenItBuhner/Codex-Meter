using CodexMeter.App.Assets;
using CodexMeter.App.Services;
using CodexMeter.App.Views;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using Microsoft.UI;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.Graphics;
using WinRT.Interop;

namespace CodexMeter.App;

public sealed partial class MainWindow : Window
{
    private readonly AppState state;
    private bool rebuildingNav;

    public MainWindow(AppState state)
    {
        this.state = state;
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Title = "Codex Meter";
        DemoBadge.Visibility = state.IsDemo ? Visibility.Visible : Visibility.Collapsed;
        Resize(1180, 820);

        ApplyAppearance();
        BuildNavigation();
        state.ProvidersChanged += OnProvidersChanged;
        state.Settings.Changed += (_, _) => ApplyAppearance();
        AppWindow.Closing += OnClosing;
        ContentFrame.NavigationFailed += (_, args) =>
        {
            App.Log($"Navigation to {args.SourcePageType?.Name} failed: {args.Exception}");
            args.Handled = true;
        };
        Nav.DisplayModeChanged += (_, args) =>
            AppTitleBar.Padding = new Thickness(args.DisplayMode == NavigationViewDisplayMode.Minimal ? 56 : 16, 0, 140, 0);
    }

    public bool AllowClose { get; set; }

    public IntPtr Handle => WindowNative.GetWindowHandle(this);

    public void Resize(int width, int height)
    {
        var scale = NativeMethods.GetDpiForWindow(Handle) / 96.0;
        AppWindow.Resize(new SizeInt32((int)(width * scale), (int)(height * scale)));
    }

    private void OnClosing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        var settings = state.Settings.Current;
        if (!AllowClose && settings.CloseToTray && settings.ShowTrayIcon)
        {
            args.Cancel = true;
            AppWindow.Hide();
        }
    }

    public void ShowAndActivate()
    {
        AppWindow.Show();
        if (AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized } presenter) presenter.Restore();
        Activate();
        NativeMethods.SetForegroundWindow(Handle);
    }

    public void ApplyAppearance()
    {
        var settings = state.Settings.Current;
        var theme = settings.Theme switch
        {
            AppTheme.Light => ElementTheme.Light,
            AppTheme.Dark => ElementTheme.Dark,
            _ => ElementTheme.Default,
        };
        if (Content is FrameworkElement root && root.RequestedTheme != theme) root.RequestedTheme = theme;

        SystemBackdrop = settings.Backdrop switch
        {
            BackdropKind.Acrylic => SystemBackdrop is DesktopAcrylicBackdrop ? SystemBackdrop : new DesktopAcrylicBackdrop(),
            BackdropKind.MicaAlt => SystemBackdrop is MicaBackdrop { Kind: MicaKind.BaseAlt } ? SystemBackdrop : new MicaBackdrop { Kind = MicaKind.BaseAlt },
            _ => SystemBackdrop is MicaBackdrop { Kind: MicaKind.Base } ? SystemBackdrop : new MicaBackdrop { Kind = MicaKind.Base },
        };

        var dark = theme == ElementTheme.Dark
                   || (theme == ElementTheme.Default && Application.Current.RequestedTheme == ApplicationTheme.Dark);
        var titleBar = AppWindow.TitleBar;
        titleBar.ButtonBackgroundColor = Colors.Transparent;
        titleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
        titleBar.ButtonForegroundColor = dark ? Colors.White : Colors.Black;
        titleBar.ButtonInactiveForegroundColor = dark ? ColorHelper.FromArgb(0xFF, 0x9A, 0x9A, 0x9A) : ColorHelper.FromArgb(0xFF, 0x80, 0x80, 0x80);
        titleBar.ButtonHoverBackgroundColor = dark ? ColorHelper.FromArgb(0x15, 0xFF, 0xFF, 0xFF) : ColorHelper.FromArgb(0x10, 0x00, 0x00, 0x00);
        titleBar.ButtonHoverForegroundColor = dark ? Colors.White : Colors.Black;
        titleBar.ButtonPressedBackgroundColor = dark ? ColorHelper.FromArgb(0x0B, 0xFF, 0xFF, 0xFF) : ColorHelper.FromArgb(0x08, 0x00, 0x00, 0x00);
    }

    private void OnProvidersChanged()
    {
        BuildNavigation(currentTag.Length > 0 ? currentTag : null);
    }

    /// <summary>One pane item per enabled provider, in the user's order, with the provider's own mark.</summary>
    private void BuildNavigation(string? select = null)
    {
        rebuildingNav = true;
        try
        {
            Nav.MenuItems.Clear();
            var enabled = state.EnabledProviders;
            foreach (var provider in enabled)
            {
                var item = new NavigationViewItem
                {
                    Content = provider.DisplayName,
                    Tag = provider.Id,
                    Icon = ProviderIcons.Create(provider.Id),
                };
                AutomationProperties.SetAutomationId(item, "Nav_" + provider.Id);
                Nav.MenuItems.Add(item);
            }
        }
        finally
        {
            rebuildingNav = false;
        }
        var target = select ?? state.Settings.Current.SelectedProvider;
        if (target is "accounts" or "settings")
        {
            Navigate(target);
            return;
        }
        var selected = UsageProvider.VisibleSelection(UsageProvider.Resolve(target), state.EnabledProviders);
        Navigate(selected.Id);
    }

    /// <summary>Opens a provider dashboard ("codex", "claude_code", "cursor"), "accounts" or "settings".</summary>
    public void Navigate(string tag)
    {
        object? item = tag switch
        {
            "settings" => Nav.SettingsItem,
            "accounts" => AccountsItem,
            _ => Nav.MenuItems.OfType<NavigationViewItem>().FirstOrDefault(i => (string)i.Tag == tag),
        };
        if (tag != "settings" && item is null) return;
        // Select the pane item without relying on SelectionChanged, then show the page directly.
        rebuildingNav = true;
        try
        {
            if (item is not null && Nav.SelectedItem != item) Nav.SelectedItem = item;
        }
        finally
        {
            rebuildingNav = false;
        }
        if (tag == currentTag && ContentFrame.Content is not null) return;
        ShowPage(tag);
    }

    private void OnNavigationSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (rebuildingNav) return;
        var tag = args.IsSettingsSelected ? "settings" : (args.SelectedItem as NavigationViewItem)?.Tag as string;
        if (tag is null) return;
        ShowPage(tag);
    }

    private void ShowPage(string tag)
    {
        currentTag = tag;
        var transition = new EntranceNavigationTransitionInfo();
        switch (tag)
        {
            case "settings":
                ContentFrame.Navigate(typeof(SettingsPage), null, transition);
                break;
            case "accounts":
                ContentFrame.Navigate(typeof(AccountsPage), null, transition);
                break;
            default:
                ContentFrame.Navigate(typeof(DashboardPage), tag, transition);
                break;
        }
        if (state.Settings.Current.SelectedProvider != tag)
            state.Settings.Update(s => s with { SelectedProvider = tag });
    }

    private string currentTag = "";

    public string CurrentTag => currentTag;

    public Type? CurrentPageType => ContentFrame.Content?.GetType();

    public ScrollViewer? CurrentScrollViewer => (ContentFrame.Content as FrameworkElement)?.FindName("PageScroller") as ScrollViewer;
}
