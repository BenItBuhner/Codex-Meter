using System.Runtime.InteropServices;
using System.Text;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;

namespace CodexMeter.Linux.Services;

/// <summary>What Linux knows about the window in front: its process and its X11 window class.</summary>
public sealed record FrontWindow(int ProcessId, string? Command, string? Executable, string? WindowClass, string? WindowInstance)
{
    /// <summary>The names an app goes by, most specific first: the process, the window class, the executable.</summary>
    public IEnumerable<string?> Names => [Command, WindowClass, WindowInstance, Executable];

    /// <summary>The service of this app by the user's "While using AI apps" list, or null when it's not on it or switched off.</summary>
    public UsageProvider? ProviderFor(AppMatcher matcher)
    {
        foreach (var name in Names)
        {
            if (matcher.TryMatch(name, out var provider)) return provider;
        }
        return null;
    }
}

/// <summary>
/// Notices which app is in front, so the tray icon can show that service while you use it: the
/// Linux version of Android's "While using AI apps". It reads the window manager's
/// _NET_ACTIVE_WINDOW over X11 (through xcb, which reports errors per request rather than through a
/// process-wide handler) every two seconds, then that window's process name and window class. Only
/// names are read. On Wayland this sees apps that run through XWayland, which includes Electron apps
/// like Cursor and Claude by default; elsewhere it stays quiet.
/// </summary>
public sealed class ForegroundApp : IDisposable
{
    /// <summary>Panels, docks and the desktop: switching to them is not leaving the app.</summary>
    private static readonly HashSet<string> Shell = new(StringComparer.OrdinalIgnoreCase)
    {
        "plasmashell", "gnome-shell", "xfce4-panel", "xfdesktop", "budgie-panel", "cinnamon", "mate-panel",
        "lxpanel", "lxqt-panel", "polybar", "tint2", "plank", "nemo-desktop", "caja",
    };

    private readonly IntPtr connection;
    private readonly uint root;
    private readonly uint activeWindow, wmPid, wmClass;
    private uint timer;

    private ForegroundApp(IntPtr connection, uint root, uint activeWindow, uint wmPid, uint wmClass)
    {
        this.connection = connection;
        this.root = root;
        this.activeWindow = activeWindow;
        this.wmPid = wmPid;
        this.wmClass = wmClass;
        Current = Read();
        timer = GLib.Functions.TimeoutAddSeconds(GLib.Constants.PRIORITY_DEFAULT_IDLE, 2, () =>
        {
            Poll();
            return true;
        });
    }

    /// <summary>The app in front, or null when there's none or it can't be told.</summary>
    public FrontWindow? Current { get; private set; }

    /// <summary>Raised on the UI thread when another app comes to the front.</summary>
    public event Action? Changed;

