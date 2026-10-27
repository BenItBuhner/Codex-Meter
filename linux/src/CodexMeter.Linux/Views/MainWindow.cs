using CodexMeter.App.Assets;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;
using CodexMeter.Linux.Widgets;

namespace CodexMeter.Linux.Views;

/// <summary>
/// The main window: an adaptive split view with providers, Accounts and Settings in the sidebar,
/// following the GNOME HIG. Below 640 px it collapses to a single pane with a back button.
/// </summary>
public sealed class MainWindow
{
    private readonly AppState state;
    private readonly Adw.NavigationSplitView split;
    private readonly Gtk.ListBox providerList;
    private readonly Gtk.ListBox appList;
    private readonly Adw.ToastOverlay toasts;
    private readonly Dictionary<string, (Gtk.ListBoxRow Row, Gtk.Label? Value)> rows = new();
    private IPage? current;
    private string shownProviders = "";
    private bool selecting;

    public MainWindow(Adw.Application app, AppState state)
    {
        this.state = state;
        Window = Adw.ApplicationWindow.New(app);
        Window.SetTitle("Codex Meter");
        Window.SetIconName(AppPaths.AppId);
        Window.SetDefaultSize(1100, 780);
        Window.SetSizeRequest(360, 480);

        providerList = Gtk.ListBox.New();
        providerList.AddCssClass("navigation-sidebar");
        providerList.SetSelectionMode(Gtk.SelectionMode.Single);
        appList = Gtk.ListBox.New();
        appList.AddCssClass("navigation-sidebar");
        appList.SetSelectionMode(Gtk.SelectionMode.Single);
        providerList.OnRowActivated += (_, args) => OnRowActivated(args.Row);
        appList.OnRowActivated += (_, args) => OnRowActivated(args.Row);

        var sidebarBox = Gtk.Box.New(Gtk.Orientation.Vertical, 0);
        sidebarBox.Append(providerList);
        var separator = Gtk.Separator.New(Gtk.Orientation.Horizontal);
        separator.SetMarginStart(12);
        separator.SetMarginEnd(12);
        separator.SetMarginTop(6);
        separator.SetMarginBottom(6);
        sidebarBox.Append(separator);
        sidebarBox.Append(appList);
        var sidebarScroll = Gtk.ScrolledWindow.New();
        sidebarScroll.SetPolicy(Gtk.PolicyType.Never, Gtk.PolicyType.Automatic);
        sidebarScroll.SetChild(sidebarBox);
        sidebarScroll.SetVexpand(true);

        var sidebarView = Adw.ToolbarView.New();
        var sidebarHeader = Adw.HeaderBar.New();
        var menu = Gio.Menu.New();
        menu.Append("Refresh All", "app.refresh");
        menu.Append("About Codex Meter", "app.about");
        menu.Append("Quit", "app.quit");
        var menuButton = Gtk.MenuButton.New();
        menuButton.SetIconName("open-menu-symbolic");
        menuButton.SetMenuModel(menu);
        menuButton.SetTooltipText("Main Menu");
        menuButton.SetPrimary(true);
        sidebarHeader.PackEnd(menuButton);
        sidebarView.AddTopBar(sidebarHeader);
        sidebarView.SetContent(sidebarScroll);
        if (state.IsDemo)
        {
            var badge = Gtk.Label.New("Demo data");
            badge.AddCssClass("demo-badge");
            badge.SetHalign(Gtk.Align.Center);
            badge.SetMarginTop(8);
            badge.SetMarginBottom(10);
            badge.SetTooltipText("Sample usage for every provider. Nothing is saved and nothing is sent.");
            sidebarView.AddBottomBar(badge);
        }
        var sidebarPage = Adw.NavigationPage.New(sidebarView, "Codex Meter");
        sidebarPage.SetTag("sidebar");

        split = Adw.NavigationSplitView.New();
        split.SetSidebar(sidebarPage);
        split.SetMinSidebarWidth(220);
        split.SetMaxSidebarWidth(280);
        // Placeholder until the first page is shown.
        split.SetContent(Adw.NavigationPage.New(Adw.Bin.New(), "Codex Meter"));

        toasts = Adw.ToastOverlay.New();
        toasts.SetChild(split);
        Window.SetContent(toasts);

        var breakpoint = Adw.Breakpoint.New(Adw.BreakpointCondition.Parse("max-width: 640sp"));
        var collapsed = new GObject.Value(GObject.Type.Boolean);
        collapsed.SetBoolean(true);
        breakpoint.AddSetter(split, "collapsed", collapsed);
        Window.AddBreakpoint(breakpoint);

        BuildAppRows();
        BuildProviderRows();
        state.ProvidersChanged += OnProvidersChanged;
        state.ProviderChanged += provider => UpdateRowValue(provider);
        state.Settings.Changed += (_, _) => { foreach (var p in UsageProvider.All) UpdateRowValue(p); };

        Window.OnCloseRequest += (_, _) =>
        {
            var settings = state.Settings.Current;
            if (AllowClose || !(settings.CloseToTray && settings.ShowTrayIcon && TrayAvailable())) return false;
            Window.SetVisible(false);
            return true;
        };
    }

