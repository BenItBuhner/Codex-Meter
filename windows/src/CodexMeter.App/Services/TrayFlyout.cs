using System.Runtime.InteropServices;
using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;
using WinRT.Interop;

namespace CodexMeter.App.Services;

/// <summary>
/// The compact panel a click on the tray icon opens, like Windows' own quick settings: every
/// connected service's windows at a glance on acrylic, a refresh button, and a way into the app.
/// It sits above the notification area and closes as soon as you click elsewhere.
/// </summary>
public sealed class TrayFlyout : IDisposable
{
    private const double Width = 360;
    private readonly AppState state;
    private readonly Action<string?> open;
    private readonly Action refresh;
    private Window? window;
    private Grid? root;
    private DateTimeOffset hiddenAt;
    private bool visible;
    private bool closing;

    public TrayFlyout(AppState state, Action<string?> open, Action refresh)
    {
        this.state = state;
        this.open = open;
        this.refresh = refresh;
        state.ProviderChanged += _ => { if (visible) Build(); };
    }

    /// <summary>The provider the tray icon is showing, marked in the panel.</summary>
    public UsageProvider? LiveProvider { get; set; }

    public IntPtr Handle => window is null ? IntPtr.Zero : WindowNative.GetWindowHandle(window);

    /// <summary>Opens the panel, or closes it when it's open (or was closed by this same click).</summary>
    public void Toggle()
    {
        if (visible)
        {
            Hide();
            return;
        }
        // The click that hid the panel by taking focus away shouldn't reopen it.
        if (DateTimeOffset.UtcNow - hiddenAt < TimeSpan.FromMilliseconds(300)) return;
        Show();
    }

    public void Show(PointInt32? anchor = null)
    {
        EnsureWindow();
        Build();
        var w = window!;
        GetCursorPos(out var cursor);
        var point = anchor ?? new PointInt32(cursor.X, cursor.Y);
        var area = DisplayArea.GetFromPoint(point, DisplayAreaFallback.Nearest);
        // Move onto that display first so the DPI read below is the display's.
        w.AppWindow.Move(new PointInt32(area.WorkArea.X, area.WorkArea.Y));
        var scale = GetDpiForWindow(Handle) / 96.0;
        var work = area.WorkArea;
        var width = (int)(Width * scale);
        var height = (int)Math.Min(EstimatedHeight() * scale, work.Height - 24 * scale);
        var margin = (int)(12 * scale);
        // Against the edge nearest the taskbar: bottom-right for the usual layout.
        var x = point.X >= work.X + work.Width / 2 ? work.X + work.Width - width - margin : work.X + margin;
        var y = point.Y >= work.Y + work.Height / 2 ? work.Y + work.Height - height - margin : work.Y + margin;
        w.AppWindow.MoveAndResize(new RectInt32(x, y, width, height));
        w.AppWindow.Show();
        w.Activate();
        SetForegroundWindow(Handle);
        visible = true;
    }

    public void Hide()
    {
        if (window is null || !visible) return;
        visible = false;
        hiddenAt = DateTimeOffset.UtcNow;
        window.AppWindow.Hide();
    }

    private void EnsureWindow()
    {
        if (window is not null) return;
        root = new Grid();
        window = new Window { Title = "Codex Meter", Content = root, SystemBackdrop = new DesktopAcrylicBackdrop() };
        var app = window.AppWindow;
        app.IsShownInSwitchers = false;
        if (app.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsResizable = false;
            presenter.IsMaximizable = false;
            presenter.IsMinimizable = false;
            presenter.IsAlwaysOnTop = true;
            presenter.SetBorderAndTitleBar(true, false);
        }
        // Rounded like the system's flyouts.
        var round = 2; // DWMWCP_ROUND
        DwmSetWindowAttribute(Handle, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */, ref round, sizeof(int));
        window.Activated += (_, args) =>
        {
            if (args.WindowActivationState == WindowActivationState.Deactivated) Hide();
        };
        app.Closing += (_, args) =>
        {
            if (closing) return;
            args.Cancel = true;
            Hide();
        };
    }

