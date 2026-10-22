using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Windows.Input;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using H.NotifyIcon;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Services;

public sealed class RelayCommand(Action execute) : ICommand
{
    public event EventHandler? CanExecuteChanged { add { } remove { } }
    public bool CanExecute(object? parameter) => true;
    public void Execute(object? parameter) => execute();
}

/// <summary>
/// The notification-area icon: it draws the first provider's remaining percentage, the tooltip
/// lists every signed-in provider, and the menu opens the app, refreshes or quits.
/// </summary>
public sealed class TrayIcon : IDisposable
{
    private readonly AppState state;
    private TaskbarIcon? icon;
    private Icon? currentIcon;

    public TrayIcon(AppState state, Action open, Action refresh, Action quit)
    {
        this.state = state;
        var menu = new MenuFlyout();
        menu.Items.Add(new MenuFlyoutItem { Text = "Open Codex Meter", Command = new RelayCommand(open) });
        menu.Items.Add(new MenuFlyoutItem { Text = "Refresh now", Command = new RelayCommand(refresh) });
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(new MenuFlyoutItem { Text = "Quit", Command = new RelayCommand(quit) });
        icon = new TaskbarIcon
        {
            ToolTipText = "Codex Meter",
            ContextMenuMode = ContextMenuMode.PopupMenu,
            ContextFlyout = menu,
            LeftClickCommand = new RelayCommand(open),
            NoLeftClickDelay = true,
        };
        Update();
        icon.ForceCreate(false);
        state.ProviderChanged += _ => Update();
        state.ProvidersChanged += Update;
    }

    /// <summary>Text for the tooltip, e.g. "Codex: 62% left (5-hour) · Claude Code: 59% left".</summary>
    public static string Summary(AppState state)
    {
        var lines = new List<string>();
        var mode = state.Settings.Current.DisplayMode;
        foreach (var provider in state.EnabledProviders)
        {
            var entry = state[provider];
            if (!entry.SignedIn) continue;
            var window = entry.Usage?.FiveHour ?? entry.Usage?.LongWindow;
            lines.Add(window is null
                ? $"{provider.DisplayName}: no data yet"
                : $"{provider.DisplayName}: {UsageFormat.Percent(window, mode)} ({UsageFormat.WindowName(window.WindowSeconds).ToLowerInvariant()})");
        }
        return lines.Count == 0 ? "Codex Meter: no accounts connected" : string.Join("\n", lines);
    }

    public void Update()
    {
        if (icon is null) return;
        var visible = state.Settings.Current.ShowTrayIcon;
        icon.Visibility = visible ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;
        icon.ToolTipText = Truncate(Summary(state), 127);
        var first = state.EnabledProviders.Select(p => state[p]).FirstOrDefault(e => e.SignedIn && e.Usage is not null);
        var window = first?.Usage?.FiveHour ?? first?.Usage?.LongWindow;
        var mode = state.Settings.Current.DisplayMode;
        var value = window is null ? (int?)null : UsageFormat.Value(window, mode);
        var previous = currentIcon;
        currentIcon = Draw(value, window?.RemainingPercent ?? 100);
        icon.Icon = currentIcon;
        previous?.Dispose();
    }

    private static string Truncate(string text, int max) => text.Length <= max ? text : text[..(max - 1)] + "…";

    /// <summary>A 32×32 badge: the percentage on a color that turns amber and red as the allowance runs low.</summary>
    internal static Icon Draw(int? value, int remainingPercent)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            g.Clear(Color.Transparent);
            var color = value is null ? Color.FromArgb(0x60, 0x5E, 0x5C)
                : remainingPercent <= 15 ? Color.FromArgb(0xC4, 0x2B, 0x1C)
                : remainingPercent <= 35 ? Color.FromArgb(0xC2, 0x76, 0x00)
                : Color.FromArgb(0x53, 0x45, 0xF3);
            using (var path = RoundedRect(new RectangleF(0.5f, 0.5f, 31, 31), 7))
            using (var brush = new SolidBrush(color))
                g.FillPath(brush, path);
            var text = value is null ? "–" : value.Value.ToString();
            var size = text.Length >= 3 ? 12f : 17f;
            using var font = new Font("Segoe UI", size, FontStyle.Bold, GraphicsUnit.Pixel);
            using var format = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(text, font, Brushes.White, new RectangleF(0, 1, 32, 32), format);
        }
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

    public void Dispose()
    {
        icon?.Dispose();
        icon = null;
        currentIcon?.Dispose();
    }
}
