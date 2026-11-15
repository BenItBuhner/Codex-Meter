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
    private readonly List<Adw.PreferencesGroup> groups = [];
    private readonly uint timer;

    public PriorityPage(AppState state, MainWindow window)
    {
        this.state = state;
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
        timer = GLib.Functions.TimeoutAddSeconds(GLib.Constants.PRIORITY_DEFAULT, 30, () => { Render(); return true; });
        Render();
        _ = state.RefreshAllAsync(force: false);
    }

    public string Tag => "priority";
    public Adw.NavigationPage Page { get; }
    public Gtk.ScrolledWindow Scroller => scroller;

    public void Detach()
    {
        state.ProviderChanged -= onChanged;
        state.ProvidersChanged -= onProviders;
        GLib.Functions.SourceRemove(timer);
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
            group.Add(row);
        }
        Add(group);
    }
}
