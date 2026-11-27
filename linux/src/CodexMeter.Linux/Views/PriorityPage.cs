using CodexMeter.App.Assets;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;
using CodexMeter.Linux.Widgets;

namespace CodexMeter.Linux.Views;

/// <summary>
/// Priority, like the Android tab and the Windows page: only what needs you across every
/// signed-in service, as preference groups that appear only when they have something.
/// </summary>
public sealed class PriorityPage : IPage
{
    private readonly AppState state;
    private readonly Adw.PreferencesPage preferences;
    private readonly Gtk.ScrolledWindow scroller;
    private readonly Action<UsageProvider> onChanged;
    private readonly Action onProviders;
    private readonly Action onService;
    private readonly Action? onUpdate;
    private readonly MainWindow window;
    private readonly List<Adw.PreferencesGroup> groups = [];
    private readonly uint timer;

    public PriorityPage(AppState state, MainWindow window)
    {
        this.state = state;
        this.window = window;
        preferences = Adw.PreferencesPage.New();
        preferences.SetName("PriorityPage");
        var view = Adw.ToolbarView.New();
        view.AddTopBar(Adw.HeaderBar.New());
        view.SetContent(preferences);
        Page = Adw.NavigationPage.New(view, "Priority");
        Page.SetTag("priority");
        scroller = AccountsPage.FindScroller(preferences) ?? Gtk.ScrolledWindow.New();
        onChanged = _ => Render();
        onProviders = Render;
        state.ProviderChanged += onChanged;
        state.ProvidersChanged += onProviders;
        onService = () => GLib.Functions.IdleAdd(GLib.Constants.PRIORITY_DEFAULT_IDLE, () => { Render(); return false; });
        state.ServiceChanged += onService;
        timer = GLib.Functions.TimeoutAddSeconds(GLib.Constants.PRIORITY_DEFAULT, 30, () => { Render(); return true; });
        if (state.Updater is { } updater)
        {
            onUpdate = Render;
            updater.Changed += onUpdate;
        }
        Render();
        _ = state.RefreshAllAsync(force: false);
        _ = state.RefreshServiceAsync(foreground: true);
    }

    public string Tag => "priority";
    public Adw.NavigationPage Page { get; }
    public Gtk.ScrolledWindow Scroller => scroller;

    public void Detach()
    {
        state.ProviderChanged -= onChanged;
        state.ProvidersChanged -= onProviders;
        state.ServiceChanged -= onService;
        GLib.Functions.SourceRemove(timer);
        if (state.Updater is { } updater && onUpdate is not null) updater.Changed -= onUpdate;
    }

    /// <summary>
    /// The update card, as on Android: only here on Priority, under the headline, while an update
    /// is out. Review Update opens Settings → Updates (channel, release notes, download and install).
    /// </summary>
    private void UpdateCard()
    {
        if (state.Updater is not { CardTitle: { } title } updater) return;
        var group = Adw.PreferencesGroup.New();
        group.SetName("PriorityUpdate");
        var row = Adw.ActionRow.New();
        row.SetUseMarkup(false);
        row.SetTitle(title);
        row.SetSubtitle(updater.CardDetail);
        row.AddPrefix(Gtk.Image.NewFromIconName("software-update-available-symbolic"));
        var review = Gtk.Button.NewWithLabel("Review Update");
        review.AddCssClass("suggested-action");
        review.SetValign(Gtk.Align.Center);
        review.SetName("PriorityUpdateButton");
        review.OnClicked += (_, _) => window.OpenUpdates();
        row.AddSuffix(review);
        row.SetActivatableWidget(review);
        group.Add(row);
        Add(group);
    }

    private void Render()
    {
        foreach (var group in groups) preferences.Remove(group);
        groups.Clear();
        var report = state.Priority(DateTimeOffset.UtcNow);
        var top = Adw.PreferencesGroup.New();
        top.SetTitle("Priority");
        top.SetDescription(report.Headline);
        top.SetName("PriorityHeadline");
        Add(top);
        UpdateCard();
        Section("Service status", report.Service, "PriorityService");
        Section("Running slow", report.Slow, "PrioritySlow", PriorityReport.SlowNote);
        Section("Limits to watch", report.Watch, "PriorityWatch");
        Section("Used up", report.UsedUp, "PriorityUsedUp");
        Section("Coming up", report.ComingUp, "PriorityComingUp");
        Section("New models", report.NewModels, "PriorityNewModels", ModelDiscovery.Note(report.NewModels));
    }

    private void Add(Adw.PreferencesGroup group)
    {
        preferences.Add(group);
        groups.Add(group);
    }

    private void Section(string title, IReadOnlyList<PriorityItem> items, string name, string? note = null)
    {
        if (items.Count == 0) return;
        var group = Adw.PreferencesGroup.New();
        group.SetTitle(title);
        group.SetName(name);
        if (note is not null) group.SetDescription(note);
        foreach (var item in items)
        {
            var row = Adw.ActionRow.New();
            row.SetUseMarkup(false);
            row.SetTitle(item.Title);
            row.SetSubtitle(item.Detail);
            var mark = ProviderIcons.Create(item.Provider.Id, 20);
            mark.SetMarginEnd(4);
            row.AddPrefix(mark);
            if (item.RemainingPercent is { } left && left > 0)
            {
                var value = Cards.Label($"{left}%", "caption", Cards.LevelClass(Cards.Level(left)));
                value.SetWrap(false);
                value.SetValign(Gtk.Align.Center);
                row.AddSuffix(value);
            }
            if (item.Link is { } link && Uri.TryCreate(link, UriKind.Absolute, out var uri))
            {
                var open = Gtk.Button.NewFromIconName("adw-external-link-symbolic");
                open.SetTooltipText("Open status page");
                open.AddCssClass("flat");
                open.SetValign(Gtk.Align.Center);
                open.OnClicked += (_, _) => _ = Browser.OpenAsync(uri);
                row.AddSuffix(open);
            }
            group.Add(row);
        }
        Add(group);
    }
}
