using CodexMeter.App.Assets;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Views;

/// <summary>Settings as libadwaita preference groups; the same options as the Windows app.</summary>
public sealed class SettingsPage : IPage
{
    private static readonly int[] Thresholds = [10, 15, 25];
    private readonly AppState state;
    private readonly Adw.PreferencesPage preferences;
    private readonly Adw.PreferencesGroup providersGroup;
    private readonly List<Gtk.Widget> providerRows = [];
    private readonly Action onProvidersChanged;
    private readonly Adw.SwitchRow closeToTray;
    private bool loading;

    public SettingsPage(AppState state, MainWindow window)
    {
        this.state = state;
        preferences = Adw.PreferencesPage.New();
        preferences.SetName("SettingsPage");
        var view = Adw.ToolbarView.New();
        view.AddTopBar(Adw.HeaderBar.New());
        view.SetContent(preferences);
        Page = Adw.NavigationPage.New(view, "Settings");
        Page.SetTag("settings");
        loading = true;
        var s = state.Settings.Current;

        // Appearance
        var appearance = Group("Appearance");
        appearance.Add(Combo("Style", "Follow the system, or always use light or dark.",
            ["Follow System", "Light", "Dark"], (uint)s.Theme, i => state.Settings.Update(x => x with { Theme = (AppTheme)i }), "ThemeRow"));

        // Providers
        providersGroup = Group("Providers in the sidebar", "Choose which services get a page and the order they appear in.");
        BuildProviders();

        // Dashboard
        var dashboard = Group("Dashboard");
        dashboard.Add(Combo("Show usage as", "The number in each ring.", ["Remaining", "Used"],
            s.DisplayMode == UsageDisplayMode.Remaining ? 0u : 1u,
            i => state.Settings.Update(x => x with { DisplayMode = i == 1 ? UsageDisplayMode.Used : UsageDisplayMode.Remaining }), "DisplayRow"));
        dashboard.Add(Combo("Reset times", "How each card says when its window resets.", ["Countdown", "Date and time", "Both"],
            (uint)s.ResetDisplay, i => state.Settings.Update(x => x with { ResetDisplay = (ResetDisplayMode)i })));
        dashboard.Add(Switch("24-hour clock", null, s.Use24HourClock, on => state.Settings.Update(x => x with { Use24HourClock = on })));
        dashboard.Add(Switch("Pace estimates", "Warn when a window would run out before it resets at your current pace.", s.ShowPace,
            on => state.Settings.Update(x => x with { ShowPace = on })));

        // Updates and alerts
        var updates = Group("Updates and alerts");
        updates.Add(Combo("Check usage every",
            "While Codex Meter runs. Claude Code is checked at most every 30 minutes because Anthropic limits polling.",
            ["5 minutes", "15 minutes", "30 minutes", "1 hour"],
            (uint)Math.Max(0, Array.IndexOf(AppSettings.RefreshChoices, s.RefreshMinutes)),
            i => state.Settings.Update(x => x with { RefreshMinutes = AppSettings.RefreshChoices[i] })));
        updates.Add(Switch("Notify when a limit resets", null, s.NotifyResets, on => state.Settings.Update(x => x with { NotifyResets = on })));
        updates.Add(Switch("Notify when running low", "When a 5-hour, weekly or monthly window drops to the threshold or runs out.",
            s.NotifyLow, on => state.Settings.Update(x => x with { NotifyLow = on })));
        var thresholdIndex = Array.IndexOf(Thresholds, s.LowThreshold);
        updates.Add(Combo("Running-low threshold", null, ["10% left", "15% left", "25% left"],
            (uint)(thresholdIndex >= 0 ? thresholdIndex : 1), i => state.Settings.Update(x => x with { LowThreshold = Thresholds[i] })));

        // Desktop
        var desktop = Group("Desktop");
        closeToTray = Switch("Keep running when the window is closed", "Closing hides the window; quit from the tray icon or the main menu.",
            s.CloseToTray, on => state.Settings.Update(x => x with { CloseToTray = on }));
        desktop.Add(Switch("Show in the system tray",
            "An icon with your remaining allowance. Needs a tray: KDE Plasma, Cinnamon, Xfce, Budgie, or GNOME with the AppIndicator extension.",
            s.ShowTrayIcon, on =>
            {
                state.Settings.Update(x => x with { ShowTrayIcon = on });
                closeToTray.SetSensitive(on);
            }, "TraySwitch"));
        closeToTray.SetSensitive(s.ShowTrayIcon);
        desktop.Add(closeToTray);
        desktop.Add(Switch("Start when you log in", "Opens in the tray when you sign in to your desktop.",
            state.IsDemo ? s.StartWithWindows : Autostart.IsEnabled, on =>
            {
                state.Settings.Update(x => x with { StartWithWindows = on });
                if (state.IsDemo) return;
                try { Autostart.Set(on); }
                catch (Exception error) { AppLog.Write("Autostart change failed: " + error.Message); window.ShowToast("Couldn't change the login item: " + error.Message); }
            }, "StartupSwitch"));

        // About
        var about = Group("About");
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
        var data = Adw.ActionRow.New();
        data.SetTitle("Your data");
        data.SetSubtitle(SignInDialog.StorageDescription(App.Current?.KeyLocation) + " Cached usage and settings live in your home folder.");
        var open = Gtk.Button.NewWithLabel("Open Folder");
        open.SetValign(Gtk.Align.Center);
        open.OnClicked += (_, _) =>
        {
            Directory.CreateDirectory(AppPaths.DataDirectory);
            _ = Browser.OpenAsync(new Uri(AppPaths.ConfigDirectory.EndsWith('/') ? AppPaths.ConfigDirectory : AppPaths.ConfigDirectory + "/"));
        };
        data.AddSuffix(open);
        about.Add(data);
        var source = Adw.ActionRow.New();
        source.SetTitle("Source code and releases");
        source.SetSubtitle("github.com/BenItBuhner/Codex-Meter");
        source.SetActivatable(true);
        source.AddSuffix(Gtk.Image.NewFromIconName("adw-external-link-symbolic"));
        source.OnActivated += (_, _) => _ = Browser.OpenAsync(new Uri("https://github.com/BenItBuhner/Codex-Meter"));
        about.Add(source);

        scroller = AccountsPage.FindScroller(preferences) ?? Gtk.ScrolledWindow.New();
        onProvidersChanged = BuildProviders;
        state.ProvidersChanged += onProvidersChanged;
        loading = false;
    }

