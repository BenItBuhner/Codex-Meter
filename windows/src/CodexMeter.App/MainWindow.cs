using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
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

/// <summary>
/// The main window: the standard WinUI title bar over Mica, and a NavigationView with a page per
/// service (with a dot when one is low or failing), Accounts, Add account and Settings.
/// </summary>
public sealed class MainWindow : Window
{
    private const string AddTag = "add";
    private readonly AppState state;
    private readonly Grid root = new();
    private readonly TitleBar titleBar;
    private readonly NavigationView nav;
    private readonly NavigationViewItem accountsItem;
    private readonly Grid host = new();
    private readonly Dictionary<string, NavigationViewItem> providerItems = [];
    private string navSignature = "";
    private bool selecting;
    private string currentTag = "";

    public MainWindow(AppState state)
    {
        this.state = state;
        Title = "Codex Meter";
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        titleBar = new TitleBar
        {
            Title = "Codex Meter",
            Subtitle = state.IsDemo ? "Demo data" : "",
            IconSource = new ImageIconSource { ImageSource = new Microsoft.UI.Xaml.Media.Imaging.BitmapImage(new Uri("ms-appx:///Assets/AppIcon.png")) },
            IsBackButtonVisible = false,
            IsPaneToggleButtonVisible = true,
        };
        AutomationProperties.SetAutomationId(titleBar, "AppTitleBar");
        root.Children.Add(titleBar);

        nav = new NavigationView
        {
            IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed,
            IsPaneToggleButtonVisible = false,
            IsSettingsVisible = true,
            IsTitleBarAutoPaddingEnabled = false,
            PaneDisplayMode = NavigationViewPaneDisplayMode.Auto,
            CompactModeThresholdWidth = 560,
            ExpandedModeThresholdWidth = 900,
            OpenPaneLength = 260,
            Content = host,
        };
        AutomationProperties.SetAutomationId(nav, "MainNavigation");
        Grid.SetRow(nav, 1);
        root.Children.Add(nav);
        host.ChildrenTransitions = [new EntranceThemeTransition { FromVerticalOffset = 24 }];

        var addItem = new NavigationViewItem { Content = "Add account", Tag = AddTag, Icon = new FontIcon { Glyph = "" }, SelectsOnInvoked = false };
        AutomationProperties.SetAutomationId(addItem, "Nav_add");
        accountsItem = new NavigationViewItem { Content = "Accounts", Tag = "accounts", Icon = new FontIcon { Glyph = "" } };
        AutomationProperties.SetAutomationId(accountsItem, "Nav_accounts");
        nav.FooterMenuItems.Add(addItem);
        nav.FooterMenuItems.Add(accountsItem);

        nav.ItemInvoked += async (_, args) =>
        {
            if (args.InvokedItemContainer?.Tag as string == AddTag) await AddAccountAsync();
        };
        nav.SelectionChanged += (_, args) =>
        {
            if (selecting) return;
            var tag = args.IsSettingsSelected ? "settings" : (args.SelectedItem as NavigationViewItem)?.Tag as string;
            if (tag is null or AddTag) return;
            ShowPage(tag);
        };
        titleBar.PaneToggleRequested += (_, _) => nav.IsPaneOpen = !nav.IsPaneOpen;

        Content = root;
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(titleBar);
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Title = "Codex Meter";
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.PreferredMinimumWidth = 420;
            presenter.PreferredMinimumHeight = 480;
        }
        Resize(1180, 820);

