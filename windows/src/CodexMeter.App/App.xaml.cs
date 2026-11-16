using System.Diagnostics;
using CodexMeter.App.Services;
using CodexMeter.Core.Models;
using CodexMeter.Core.Services;
using CodexMeter.Core.Updates;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace CodexMeter.App;

public partial class App : Application
{
    private readonly LaunchOptions options;
    private MainWindow? window;
    private TrayIcon? tray;
    private Notifier? notifier;
    private DispatcherQueueTimer? timer;

    public App(LaunchOptions options)
    {
        this.options = options;
        InitializeComponent();
        UnhandledException += (_, e) =>
        {
            Log("Unhandled: " + e.Exception);
            if (options.ScreenshotDirectory is not null)
            {
                e.Handled = true;
                ExitCode = 3;
                Exit();
            }
        };
    }

    public static new App? Current => Application.Current as App;

    public AppState State { get; private set; } = null!;

    /// <summary>Kept static so <c>Main</c> can read it after the application object is gone.</summary>
    public static int ExitCode { get; private set; }

    public MainWindow? Window => window;

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            State = await CreateStateAsync();
            await State.InitializeAsync();
            ApplyThemeOverride();

            CreateUpdater();
            window = new MainWindow(State);
            notifier = new Notifier();
            notifier.Invoked += action => window.DispatcherQueue.TryEnqueue(() => OnNotificationInvoked(action));
            if (!State.IsDemo || options.UpdateFeed is not null) notifier.Register();
            State.Alerts += alerts => { foreach (var alert in alerts) notifier.Show(alert); };
            if (State.Updater is { } updater)
            {
                updater.UpdateFound += release => notifier.Show(new UsageAlert(UsageAlertKind.Update,
                    $"Codex Meter {release.Version.Normalized} is available",
                    "Install it from Settings → Updates. The download is checked against the release's SHA-256 checksums.",
                    "update:" + release.Version.Normalized), "update");
                updater.QuitRequested += Quit;
            }

            if (options.ScreenshotDirectory is null)
            {
                tray = new TrayIcon(State, OpenFromTray, () => _ = State.RefreshAllAsync(force: true), Quit);
                timer = DispatcherQueue.GetForCurrentThread().CreateTimer();
                timer.Interval = TimeSpan.FromMinutes(1);
                timer.Tick += async (_, _) => await State.BackgroundTickAsync();
                timer.Start();
            }

            var hidden = options.StartHidden && State.Settings.Current.ShowTrayIcon;
            if (!hidden) window.Activate();
            if (options.Page is { } page) window.Navigate(page);

            _ = State.RefreshAllAsync(force: false);

