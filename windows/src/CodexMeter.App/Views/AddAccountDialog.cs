using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.Core.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;

namespace CodexMeter.App.Views;

/// <summary>
/// Accounts → Add account: every provider not yet connected, searchable by name, company, what it
/// meters or how it signs in, like the phone apps' picker. Picking one starts its sign-in.
/// </summary>
public static class AddAccountDialog
{
    /// <summary>Shows the picker; returns the chosen provider, or null.</summary>
    public static async Task<UsageProvider?> PickAsync(XamlRoot root, Action<ContentDialog>? opened = null)
    {
        var state = App.Current!.State;
        var connected = state.SignedIn;
        UsageProvider? chosen = null;

        var search = new AutoSuggestBox
        {
            PlaceholderText = "Search by name, company or sign-in",
            QueryIcon = new SymbolIcon(Symbol.Find),
        };
        AutomationProperties.SetAutomationId(search, "AddAccountSearch");
        AutomationProperties.SetName(search, "Search services");

        var list = new ListView
        {
            SelectionMode = ListViewSelectionMode.None,
            IsItemClickEnabled = true,
            Height = 380,
            Margin = new Thickness(-12, 0, -12, 0),
        };
        AutomationProperties.SetAutomationId(list, "AddAccountList");
        var empty = new TextBlock
        {
            Text = "No services match. Try a company name like GitHub or Moonshot.",
            Style = Cards.Style("SecondaryTextStyle"),
            Visibility = Visibility.Collapsed,
            Margin = new Thickness(0, 12, 0, 0),
        };

        var body = new StackPanel { Spacing = 12, Width = 460 };
        body.Children.Add(search);
        body.Children.Add(list);
        body.Children.Add(empty);

        var dialog = SignInFlow.Dialog(root, "Add account", body, "AddAccountDialog");

        void Fill()
        {
            list.Items.Clear();
            var providers = UsageProvider.PickerProviders(connected, search.Text).Where(state.Supports).ToList();
            foreach (var provider in providers) list.Items.Add(Row(provider));
            empty.Visibility = providers.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        search.TextChanged += (_, args) =>
        {
            if (args.Reason == AutoSuggestionBoxTextChangeReason.UserInput) Fill();
        };
        search.QuerySubmitted += (_, _) =>
        {
            Fill();
            if (list.Items.Count == 1 && list.Items[0] is FrameworkElement { Tag: UsageProvider only })
            {
                chosen = only;
                dialog.Hide();
            }
        };
        list.ItemClick += (_, args) =>
        {
            if (args.ClickedItem is FrameworkElement { Tag: UsageProvider provider })
            {
                chosen = provider;
                dialog.Hide();
            }
        };
        Fill();
        dialog.Opened += (_, _) =>
        {
            search.Focus(FocusState.Programmatic);
            opened?.Invoke(dialog);
        };
        await dialog.ShowAsync();
        return chosen;
    }

    private static Grid Row(UsageProvider provider)
    {
        var row = new Grid { ColumnSpacing = 14, Padding = new Thickness(0, 8, 0, 8), Tag = provider };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var name = new TextBlock { Text = provider.DisplayName, Style = Cards.Style("BodyStrongTextBlockStyle") };
        var icon = ProviderIcons.Create(provider.Id, 24);
        // List items don't pass their text color down to path icons, so follow the name's color.
        icon.SetBinding(IconElement.ForegroundProperty, new Binding { Source = name, Path = new PropertyPath("Foreground") });
        row.Children.Add(new Border { Width = 28, Height = 28, Child = icon, VerticalAlignment = VerticalAlignment.Center });

        var text = new StackPanel { Spacing = 1, VerticalAlignment = VerticalAlignment.Center };
        var title = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        title.Children.Add(name);
        title.Children.Add(new TextBlock { Text = provider.VendorName == provider.DisplayName ? "" : provider.VendorName, Style = Cards.Style("CaptionSecondaryTextStyle"), VerticalAlignment = VerticalAlignment.Center });
        text.Children.Add(title);
        text.Children.Add(new TextBlock
        {
            Text = provider.PickerSummary,
            Style = Cards.Style("CaptionSecondaryTextStyle"),
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        Grid.SetColumn(text, 1);
        row.Children.Add(text);

        if (provider.Inferred)
        {
            var tag = new Border
            {
                Background = Cards.Brush("SystemFillColorAttentionBackgroundBrush"),
                BorderBrush = Cards.Brush("CardStrokeColorDefaultBrush"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(4),
                Padding = new Thickness(6, 1, 6, 2),
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock { Text = "Unconfirmed", Style = Cards.Style("CaptionTextBlockStyle") },
            };
            ToolTipService.SetToolTip(tag, "The sign-in follows this provider's own tools but hasn't been confirmed with a real account yet.");
            Grid.SetColumn(tag, 2);
            row.Children.Add(tag);
        }
        AutomationProperties.SetAutomationId(row, "Pick_" + provider.Id);
        AutomationProperties.SetName(row, $"{provider.DisplayName}, {provider.PickerSummary}");
        return row;
    }
}