        ApplyAppearance();
        BuildNavigation(initial: true);
        state.ProvidersChanged += () => BuildNavigation(initial: false);
        state.ProviderChanged += UpdateBadge;
        state.Settings.Changed += (_, _) => ApplyAppearance();
        AppWindow.Closing += OnClosing;
    }

    public bool AllowClose { get; set; }

    public IntPtr Handle => WindowNative.GetWindowHandle(this);

    public string CurrentTag => currentTag;

    public Type? CurrentPageType => host.Children.FirstOrDefault()?.GetType();

    public ScrollViewer? CurrentScrollViewer => (host.Children.FirstOrDefault() as PageBase)?.Scroller;

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
        if (root.RequestedTheme != theme) root.RequestedTheme = theme;

        SystemBackdrop = settings.Backdrop switch
        {
            BackdropKind.Acrylic => SystemBackdrop is DesktopAcrylicBackdrop ? SystemBackdrop : new DesktopAcrylicBackdrop(),
            BackdropKind.MicaAlt => SystemBackdrop is MicaBackdrop { Kind: MicaKind.BaseAlt } ? SystemBackdrop : new MicaBackdrop { Kind = MicaKind.BaseAlt },
            _ => SystemBackdrop is MicaBackdrop { Kind: MicaKind.Base } ? SystemBackdrop : new MicaBackdrop { Kind = MicaKind.Base },
        };

        var dark = theme == ElementTheme.Dark
                   || (theme == ElementTheme.Default && Application.Current.RequestedTheme == ApplicationTheme.Dark);
        var caption = AppWindow.TitleBar;
        caption.ButtonBackgroundColor = Colors.Transparent;
        caption.ButtonInactiveBackgroundColor = Colors.Transparent;
        caption.ButtonForegroundColor = dark ? Colors.White : Colors.Black;
        caption.ButtonInactiveForegroundColor = dark ? ColorHelper.FromArgb(0xFF, 0x9A, 0x9A, 0x9A) : ColorHelper.FromArgb(0xFF, 0x80, 0x80, 0x80);
        caption.ButtonHoverBackgroundColor = dark ? ColorHelper.FromArgb(0x15, 0xFF, 0xFF, 0xFF) : ColorHelper.FromArgb(0x10, 0x00, 0x00, 0x00);
        caption.ButtonHoverForegroundColor = dark ? Colors.White : Colors.Black;
        caption.ButtonPressedBackgroundColor = dark ? ColorHelper.FromArgb(0x0B, 0xFF, 0xFF, 0xFF) : ColorHelper.FromArgb(0x08, 0x00, 0x00, 0x00);
    }

    /// <summary>One pane item per enabled service, in the user's order, with the service's own mark.</summary>
    private void BuildNavigation(bool initial)
    {
        var enabled = state.EnabledProviders;
        var signature = string.Join(",", enabled.Select(p => p.Id));
        if (!initial && signature == navSignature) return;
        navSignature = signature;

        selecting = true;
        try
        {
            nav.MenuItems.Clear();
            providerItems.Clear();
            foreach (var provider in enabled)
            {
                var item = new NavigationViewItem { Content = provider.DisplayName, Tag = provider.Id, Icon = ProviderIcons.Create(provider.Id) };
                AutomationProperties.SetAutomationId(item, "Nav_" + provider.Id);
                providerItems[provider.Id] = item;
                nav.MenuItems.Add(item);
                UpdateBadge(provider);
            }
        }
        finally
        {
            selecting = false;
        }

        if (initial && !state.Settings.Current.OnboardingDone && state.SignedIn.Count == 0)
        {
            ShowPage("welcome");
            return;
        }
        var target = initial || currentTag.Length == 0 ? state.Settings.Current.SelectedProvider : currentTag;
        if (target is "accounts" or "settings")
        {
            Navigate(target);
            return;
        }
        if (target == "welcome" && state.SignedIn.Count == 0) return;
        Navigate(UsageProvider.VisibleSelection(UsageProvider.Resolve(target), enabled).Id);
    }

    /// <summary>A red dot when a refresh failed, amber when a window is nearly used up.</summary>
    private void UpdateBadge(UsageProvider provider)
    {
        if (!providerItems.TryGetValue(provider.Id, out var item)) return;
        var entry = state[provider];
        var style = entry.HasError ? "CriticalDotInfoBadgeStyle" : entry.IsLow ? "CautionDotInfoBadgeStyle" : null;
        if (style is null)
        {
            item.InfoBadge = null;
            return;
        }
        var badge = new InfoBadge { Style = Cards.Style(style) };
        AutomationProperties.SetName(badge, entry.HasError ? "Couldn't refresh" : "Running low");
        item.InfoBadge = badge;
    }

    /// <summary>Opens a service dashboard ("codex", "cursor", ...), "accounts", "settings" or "welcome".</summary>
    public void Navigate(string tag)
    {
        object? item = tag switch
        {
            "settings" => nav.SettingsItem,
            "accounts" => accountsItem,
            "welcome" => null,
            _ => providerItems.GetValueOrDefault(tag),
        };
        if (tag is not ("settings" or "welcome") && item is null) return;
        selecting = true;
        try
        {
            if (nav.SelectedItem != item) nav.SelectedItem = item;
        }
        finally
        {
            selecting = false;
        }
        if (tag == currentTag && host.Children.Count > 0) return;
        ShowPage(tag);
    }

    private void ShowPage(string tag)
    {
        currentTag = tag;
        UIElement page = tag switch
        {
            "settings" => new SettingsPage(),
            "accounts" => new AccountsPage(),
            "welcome" => new WelcomePage(),
            _ => new DashboardPage(UsageProvider.Resolve(tag)),
        };
        host.Children.Clear();
        host.Children.Add(page);
        if (tag != "welcome" && state.Settings.Current.SelectedProvider != tag)
            state.Settings.Update(s => s with { SelectedProvider = tag });
    }

    /// <summary>Add account: pick a service, run its sign-in, then open its page. Returns an error to show.</summary>
    public async Task<string?> AddAccountAsync(Action<ContentDialog>? opened = null)
    {
        var xamlRoot = root.XamlRoot;
        if (xamlRoot is null) return null;
        var provider = await AddAccountDialog.PickAsync(xamlRoot, opened);
        if (provider is null) return null;
        var error = await SignInFlow.RunAsync(provider, xamlRoot);
        if (error is null && state[provider].SignedIn) Navigate(provider.Id);
        return error;
    }

    /// <summary>Opens Edit dashboard for the page on screen.</summary>
    public Task EditDashboardAsync(Action<ContentDialog>? opened = null) =>
        host.Children.FirstOrDefault() is DashboardPage page && root.XamlRoot is { } xamlRoot
            ? EditDashboardDialog.ShowAsync(page.Provider, xamlRoot, opened)
            : Task.CompletedTask;
}
