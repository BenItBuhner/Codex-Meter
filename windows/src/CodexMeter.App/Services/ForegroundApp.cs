using System.Diagnostics;
using System.Runtime.InteropServices;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;

namespace CodexMeter.App.Services;

/// <summary>
/// Notices when an AI app (ChatGPT, Codex, Claude, Cursor, ...) comes to the front, so the tray
/// icon can show that provider while you use it: the desktop version of Android's "While using AI
/// apps". It listens for Windows' foreground-change event, so nothing polls, and reads only the
/// process name of the window in front.
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
        var name = ProcessName(window);
        // Clicking the tray icon or the taskbar keeps whatever was showing.
        if (name is null || Shell.Contains(name)) return;
        var provider = LiveUsage.ProviderForApp(name);
        if (provider == Provider) return;
        Provider = provider;
        Changed?.Invoke();
    }

    private static UsageProvider? ProviderFor(IntPtr window) => LiveUsage.ProviderForApp(ProcessName(window));

    private static string? ProcessName(IntPtr window)
    {
        if (window == IntPtr.Zero) return null;
        GetWindowThreadProcessId(window, out var pid);
        if (pid == 0 || pid == Environment.ProcessId) return null;
        try
        {
            using var process = Process.GetProcessById((int)pid);
            return process.ProcessName;
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return null;
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
}
