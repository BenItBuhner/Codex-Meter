using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;
using CodexMeter.Linux.Views;
using CodexMeter.Linux.Widgets;

namespace CodexMeter.Linux;

/// <summary>
/// The GTK application. It is single-instance (a second launch presents the running window), keeps
/// running in the background when the tray is on, and refreshes usage once a minute.
/// </summary>
public sealed class App
{
    private readonly LaunchOptions options;
    private readonly Adw.Application application;
    private MainWindow? window;
    private TrayIcon? tray;
    private bool started;
    private bool held;

    public App(LaunchOptions options)
    {
        this.options = options;
        Current = this;
        application = Adw.Application.New(AppPaths.AppId,
            options.AllowMultipleInstances ? Gio.ApplicationFlags.NonUnique : Gio.ApplicationFlags.DefaultFlags);
        GLib.Functions.SetApplicationName("Codex Meter");
        application.OnStartup += (_, _) =>
        {
            Style.Install();
            if (Gdk.Display.GetDefault() is { } display)
                Gtk.IconTheme.GetForDisplay(display).AddSearchPath(Path.Combine(AppContext.BaseDirectory, "icons"));
            Gtk.Window.SetDefaultIconName(AppPaths.AppId);
        };
        application.OnActivate += (_, _) => OnActivate();
        AddAction("refresh", () => _ = State?.RefreshAllAsync(force: true));
        AddAction("about", ShowAbout);
        AddAction("quit", Quit);
        application.SetAccelsForAction("app.quit", ["<Control>q"]);
        application.SetAccelsForAction("app.refresh", ["<Control>r", "F5"]);
        AddAction("show", () => window?.ShowAndActivate());
    }

    public static App? Current { get; private set; }

    public AppState? State { get; private set; }

    public KeyLocation? KeyLocation { get; private set; }

    public int ExitCode { get; private set; }

    public Adw.Application Application => application;

    private void AddAction(string name, Action action)
    {
        var simple = Gio.SimpleAction.New(name, null);
        simple.OnActivate += (_, _) => action();
        application.AddAction(simple);
    }

    public int Run()
    {
        var code = application.RunWithSynchronizationContext([]);
        return ExitCode != 0 ? ExitCode : code;
    }

    private void OnActivate()
    {
        if (started)
        {
            // A second launch (app menu, autostart, desktop file) brings the window back.
            window?.ShowAndActivate();
            return;
        }
        started = true;
        // Keep the app alive while the asynchronous start-up runs (and later while hidden in the tray).
        application.Hold();
        held = true;
        _ = StartAsync();
    }

    private async Task StartAsync()
    {
        try
        {
            State = await CreateStateAsync();
            await State.InitializeAsync();
            ApplyThemeOverride();
            ApplyTheme();
            State.Settings.Changed += (_, _) => ApplyTheme();

            window = new MainWindow(application, State);
            var notifier = new Notifier(application, enabled: !State.IsDemo);
            State.Alerts += alerts => { foreach (var alert in alerts) notifier.Show(alert); };

            if (options.ScreenshotDirectory is null)
            {
                tray = new TrayIcon(State, () => window.ShowAndActivate(), () => _ = State.RefreshAllAsync(force: true), Quit);
                _ = tray.StartAsync();
                window.TrayAvailable = () => tray.IsShowing;
                GLib.Functions.TimeoutAddSeconds(GLib.Constants.PRIORITY_DEFAULT, 60, () =>
                {
                    _ = State.BackgroundTickAsync();
                    return true;
                });
            }

            window.ShowInitialPage(options.Page);
            var hidden = options.StartHidden && State.Settings.Current.ShowTrayIcon;
            if (!hidden) window.ShowAndActivate();
            else
            {
                // Give the tray a moment to register; with no tray host the window must still appear.
                GLib.Functions.TimeoutAddSeconds(GLib.Constants.PRIORITY_DEFAULT, 5, () =>
                {
                    if (tray is not { IsShowing: true }) window.ShowAndActivate();
                    return false;
                });
            }

            _ = State.RefreshAllAsync(force: false);

            if (options.ScreenshotDirectory is { } directory)
            {
                ExitCode = await new ScreenshotRunner(window, State, directory, options.SignedOutDemo ? "signed-out-" : "").RunAsync();
                Quit();
                return;
            }
            // From now on the window (or the tray) keeps the app running; release the start-up hold
            // unless the tray should keep us alive while the window is closed.
            UpdateHold();
            State.Settings.Changed += (_, _) => UpdateHold();
        }
        catch (Exception error)
        {
            AppLog.Write("Launch failed: " + error);
            ExitCode = 2;
            Quit();
        }
    }

