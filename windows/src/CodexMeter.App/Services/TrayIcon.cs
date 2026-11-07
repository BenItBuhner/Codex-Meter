using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Windows.Input;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using H.NotifyIcon;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Win32;

namespace CodexMeter.App.Services;

public sealed class RelayCommand(Action execute) : ICommand
{
    public event EventHandler? CanExecuteChanged { add { } remove { } }
    public bool CanExecute(object? parameter) => true;
    public void Execute(object? parameter) => execute();
}

/// <summary>
/// Live usage in the notification area, the Windows twin of the Android live notification: the
/// icon is redrawn as a ring (or a number) for one provider's tighter window, the provider follows
/// the AI app in front, the tooltip leads with that value and lists the rest, a click opens a
/// compact flyout, and the menu opens the app, refreshes or quits.
/// </summary>
public sealed class TrayIcon : IDisposable
{
    private readonly AppState state;
    private readonly ForegroundApp? foreground;
    private readonly TrayFlyout flyout;
    private readonly DispatcherQueueTimer ticker;
    private TaskbarIcon? icon;
    private Icon? currentIcon;
    private string iconKey = "";

    public TrayIcon(AppState state, Action<string?> open, Action refresh, Action quit)
    {
        this.state = state;
        flyout = new TrayFlyout(state, open, refresh);
        var menu = new MenuFlyout();
        menu.Items.Add(new MenuFlyoutItem { Text = "Open Codex Meter", Command = new RelayCommand(() => open(null)) });
        menu.Items.Add(new MenuFlyoutItem { Text = "Refresh now", Command = new RelayCommand(refresh) });
        menu.Items.Add(new MenuFlyoutItem { Text = "Live usage settings", Command = new RelayCommand(() => open("settings")) });
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(new MenuFlyoutItem { Text = "Quit", Command = new RelayCommand(quit) });
        icon = new TaskbarIcon
        {
            ToolTipText = "Codex Meter",
            ContextMenuMode = ContextMenuMode.PopupMenu,
            ContextFlyout = menu,
            LeftClickCommand = new RelayCommand(flyout.Toggle),
            NoLeftClickDelay = true,
        };
        try
        {
            foreground = new ForegroundApp();
            foreground.Changed += Update;
        }
        catch (Exception error)
        {
            App.Log("Foreground app tracking unavailable: " + error.Message);
        }
        Update();
        icon.ForceCreate(false);
        state.ProviderChanged += _ => Update();
        state.ProvidersChanged += Update;
        // Countdowns, and the taskbar's light or dark theme, change without a refresh.
        ticker = DispatcherQueue.GetForCurrentThread().CreateTimer();
        ticker.Interval = TimeSpan.FromMinutes(1);
        ticker.Tick += (_, _) => Update();
        ticker.Start();
    }

    /// <summary>The provider of the AI app in front, when "While using AI apps" is on.</summary>
    private UsageProvider? FrontApp => state.Settings.Current.LiveFollowApps ? foreground?.Provider : null;

    /// <summary>What the icon shows right now, or null when nothing is connected.</summary>
    public static LiveReading? Current(AppState state, UsageProvider? frontApp)
    {
        var settings = state.Settings.Current;
        var candidates = state.EnabledProviders.Where(p => state[p].SignedIn && state[p].Usage is not null).ToList();
        if (LiveUsage.ChooseProvider(settings.LiveProvider, frontApp, candidates) is not { } provider) return null;
        return LiveUsage.Read(provider, state[provider].Usage, settings.LiveWindow);
    }

    /// <summary>
    /// The tooltip: the live value first, when it resets, then every other provider, e.g.
    /// "Codex · 5-hour 62% left / Resets in 2h 14m / Claude Code: 41% (weekly)".
    /// </summary>
    public static string Summary(AppState state, LiveReading? live, DateTimeOffset now)
    {
        var settings = state.Settings.Current;
        var mode = settings.DisplayMode;
        var lines = new List<string>();
        if (live is not null)
        {
            lines.Add(live.Headline(mode, now));
            if (!live.Exhausted && live.Window is { } window)
            {
                var reset = UsageFormat.Reset(window, ResetDisplayMode.Relative, live.FetchedAt, now);
                if (reset.Length > 0) lines.Add(reset);
            }
        }
        foreach (var provider in state.EnabledProviders)
        {
            var entry = state[provider];
            if (!entry.SignedIn || provider == live?.Provider) continue;
            var other = LiveUsage.Read(provider, entry.Usage, settings.LiveWindow);
            lines.Add(other.Window is null
                ? $"{provider.DisplayName}: no data yet"
                : $"{provider.DisplayName}: {other.CompactText(mode, now)} ({other.WindowLabel.ToLowerInvariant()})");
        }
        return lines.Count == 0 ? "Codex Meter: no accounts connected" : string.Join("\n", lines);
    }

    public void Update()
    {
        if (icon is null) return;
        var settings = state.Settings.Current;
        icon.Visibility = settings.ShowTrayIcon ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;
        var now = DateTimeOffset.UtcNow;
        var live = Current(state, FrontApp);
        flyout.LiveProvider = live?.Provider;
        // Windows caps tray tooltips at 127 characters.
        icon.ToolTipText = Truncate(Summary(state, live, now), 127);

        var size = SmallIconSize();
        var dark = TaskbarIsDark();
        var text = live?.IconText(settings.DisplayMode, now) ?? "–";
        var key = $"{size}|{dark}|{settings.LiveIconStyle}|{text}|{live?.Fraction(settings.DisplayMode):0.00}|{live?.Level}";
        if (key == iconKey) return;
        iconKey = key;
        var previous = currentIcon;
        using (var bitmap = Draw(live, settings.LiveIconStyle, settings.DisplayMode, size, dark, now))
            currentIcon = ToIcon(bitmap);
        icon.Icon = currentIcon;
        previous?.Dispose();
    }