    private IReadOnlyList<ProviderEntry> Shown =>
        state.EnabledProviders.Select(p => state[p]).Where(e => e.SignedIn).ToList();

    private static IEnumerable<(string Label, UsageWindow Window)> WindowsOf(UsageSnapshot? usage)
    {
        if (usage is null) yield break;
        if (usage.FiveHour is { } five) yield return ("5-hour", five);
        if (usage.Weekly is { } weekly) yield return ("Weekly", weekly);
        if (usage.Monthly is { } monthly) yield return ("Monthly", monthly);
        if (usage.FiveHour is null && usage.Weekly is null && usage.Monthly is null
            && usage.AdditionalLimits.FirstOrDefault() is { } limit && (limit.Primary ?? limit.Secondary) is { } extra)
            yield return (limit.DisplayName, extra);
    }

    private double EstimatedHeight()
    {
        var height = 64.0 + 56; // header and footer
        var shown = Shown;
        if (shown.Count == 0) return height + 80;
        foreach (var entry in shown) height += 44 + 30 * Math.Max(1, WindowsOf(entry.Usage).Count()) + 8;
        return height + 8;
    }

    private void Build()
    {
        if (root is null || window is null) return;
        var settings = state.Settings.Current;
        root.RequestedTheme = settings.Theme switch
        {
            AppTheme.Light => ElementTheme.Light,
            AppTheme.Dark => ElementTheme.Dark,
            _ => ElementTheme.Default,
        };
        root.Children.Clear();
        root.RowDefinitions.Clear();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        AutomationProperties.SetAutomationId(root, "TrayFlyout");

        var now = DateTimeOffset.UtcNow;
        var shown = Shown;

        var header = new Grid { Padding = new Thickness(16, 14, 10, 8), ColumnSpacing = 10 };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var titles = new StackPanel { Spacing = 0 };
        titles.Children.Add(Cards.Text("Codex Meter", "BodyStrongTextBlockStyle", wrap: false));
        var latest = shown.Select(e => e.Usage?.FetchedAt).Where(d => d is not null).Max();
        titles.Children.Add(Cards.Text(state.IsDemo ? "Demo data" : UsageFormat.Updated(latest, now), "CaptionTextBlockStyle", wrap: false));
        header.Children.Add(titles);
        var refreshing = shown.Any(e => e.Refreshing);
        var refreshButton = new Button
        {
            Content = refreshing ? new ProgressRing { IsActive = true, Width = 16, Height = 16 } : new FontIcon { Glyph = "", FontSize = 14 },
            IsEnabled = !refreshing,
            Padding = new Thickness(8),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            BorderThickness = new Thickness(0),
        };
        AutomationProperties.SetName(refreshButton, "Refresh all");
        AutomationProperties.SetAutomationId(refreshButton, "FlyoutRefresh");
        ToolTipService.SetToolTip(refreshButton, "Refresh all");
        refreshButton.Click += (_, _) => refresh();
        Grid.SetColumn(refreshButton, 1);
        header.Children.Add(refreshButton);
        root.Children.Add(header);

        var list = new StackPanel { Spacing = 8, Padding = new Thickness(12, 0, 12, 8) };
        if (shown.Count == 0)
        {
            list.Children.Add(Cards.Text("No services connected yet. Open Codex Meter to sign in.", "BodyTextBlockStyle"));
        }
        foreach (var entry in shown) list.Children.Add(ProviderCard(entry, settings, now));
        var scroller = new ScrollViewer { Content = list, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Grid.SetRow(scroller, 1);
        root.Children.Add(scroller);

        var footer = new Grid
        {
            Padding = new Thickness(12, 10, 12, 10),
            ColumnSpacing = 4,
            Background = Cards.Brush("LayerOnAcrylicFillColorDefaultBrush"),
            BorderBrush = Cards.Brush("CardStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(0, 1, 0, 0),
        };
        footer.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        footer.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var openButton = new Button { Content = "Open Codex Meter" };
        AutomationProperties.SetAutomationId(openButton, "FlyoutOpen");
        openButton.Click += (_, _) => { Hide(); open(null); };
        footer.Children.Add(openButton);
        var settingsButton = new Button { Content = new FontIcon { Glyph = "", FontSize = 14 }, Padding = new Thickness(8), Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0) };
        AutomationProperties.SetName(settingsButton, "Settings");
        ToolTipService.SetToolTip(settingsButton, "Settings");
        settingsButton.Click += (_, _) => { Hide(); open("settings"); };
        Grid.SetColumn(settingsButton, 1);
        footer.Children.Add(settingsButton);
        Grid.SetRow(footer, 2);
        root.Children.Add(footer);
    }

    private Border ProviderCard(ProviderEntry entry, AppSettings settings, DateTimeOffset now)
    {
        var provider = entry.Provider;
        var stack = new StackPanel { Spacing = 6 };
        var title = new Grid { ColumnSpacing = 8 };
        title.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        title.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        title.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        title.Children.Add(ProviderIcons.Create(provider.Id, 16));
        var name = Cards.Text(provider.DisplayName, "BodyStrongTextBlockStyle", wrap: false);
        Grid.SetColumn(name, 1);
        title.Children.Add(name);
        if (provider == LiveProvider)
        {
            var tag = Cards.Text("In the tray", "CaptionTextBlockStyle", wrap: false);
            tag.Foreground = Cards.Brush("TextFillColorSecondaryBrush");
            tag.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(tag, 2);
            title.Children.Add(tag);
        }
        stack.Children.Add(title);

        var windows = WindowsOf(entry.Usage).ToList();
        if (windows.Count == 0)
        {
            var empty = Cards.Text(entry.Refreshing ? "Refreshing…" : entry.HasError ? "Couldn't refresh" : "No usage yet", "CaptionTextBlockStyle");
            empty.Foreground = Cards.Brush("TextFillColorSecondaryBrush");
            stack.Children.Add(empty);
        }
        foreach (var (label, window) in windows)
        {
            var row = new Grid { ColumnSpacing = 10 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(58) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(118) });
            var labelText = Cards.Text(label, "CaptionTextBlockStyle", wrap: false);
            labelText.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(labelText);
            var bar = Cards.Bar(UsageFormat.Value(window, settings.DisplayMode), Cards.Level(window.RemainingPercent));
            bar.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(bar, 1);
            row.Children.Add(bar);
            var reading = new LiveReading(provider, window, label, entry.Usage!.FetchedAt);
            var detail = reading.Exhausted ? $"Resets in {reading.CompactText(settings.DisplayMode, now)}"
                : $"{UsageFormat.Percent(window, settings.DisplayMode)}";
            var reset = reading.Exhausted ? "" : UsageFormat.Reset(window, ResetDisplayMode.Relative, entry.Usage.FetchedAt, now).Replace("Resets ", "");
            var value = Cards.Text(reset.Length > 0 ? $"{detail} · {reset}" : detail, "CaptionTextBlockStyle", wrap: false);
            value.HorizontalAlignment = HorizontalAlignment.Right;
            value.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(value, 2);
            row.Children.Add(value);
            AutomationProperties.SetName(row, $"{provider.DisplayName} {label}: {value.Text}");
            stack.Children.Add(row);
        }

        var button = new Button
        {
            Content = stack,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(12, 10, 12, 10),
            Background = Cards.Brush("CardBackgroundFillColorDefaultBrush"),
            BorderBrush = Cards.Brush("CardStrokeColorDefaultBrush"),
            CornerRadius = new CornerRadius(8),
        };
        AutomationProperties.SetAutomationId(button, "FlyoutCard_" + provider.Id);
        AutomationProperties.SetName(button, $"Open {provider.DisplayName}");
        button.Click += (_, _) => { Hide(); open(provider.Id); };
        return new Border { Child = button };
    }

    public void Dispose()
    {
        visible = false;
        closing = true;
        window?.Close();
        window = null;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CursorPoint
    {
        public int X, Y;
    }

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out CursorPoint point);

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