    public Adw.ApplicationWindow Window { get; }

    /// <summary>Set when quitting: the close button then really closes.</summary>
    public bool AllowClose { get; set; }

    /// <summary>Whether a tray host is showing our icon; without one, closing must not hide the only way back.</summary>
    public Func<bool> TrayAvailable { get; set; } = () => false;

    public string CurrentTag => current?.Tag ?? "";

    public IPage? CurrentPage => current;

    public bool IsCollapsed => split.GetCollapsed();

    public void ShowToast(string message)
    {
        var toast = Adw.Toast.New(message);
        toast.SetTimeout(4);
        toasts.AddToast(toast);
    }

    public void ShowAndActivate()
    {
        Window.SetVisible(true);
        Window.Present();
    }

    private void OnProvidersChanged()
    {
        // Settings.Changed fires for every setting; only rebuild when the sidebar's providers changed.
        var ids = string.Join(",", state.EnabledProviders.Select(p => p.Id) .Concat(state.SignedIn.Select(p => "+" + p.Id)));
        if (ids == shownProviders) return;
        BuildProviderRows();
        var tag = CurrentTag;
        if (tag is "accounts" or "settings" || state.EnabledProviders.Any(p => p.Id == tag)) Select(tag);
        else Navigate(state.EnabledProviders.FirstOrDefault()?.Id ?? "accounts");
    }

    private Gtk.ListBoxRow Row(string tag, Gtk.Widget icon, string title, bool withValue, out Gtk.Label? value)
    {
        var box = Gtk.Box.New(Gtk.Orientation.Horizontal, 12);
        box.SetMarginTop(2);
        box.SetMarginBottom(2);
        box.Append(icon);
        var label = Gtk.Label.New(title);
        label.SetXalign(0);
        label.SetHexpand(true);
        label.SetEllipsize(Pango.EllipsizeMode.End);
        box.Append(label);
        value = null;
        if (withValue)
        {
            value = Gtk.Label.New("");
            value.AddCssClass("dim-label");
            value.AddCssClass("caption");
            value.AddCssClass("sidebar-value");
            box.Append(value);
        }
        var row = Gtk.ListBoxRow.New();
        row.SetChild(box);
        row.SetName("Nav_" + tag);
        row.SetTooltipText(title);
        rows[tag] = (row, value);
        return row;
    }

    private void BuildAppRows()
    {
        var accounts = Gtk.Image.NewFromIconName("system-users-symbolic");
        appList.Append(Row("accounts", accounts, "Accounts", false, out _));
        var settings = Gtk.Image.NewFromIconName("emblem-system-symbolic");
        appList.Append(Row("settings", settings, "Settings", false, out _));
    }

