using System.Globalization;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;

namespace CodexMeter.Linux.Widgets;

/// <summary>
/// The usage history card, like the phone and Windows apps: a chart of the current window against
/// your typical path, a 5-hour / weekly / monthly switcher, highlights and previous windows.
/// The chart is drawn with Cairo in the theme's accent and text colors.
/// </summary>
public sealed class HistoryCard
{
    private readonly ProviderHistory history;
    private readonly IReadOnlyList<string> overrides;
    private readonly bool use24Hour;
    private readonly Gtk.DrawingArea chart;
    private readonly Gtk.Box details;
    private UsageHistory selected;
    private double? hoverX;
    private (double X, double Y, double Width, double Height) plot;

    public HistoryCard(ProviderHistory history, IReadOnlyList<string> overrides, bool use24Hour,
        Action<string, bool> setHighlight, Action clear)
    {
        this.history = history;
        this.overrides = overrides;
        this.use24Hour = use24Hour;
        var kinds = history.Recorded;
        selected = kinds.FirstOrDefault() ?? history.FiveHour;

        var root = Gtk.Box.New(Gtk.Orientation.Vertical, 12);
        var top = Gtk.Box.New(Gtk.Orientation.Horizontal, 8);
        var icon = Gtk.Image.NewFromIconName("document-open-recent-symbolic");
        top.Append(icon);
        var heading = Cards.Label("Usage history", "heading");
        heading.SetHexpand(true);
        heading.SetValign(Gtk.Align.Center);
        top.Append(heading);

        if (kinds.Count > 1)
        {
            // A linked row of toggle buttons: GNOME's segmented control.
            var group = Gtk.Box.New(Gtk.Orientation.Horizontal, 0);
            group.AddCssClass("linked");
            group.SetValign(Gtk.Align.Center);
            group.SetName("HistoryKinds");
            Gtk.ToggleButton? first = null;
            foreach (var kind in kinds)
            {
                var button = Gtk.ToggleButton.NewWithLabel(KindName(kind.Kind));
                button.SetName("HistoryKind_" + kind.Kind);
                if (first is null) first = button;
                else button.SetGroup(first);
                button.SetActive(kind.Kind == selected.Kind);
                var value = kind;
                button.OnToggled += (_, _) =>
                {
                    if (!button.GetActive() || selected.Kind == value.Kind) return;
                    selected = value;
                    Render();
                };
                group.Append(button);
            }
            top.Append(group);
        }

        var menu = Gtk.MenuButton.New();
        menu.SetIconName("view-more-symbolic");
        menu.AddCssClass("flat");
        menu.SetValign(Gtk.Align.Center);
        menu.SetTooltipText("History Options");
        menu.SetName("HistoryOptions");
        var popoverBox = Gtk.Box.New(Gtk.Orientation.Vertical, 2);
        popoverBox.SetMarginTop(6);
        popoverBox.SetMarginBottom(6);
        popoverBox.SetMarginStart(6);
        popoverBox.SetMarginEnd(6);
        foreach (var key in HistorySections.All)
        {
            var check = Gtk.CheckButton.NewWithLabel(HistorySections.Label(key));
            check.SetActive(HistorySections.IsVisible(overrides, key));
            check.SetName("Highlight_" + key);
            var value = key;
            check.OnToggled += (_, _) => setHighlight(value, check.GetActive());
            popoverBox.Append(check);
        }
        popoverBox.Append(Gtk.Separator.New(Gtk.Orientation.Horizontal));
        var popover = Gtk.Popover.New();
        var clearButton = Gtk.Button.NewWithLabel("Clear History…");
        clearButton.AddCssClass("flat");
        clearButton.AddCssClass("destructive-action");
        clearButton.OnClicked += (_, _) =>
        {
            popover.Popdown();
            clear();
        };
        popoverBox.Append(clearButton);
        popover.SetChild(popoverBox);
        menu.SetPopover(popover);
        top.Append(menu);
        root.Append(top);

        chart = Gtk.DrawingArea.New();
        chart.SetContentHeight(200);
        chart.SetHexpand(true);
        chart.AddCssClass("history-chart");
        chart.SetName("HistoryChart");
        chart.SetDrawFunc((widget, cr, width, height) => Draw(widget, cr, width, height));
        var motion = Gtk.EventControllerMotion.New();
        motion.OnMotion += (_, args) =>
        {
            hoverX = args.X;
            chart.QueueDraw();
        };
        motion.OnLeave += (_, _) =>
        {
            hoverX = null;
            chart.QueueDraw();
        };
        chart.AddController(motion);
        root.Append(chart);

        details = Gtk.Box.New(Gtk.Orientation.Vertical, 14);
        root.Append(details);
        Root = Cards.Card(root, "HistoryCard");
        Root.RemoveCssClass("meter-card");
        Root.AddCssClass("extra-card");
        Render();
    }

