using Windows.Foundation;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Controls;

/// <summary>
/// Lays cards out in as many equal columns as fit (at least <see cref="MinColumnWidth"/> wide),
/// row by row, so the dashboard reflows from one column on a narrow window to three on a wide one.
/// </summary>
public sealed class CardPanel : Panel
{
    public double MinColumnWidth { get; set; } = 340;
    public double Spacing { get; set; } = 12;
    public int MaxColumns { get; set; } = 3;

    private int Columns(double width)
    {
        if (double.IsInfinity(width) || width <= 0) return 1;
        var columns = (int)Math.Floor((width + Spacing) / (MinColumnWidth + Spacing));
        return Math.Clamp(columns, 1, Math.Max(1, Math.Min(MaxColumns, Children.Count)));
    }

    private double ColumnWidth(double width, int columns) =>
        double.IsInfinity(width) ? MinColumnWidth : Math.Max(0, (width - Spacing * (columns - 1)) / columns);

    protected override Size MeasureOverride(Size availableSize)
    {
        var columns = Columns(availableSize.Width);
        var columnWidth = ColumnWidth(availableSize.Width, columns);
        double height = 0, rowHeight = 0;
        for (var i = 0; i < Children.Count; i++)
        {
            var child = Children[i];
            child.Measure(new Size(columnWidth, double.PositiveInfinity));
            rowHeight = Math.Max(rowHeight, child.DesiredSize.Height);
            if (i % columns == columns - 1 || i == Children.Count - 1)
            {
                height += rowHeight + (i < Children.Count - 1 ? Spacing : 0);
                rowHeight = 0;
            }
        }
        var width = double.IsInfinity(availableSize.Width) ? columnWidth * columns + Spacing * (columns - 1) : availableSize.Width;
        return new Size(width, height);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        var columns = Columns(finalSize.Width);
        var columnWidth = ColumnWidth(finalSize.Width, columns);
        double y = 0;
        for (var start = 0; start < Children.Count; start += columns)
        {
            var count = Math.Min(columns, Children.Count - start);
            double rowHeight = 0;
            for (var j = 0; j < count; j++) rowHeight = Math.Max(rowHeight, Children[start + j].DesiredSize.Height);
            for (var j = 0; j < count; j++)
                Children[start + j].Arrange(new Rect(j * (columnWidth + Spacing), y, columnWidth, rowHeight));
            y += rowHeight + Spacing;
        }
        return finalSize;
    }
}
