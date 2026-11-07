using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Runtime.InteropServices;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;

namespace CodexMeter.App.Services;

/// <summary>
/// Notices when an AI app (ChatGPT, Codex, Claude, Cursor, ...) comes to the front, so the tray
/// icon can show that provider while you use it: the desktop version of Android's "While using AI
/// apps". It listens for Windows' foreground-change event, so nothing polls, and reads only the
/// process name of the window in front and its executable's ProductName and CompanyName. Those
/// catch apps whose process name isn't the provider's, like SpaceXSI's Grok ("Grok Bot",
/// published by SpaceXAI), Cursor (Anysphere) and Claude (Anthropic).
/// </summary>
public sealed partial class ForegroundApp : IDisposable
{
    private const uint EventSystemForeground = 0x0003;
    private const uint WinEventOutOfContext = 0x0000;
    private const uint WinEventSkipOwnProcess = 0x0002;

    /// <summary>The shell's own surfaces: the taskbar, its flyouts and the tray overflow. Switching to them is not leaving the app.</summary>
    private static readonly HashSet<string> Shell = new(StringComparer.OrdinalIgnoreCase)
    {
        "explorer", "ShellExperienceHost", "StartMenuExperienceHost", "SearchHost", "SearchApp", "TextInputHost",
        "LockApp", "Widgets", "ShellHost",
    };

    private const uint ProcessQueryLimitedInformation = 0x1000;

    /// <summary>ProductName and CompanyName by executable path, read once per app.</summary>
    private static readonly ConcurrentDictionary<string, (string? Product, string? Company)> VersionInfo =
        new(StringComparer.OrdinalIgnoreCase);

    // Kept in a field: the hook calls it from native code for as long as it's installed.
    private readonly WinEventProc callback;
    private IntPtr hook;

    public ForegroundApp()
    {
        callback = OnForegroundChanged;
        // Out of context and on this (UI) thread's message loop, so the callback runs on the UI thread.
        hook = SetWinEventHook(EventSystemForeground, EventSystemForeground, IntPtr.Zero, callback, 0, 0,
            WinEventOutOfContext | WinEventSkipOwnProcess);
        Provider = ProviderFor(GetForegroundWindow());
    }

    /// <summary>The provider of the AI app in front, or null when another app is.</summary>
    public UsageProvider? Provider { get; private set; }

    public event Action? Changed;

    private void OnForegroundChanged(IntPtr hook, uint type, IntPtr window, int objectId, int childId, uint thread, uint time)
    {
        var app = AppInfo(window);
        // Clicking the tray icon or the taskbar keeps whatever was showing.
        if (app is null || Shell.Contains(app.Value.Name)) return;
        var provider = ProviderFor(app.Value);
        if (provider == Provider) return;
        Provider = provider;
        Changed?.Invoke();
    }

    private static UsageProvider? ProviderFor(IntPtr window) => AppInfo(window) is { } app ? ProviderFor(app) : null;

    private static UsageProvider? ProviderFor((string Name, string? Path) app)
    {
        // The process name alone settles most apps, without opening the executable.
        if (LiveUsage.ProviderForApp(app.Name) is { } byName) return byName;
        if (app.Path is null) return null;
        var (product, company) = VersionInfo.GetOrAdd(app.Path, ReadVersionInfo);
        return LiveUsage.ProviderForApp(null, product, company);
    }

    private static (string? Product, string? Company) ReadVersionInfo(string path)
    {
        try
        {
            var info = FileVersionInfo.GetVersionInfo(path);
            return (info.ProductName, info.CompanyName);
        }
        catch (Exception error) when (error is FileNotFoundException or IOException or UnauthorizedAccessException or ArgumentException)
        {
            return (null, null);
        }
    }

    /// <summary>The process name and executable path of the window's app, or null for none or this app.</summary>
    private static (string Name, string? Path)? AppInfo(IntPtr window)
    {
        if (window == IntPtr.Zero) return null;
        GetWindowThreadProcessId(window, out var pid);
        if (pid == 0 || pid == Environment.ProcessId) return null;
        string name;
        try
        {
            using var process = Process.GetProcessById((int)pid);
            name = process.ProcessName;
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return null;
        }
        return (name, ImagePath(pid));
    }

    /// <summary>
    /// The executable's full path. Limited query rights work for most apps, including ones from
    /// the Store and other users' elevated processes, where Process.MainModule would throw.
    /// </summary>
    private static string? ImagePath(uint pid)
    {
        var handle = OpenProcess(ProcessQueryLimitedInformation, false, pid);
        if (handle == IntPtr.Zero) return null;
        try
        {
            var buffer = new StringBuilder(1024);
            var size = (uint)buffer.Capacity;
            return QueryFullProcessImageName(handle, 0, buffer, ref size) ? buffer.ToString(0, (int)size) : null;
        }
        finally
        {
            CloseHandle(handle);
        }
    }

    public void Dispose()
    {
        if (hook != IntPtr.Zero) UnhookWinEvent(hook);
        hook = IntPtr.Zero;
    }

    private delegate void WinEventProc(IntPtr hook, uint type, IntPtr window, int objectId, int childId, uint thread, uint time);

    [DllImport("user32.dll")]
    private static extern IntPtr SetWinEventHook(uint eventMin, uint eventMax, IntPtr module, WinEventProc callback,
        uint processId, uint threadId, uint flags);

    [DllImport("user32.dll")]
    private static extern bool UnhookWinEvent(IntPtr hook);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inheritHandle, uint processId);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "QueryFullProcessImageNameW")]
    private static extern bool QueryFullProcessImageName(IntPtr process, uint flags, StringBuilder name, ref uint size);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
}