    public Gtk.Box Root { get; }

    private static string KindName(UsageHistoryKind kind) => kind switch
    {
        UsageHistoryKind.FiveHour => "5-hour",
        UsageHistoryKind.Weekly => "Weekly",
        _ => "Monthly",
    };

    private bool Shows(string key) => HistorySections.IsVisible(overrides, key);

    private void Render()
    {
        chart.QueueDraw();
        while (details.GetFirstChild() is { } child) details.Remove(child);
        if (selected.Samples.Count == 0)
        {
            details.Append(Wide("History builds up as Codex Meter checks your usage. Leave it running in the background to see trends here.", "dim-label"));
            return;
        }
        var now = DateTimeOffset.UtcNow;
        var latest = selected.CurrentWindowSamples[^1];

        var stats = Gtk.FlowBox.New();
        stats.SetSelectionMode(Gtk.SelectionMode.None);
        stats.SetHomogeneous(true);
        stats.SetMinChildrenPerLine(2);
        stats.SetMaxChildrenPerLine(4);
        stats.SetColumnSpacing(16);
        stats.SetRowSpacing(12);
        stats.SetName("HistoryStats");
        void Add(Gtk.Widget stat)
        {
            var child = Gtk.FlowBoxChild.New();
            child.SetChild(stat);
            child.SetFocusable(false);
            stats.Append(child);
        }
        if (Shows(HistorySections.InsightPace))
        {
            var fraction = (now - latest.WindowStart).TotalSeconds / latest.WindowSeconds;
            if (UsageStats.TypicalUsedPercent(selected, fraction) is { } usual)
            {
                var difference = latest.UsedPercent - usual;
                Add(Stat("Pace vs. typical",
                    Math.Abs(difference) < 3 ? "On your usual pace" : $"{Math.Abs(difference):0} points {(difference > 0 ? "ahead" : "behind")}",
                    $"Usually {usual:0}% used by now"));
            }
            else Add(Stat("Pace vs. typical", "Not enough history", "Shows after a full window"));
        }
        if (Shows(HistorySections.InsightExhaustion))
        {
            Add(UsageStats.ProjectedExhaustion(selected, now) is { } at
                ? Stat("Projected run-out", "In " + UsageFormat.Duration(at - now), "Before the window resets")
                : Stat("Projected run-out", selected.ObservedBurnRate > 0 ? "Lasts until reset" : "No trend yet", "At the recent burn rate"));
        }
        if (Shows(HistorySections.InsightAverage))
        {
            var average = UsageStats.AverageFinalPercent(selected);
            Add(Stat("Average window", average is { } a ? $"{a:0}% used" : "—",
                $"{selected.CompletedWindowCount} finished window{(selected.CompletedWindowCount == 1 ? "" : "s")}"));
        }
        if (Shows(HistorySections.InsightPeak))
        {
            var peak = UsageStats.PeakBurnPercentPerHour(selected);
            Add(Stat("Peak burn rate", peak > 0 ? UsageStats.FormatRate(peak) : "—", "Fastest stretch recorded"));
        }
        if (stats.GetFirstChild() is not null) details.Append(stats);

        if (Shows(HistorySections.WindowList))
        {
            var windows = UsageStats.Breakdown(selected, 7).Where(w => w.Complete).Reverse().ToList();
            if (windows.Count > 0)
            {
                var list = Gtk.Box.New(Gtk.Orientation.Vertical, 6);
                list.SetName("PreviousWindows");
                list.Append(Cards.Label("Previous windows", "heading"));
                foreach (var window in windows)
                {
                    var row = Gtk.Box.New(Gtk.Orientation.Horizontal, 12);
                    var range = Gtk.Label.New(Range(window.WindowStart, window.ResetAt));
                    range.SetXalign(0);
                    range.SetHexpand(true);
                    range.SetEllipsize(Pango.EllipsizeMode.End);
                    range.AddCssClass("caption");
                    range.AddCssClass("dim-label");
                    row.Append(range);
                    var bar = Gtk.ProgressBar.New();
                    bar.SetFraction(Math.Clamp(window.FinalPercent / 100.0, 0, 1));
                    bar.SetSizeRequest(140, -1);
                    bar.SetValign(Gtk.Align.Center);
                    bar.AddCssClass("history-bar");
                    bar.AddCssClass(Cards.LevelClass(Cards.Level(100 - window.FinalPercent)));
                    row.Append(bar);
                    var value = Gtk.Label.New(window.FinalPercent + "%");
                    value.SetXalign(1);
                    value.SetSizeRequest(44, -1);
                    value.AddCssClass("caption");
                    value.AddCssClass("numeric");
                    row.Append(value);
                    list.Append(row);
                }
                details.Append(list);
            }
        }

        if (Shows(HistorySections.Guide))
        {
            details.Append(Wide("The solid line is this window's usage as Codex Meter saw it. The dashed line is your typical path, " +
                "averaged over finished windows. Point at the chart to read a moment. History stays on this computer.", "caption", "dim-label"));
        }
    }

