using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.Core.Models;
using CodexMeter.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Views;

/// <summary>
/// Priority, like the Android tab: only what needs you, across every signed-in service. Limits to
/// watch, used-up limits with their reset times, what's coming up, and new models from the makers
/// you use, each shown only when it has something.
/// </summary>
public sealed class PriorityPage : PageBase
{
    private readonly StackPanel content = new() { Spacing = 4 };
    private DispatcherTimer? ticker;

    public PriorityPage() : base(1000)
    {
        Body.Children.Add(PageTitle("Priority", "PriorityTitle"));
        Body.Children.Add(content);
    }

    protected override void Attach()
    {
        State.ProviderChanged += OnChanged;
        State.ProvidersChanged += Render;
        ticker = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        ticker.Tick += (_, _) => Render();
        ticker.Start();
        Render();
        _ = State.RefreshAllAsync(force: false);
    }

    protected override void Detach()
    {
        State.ProviderChanged -= OnChanged;
        State.ProvidersChanged -= Render;
        ticker?.Stop();
        ticker = null;
    }

    private void OnChanged(Core.Providers.UsageProvider _) => Render();

    private void Render()
    {
        var report = State.Priority(DateTimeOffset.UtcNow);
        content.Children.Clear();
        var headline = Cards.Text(report.Headline, "SecondaryTextStyle");
        AutomationProperties.SetAutomationId(headline, "PriorityHeadline");
        content.Children.Add(headline);
        Section("Limits to watch", "", report.Watch, "PriorityWatch");
        Section("Used up", "", report.UsedUp, "PriorityUsedUp");
        Section("Coming up", "", report.ComingUp, "PriorityComingUp");
        if (Section("New models", "", report.NewModels, "PriorityNewModels"))
            content.Children.Add(Cards.Text(ModelDiscovery.Note(report.NewModels), "CaptionSecondaryTextStyle"));
    }

    private bool Section(string title, string glyph, IReadOnlyList<PriorityItem> items, string id)
    {
        if (items.Count == 0) return false;
        content.Children.Add(SectionHeader(title));
        var list = new StackPanel { Spacing = 10 };
        foreach (var item in items)
        {
            var row = new Grid { ColumnSpacing = 12 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var icon = ProviderIcons.Create(item.Provider.Id, 24);
            icon.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(icon);
            var texts = new StackPanel { Spacing = 1 };
            texts.Children.Add(Cards.Text(item.Title, "BodyStrongTextBlockStyle"));
            texts.Children.Add(Cards.Text(item.Detail, "SecondaryTextStyle"));
            if (item.RemainingPercent is { } left && left > 0) texts.Children.Add(Cards.Bar(left, Cards.Level(left)));
            Grid.SetColumn(texts, 1);
            row.Children.Add(texts);
            list.Children.Add(row);
        }
        var header = new StackPanel { Spacing = 10 };
        header.Children.Add(Cards.Header(glyph, title));
        header.Children.Add(list);
        var card = Cards.Card(header, id);
        AutomationProperties.SetName(card, title);
        content.Children.Add(card);
        return true;
    }
}
