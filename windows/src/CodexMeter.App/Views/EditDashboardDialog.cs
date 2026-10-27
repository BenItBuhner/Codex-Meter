using System.Collections.ObjectModel;
using CodexMeter.App.Controls;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace CodexMeter.App.Views;

/// <summary>
/// Edit dashboard: drag cards into a new order and switch them on or off, per provider, like the
/// phone apps. Reset puts the default order back and shows every card.
/// </summary>
public static class EditDashboardDialog
{
    private sealed record Section(string Key, string Label)
    {
        public bool Visible { get; set; } = true;
    }

    public static async Task ShowAsync(UsageProvider provider, XamlRoot root, Action<ContentDialog>? opened = null)
    {
        var state = App.Current!.State;
        var entry = state[provider];
        var settings = state.Settings.Current;
        var available = DashboardSections.Available(entry.Usage, state.HasResetCredits(provider));
        var hidden = settings.HiddenSectionsFor(provider.Id).ToHashSet();
        var sections = DashboardSections.ResolveOrder(settings.SectionOrderFor(provider.Id), available)
            .Select(key => new Section(key, DashboardSections.Label(key, entry.Usage)) { Visible = !hidden.Contains(key) })
            .ToList();

        var rows = new ObservableCollection<FrameworkElement>();
        var list = new ListView
        {
            ItemsSource = rows,
            SelectionMode = ListViewSelectionMode.None,
            CanReorderItems = true,
            CanDragItems = true,
            AllowDrop = true,
            MaxHeight = 420,
            Margin = new Thickness(-12, 0, -12, 0),
        };
        AutomationProperties.SetAutomationId(list, "EditDashboardList");

        void Fill()
        {
            rows.Clear();
            foreach (var section in sections) rows.Add(Row(section));
        }

        FrameworkElement Row(Section section)
        {
            var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 4, 0, 4), Tag = section };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Children.Add(new FontIcon { Glyph = "", FontSize = 14, Foreground = Cards.Brush("TextFillColorTertiaryBrush"), VerticalAlignment = VerticalAlignment.Center });
            var check = new CheckBox { Content = section.Label, IsChecked = section.Visible, MinWidth = 0 };
            AutomationProperties.SetAutomationId(check, "Section_" + section.Key);
            check.Checked += (_, _) => section.Visible = true;
            check.Unchecked += (_, _) => section.Visible = false;
            Grid.SetColumn(check, 1);
            row.Children.Add(check);
            var up = MoveButton("", $"Move {section.Label} up", () => Move(section, -1));
            Grid.SetColumn(up, 2);
            row.Children.Add(up);
            var down = MoveButton("", $"Move {section.Label} down", () => Move(section, 1));
            Grid.SetColumn(down, 3);
            row.Children.Add(down);
            return row;
        }

        void Move(Section section, int delta)
        {
            Sync();
            var index = sections.IndexOf(section);
            var target = Math.Clamp(index + delta, 0, sections.Count - 1);
            if (index == target) return;
            sections.RemoveAt(index);
            sections.Insert(target, section);
            Fill();
        }

        // Drag and drop reorders the rows; read the order back from them.
        void Sync() => sections = rows.Select(r => (Section)r.Tag).ToList();

        var body = new StackPanel { Spacing = 10, Width = 420 };
        body.Children.Add(new TextBlock
        {
            Text = $"Drag cards to reorder the {provider.DisplayName} dashboard, and clear a box to hide a card.",
            Style = Cards.Style("SecondaryTextStyle"),
            TextWrapping = TextWrapping.WrapWholeWords,
        });
        body.Children.Add(list);
        Fill();

        var dialog = SignInFlow.Dialog(root, "Edit dashboard", body, "EditDashboardDialog");
        dialog.PrimaryButtonText = "Save";
        dialog.SecondaryButtonText = "Reset";
        dialog.DefaultButton = ContentDialogButton.Primary;
        dialog.SecondaryButtonClick += (_, args) =>
        {
            args.Cancel = true;
            sections = available.Select(key => new Section(key, DashboardSections.Label(key, entry.Usage))).ToList();
            Fill();
        };
        if (opened is not null) dialog.Opened += (_, _) => opened(dialog);
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        Sync();
        state.Settings.Update(s => s.WithSections(provider.Id,
            sections.Select(x => x.Key).ToList(),
            sections.Where(x => !x.Visible).Select(x => x.Key).ToList()));
    }

    private static Button MoveButton(string glyph, string name, Action move)
    {
        var button = new Button
        {
            Content = new FontIcon { Glyph = glyph, FontSize = 12 },
            Padding = new Thickness(8, 6, 8, 6),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            BorderThickness = new Thickness(0),
        };
        AutomationProperties.SetName(button, name);
        ToolTipService.SetToolTip(button, name);
        button.Click += (_, _) => move();
        return button;
    }
}