    private static Gtk.Label Wide(string text, params string[] classes)
    {
        var label = Cards.Label(text, classes);
        label.SetMaxWidthChars(90);
        return label;
    }

    private static Gtk.Widget Stat(string label, string value, string detail)
    {
        var box = Gtk.Box.New(Gtk.Orientation.Vertical, 1);
        box.Append(Cards.Label(label, "caption", "dim-label"));
        box.Append(Cards.Label(value, "heading"));
        box.Append(Cards.Label(detail, "caption", "dim-label"));
        return box;
    }

    private string Range(DateTimeOffset start, DateTimeOffset end) => selected.Kind == UsageHistoryKind.FiveHour
        ? $"{Local(start).ToString("MMM d", CultureInfo.InvariantCulture)}, {Time(start)} – {Time(end)}"
        : $"{Local(start).ToString("MMM d", CultureInfo.InvariantCulture)} – {Local(end).ToString("MMM d", CultureInfo.InvariantCulture)}";

    private static DateTime Local(DateTimeOffset value) => TimeZoneInfo.ConvertTime(value, TimeZoneInfo.Local).DateTime;

    private string Time(DateTimeOffset value) => Local(value).ToString(use24Hour ? "HH:mm" : "h:mm tt", CultureInfo.InvariantCulture);

    private string AxisLabel(DateTimeOffset value) => selected.Kind switch
    {
        UsageHistoryKind.FiveHour => Time(value),
        UsageHistoryKind.Weekly => Local(value).ToString("ddd", CultureInfo.InvariantCulture),
        _ => Local(value).ToString("MMM d", CultureInfo.InvariantCulture),
    };

    private double X(DateTimeOffset at, UsageSample reference) =>
        plot.X + Math.Clamp((at - reference.WindowStart).TotalSeconds / reference.WindowSeconds, 0, 1) * plot.Width;

    private double Y(double percent) => plot.Y + plot.Height - Math.Clamp(percent, 0, 100) / 100 * plot.Height;

    private void Draw(Gtk.DrawingArea widget, Cairo.Context cr, int width, int height)
    {
        // The area's own color is the accent (see Style); text and grid use the card's text color.
        widget.GetColor(out var accent);
        var fg = accent;
        widget.GetParent()?.GetColor(out fg);
        plot = (40, 8, Math.Max(10, width - 48), Math.Max(10, height - 32));
        var right = plot.X + plot.Width;
        var bottom = plot.Y + plot.Height;

        cr.LineWidth = 1;
        foreach (var percent in new[] { 0, 25, 50, 75, 100 })
        {
            var y = Math.Round(Y(percent)) + 0.5;
            cr.SetSourceRgba(fg.Red, fg.Green, fg.Blue, 0.1);
            cr.MoveTo(plot.X, y);
            cr.LineTo(right, y);
            cr.Stroke();
            if (percent % 50 == 0) Text(widget, cr, fg, percent + "%", 0, y - 8, 0);
        }

        var current = selected.CurrentWindowSamples;
        if (current.Count == 0) return;
        var reference = current[^1];
        var now = DateTimeOffset.UtcNow;

        // Typical path across the whole window, from finished windows.
        var typical = new List<(double X, double Y)>();
        for (var step = 0; step <= 60; step++)
        {
            if (UsageStats.TypicalUsedPercent(selected, step / 60.0) is not { } value) break;
            typical.Add((plot.X + step / 60.0 * plot.Width, Y(value)));
        }
        if (typical.Count > 1)
        {
            cr.SetSourceRgba(fg.Red, fg.Green, fg.Blue, 0.45);
            cr.LineWidth = 1.5;
            cr.SetDash([4, 3], 0);
            Polyline(cr, typical);
            cr.Stroke();
            cr.SetDash([], 0);
        }

        // This window: a filled area under an accent line.
        var line = current.Select(s => (X: X(s.ObservedAt, reference), Y: Y(s.UsedPercent))).ToList();
        if (line.Count == 1) line.Insert(0, (X(reference.WindowStart, reference), Y(0)));
        cr.MoveTo(line[0].X, Y(0));
        foreach (var point in line) cr.LineTo(point.X, point.Y);
        cr.LineTo(line[^1].X, Y(0));
        cr.ClosePath();
        cr.SetSourceRgba(accent.Red, accent.Green, accent.Blue, 0.16);
        cr.Fill();
        cr.SetSourceRgba(accent.Red, accent.Green, accent.Blue, 1);
        cr.LineWidth = 2.5;
        cr.LineJoin = Cairo.LineJoin.Round;
        cr.LineCap = Cairo.LineCap.Round;
        Polyline(cr, line);
        cr.Stroke();
        cr.Arc(line[^1].X, line[^1].Y, 4.5, 0, 2 * Math.PI);
        cr.Fill();

        // Axis: window start, now, reset.
        Text(widget, cr, fg, AxisLabel(reference.WindowStart), plot.X, bottom + 6, 0);
        Text(widget, cr, fg, AxisLabel(reference.ResetAt), right, bottom + 6, 1);
        var nowX = X(now, reference);
        if (nowX > plot.X + 60 && nowX < right - 60)
        {
            cr.SetSourceRgba(fg.Red, fg.Green, fg.Blue, 0.4);
            cr.LineWidth = 1;
            cr.SetDash([2, 3], 0);
            cr.MoveTo(Math.Round(nowX) + 0.5, plot.Y);
            cr.LineTo(Math.Round(nowX) + 0.5, bottom);
            cr.Stroke();
            cr.SetDash([], 0);
            Text(widget, cr, fg, "Now", nowX, bottom + 6, 0.5);
        }

        if (hoverX is { } hx && hx >= plot.X && hx <= right) DrawHover(widget, cr, fg, hx, reference, current, width);
    }

