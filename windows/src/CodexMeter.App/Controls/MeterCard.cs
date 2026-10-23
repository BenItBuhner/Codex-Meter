using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;

namespace CodexMeter.App.Controls;

public enum MeterLevel { Normal, Caution, Critical }

/// <summary>Builds the dashboard's cards from themed styles, so light and dark both stay correct.</summary>
public static class Cards
{
    public static Style Style(string key) => (Style)Application.Current.Resources[key];

    public static MeterLevel Level(int remainingPercent) =>
        remainingPercent <= 15 ? MeterLevel.Critical : remainingPercent <= 35 ? MeterLevel.Caution : MeterLevel.Normal;

    public static Border Card(UIElement child, string? automationId = null)
    {
        var border = new Border { Style = Style("CardBorderStyle"), Child = child };
        if (automationId is not null) AutomationProperties.SetAutomationId(border, automationId);
        return border;
    }

    public static TextBlock Text(string text, string style, double? size = null)
    {
        var block = new TextBlock { Text = text, Style = Style(style), TextWrapping = TextWrapping.WrapWholeWords };
        if (size is { } s) block.FontSize = s;
        return block;
    }

    /// <summary>A ring that fills clockwise from the top.</summary>
    public static Grid Ring(double fraction, MeterLevel level, string center, string caption, double size = 92)
    {
        var grid = new Grid { Width = size, Height = size };
        const double thickness = 9;
        var radius = (size - thickness) / 2;
        var c = size / 2;
        grid.Children.Add(new Microsoft.UI.Xaml.Shapes.Path
        {
            Style = Style("RingTrackStyle"),
            Data = new EllipseGeometry { Center = new Point(c, c), RadiusX = radius, RadiusY = radius },
        });
        fraction = Math.Clamp(fraction, 0, 1);
        if (fraction > 0)
        {
            Geometry arc;
            if (fraction >= 0.9999)
            {
                arc = new EllipseGeometry { Center = new Point(c, c), RadiusX = radius, RadiusY = radius };
            }
            else
            {
                var angle = fraction * 2 * Math.PI;
                var end = new Point(c + radius * Math.Sin(angle), c - radius * Math.Cos(angle));
                var figure = new PathFigure { StartPoint = new Point(c, c - radius), IsClosed = false, IsFilled = false };
                figure.Segments.Add(new ArcSegment
                {
                    Point = end,
                    Size = new Size(radius, radius),
                    IsLargeArc = fraction > 0.5,
                    SweepDirection = SweepDirection.Clockwise,
                });
                var path = new PathGeometry();
                path.Figures.Add(figure);
                arc = path;
            }
            grid.Children.Add(new Microsoft.UI.Xaml.Shapes.Path
            {
                Style = Style(level switch
                {
                    MeterLevel.Critical => "RingCriticalStyle",
                    MeterLevel.Caution => "RingCautionStyle",
                    _ => "RingNormalStyle",
                }),
                Data = arc,
            });
        }
        var label = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        label.Children.Add(new TextBlock
        {
            Text = center,
            Style = Style("SubtitleTextBlockStyle"),
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        label.Children.Add(new TextBlock
        {
            Text = caption,
            Style = Style("CaptionSecondaryTextStyle"),
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, -2, 0, 0),
        });
        grid.Children.Add(label);
        return grid;
    }

    /// <summary>A thin horizontal bar for compact meters.</summary>
    public static Grid Bar(double fraction, MeterLevel level)
    {
        var grid = new Grid { Height = 6 };
        grid.Children.Add(new Border { Style = Style("BarTrackStyle") });
        var fill = new Border
        {
            Style = Style(level switch
            {
                MeterLevel.Critical => "BarCriticalStyle",
                MeterLevel.Caution => "BarCautionStyle",
                _ => "BarNormalStyle",
            }),
        };
        grid.SizeChanged += (_, e) => fill.Width = Math.Max(fraction > 0 ? 6 : 0, e.NewSize.Width * Math.Clamp(fraction, 0, 1));
        grid.Children.Add(fill);
        return grid;
    }

    public sealed record MeterOptions(UsageDisplayMode Display, ResetDisplayMode Reset, bool Use24Hour, bool ShowPace);

    /// <summary>The main meter card: ring, title, value, the other half, reset time and pace.</summary>
    public static Border Meter(string title, string glyph, UsageWindow window, DateTimeOffset fetchedAt, MeterOptions options,
        bool limitReached = false, string? automationId = null)
    {
        var now = DateTimeOffset.UtcNow;
        var value = UsageFormat.Value(window, options.Display);
        var level = Level(window.RemainingPercent);
        var layout = new Grid { ColumnSpacing = 20 };
        layout.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        layout.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        layout.Children.Add(Ring(value / 100.0, level, value.ToString(), options.Display == UsageDisplayMode.Used ? "% used" : "% left"));

        var details = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(details, 1);
        var header = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(0, 0, 0, 2) };
        header.Children.Add(new FontIcon { Glyph = glyph, FontSize = 16 });
        header.Children.Add(new TextBlock { Text = title, Style = Style("BodyStrongTextBlockStyle"), TextWrapping = TextWrapping.WrapWholeWords });
        details.Children.Add(header);
        details.Children.Add(Text(UsageFormat.Complement(window, options.Display), "SecondaryTextStyle"));
        var reset = UsageFormat.Reset(window, options.Reset, fetchedAt, now, options.Use24Hour);
        if (reset.Length > 0) details.Children.Add(Text(reset, "BodyTextBlockStyle"));
        if (options.ShowPace)
        {
            var pace = UsagePace.Describe(window, fetchedAt, now);
            if (pace.Length > 0)
                details.Children.Add(Text(pace, pace.StartsWith("At this pace", StringComparison.Ordinal) ? "CriticalTextStyle" : "CaptionSecondaryTextStyle"));
        }
        if (limitReached || window.RemainingPercent == 0) details.Children.Add(Text("Limit reached", "CriticalTextStyle"));
        layout.Children.Add(details);
        var card = Card(layout, automationId);
        AutomationProperties.SetName(card, $"{title}: {UsageFormat.Percent(window, options.Display)}");
        return card;
    }

    public static string Glyph(long windowSeconds) => windowSeconds switch
    {
        <= 28_800 => "",   // Stopwatch
        <= 864_000 => "",  // Calendar
        _ => "",           // CalendarWeek / month
    };
}
