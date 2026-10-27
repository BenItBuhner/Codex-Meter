using System.Diagnostics;
using CodexMeter.App.Services;
using CodexMeter.Core.Models;
using CodexMeter.Core.Services;
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

            window = new MainWindow(State);
            notifier = new Notifier();
            if (!State.IsDemo) notifier.Register();
            State.Alerts += alerts => { foreach (var alert in alerts) notifier.Show(alert); };

            if (options.ScreenshotDirectory is null)
            {
                tray = new TrayIcon(State, ShowMainWindow, () => _ = State.RefreshAllAsync(force: true), Quit);
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
            return new AppState(hub, settings, history);
        }

        var data = AppPaths.DataDirectory;
        Directory.CreateDirectory(data);
        var live = new ProviderContext(ProviderHttp.CreateClient(), new FileTokenStore(data, new DpapiProtector()),
            new ProviderCacheStore(data)) { Log = (category, message) => Debug.WriteLine($"[{category}] {message}") };
        return new AppState(ProviderHub.Live(live), new SettingsStore(Path.Combine(data, "settings.json")), new UsageHistoryStore(data));
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

    internal static void Log(string message)
    {
        Debug.WriteLine(message);
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
