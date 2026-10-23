using CodexMeter.Linux.Widgets;

namespace CodexMeter.App.Assets;

/// <summary>
/// Monochrome provider marks drawn with Cairo in the widget's text color, so they follow light
/// and dark. The path data is shared with the Windows app (windows/tools/gen_provider_icons.py).
/// </summary>
internal static partial class ProviderIcons
{
    private static readonly Dictionary<string, PathData.Parsed> Parsed = new();

    public static bool Has(string providerId) => Paths.ContainsKey(providerId);

    /// <summary>Every mark's raw path data, for tests.</summary>
    internal static IReadOnlyDictionary<string, string> AllData => Paths.ToDictionary(p => p.Key, p => p.Value.Data);

    /// <summary>A fresh widget <paramref name="size"/> pixels square that draws the mark.</summary>
    public static Gtk.Widget Create(string providerId, int size = 16)
    {
        if (!Paths.ContainsKey(providerId))
        {
            var fallback = Gtk.Image.NewFromIconName("applications-system-symbolic");
            fallback.SetPixelSize(size);
            return fallback;
        }
        var area = Gtk.DrawingArea.New();
        area.SetContentWidth(size);
        area.SetContentHeight(size);
        area.SetValign(Gtk.Align.Center);
        area.SetHalign(Gtk.Align.Center);
        area.AddCssClass("provider-mark");
        area.SetDrawFunc((widget, cr, width, height) =>
        {
            widget.GetColor(out var color);
            cr.SetSourceRgba(color.Red, color.Green, color.Blue, color.Alpha);
            Draw(cr, providerId, 0, 0, Math.Min(width, height));
        });
        return area;
    }

    /// <summary>Fills the mark into the square at (x, y) with the current source.</summary>
    public static void Draw(Cairo.Context cr, string providerId, double x, double y, double size)
    {
        if (!Paths.TryGetValue(providerId, out var mark)) return;
        PathData.Parsed parsed;
        lock (Parsed)
        {
            if (!Parsed.TryGetValue(providerId, out parsed!))
                Parsed[providerId] = parsed = PathData.Parse(mark.Data);
        }
        var scale = size / Math.Max(mark.Width, mark.Height);
        cr.Save();
        cr.Translate(x + (size - mark.Width * scale) / 2, y + (size - mark.Height * scale) / 2);
        cr.Scale(scale, scale);
        cr.NewPath();
        PathData.Append(cr, parsed);
        cr.FillRule = parsed.EvenOdd ? Cairo.FillRule.EvenOdd : Cairo.FillRule.Winding;
        cr.Fill();
        cr.Restore();
    }
}
