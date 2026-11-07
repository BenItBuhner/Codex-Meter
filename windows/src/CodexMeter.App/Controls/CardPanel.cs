using Windows.Foundation;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Controls;

/// <summary>
/// Lays cards out in as many equal columns as fit (at least <see cref="MinColumnWidth"/> wide),
/// row by row, so the dashboard reflows from one column on a narrow window to three on a wide
/// one. A card marked <see cref="IsWideProperty"/> (the usage chart) takes a row of its own.
/// </summary>
public sealed class CardPanel : Panel
{
    public static readonly DependencyProperty IsWideProperty =
        DependencyProperty.RegisterAttached("IsWide", typeof(bool), typeof(CardPanel), new PropertyMetadata(false));

    public static void SetIsWide(UIElement element, bool value) => element.SetValue(IsWideProperty, value);

    public static bool GetIsWide(UIElement element) => element.GetValue(IsWideProperty) is true;

    public double MinColumnWidth { get; set; } = 300;
    public double Spacing { get; set; } = 12;
    public int MaxColumns { get; set; } = 3;

    private int Columns(double width)
    {
        if (double.IsInfinity(width) || width <= 0) return 1;
        var narrow = Children.Count(c => !GetIsWide(c));
        var columns = (int)Math.Floor((width + Spacing) / (MinColumnWidth + Spacing));
        return Math.Clamp(columns, 1, Math.Max(1, Math.Min(MaxColumns, Math.Max(1, narrow))));
    }

    private double ColumnWidth(double width, int columns) =>
        double.IsInfinity(width) ? MinColumnWidth : Math.Max(0, (width - Spacing * (columns - 1)) / columns);

    /// <summary>Rows of child indexes: wide cards alone, the rest filled left to right.</summary>
    private List<List<int>> Rows(int columns)
    {
        var rows = new List<List<int>>();
        List<int>? current = null;
        for (var i = 0; i < Children.Count; i++)
        {
            if (Children[i].Visibility == Visibility.Collapsed) continue;
            if (GetIsWide(Children[i]))
            {
                rows.Add([i]);
                current = null;
                continue;
            }
            if (current is null || current.Count == columns)
            {
                current = [];
                rows.Add(current);
            }
            current.Add(i);
        }
        return rows;
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        var columns = Columns(availableSize.Width);
        var columnWidth = ColumnWidth(availableSize.Width, columns);
        var fullWidth = double.IsInfinity(availableSize.Width) ? columnWidth * columns + Spacing * (columns - 1) : availableSize.Width;
        double height = 0;
        var rows = Rows(columns);
        foreach (var row in rows)
        {
            double rowHeight = 0;
            foreach (var index in row)
            {
                var child = Children[index];
                child.Measure(new Size(GetIsWide(child) ? fullWidth : columnWidth, double.PositiveInfinity));
                rowHeight = Math.Max(rowHeight, child.DesiredSize.Height);
            }
            height += rowHeight;
        }
        if (rows.Count > 1) height += Spacing * (rows.Count - 1);
        foreach (var child in Children.Where(c => c.Visibility == Visibility.Collapsed)) child.Measure(new Size(0, 0));
        return new Size(fullWidth, height);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        var columns = Columns(finalSize.Width);
        var columnWidth = ColumnWidth(finalSize.Width, columns);
        double y = 0;
        foreach (var row in Rows(columns))
        {
            var rowHeight = row.Max(i => Children[i].DesiredSize.Height);
            for (var j = 0; j < row.Count; j++)
            {
                var child = Children[row[j]];
                if (GetIsWide(child)) child.Arrange(new Rect(0, y, finalSize.Width, rowHeight));
                else child.Arrange(new Rect(j * (columnWidth + Spacing), y, columnWidth, rowHeight));
            }
            y += rowHeight + Spacing;
        }
        foreach (var child in Children.Where(c => c.Visibility == Visibility.Collapsed)) child.Arrange(new Rect(0, 0, 0, 0));
        return finalSize;
    }
}