            if (options.ScreenshotDirectory is { } directory)
            {
                ExitCode = await new ScreenshotRunner(window, State, directory).RunAsync();
                window.AllowClose = true;
                Exit();
            }
        }
        catch (Exception error)
        {
            Log("Launch failed: " + error);
            ExitCode = 2;
            Exit();
        }
    }

    /// <summary>
    /// The in-app updater. The version is the one the release build was stamped with
    /// (v2.9.0-alpha.27 -> 2.9.0-alpha.27). <c>--update-feed</c> points it at a loopback test feed
    /// (CI uses it to show the whole flow); demo mode otherwise never checks on its own.
    /// </summary>
    private void CreateUpdater()
    {
        try
        {
            var client = new UpdateClient($"codex-meter-windows-updater/{AppVersion.Current}", testFeed: options.UpdateFeed);
            State.Updater = new DesktopUpdater(client, new WindowsUpdateInstaller(), options.InstalledVersionOverride ?? AppVersion.Current,
                () => State.Settings.Current.Updates, change => State.Settings.Update(s => s with { Updates = change(s.Updates) }));
            if (State.IsDemo && options.UpdateFeed is null)
                State.Settings.Update(s => s with { Updates = s.Updates with { AutoCheck = false } });
        }
        catch (Exception error)
        {
            Log("Updater unavailable: " + error.Message);
        }
    }

    private void OnNotificationInvoked(string? action)
    {
        ShowMainWindow();
        if (action != "update") return;
        window?.Navigate("settings");
        if (State.Updater is { Status: UpdateStatus.Available } updater) _ = updater.DownloadAndInstallAsync();
    }

    /// <summary>Services connected in demo mode; the rest show as available to add.</summary>
    private static readonly string[] DemoConnected = ["codex", "claude_code", "cursor", "copilot", "kimi", "zai"];

    private async Task<AppState> CreateStateAsync()
    {
        if (options.Demo)
        {
            var context = new ProviderContext(ProviderHttp.CreateClient(), new MemoryTokenStore(), new ProviderCacheStore(null));
            var signedOut = Core.Providers.UsageProvider.All.Select(p => p.Id)
                .Where(id => options.SignedOutDemo || !DemoConnected.Contains(id));
            var hub = await ProviderHub.DemoAsync(context, signedOut);
            var settings = new SettingsStore(null);
            var connected = options.SignedOutDemo ? new List<string>() : DemoConnected.Where(id => hub.Providers.Any(p => p.Id == id)).ToList();
            settings.Update(s => s with
            {
                CloseToTray = false,
                OnboardingDone = !options.SignedOutDemo,
                EnabledProviders = connected.Count > 0 ? connected : s.EnabledProviders,
                ProviderOrder = connected.Concat(s.ProviderOrder).Distinct().ToList(),
            });
            // A few weeks of sample history so the chart and its highlights have something to show.
            var history = new UsageHistoryStore(null);
            var now = DateTimeOffset.UtcNow;
            foreach (var provider in hub.Providers.Where(p => connected.Contains(p.Id)))
                await history.SetAsync(provider.Id, DemoData.History(provider, now));
            // Demo mode saves nothing: the diagnostic log switch works but keeps no file.
            return new AppState(hub, settings, history) { Diagnostics = new DiagnosticLog(null) };
        }

        var data = AppPaths.DataDirectory;
        Directory.CreateDirectory(data);
        var diagnostics = new DiagnosticLog(data);
        Diagnostics = diagnostics;
        var live = new ProviderContext(ProviderHttp.CreateClient(), new FileTokenStore(data, new DpapiProtector()),
            new ProviderCacheStore(data))
        {
            Log = (category, message) =>
            {
                Debug.WriteLine($"[{category}] {message}");
                diagnostics.Write(category, message);
            },
        };
        // Status feeds and OpenRouter speed figures are public: their own client, no account headers.
        var publicHttp = ProviderHttp.CreateClient();
        return new AppState(ProviderHub.Live(live), new SettingsStore(Path.Combine(data, "settings.json")), new UsageHistoryStore(data))
        {
            Models = new ModelDiscoveryStore(data, ProviderHttp.CreateClient()),
            Status = new ProviderStatusStore(data, publicHttp),
            Speed = new ModelSpeedStore(data, publicHttp),
            Diagnostics = diagnostics,
        };
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
        if (theme is { } value) State.Settings.Update(s => s with { Theme = value });
    }

    /// <summary>A second launch (Start menu, taskbar) redirected here: bring the window back.</summary>
    public void ShowMainWindowFromRedirect() => window?.DispatcherQueue.TryEnqueue(ShowMainWindow);

    public void ShowMainWindow() => window?.ShowAndActivate();

    /// <summary>The tray menu and flyout: bring the window back, on a service's page or Settings when given.</summary>
    private void OpenFromTray(string? page)
    {
        ShowMainWindow();
        if (page is not null) window?.Navigate(page);
    }

    public void Quit()
    {
        timer?.Stop();
        tray?.Dispose();
        tray = null;
        notifier?.Unregister();
        if (window is not null)
        {
            window.AllowClose = true;
            window.Close();
        }
        Exit();
    }

    /// <summary>The opt-in diagnostic log; app messages go there too while it is on.</summary>
    private static DiagnosticLog? Diagnostics;

    internal static void Log(string message)
    {
        Debug.WriteLine(message);
        Diagnostics?.Write("app", message);
        try
        {
            var path = Path.Combine(AppPaths.DataDirectory, "codexmeter.log");
            Directory.CreateDirectory(AppPaths.DataDirectory);
            if (File.Exists(path) && new FileInfo(path).Length > 512 * 1024) File.Delete(path);
            File.AppendAllText(path, $"{DateTimeOffset.Now:u} {message}{Environment.NewLine}");
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
