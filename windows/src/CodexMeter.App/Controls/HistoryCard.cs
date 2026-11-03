using System.Globalization;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;

namespace CodexMeter.App.Controls;

/// <summary>
/// The usage history card: a line chart of the current window against your typical path, a
/// selector for 5-hour / weekly / monthly, and the highlights the phone apps show (pace vs.
/// typical, projected run-out, average window, peak burn, previous windows).
/// </summary>
public sealed class HistoryCard
{
    private readonly ProviderHistory history;
    private readonly IReadOnlyList<string> overrides;
    private readonly bool use24Hour;
    private readonly Canvas canvas = new() { Height = 200, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
    private readonly StackPanel details = new() { Spacing = 12 };
    private readonly Line hoverLine = new() { StrokeThickness = 1, Visibility = Visibility.Collapsed };
    private readonly Border hoverTip = new()
    {
        Padding = new Thickness(8, 4, 8, 5),
        CornerRadius = new CornerRadius(4),
        BorderThickness = new Thickness(1),
        Visibility = Visibility.Collapsed,
        IsHitTestVisible = false,
    };
    private readonly TextBlock hoverText = new();
    private UsageHistory selected;
    private Rect plot;

    public HistoryCard(ProviderHistory history, IReadOnlyList<string> overrides, bool use24Hour, Action<string, bool> setHighlight,
        Action clear)
    {
        this.history = history;
        this.overrides = overrides;
        this.use24Hour = use24Hour;
        var kinds = history.Recorded;
        selected = kinds.FirstOrDefault() ?? history.FiveHour;

        var root = new StackPanel { Spacing = 12 };
        var top = new Grid { ColumnSpacing = 12 };
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.Children.Add(Cards.Header("", "Usage history"));

        if (kinds.Count > 1)
        {
            var selector = new SelectorBar { VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetAutomationId(selector, "HistoryKinds");
            foreach (var kind in kinds)
            {
                var item = new SelectorBarItem { Text = KindName(kind.Kind), Tag = kind.Kind, IsSelected = kind.Kind == selected.Kind };
                selector.Items.Add(item);
            }
            selector.SelectionChanged += (sender, _) =>
            {
                if (sender.SelectedItem?.Tag is UsageHistoryKind kind)
                {
                    selected = history[kind];
                    Render();
                }
            };
            Grid.SetColumn(selector, 1);
            top.Children.Add(selector);
        }

        var more = new Button
        {
            Content = new FontIcon { Glyph = "", FontSize = 14 },
            Padding = new Thickness(8, 6, 8, 6),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            BorderThickness = new Thickness(0),
            VerticalAlignment = VerticalAlignment.Center,
        };
        AutomationProperties.SetName(more, "History options");
        ToolTipService.SetToolTip(more, "History options");
        var flyout = new MenuFlyout();
        foreach (var key in HistorySections.All)
        {
            var toggle = new ToggleMenuFlyoutItem { Text = HistorySections.Label(key), IsChecked = HistorySections.IsVisible(overrides, key) };
            toggle.Click += (_, _) => setHighlight(key, toggle.IsChecked);
            flyout.Items.Add(toggle);
        }
        flyout.Items.Add(new MenuFlyoutSeparator());
        var clearItem = new MenuFlyoutItem { Text = "Clear history", Icon = new FontIcon { Glyph = "" } };
        clearItem.Click += (_, _) => clear();
        flyout.Items.Add(clearItem);
        more.Flyout = flyout;
        Grid.SetColumn(more, 2);
        top.Children.Add(more);
        root.Children.Add(top);

        hoverLine.Stroke = Cards.Brush("TextFillColorTertiaryBrush");
        hoverLine.StrokeDashArray = [2, 2];
        hoverTip.Background = Cards.Brush("AcrylicInAppFillColorDefaultBrush");
        hoverTip.BorderBrush = Cards.Brush("SurfaceStrokeColorFlyoutBrush");
        hoverText.Style = Cards.Style("CaptionTextBlockStyle");
        hoverTip.Child = hoverText;

        canvas.SizeChanged += (_, _) => Draw();
        canvas.PointerMoved += OnPointerMoved;
        canvas.PointerExited += (_, _) => HideHover();
        AutomationProperties.SetName(canvas, "Usage chart");
        root.Children.Add(canvas);
        root.Children.Add(details);
        Root = Cards.Card(root, "HistoryCard", wide: true);
        AutomationProperties.SetName(Root, "Usage history");
        Render();
    }

    public Border Root { get; }

    private static string KindName(UsageHistoryKind kind) => kind switch
    {
        UsageHistoryKind.FiveHour => "5-hour",
        UsageHistoryKind.Weekly => "Weekly",
        _ => "Monthly",
    };

    private bool Shows(string key) => HistorySections.IsVisible(overrides, key);

    private void Render()
    {
        Draw();
        details.Children.Clear();
        if (selected.Samples.Count == 0)
        {
            details.Children.Add(Cards.Text("History builds up as Codex Meter checks your usage. Leave it running in the notification area to see trends here.",
                "SecondaryTextStyle"));
            return;
        }
        var now = DateTimeOffset.UtcNow;
        var current = selected.CurrentWindowSamples;
        var latest = current[^1];

        var stats = new CardPanel { MinColumnWidth = 170, MaxColumns = 4, Spacing = 16 };
        if (Shows(HistorySections.InsightPace))
        {
            var fraction = (now - latest.WindowStart).TotalSeconds / latest.WindowSeconds;
            var typical = UsageStats.TypicalUsedPercent(selected, fraction);
            if (typical is { } usual)
            {
                var difference = latest.UsedPercent - usual;
                stats.Children.Add(Stat("Pace vs. typical",
                    Math.Abs(difference) < 3 ? "On your usual pace" : $"{Math.Abs(difference):0} points {(difference > 0 ? "ahead" : "behind")}",
                    $"Usually {usual:0}% used by now"));
            }
            else stats.Children.Add(Stat("Pace vs. typical", "Not enough history", "Shows after a full window"));
        }
        if (Shows(HistorySections.InsightExhaustion))
        {
            var runOut = UsageStats.ProjectedExhaustion(selected, now);
            stats.Children.Add(runOut is { } at
                ? Stat("Projected run-out", "In " + UsageFormat.Duration(at - now), "Before the window resets")
                : Stat("Projected run-out", selected.ObservedBurnRate > 0 ? "Lasts until reset" : "No trend yet",
                    "At the recent burn rate"));
        }
        if (Shows(HistorySections.InsightAverage))
        {
            var average = UsageStats.AverageFinalPercent(selected);
            stats.Children.Add(Stat("Average window", average is { } a ? $"{a:0}% used" : "—",
                $"{selected.CompletedWindowCount} finished window{(selected.CompletedWindowCount == 1 ? "" : "s")}"));
        }
        if (Shows(HistorySections.InsightPeak))
        {
            var peak = UsageStats.PeakBurnPercentPerHour(selected);
            stats.Children.Add(Stat("Peak burn rate", peak > 0 ? UsageStats.FormatRate(peak) : "—", "Fastest stretch recorded"));
        }
        if (stats.Children.Count > 0) details.Children.Add(stats);

        if (Shows(HistorySections.WindowList))
        {
            var windows = UsageStats.Breakdown(selected, 7).Where(w => w.Complete).Reverse().ToList();
            if (windows.Count > 0)
            {
                var list = new StackPanel { Spacing = 6 };
                list.Children.Add(Cards.Text("Previous windows", "BodyStrongTextBlockStyle"));
                foreach (var window in windows)
                {
                    var row = new Grid { ColumnSpacing = 12 };
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(140) });
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(48) });
                    row.Children.Add(Cards.Text(Range(window.WindowStart, window.ResetAt), "CaptionSecondaryTextStyle", wrap: false));
                    var bar = Cards.Bar(window.FinalPercent, Cards.Level(100 - window.FinalPercent));
                    bar.VerticalAlignment = VerticalAlignment.Center;
                    Grid.SetColumn(bar, 1);
                    row.Children.Add(bar);
                    var value = Cards.Text(window.FinalPercent + "%", "CaptionTextBlockStyle", wrap: false);
                    value.HorizontalAlignment = HorizontalAlignment.Right;
                    Grid.SetColumn(value, 2);
                    row.Children.Add(value);
                    list.Children.Add(row);
                }
                details.Children.Add(list);
            }
        }

        if (Shows(HistorySections.Guide))
        {
            details.Children.Add(Cards.Text(
                "The solid line is this window's usage as Codex Meter saw it. The dashed line is your typical path, averaged over " +
                "finished windows. Point at the chart to read a moment. History stays on this PC.", "CaptionSecondaryTextStyle"));
        }
    }

    private static StackPanel Stat(string label, string value, string detail)
    {
        var panel = new StackPanel { Spacing = 1 };
        panel.Children.Add(Cards.Text(label, "CaptionSecondaryTextStyle", wrap: false));
        panel.Children.Add(Cards.Text(value, "BodyStrongTextBlockStyle"));
        panel.Children.Add(Cards.Text(detail, "CaptionSecondaryTextStyle"));
        return panel;
    }

    private string Range(DateTimeOffset start, DateTimeOffset end) => selected.Kind == UsageHistoryKind.FiveHour
        ? $"{Local(start).ToString("MMM d", CultureInfo.CurrentCulture)}, {Time(start)} – {Time(end)}"
        : $"{Local(start).ToString("MMM d", CultureInfo.CurrentCulture)} – {Local(end).ToString("MMM d", CultureInfo.CurrentCulture)}";

    private static DateTime Local(DateTimeOffset value) => TimeZoneInfo.ConvertTime(value, TimeZoneInfo.Local).DateTime;

    private string Time(DateTimeOffset value) => Local(value).ToString(use24Hour ? "HH:mm" : "h:mm tt", CultureInfo.CurrentCulture);

    private string AxisLabel(DateTimeOffset value) => selected.Kind switch
    {
        UsageHistoryKind.FiveHour => Time(value),
        UsageHistoryKind.Weekly => Local(value).ToString("ddd", CultureInfo.CurrentCulture),
        _ => Local(value).ToString("MMM d", CultureInfo.CurrentCulture),
    };

    private double X(DateTimeOffset at, UsageSample reference) =>
        plot.X + Math.Clamp((at - reference.WindowStart).TotalSeconds / reference.WindowSeconds, 0, 1) * plot.Width;

    private double Y(double percent) => plot.Y + plot.Height - Math.Clamp(percent, 0, 100) / 100 * plot.Height;

    private void Draw()
    {
        canvas.Children.Clear();
        var width = canvas.ActualWidth;
        var height = canvas.ActualHeight;
        if (width <= 0 || height <= 0) return;
        plot = new Rect(40, 8, Math.Max(10, width - 48), Math.Max(10, height - 32));

        var grid = Cards.Brush("DividerStrokeColorDefaultBrush");
        var labelStyle = Cards.Style("CaptionSecondaryTextStyle");
        foreach (var percent in new[] { 0, 25, 50, 75, 100 })
        {
            var y = Y(percent);
            canvas.Children.Add(new Line { X1 = plot.X, X2 = plot.Right, Y1 = y, Y2 = y, Stroke = grid, StrokeThickness = 1 });
            if (percent % 50 != 0) continue;
            var label = new TextBlock { Text = percent + "%", Style = labelStyle, FontSize = 11 };
            Canvas.SetLeft(label, 0);
            Canvas.SetTop(label, y - 8);
            canvas.Children.Add(label);
        }

        var current = selected.CurrentWindowSamples;
        if (current.Count == 0) return;
        var reference = current[^1];
        var now = DateTimeOffset.UtcNow;

        // Typical path across the whole window, from finished windows.
        var typical = new PointCollection();
        for (var step = 0; step <= 60; step++)
        {
            if (UsageStats.TypicalUsedPercent(selected, step / 60.0) is not { } value) break;
            typical.Add(new Point(plot.X + step / 60.0 * plot.Width, Y(value)));
        }
        if (typical.Count > 1)
        {
            canvas.Children.Add(new Polyline
            {
                Points = typical,
                Stroke = Cards.Brush("TextFillColorTertiaryBrush"),
                StrokeThickness = 1.5,
                StrokeDashArray = [4, 3],
            });
        }

        // This window: a filled area under an accent line.
        var line = new PointCollection();
        foreach (var sample in current) line.Add(new Point(X(sample.ObservedAt, reference), Y(sample.UsedPercent)));
        if (line.Count == 1) line.Insert(0, new Point(X(reference.WindowStart, reference), Y(0)));
        var area = new PointCollection { new Point(line[0].X, Y(0)) };
        foreach (var point in line) area.Add(point);
        area.Add(new Point(line[^1].X, Y(0)));
        var accent = Cards.Brush("AccentFillColorDefaultBrush");
        canvas.Children.Add(new Polygon { Points = area, Fill = accent, Opacity = 0.14 });
        canvas.Children.Add(new Polyline
        {
            Points = line,
            Stroke = accent,
            StrokeThickness = 2.5,
            StrokeLineJoin = PenLineJoin.Round,
            StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round,
        });
        var dot = new Ellipse { Width = 9, Height = 9, Fill = accent, Stroke = Cards.Brush("CardBackgroundFillColorDefaultBrush"), StrokeThickness = 2 };
        Canvas.SetLeft(dot, line[^1].X - 4.5);
        Canvas.SetTop(dot, line[^1].Y - 4.5);
        canvas.Children.Add(dot);

        // Axis: window start, now, reset.
        AddAxisLabel(AxisLabel(reference.WindowStart), plot.X, HorizontalAlignment.Left);
        AddAxisLabel(AxisLabel(reference.ResetAt), plot.Right, HorizontalAlignment.Right);
        var nowX = X(now, reference);
        if (nowX > plot.X + 60 && nowX < plot.Right - 60)
        {
            canvas.Children.Add(new Line
            {
                X1 = nowX, X2 = nowX, Y1 = plot.Y, Y2 = plot.Bottom,
                Stroke = Cards.Brush("TextFillColorTertiaryBrush"), StrokeThickness = 1, StrokeDashArray = [2, 3],
            });
            AddAxisLabel("Now", nowX, HorizontalAlignment.Center);
        }

        canvas.Children.Add(hoverLine);
        canvas.Children.Add(hoverTip);
    }

    private void AddAxisLabel(string text, double x, HorizontalAlignment align)
    {
        var label = new TextBlock { Text = text, Style = Cards.Style("CaptionSecondaryTextStyle"), FontSize = 11 };
        label.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        var left = align switch
        {
            HorizontalAlignment.Right => x - label.DesiredSize.Width,
            HorizontalAlignment.Center => x - label.DesiredSize.Width / 2,
            _ => x,
        };
        Canvas.SetLeft(label, left);
        Canvas.SetTop(label, plot.Bottom + 6);
        canvas.Children.Add(label);
    }

    private void OnPointerMoved(object sender, PointerRoutedEventArgs e)
    {
        var current = selected.CurrentWindowSamples;
        if (current.Count == 0 || plot.Width <= 0) return;
        var reference = current[^1];
        var x = e.GetCurrentPoint(canvas).Position.X;
        if (x < plot.X || x > plot.Right)
        {
            HideHover();
            return;
        }
        var at = reference.WindowStart.AddSeconds((x - plot.X) / plot.Width * reference.WindowSeconds);
        var value = UsageStats.UsedPercentAt(at, current);
        var typical = UsageStats.TypicalUsedPercent(selected, (x - plot.X) / plot.Width);
        if (value is null && typical is null)
        {
            HideHover();
            return;
        }
        var label = selected.Kind == UsageHistoryKind.FiveHour ? Time(at) : $"{Local(at).ToString("ddd", CultureInfo.CurrentCulture)} {Time(at)}";
        var parts = new List<string> { label };
        if (value is { } v) parts.Add($"{v:0}% used");
        if (typical is { } t) parts.Add($"typical {t:0}%");
        hoverText.Text = string.Join(" · ", parts);
        hoverLine.X1 = hoverLine.X2 = x;
        hoverLine.Y1 = plot.Y;
        hoverLine.Y2 = plot.Bottom;
        hoverLine.Visibility = Visibility.Visible;
        hoverTip.Visibility = Visibility.Visible;
        hoverTip.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        Canvas.SetLeft(hoverTip, Math.Clamp(x + 10, 0, Math.Max(0, canvas.ActualWidth - hoverTip.DesiredSize.Width)));
        Canvas.SetTop(hoverTip, plot.Y);
    }

    private void HideHover()
    {
        hoverLine.Visibility = Visibility.Collapsed;
        hoverTip.Visibility = Visibility.Collapsed;
    }
}
