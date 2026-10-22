using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.Media;

namespace CodexMeter.App.Assets;

/// <summary>Monochrome provider marks (the same ones the Android tabs use) as XAML icons.</summary>
internal static partial class ProviderIcons
{
    /// <summary>A fresh icon <paramref name="size"/> pixels square that follows the text color.</summary>
    public static IconElement Create(string providerId, double size = 16)
    {
        if (!Paths.TryGetValue(providerId, out var mark))
            return new FontIcon { Glyph = "", FontSize = size };
        var geometry = (Geometry)XamlBindingHelper.ConvertValue(typeof(Geometry), mark.Data);
        var scale = size / Math.Max(mark.Width, mark.Height);
        geometry.Transform = new ScaleTransform { ScaleX = scale, ScaleY = scale };
        return new PathIcon
        {
            Data = geometry,
            Width = size,
            Height = size,
            HorizontalAlignment = Microsoft.UI.Xaml.HorizontalAlignment.Center,
            VerticalAlignment = Microsoft.UI.Xaml.VerticalAlignment.Center,
        };
    }

    public static bool Has(string providerId) => Paths.ContainsKey(providerId);
}
