using CodexMeter.App.Controls;
using CodexMeter.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;

namespace CodexMeter.App.Views;

/// <summary>
/// The "What's new" page after a big update (<see cref="WhatsNew"/>), over the whole window below
/// the title bar, as Windows apps show their first-run and update pages: the app icon and a large
/// title, each new feature beside its Fluent icon, and an accent Continue button pinned at the bottom.
/// </summary>
public sealed class WhatsNewView : UserControl
{
    public WhatsNewView(Action onDone)
    {
        AutomationProperties.SetAutomationId(this, "WhatsNew");
        var column = new StackPanel { MaxWidth = 560, Padding = new Thickness(32, 40, 32, 24), Spacing = 0 };
        column.Children.Add(new Image
        {
            Source = new BitmapImage(new Uri("ms-appx:///Assets/AppIcon.png")),
            Width = 96,
            Height = 96,
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        var title = Cards.Text(WhatsNew.Title, "TitleLargeTextBlockStyle");
        title.TextAlignment = TextAlignment.Center;
        title.HorizontalAlignment = HorizontalAlignment.Center;
        title.Margin = new Thickness(0, 20, 0, 32);
        AutomationProperties.SetAutomationId(title, "WhatsNewTitle");
        AutomationProperties.SetHeadingLevel(title, AutomationHeadingLevel.Level1);
        column.Children.Add(title);

        foreach (var feature in WhatsNew.Features(WhatsNew.Platform.Windows))
        {
            var row = new Grid { ColumnSpacing = 18, Margin = new Thickness(0, 0, 0, 22) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var icon = new FontIcon
            {
                Glyph = Glyph(feature.Id),
                FontSize = 26,
                Foreground = Cards.Brush("AccentTextFillColorPrimaryBrush"),
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, 2, 0, 0),
            };
            AutomationProperties.SetAccessibilityView(icon, AccessibilityView.Raw);
            row.Children.Add(icon);
            var text = new StackPanel { Spacing = 2 };
            text.Children.Add(Cards.Text(feature.Title, "BodyStrongTextBlockStyle"));
            text.Children.Add(Cards.Text(feature.Body, "SecondaryTextStyle"));
            Grid.SetColumn(text, 1);
            row.Children.Add(text);
            column.Children.Add(row);
        }

        var scroller = new ScrollViewer
        {
            Content = column,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalContentAlignment = HorizontalAlignment.Center,
        };
        var next = new Button
        {
            Content = WhatsNew.Button,
            Style = (Style)Application.Current.Resources["AccentButtonStyle"],
            MinWidth = 240,
            Padding = new Thickness(24, 8, 24, 8),
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        AutomationProperties.SetAutomationId(next, "WhatsNewContinue");
        next.Click += (_, _) => onDone();
        var bar = new Border
        {
            Child = next,
            Padding = new Thickness(24, 16, 24, 24),
            BorderThickness = new Thickness(0, 1, 0, 0),
            BorderBrush = Cards.Brush("CardStrokeColorDefaultBrush"),
        };

        var layout = new Grid();
        // The page covers the window, so it takes the base fill of the theme it actually shows in
        // (SolidBackgroundFillColorBase): read from ActualTheme, it follows a theme change while open.
        void Fill() => layout.Background = new SolidColorBrush(ActualTheme == ElementTheme.Dark
            ? Microsoft.UI.ColorHelper.FromArgb(255, 0x20, 0x20, 0x20)
            : Microsoft.UI.ColorHelper.FromArgb(255, 0xF3, 0xF3, 0xF3));
        ActualThemeChanged += (_, _) => Fill();
        Loaded += (_, _) => Fill();
        layout.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.Children.Add(scroller);
        Grid.SetRow(bar, 1);
        layout.Children.Add(bar);
        Content = layout;
        Loaded += (_, _) => next.Focus(FocusState.Programmatic);
    }

    /// <summary>Segoe Fluent Icons for each feature.</summary>
    public static string Glyph(string id) => id switch
    {
        "providers" => "",
        "priority" => "",
        "resets" => "",
        "live" => "",
        "alerts" => "",
        "value" => "",
        "status" => "",
        "updates" => "",
        "devices" => "",
        _ => "",
    };
}