    /// <summary>One row per enabled provider, in the user's order, with its mark and headline number.</summary>
    private void BuildProviderRows()
    {
        selecting = true;
        try
        {
            while (providerList.GetFirstChild() is { } child) providerList.Remove(child);
            foreach (var provider in UsageProvider.All) rows.Remove(provider.Id);
            shownProviders = string.Join(",", state.EnabledProviders.Select(p => p.Id).Concat(state.SignedIn.Select(p => "+" + p.Id)));
            foreach (var provider in state.EnabledProviders)
            {
                providerList.Append(Row(provider.Id, ProviderIcons.Create(provider.Id, 16), provider.DisplayName, true, out _));
                UpdateRowValue(provider);
            }
        }
        finally { selecting = false; }
    }

    /// <summary>The sidebar's trailing number: the 5-hour window (or the longest one) for each provider.</summary>
    private void UpdateRowValue(UsageProvider provider)
    {
        if (!rows.TryGetValue(provider.Id, out var row) || row.Value is null) return;
        var entry = state[provider];
        var window = entry.Usage?.FiveHour ?? entry.Usage?.LongWindow;
        var label = row.Value;
        foreach (var css in new[] { "level-normal", "level-caution", "level-critical" }) label.RemoveCssClass(css);
        if (!entry.SignedIn || window is null)
        {
            label.SetText(entry.SignedIn ? "" : "Off");
            return;
        }
        label.SetText(UsageFormat.Percent(window, state.Settings.Current.DisplayMode, compact: true));
        label.AddCssClass(Cards.LevelClass(Cards.Level(window.RemainingPercent)));
    }

    private void OnRowActivated(Gtk.ListBoxRow row)
    {
        if (selecting) return;
        var tag = rows.FirstOrDefault(pair => pair.Value.Row == row).Key;
        if (tag is null) return;
        Navigate(tag);
        split.SetShowContent(true);
    }

    private void Select(string tag)
    {
        selecting = true;
        try
        {
            providerList.UnselectAll();
            appList.UnselectAll();
            if (rows.TryGetValue(tag, out var row))
            {
                (row.Row.GetParent() as Gtk.ListBox)?.SelectRow(row.Row);
            }
        }
        finally { selecting = false; }
    }

    /// <summary>Opens a provider dashboard ("codex", "claude_code", "cursor"), "accounts" or "settings".</summary>
    public void Navigate(string tag)
    {
        if (tag is not ("accounts" or "settings") && state.EnabledProviders.All(p => p.Id != tag))
        {
            if (UsageProvider.Find(tag) is null) return;
            // A provider that isn't in the sidebar still opens (e.g. from --page), like the phone apps.
        }
        Select(tag);
        if (tag == CurrentTag && current is not null) return;
        current?.Detach();
        current = tag switch
        {
            "accounts" => new AccountsPage(state, this),
            "settings" => new SettingsPage(state, this),
            _ => new DashboardPage(state, this, UsageProvider.Resolve(tag)),
        };
        split.SetContent(current.Page);
        if (state.Settings.Current.SelectedProvider != tag)
            state.Settings.Update(s => s with { SelectedProvider = tag });
    }

    /// <summary>Shows the saved page (or the first enabled provider).</summary>
    public void ShowInitialPage(string? requested)
    {
        var target = requested ?? state.Settings.Current.SelectedProvider;
        if (target is "accounts" or "settings")
        {
            Navigate(target);
            return;
        }
        if (requested is not null && UsageProvider.Find(requested) is not null)
        {
            Navigate(requested);
            return;
        }
        Navigate(UsageProvider.VisibleSelection(UsageProvider.Resolve(target), state.EnabledProviders).Id);
    }

    /// <summary>For the compact screenshots: show the sidebar or the content pane when collapsed.</summary>
    public void ShowContent(bool content) => split.SetShowContent(content);
}
