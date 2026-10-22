using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace CodexMeter.App;

/// <summary>
/// Custom entry point so a second launch (Start menu, startup entry) brings the running window
/// forward instead of opening another copy with its own tray icon.
/// </summary>
public static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        WinRT.ComWrappersSupport.InitializeComWrappers();
        var options = LaunchOptions.Parse(args);
        if (!options.AllowMultipleInstances)
        {
            var main = AppInstance.FindOrRegisterForKey("CodexMeter.Main");
            if (!main.IsCurrent)
            {
                var activation = AppInstance.GetCurrent().GetActivatedEventArgs();
                Task.Run(() => main.RedirectActivationToAsync(activation).AsTask()).Wait();
                return 0;
            }
            main.Activated += (_, _) => App.Current?.ShowMainWindowFromRedirect();
        }

        Application.Start(_ignored =>
        {
            var context = new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread());
            SynchronizationContext.SetSynchronizationContext(context);
            _ = new App(options);
        });
        return App.Current?.ExitCode ?? 0;
    }
}

/// <summary>Command-line switches. <c>--demo</c> shows sample data; <c>--screenshots DIR</c> captures every page and exits.</summary>
public sealed record LaunchOptions(bool Demo, string? ScreenshotDirectory, bool StartHidden, string? Theme, string? Page,
    bool SignedOutDemo)
{
    public bool AllowMultipleInstances => Demo || ScreenshotDirectory is not null;

    public static LaunchOptions Parse(string[] args)
    {
        string? Value(string name)
        {
            var index = Array.FindIndex(args, a => string.Equals(a, name, StringComparison.OrdinalIgnoreCase));
            return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
        }
        bool Flag(string name) => args.Any(a => string.Equals(a, name, StringComparison.OrdinalIgnoreCase));
        var screenshots = Value("--screenshots");
        return new LaunchOptions(Flag("--demo") || screenshots is not null, screenshots, Flag("--minimized"),
            Value("--theme"), Value("--page"), Flag("--demo-signed-out"));
    }
}