    private readonly Gtk.ScrolledWindow scroller;

    public string Tag => "settings";
    public Adw.NavigationPage Page { get; }
    public Gtk.ScrolledWindow Scroller => scroller;

    public void Detach() => state.ProvidersChanged -= onProvidersChanged;

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

    private Adw.PreferencesGroup Group(string title, string? description = null)
    {
        var group = Adw.PreferencesGroup.New();
        group.SetTitle(title);
        if (description is not null) group.SetDescription(description);
        preferences.Add(group);
        return group;
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

    private void BuildProviders()
    {
        foreach (var row in providerRows) providersGroup.Remove(row);
        providerRows.Clear();
        var enabled = state.EnabledProviders;
        var order = UsageProvider.OrderFromIds(state.Settings.Current.ProviderOrder).Where(p => p.IsBuiltIn).ToList();
        for (var index = 0; index < order.Count; index++)
        {
            var provider = order[index];
            var entry = state[provider];
            var row = Adw.ActionRow.New();
            row.SetTitle(provider.DisplayName);
            row.SetSubtitle(entry.SignedIn ? $"Connected · {provider.Meters}" : $"Not connected · {provider.Meters}");
            row.SetName("ProviderRow_" + provider.Id);
            var mark = ProviderIcons.Create(provider.Id, 20);
            mark.SetMarginEnd(4);
            row.AddPrefix(mark);

            var up = Gtk.Button.NewFromIconName("go-up-symbolic");
            up.AddCssClass("flat");
            up.SetValign(Gtk.Align.Center);
            up.SetSensitive(index > 0);
            up.SetTooltipText($"Move {provider.DisplayName} up");
            up.OnClicked += (_, _) => state.Move(provider, -1);
            var down = Gtk.Button.NewFromIconName("go-down-symbolic");
            down.AddCssClass("flat");
            down.SetValign(Gtk.Align.Center);
            down.SetSensitive(index < order.Count - 1);
            down.SetTooltipText($"Move {provider.DisplayName} down");
            down.OnClicked += (_, _) => state.Move(provider, 1);
            var toggle = Gtk.Switch.New();
            toggle.SetValign(Gtk.Align.Center);
            toggle.SetActive(enabled.Contains(provider));
            toggle.SetSensitive(!(toggle.GetActive() && enabled.Count == 1));
            toggle.SetName("ProviderToggle_" + provider.Id);
            toggle.SetTooltipText("Show " + provider.DisplayName);
            toggle.OnStateSet += (_, args) =>
            {
                // Rebuilding the sidebar happens through ProvidersChanged; defer so the switch finishes its own update.
                var on = args.State;
                GLib.Functions.IdleAdd(GLib.Constants.PRIORITY_DEFAULT_IDLE, () => { state.SetEnabled(provider, on); return false; });
                return false;
            };
            row.AddSuffix(up);
            row.AddSuffix(down);
            row.AddSuffix(toggle);
            providersGroup.Add(row);
            providerRows.Add(row);
        }
    }
}