    /// <summary>Holds the application open while "keep running" is on, so hiding the window doesn't quit.</summary>
    private void UpdateHold()
    {
        var settings = State!.Settings.Current;
        var keep = settings.CloseToTray && settings.ShowTrayIcon;
        if (keep && !held) { application.Hold(); held = true; }
        else if (!keep && held) { application.Release(); held = false; }
    }

    private async Task<AppState> CreateStateAsync()
    {
        if (options.Demo)
        {
            var context = new ProviderContext(CreateHttpClient(), new MemoryTokenStore(), new ProviderCacheStore(null));
            var signedOut = options.SignedOutDemo ? UsageProvider.All.Select(p => p.Id) : null;
            var hub = await ProviderHub.DemoAsync(context, signedOut);
            var settings = new SettingsStore(null);
            settings.Update(s => s with { CloseToTray = false });
            return new AppState(hub, settings);
        }

        AppPaths.EnsurePrivate(AppPaths.DataDirectory);
        AppPaths.EnsurePrivate(AppPaths.ConfigDirectory);
        AppPaths.EnsurePrivate(AppPaths.CacheDirectory);
        var protector = await Task.Run(() => AesKeyProtector.Open(AppPaths.DataDirectory));
        KeyLocation = protector.Location;
        var live = new ProviderContext(CreateHttpClient(), new FileTokenStore(AppPaths.DataDirectory, protector),
            new ProviderCacheStore(AppPaths.CacheDirectory)) { Log = (category, message) => AppLog.Write($"[{category}] {message}") };
        return new AppState(ProviderHub.Live(live), new SettingsStore(Path.Combine(AppPaths.ConfigDirectory, "settings.json")));
    }

    private static HttpClient CreateHttpClient()
    {
        var client = ProviderHttp.CreateClient();
        client.DefaultRequestHeaders.UserAgent.Clear();
        var version = typeof(App).Assembly.GetName().Version;
        client.DefaultRequestHeaders.UserAgent.ParseAdd($"codex-meter-linux/{version?.ToString(3) ?? "0"}");
        return client;
    }

    private void ApplyThemeOverride()
    {
        var theme = options.Theme?.ToLowerInvariant() switch
        {
            "light" => AppTheme.Light,
            "dark" => AppTheme.Dark,
            "system" => AppTheme.System,
            _ => (AppTheme?)null,
        };
        if (theme is { } value) State!.Settings.Update(s => s with { Theme = value });
    }

    private void ApplyTheme()
    {
        var scheme = State!.Settings.Current.Theme switch
        {
            AppTheme.Light => Adw.ColorScheme.ForceLight,
            AppTheme.Dark => Adw.ColorScheme.ForceDark,
            _ => Adw.ColorScheme.Default,
        };
        var manager = Adw.StyleManager.GetDefault();
        if (manager.GetColorScheme() != scheme) manager.SetColorScheme(scheme);
    }

    private void ShowAbout()
    {
        var about = Adw.AboutDialog.New();
        about.SetApplicationName("Codex Meter");
        about.SetApplicationIcon(AppPaths.AppId);
        about.SetVersion(SettingsPage.VersionText().Replace("Version ", ""));
        about.SetDeveloperName("Codex Meter contributors");
        about.SetComments("See your Codex, Claude Code and Cursor usage limits at a glance. Unofficial; not affiliated with OpenAI, Anthropic or Anysphere.");
        about.SetWebsite("https://github.com/BenItBuhner/Codex-Meter");
        about.SetIssueUrl("https://github.com/BenItBuhner/Codex-Meter/issues");
        about.SetLicenseType(Gtk.License.Custom);
        about.SetLicense("See the LICENSE file in the source repository.");
        if (window is not null) about.Present(window.Window);
    }

    public void Quit()
    {
        tray?.Dispose();
        tray = null;
        if (window is not null)
        {
            window.AllowClose = true;
            window.Window.Close();
        }
        if (held)
        {
            application.Release();
            held = false;
        }
        application.Quit();
    }
}