    /// <summary>Starts watching, or returns null when there's no X11 display to ask.</summary>
    public static ForegroundApp? TryStart()
    {
        if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("DISPLAY"))) return null;
        IntPtr connection;
        try
        {
            connection = Xcb.xcb_connect(null, out _);
        }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException)
        {
            return null;
        }
        if (connection == IntPtr.Zero || Xcb.xcb_connection_has_error(connection) != 0)
        {
            if (connection != IntPtr.Zero) Xcb.xcb_disconnect(connection);
            return null;
        }
        var root = Xcb.FirstRoot(connection);
        var active = Xcb.Atom(connection, "_NET_ACTIVE_WINDOW");
        if (root == 0 || active == 0)
        {
            Xcb.xcb_disconnect(connection);
            return null;
        }
        return new ForegroundApp(connection, root, active, Xcb.Atom(connection, "_NET_WM_PID"), Xcb.Atom(connection, "WM_CLASS"));
    }

    private void Poll()
    {
        if (Read() is not { } app) return;
        if (Shell.Contains(app.Command ?? "") || app.ProcessId == Environment.ProcessId) return;
        if (app == Current) return;
        Current = app;
        Changed?.Invoke();
    }

    private FrontWindow? Read()
    {
        if (Xcb.xcb_connection_has_error(connection) != 0) return null;
        if (Xcb.Property(connection, root, activeWindow) is not { Length: >= 4 } active) return null;
        var window = BitConverter.ToUInt32(active, 0);
        if (window == 0) return null;
        var pid = Xcb.Property(connection, window, wmPid) is { Length: >= 4 } pidBytes
            ? (int)BitConverter.ToUInt32(pidBytes, 0) : 0;
        string? instance = null, windowClass = null;
        if (Xcb.Property(connection, window, wmClass) is { Length: > 0 } classBytes)
        {
            // Two NUL-terminated strings: the instance ("cursor") and the class ("Cursor").
            var parts = Encoding.UTF8.GetString(classBytes).Split('\0', StringSplitOptions.RemoveEmptyEntries);
            instance = parts.ElementAtOrDefault(0);
            windowClass = parts.ElementAtOrDefault(1);
        }
        return new FrontWindow(pid, pid > 0 ? ReadComm(pid) : null, pid > 0 ? ReadExecutable(pid) : null, windowClass, instance);
    }

    private static string? ReadComm(int pid)
    {
        try
        {
            return File.ReadAllText($"/proc/{pid}/comm").Trim();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static string? ReadExecutable(int pid)
    {
        try
        {
            var target = new FileInfo($"/proc/{pid}/exe").LinkTarget;
            return target is null ? null : Path.GetFileName(target);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    public void Dispose()
    {
        if (timer != 0) GLib.Functions.SourceRemove(timer);
        timer = 0;
        Xcb.xcb_disconnect(connection);
    }

    private static class Xcb
    {
        /// <summary>AnyPropertyType: some window managers store these with unusual types.</summary>
        private const uint AnyType = 0;
        private const string Library = "libxcb.so.1";

        [StructLayout(LayoutKind.Sequential)]
        public struct Cookie
        {
            public uint Sequence;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct ScreenIterator
        {
            public IntPtr Data;
            public int Remaining;
            public int Index;
        }

        [DllImport(Library)]
        public static extern IntPtr xcb_connect(string? display, out int screen);

        [DllImport(Library)]
        public static extern int xcb_connection_has_error(IntPtr connection);

        [DllImport(Library)]
        public static extern void xcb_disconnect(IntPtr connection);

        [DllImport(Library)]
        private static extern IntPtr xcb_get_setup(IntPtr connection);

        [DllImport(Library)]
        private static extern ScreenIterator xcb_setup_roots_iterator(IntPtr setup);

        [DllImport(Library)]
        private static extern Cookie xcb_intern_atom(IntPtr connection, byte onlyIfExists, ushort length, byte[] name);

        [DllImport(Library)]
        private static extern IntPtr xcb_intern_atom_reply(IntPtr connection, Cookie cookie, out IntPtr error);

        [DllImport(Library)]
        private static extern Cookie xcb_get_property(IntPtr connection, byte delete, uint window, uint property, uint type,
            uint longOffset, uint longLength);

        [DllImport(Library)]
        private static extern IntPtr xcb_get_property_reply(IntPtr connection, Cookie cookie, out IntPtr error);

        [DllImport(Library)]
        private static extern IntPtr xcb_get_property_value(IntPtr reply);

        [DllImport(Library)]
        private static extern int xcb_get_property_value_length(IntPtr reply);

        [DllImport("libc")]
        private static extern void free(IntPtr pointer);

        /// <summary>The first screen's root window (xcb_screen_t starts with it).</summary>
        public static uint FirstRoot(IntPtr connection)
        {
            var screens = xcb_setup_roots_iterator(xcb_get_setup(connection));
            return screens.Data == IntPtr.Zero ? 0 : (uint)Marshal.ReadInt32(screens.Data);
        }

        public static uint Atom(IntPtr connection, string name)
        {
            var bytes = Encoding.ASCII.GetBytes(name);
            var reply = xcb_intern_atom_reply(connection, xcb_intern_atom(connection, 1, (ushort)bytes.Length, bytes), out var error);
            if (error != IntPtr.Zero) free(error);
            if (reply == IntPtr.Zero) return 0;
            // response_type, pad, sequence (2), length (4), then the atom.
            var atom = (uint)Marshal.ReadInt32(reply, 8);
            free(reply);
            return atom;
        }

        /// <summary>A window property's raw bytes (at most 1 KB), or null when it's missing or the window is gone.</summary>
        public static byte[]? Property(IntPtr connection, uint window, uint property)
        {
            if (property == 0) return null;
            var reply = xcb_get_property_reply(connection, xcb_get_property(connection, 0, window, property, AnyType, 0, 256), out var error);
            if (error != IntPtr.Zero) free(error);
            if (reply == IntPtr.Zero) return null;
            try
            {
                var length = xcb_get_property_value_length(reply);
                if (length <= 0) return null;
                var bytes = new byte[length];
                Marshal.Copy(xcb_get_property_value(reply), bytes, 0, length);
                return bytes;
            }
            finally
            {
                free(reply);
            }
        }
    }
}
