using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;

namespace CodexMeter.Linux.Widgets;

public enum MeterLevel { Normal, Caution, Critical }

/// <summary>
/// Builds the dashboard's cards from libadwaita style classes (<c>.card</c>, <c>.title-*</c>,
/// <c>.dim-label</c>…) and colors from the theme, so light, dark and high contrast stay right.
/// </summary>
public static class Cards
{
    public static MeterLevel Level(int remainingPercent) =>
        remainingPercent <= 15 ? MeterLevel.Critical : remainingPercent <= 35 ? MeterLevel.Caution : MeterLevel.Normal;

    public static string LevelClass(MeterLevel level) => level switch
    {
        MeterLevel.Critical => "level-critical",
        MeterLevel.Caution => "level-caution",
        _ => "level-normal",
    };

    public static Gtk.Label Label(string text, params string[] classes)
    {
        var label = Gtk.Label.New(text);
        label.SetXalign(0);
        label.SetWrap(true);
        label.SetWrapMode(Pango.WrapMode.WordChar);
        // Keeps the natural width modest so two meter cards fit side by side; wrapping still fills the card.
        label.SetMaxWidthChars(18);
        foreach (var css in classes) label.AddCssClass(css);
        return label;
    }

    public static Gtk.Box Card(Gtk.Widget child, string? name = null)
    {
        var card = Gtk.Box.New(Gtk.Orientation.Vertical, 0);
        card.AddCssClass("card");
        card.AddCssClass("meter-card");
        card.Append(child);
        if (name is not null) card.SetName(name);
        return card;
    }

    /// <summary>A ring that fills clockwise from the top, drawn in the level's theme color.</summary>
    public static Gtk.Widget Ring(double fraction, MeterLevel level, string center, string caption, int size = 92)
    {
        var overlay = Gtk.Overlay.New();
        var area = Gtk.DrawingArea.New();
        area.SetContentWidth(size);
        area.SetContentHeight(size);
        area.AddCssClass("meter-ring");
        area.AddCssClass(LevelClass(level));
        fraction = Math.Clamp(fraction, 0, 1);
        area.SetDrawFunc((widget, cr, width, height) =>
        {
            const double thickness = 9;
            var radius = (Math.Min(width, height) - thickness) / 2;
            var cx = width / 2.0;
            var cy = height / 2.0;
            widget.GetColor(out var color);
            cr.LineWidth = thickness;
            cr.SetSourceRgba(color.Red, color.Green, color.Blue, 0.16);
            cr.Arc(cx, cy, radius, 0, 2 * Math.PI);
            cr.Stroke();
            if (fraction <= 0) return;
            cr.LineCap = Cairo.LineCap.Round;
            cr.SetSourceRgba(color.Red, color.Green, color.Blue, color.Alpha);
            var start = -Math.PI / 2;
            cr.Arc(cx, cy, radius, start, start + 2 * Math.PI * Math.Min(fraction, 0.9999));
            cr.Stroke();
        });
        overlay.SetChild(area);
        var labels = Gtk.Box.New(Gtk.Orientation.Vertical, 0);
        labels.SetHalign(Gtk.Align.Center);
        labels.SetValign(Gtk.Align.Center);
        var value = Gtk.Label.New(center);
        value.AddCssClass("title-2");
        value.AddCssClass("numeric");
        labels.Append(value);
        var small = Gtk.Label.New(caption);
        small.AddCssClass("caption");
        small.AddCssClass("dim-label");
        labels.Append(small);
        overlay.AddOverlay(labels);
        overlay.SetValign(Gtk.Align.Center);
        return overlay;
    }

    public sealed record MeterOptions(UsageDisplayMode Display, ResetDisplayMode Reset, bool Use24Hour, bool ShowPace);

    /// <summary>The main meter card: ring, title, value, the other half, reset time and pace.</summary>
    public static Gtk.Widget Meter(string title, string icon, UsageWindow window, DateTimeOffset fetchedAt, MeterOptions options,
        bool limitReached = false, string? name = null)
    {
        var now = DateTimeOffset.UtcNow;
        var value = UsageFormat.Value(window, options.Display);
        var level = Level(window.RemainingPercent);
        var layout = Gtk.Box.New(Gtk.Orientation.Horizontal, 20);
        layout.Append(Ring(value / 100.0, level, value.ToString(), options.Display == UsageDisplayMode.Used ? "% used" : "% left"));

        var details = Gtk.Box.New(Gtk.Orientation.Vertical, 3);
        details.SetValign(Gtk.Align.Center);
        details.SetHexpand(true);
        var header = Gtk.Box.New(Gtk.Orientation.Horizontal, 8);
        var image = Gtk.Image.NewFromIconName(icon);
        header.Append(image);
        var heading = Label(title, "heading");
        heading.SetHexpand(true);
        header.Append(heading);
        header.SetMarginBottom(2);
        details.Append(header);
        details.Append(Label(UsageFormat.Complement(window, options.Display), "dim-label"));
        var reset = UsageFormat.Reset(window, options.Reset, fetchedAt, now, options.Use24Hour);
        if (reset.Length > 0) details.Append(Label(reset));
        if (options.ShowPace)
        {
            var pace = UsagePace.Describe(window, fetchedAt, now);
            if (pace.Length > 0)
                details.Append(pace.StartsWith("At this pace", StringComparison.Ordinal)
                    ? Label(pace, "caption", "error", "pace-warning")
                    : Label(pace, "caption", "dim-label"));
        }
        if (limitReached || window.RemainingPercent == 0) details.Append(Label("Limit reached", "caption-heading", "error"));
        layout.Append(details);
        var card = Card(layout, name);
        card.SetTooltipText($"{title}: {UsageFormat.Percent(window, options.Display)}");
        return card;
    }

    /// <summary>Adwaita symbolic icons for the 5-hour, weekly and monthly windows.</summary>
    public static string Icon(long windowSeconds) => windowSeconds switch
    {
        <= 28_800 => "preferences-system-time-symbolic",
        <= 864_000 => "x-office-calendar-symbolic",
        _ => "view-grid-symbolic",
    };
}
