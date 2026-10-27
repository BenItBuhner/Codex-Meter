using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace CodexMeter.App.Controls;

public enum MeterLevel { Normal, Caution, Critical }

/// <summary>
/// Builds the dashboard's cards from Windows 11 parts: the card recipe from the WinUI gallery
/// (layer fill, hairline stroke, 8px corners), native progress bars that turn amber (paused) and
/// red (error) as an allowance runs low, and theme brushes so light and dark both stay correct.
/// </summary>
public static class Cards
{
    public static Style Style(string key) => (Style)Application.Current.Resources[key];

    public static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    public static MeterLevel Level(int remainingPercent) =>
        remainingPercent <= 15 ? MeterLevel.Critical : remainingPercent <= 35 ? MeterLevel.Caution : MeterLevel.Normal;

    /// <summary>A card. Wide cards take a whole row in <see cref="CardPanel"/>.</summary>
    public static Border Card(UIElement child, string? automationId = null, bool wide = false)
    {
        var border = new Border { Style = Style("CardBorderStyle"), Child = child };
        if (wide) CardPanel.SetIsWide(border, true);
        if (automationId is not null) AutomationProperties.SetAutomationId(border, automationId);
        return border;
    }

    public static TextBlock Text(string text, string style, double? size = null, bool wrap = true)
    {
        var block = new TextBlock
        {
            Text = text,
            Style = Style(style),
            TextWrapping = wrap ? TextWrapping.WrapWholeWords : TextWrapping.NoWrap,
            TextTrimming = wrap ? TextTrimming.None : TextTrimming.CharacterEllipsis,
        };
        if (size is { } s) block.FontSize = s;
        return block;
    }

    /// <summary>Glyph and title row used at the top of every card.</summary>
    public static StackPanel Header(string glyph, string title)
    {
        var header = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        header.Children.Add(new FontIcon { Glyph = glyph, FontSize = 16, Foreground = Brush("TextFillColorSecondaryBrush") });
        header.Children.Add(new TextBlock
        {
            Text = title,
            Style = Style("BodyStrongTextBlockStyle"),
            TextWrapping = TextWrapping.NoWrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        return header;
    }

    /// <summary>The native determinate progress bar, amber when low and red when nearly out.</summary>
    public static ProgressBar Bar(double percent, MeterLevel level)
    {
        var bar = new ProgressBar
        {
            Minimum = 0,
            Maximum = 100,
            Value = Math.Clamp(percent, 0, 100),
            IsIndeterminate = false,
            ShowPaused = level == MeterLevel.Caution,
            ShowError = level == MeterLevel.Critical,
            MinHeight = 4,
        };
        return bar;
    }

    public sealed record MeterOptions(UsageDisplayMode Display, ResetDisplayMode Reset, bool Use24Hour, bool ShowPace);

    /// <summary>
    /// A usage window: title, the big number, a progress bar, the other half, reset time and pace.
    /// </summary>
    public static Border Meter(string title, string glyph, UsageWindow window, DateTimeOffset fetchedAt, MeterOptions options,
        bool limitReached = false, string? automationId = null)
    {
        var now = DateTimeOffset.UtcNow;
        var value = UsageFormat.Value(window, options.Display);
        var level = Level(window.RemainingPercent);
        var panel = new StackPanel { Spacing = 4 };
        panel.Children.Add(Header(glyph, title));

        var figure = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, Margin = new Thickness(0, 6, 0, 2) };
        figure.Children.Add(new TextBlock { Text = value + "%", Style = Style("TitleTextBlockStyle") });
        figure.Children.Add(new TextBlock
        {
            Text = options.Display == UsageDisplayMode.Used ? "used" : "left",
            Style = Style("SecondaryTextStyle"),
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, 0, 5),
        });
        panel.Children.Add(figure);
        var bar = Bar(value, level);
        bar.Margin = new Thickness(0, 2, 0, 8);
        AutomationProperties.SetName(bar, $"{title} {UsageFormat.Percent(window, options.Display)}");
        panel.Children.Add(bar);

        var reset = UsageFormat.Reset(window, options.Reset, fetchedAt, now, options.Use24Hour);
        var complement = UsageFormat.Complement(window, options.Display);
        panel.Children.Add(Text(reset.Length > 0 ? $"{complement} · {reset}" : complement, "CaptionSecondaryTextStyle"));
        if (options.ShowPace)
        {
            var pace = UsagePace.Describe(window, fetchedAt, now);
            if (pace.Length > 0)
            {
                var accelerated = pace.StartsWith("At this pace", StringComparison.Ordinal);
                panel.Children.Add(accelerated
                    ? Status(pace, "CaptionTextBlockStyle", "\uE7BA", "SystemFillColorCautionBrush")
                    : Status(pace, "CaptionSecondaryTextStyle", null, null));
            }
        }
        if (limitReached || window.RemainingPercent == 0) panel.Children.Add(Status("Limit reached", "CriticalTextStyle", "\uE783", "SystemFillColorCriticalBrush"));
        var card = Card(panel, automationId);
        AutomationProperties.SetName(card, $"{title}: {UsageFormat.Percent(window, options.Display)}");
        return card;
    }

    /// <summary>A short status line with an optional leading glyph.</summary>
    public static StackPanel Status(string text, string style, string? glyph, string? glyphBrush)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        var block = Text(text, style);
        if (glyph is not null)
            row.Children.Add(new FontIcon
            {
                Glyph = glyph,
                FontSize = 12,
                Foreground = glyphBrush is null ? block.Foreground : Brush(glyphBrush),
                VerticalAlignment = VerticalAlignment.Center,
            });
        row.Children.Add(block);
        return row;
    }

    /// <summary>Segoe Fluent Icons glyph for a window length.</summary>
    public static string Glyph(long windowSeconds) => windowSeconds switch
    {
        <= 28_800 => "",   // Stopwatch
        <= 864_000 => "",  // Calendar
        _ => "",           // CalendarWeek
    };
}