    private static string Truncate(string text, int max) => text.Length <= max ? text : text[..(max - 1)] + "…";

    /// <summary>The notification area's icon size at the system DPI: 16px at 100%, 24px at 150%, 32px at 200%.</summary>
    private static int SmallIconSize()
    {
        try
        {
            var size = GetSystemMetricsForDpi(49 /* SM_CXSMICON */, GetDpiForSystem());
            return size is >= 16 and <= 64 ? size : 32;
        }
        catch (EntryPointNotFoundException)
        {
            return 32;
        }
    }

    /// <summary>Whether the taskbar uses the dark theme (Windows' default), which the ring's colors follow.</summary>
    internal static bool TaskbarIsDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("SystemUsesLightTheme") is not int light || light == 0;
        }
        catch (Exception)
        {
            return true;
        }
    }

    /// <summary>Ring and text colors for a level, tuned for contrast on a dark or light taskbar.</summary>
    internal static Color LevelColor(LiveLevel level, bool dark) => level switch
    {
        LiveLevel.Critical => dark ? Color.FromArgb(0xFF, 0x6B, 0x5E) : Color.FromArgb(0xC4, 0x2B, 0x1C),
        LiveLevel.Low => dark ? Color.FromArgb(0xF7, 0xB5, 0x2C) : Color.FromArgb(0xB3, 0x6B, 0x00),
        LiveLevel.Healthy => dark ? Color.FromArgb(0x9D, 0x95, 0xFF) : Color.FromArgb(0x53, 0x45, 0xF3),
        _ => Color.FromArgb(0x8A, 0x88, 0x86),
    };

    /// <summary>
    /// Draws the icon at <paramref name="size"/> pixels. The ring fills clockwise from the top with
    /// what's left (or used), over a faint track that turns red once the window is used up. The
    /// number style is a filled badge with the value, or the time until reset when used up.
    /// </summary>
    internal static Bitmap Draw(LiveReading? live, LiveIconStyle style, UsageDisplayMode mode, int size, bool darkTaskbar, DateTimeOffset now)
    {
        var bitmap = new Bitmap(size, size);
        using var g = Graphics.FromImage(bitmap);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        g.Clear(Color.Transparent);
        var level = live?.Level ?? LiveLevel.None;
        if (style == LiveIconStyle.Number)
        {
            var fill = live?.Window is null ? Color.FromArgb(0x60, 0x5E, 0x5C)
                : level == LiveLevel.Critical ? Color.FromArgb(0xC4, 0x2B, 0x1C)
                : level == LiveLevel.Low ? Color.FromArgb(0xB3, 0x6B, 0x00)
                : Color.FromArgb(0x53, 0x45, 0xF3);
            using (var path = RoundedRect(new RectangleF(0, 0, size, size), size * 0.22f))
            using (var brush = new SolidBrush(fill))
                g.FillPath(brush, path);
            var text = live?.IconText(mode, now) ?? "–";
            // Two characters fill the badge; three ("100", "45m") step down so they still fit.
            var em = size * (text.Length >= 3 ? 0.42f : 0.6f);
            using var font = new Font("Segoe UI", em, FontStyle.Bold, GraphicsUnit.Pixel);
            using var format = new StringFormat(StringFormat.GenericTypographic)
            {
                Alignment = StringAlignment.Center,
                LineAlignment = StringAlignment.Center,
                FormatFlags = StringFormatFlags.NoWrap | StringFormatFlags.NoClip,
            };
            g.DrawString(text, font, Brushes.White, new RectangleF(-2, size * 0.04f, size + 4, size), format);
            return bitmap;
        }

        // Thick enough to read at 16px (3px), in proportion above that.
        var stroke = Math.Max(3f, size * 0.17f);
        var inset = stroke / 2 + size / 32f;
        var bounds = new RectangleF(inset, inset, size - 2 * inset, size - 2 * inset);
        var exhausted = live?.Exhausted == true;
        var trackColor = exhausted ? Color.FromArgb(0x70, LevelColor(LiveLevel.Critical, darkTaskbar))
            : darkTaskbar ? Color.FromArgb(0x55, 0xFF, 0xFF, 0xFF) : Color.FromArgb(0x40, 0x00, 0x00, 0x00);
        using (var track = new Pen(trackColor, stroke))
            g.DrawEllipse(track, bounds);
        var fraction = live is null ? 0 : live.Fraction(mode);
        if (fraction > 0)
        {
            using var arc = new Pen(LevelColor(level, darkTaskbar), stroke) { StartCap = LineCap.Round, EndCap = LineCap.Round };
            if (fraction >= 0.995) g.DrawEllipse(arc, bounds);
            else g.DrawArc(arc, bounds, -90, (float)Math.Max(8, fraction * 360));
        }
        return bitmap;
    }

    internal static Icon ToIcon(Bitmap bitmap)
    {
        var handle = bitmap.GetHicon();
        try
        {
            using var temporary = Icon.FromHandle(handle);
            return (Icon)temporary.Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    private static GraphicsPath RoundedRect(RectangleF r, float radius)
    {
        var path = new GraphicsPath();
        var d = radius * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetricsForDpi(int index, uint dpi);

    [DllImport("user32.dll")]
    private static extern uint GetDpiForSystem();

    public void Dispose()
    {
        ticker.Stop();
        foreground?.Dispose();
        flyout.Dispose();
        icon?.Dispose();
        icon = null;
        currentIcon?.Dispose();
    }
}