    private void DrawHover(Gtk.Widget widget, Cairo.Context cr, Gdk.RGBA fg, double x, UsageSample reference,
        IReadOnlyList<UsageSample> current, int width)
    {
        var at = reference.WindowStart.AddSeconds((x - plot.X) / plot.Width * reference.WindowSeconds);
        var value = UsageStats.UsedPercentAt(at, current);
        var typical = UsageStats.TypicalUsedPercent(selected, (x - plot.X) / plot.Width);
        if (value is null && typical is null) return;
        var parts = new List<string>
        {
            selected.Kind == UsageHistoryKind.FiveHour ? Time(at) : $"{Local(at).ToString("ddd", CultureInfo.InvariantCulture)} {Time(at)}",
        };
        if (value is { } v) parts.Add($"{v:0}% used");
        if (typical is { } t) parts.Add($"typical {t:0}%");

        cr.SetSourceRgba(fg.Red, fg.Green, fg.Blue, 0.5);
        cr.LineWidth = 1;
        cr.SetDash([2, 2], 0);
        cr.MoveTo(Math.Round(x) + 0.5, plot.Y);
        cr.LineTo(Math.Round(x) + 0.5, plot.Y + plot.Height);
        cr.Stroke();
        cr.SetDash([], 0);

        var layout = widget.CreatePangoLayout(string.Join(" · ", parts));
        layout.GetPixelSize(out var tw, out var th);
        var left = Math.Clamp(x + 10, 0, Math.Max(0, width - tw - 16));
        // A small rounded tooltip in the inverse of the text color, like GTK's own tooltips.
        RoundedRect(cr, left, plot.Y, tw + 16, th + 8, 6);
        cr.SetSourceRgba(fg.Red, fg.Green, fg.Blue, 0.88);
        cr.Fill();
        var inverse = fg.Red + fg.Green + fg.Blue > 1.5 ? 0.0 : 1.0;
        cr.SetSourceRgba(inverse, inverse, inverse, 1);
        cr.MoveTo(left + 8, plot.Y + 4);
        PangoCairo.Functions.ShowLayout(cr, layout);
    }

    private static void Polyline(Cairo.Context cr, IReadOnlyList<(double X, double Y)> points)
    {
        cr.MoveTo(points[0].X, points[0].Y);
        foreach (var point in points.Skip(1)) cr.LineTo(point.X, point.Y);
    }

    private static void RoundedRect(Cairo.Context cr, double x, double y, double w, double h, double r)
    {
        cr.NewSubPath();
        cr.Arc(x + w - r, y + r, r, -Math.PI / 2, 0);
        cr.Arc(x + w - r, y + h - r, r, 0, Math.PI / 2);
        cr.Arc(x + r, y + h - r, r, Math.PI / 2, Math.PI);
        cr.Arc(x + r, y + r, r, Math.PI, 3 * Math.PI / 2);
        cr.ClosePath();
    }

    /// <summary>Small caption text; <paramref name="align"/> 0 = left edge at x, 0.5 = centered, 1 = right edge.</summary>
    private static void Text(Gtk.Widget widget, Cairo.Context cr, Gdk.RGBA fg, string text, double x, double y, double align)
    {
        var layout = widget.CreatePangoLayout(text);
        var font = Pango.FontDescription.FromString("9");
        layout.SetFontDescription(font);
        layout.GetPixelSize(out var w, out _);
        cr.SetSourceRgba(fg.Red, fg.Green, fg.Blue, 0.6);
        cr.MoveTo(x - w * align, y);
        PangoCairo.Functions.ShowLayout(cr, layout);
    }
}
