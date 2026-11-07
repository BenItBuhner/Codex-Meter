[assembly: System.Runtime.Versioning.SupportedOSPlatform("linux")]

namespace CodexMeter.Linux;

public static class Program
{
    public static int Main(string[] args)
    {
        var options = LaunchOptions.Parse(args);
        if (options.Help)
        {
            Console.WriteLine(LaunchOptions.Usage);
            return 0;
        }
        Adw.Module.Initialize();
        return new App(options).Run();
    }
}

/// <summary>Command-line switches. <c>--demo</c> shows sample data; <c>--screenshots DIR</c> captures every page and exits.</summary>
public sealed record LaunchOptions(bool Demo, string? ScreenshotDirectory, bool StartHidden, string? Theme, string? Page,
    bool SignedOutDemo, bool Help = false)
{
    public const string Usage = """
        Usage: codex-meter [options]

          --demo                 Sample data for every provider, nothing saved, no network.
          --demo-signed-out      Demo mode with no accounts connected.
          --page PAGE            Open codex, claude_code, cursor, accounts or settings.
          --theme THEME          light, dark or system.
          --minimized            Start in the tray (used by "Start when you log in").
          --screenshots DIR      Capture every page in light and dark into DIR, then exit.
        """;

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
        var signedOut = Flag("--demo-signed-out");
        return new LaunchOptions(Flag("--demo") || signedOut || screenshots is not null, screenshots, Flag("--minimized"),
            Value("--theme"), Value("--page"), signedOut, Flag("--help") || Flag("-h"));
    }
}
