using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.Core.Models;
using CodexMeter.Core.Services;
using CodexMeter.Core.Updates;
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
    private readonly StackPanel update = new();
    private DispatcherTimer? ticker;

    public PriorityPage() : base(1000)
    {
        Body.Children.Add(PageTitle("Priority", "PriorityTitle"));
        Body.Children.Add(update);
        Body.Children.Add(content);
    }

    protected override void Attach()
    {
        State.ProviderChanged += OnChanged;
        State.ProvidersChanged += Render;
        State.ServiceChanged += OnServiceChanged;
        ticker = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        ticker.Tick += (_, _) => Render();
        ticker.Start();
        if (State.Updater is { } updater) updater.Changed += RenderUpdate;
        RenderUpdate();
        Render();
        _ = State.RefreshAllAsync(force: false);
        _ = State.RefreshServiceAsync(foreground: true);
    }

    protected override void Detach()
    {
        State.ProviderChanged -= OnChanged;
        State.ProvidersChanged -= Render;
        State.ServiceChanged -= OnServiceChanged;
        ticker?.Stop();
        ticker = null;
        if (State.Updater is { } updater) updater.Changed -= RenderUpdate;
    }

    /// <summary>
    /// The update card, as on Android: only here on Priority, at the top, while an update is out.
    /// Review update opens Settings → Updates (channel, release notes, download and install).
    /// </summary>
    private void RenderUpdate()
    {
        update.Children.Clear();
        if (State.Updater is not { CardTitle: { } title } updater) return;
        var stack = new StackPanel { Spacing = 8 };
        stack.Children.Add(Cards.Header("\uE896", title));
        stack.Children.Add(Cards.Text(updater.CardDetail, "SecondaryTextStyle"));
        if (updater.Status is UpdateStatus.Downloading or UpdateStatus.Installing)
            stack.Children.Add(new ProgressBar { Minimum = 0, Maximum = 1, Value = updater.Progress, IsIndeterminate = updater.Status == UpdateStatus.Installing });
        var review = new Button { Content = "Review update", Style = Cards.Style("AccentButtonStyle"), Margin = new Thickness(0, 4, 0, 0) };
        AutomationProperties.SetAutomationId(review, "PriorityUpdateButton");
        review.Click += (_, _) => App.Current?.Window?.OpenUpdates();
        stack.Children.Add(review);
        var card = Cards.Card(stack, "PriorityUpdateCard");
        AutomationProperties.SetName(card, title);
        card.Margin = new Thickness(0, 0, 0, 12);
        update.Children.Add(card);
    }

    private void OnChanged(Core.Providers.UsageProvider _) => Render();

    private void OnServiceChanged() => DispatcherQueue.TryEnqueue(Render);

    private void Render()
    {
        var report = State.Priority(DateTimeOffset.UtcNow);
        content.Children.Clear();
        var headline = Cards.Text(report.Headline, "SecondaryTextStyle");
        AutomationProperties.SetAutomationId(headline, "PriorityHeadline");
        content.Children.Add(headline);
        Section("Service status", "\uE7BA", report.Service, "PriorityService");
        if (Section("Running slow", "\uE916", report.Slow, "PrioritySlow"))
            content.Children.Add(Cards.Text(PriorityReport.SlowNote, "CaptionSecondaryTextStyle"));
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
            if (item.Link is { } link && Uri.TryCreate(link, UriKind.Absolute, out var uri))
                texts.Children.Add(new HyperlinkButton { Content = "Open status page", NavigateUri = uri, Padding = new Thickness(0, 2, 0, 0) });
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
