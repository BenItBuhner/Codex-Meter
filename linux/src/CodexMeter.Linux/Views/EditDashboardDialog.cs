using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Linux.Services;
using CodexMeter.Linux.Widgets;

namespace CodexMeter.Linux.Views;

/// <summary>
/// Edit dashboard: switch cards on or off and move them up or down, per provider, like the phone
/// and Windows apps. A boxed list in a dialog with Cancel and Save in the header bar.
/// </summary>
public static class EditDashboardDialog
{
    private sealed class Section(string key, string label)
    {
        public string Key { get; } = key;
        public string Label { get; } = label;
        public bool Visible { get; set; } = true;
    }

    public static Adw.Dialog Present(AppState state, UsageProvider provider, Gtk.Window parent)
    {
        var entry = state[provider];
        var settings = state.Settings.Current;
        var available = DashboardSections.Available(entry.Usage, state.HasResetCredits(provider));
        var hidden = settings.HiddenSectionsFor(provider.Id).ToHashSet();
        var sections = DashboardSections.ResolveOrder(settings.SectionOrderFor(provider.Id), available)
            .Select(key => new Section(key, DashboardSections.Label(key, entry.Usage)) { Visible = !hidden.Contains(key) })
            .ToList();

        var dialog = Adw.Dialog.New();
        dialog.SetTitle("Edit Dashboard");
        dialog.SetName("EditDashboardDialog");
        dialog.SetContentWidth(440);

        var header = Adw.HeaderBar.New();
        header.SetShowEndTitleButtons(false);
        header.SetShowStartTitleButtons(false);
        var cancel = Gtk.Button.NewWithMnemonic("_Cancel");
        cancel.OnClicked += (_, _) => dialog.Close();
        header.PackStart(cancel);
        var save = Gtk.Button.NewWithMnemonic("_Save");
        save.AddCssClass("suggested-action");
        save.SetName("SaveDashboard");
        header.PackEnd(save);

        var list = Gtk.ListBox.New();
        list.AddCssClass("boxed-list");
        list.SetSelectionMode(Gtk.SelectionMode.None);
        list.SetName("EditDashboardList");

        void Fill()
        {
            while (list.GetFirstChild() is { } child) list.Remove(child);
            for (var i = 0; i < sections.Count; i++) list.Append(Row(sections[i], i));
        }

        Gtk.Widget Row(Section section, int index)
        {
            var row = Adw.ActionRow.New();
            row.SetTitle(section.Label);
            var check = Gtk.CheckButton.New();
            check.SetActive(section.Visible);
            check.SetValign(Gtk.Align.Center);
            check.SetName("Section_" + section.Key);
            check.OnToggled += (_, _) => section.Visible = check.GetActive();
            row.AddPrefix(check);
            row.SetActivatableWidget(check);
            var up = MoveButton("go-up-symbolic", $"Move {section.Label} Up", index > 0, () => Move(section, -1));
            var down = MoveButton("go-down-symbolic", $"Move {section.Label} Down", index < sections.Count - 1, () => Move(section, 1));
            row.AddSuffix(up);
            row.AddSuffix(down);
            return row;
        }

        void Move(Section section, int delta)
        {
            var index = sections.IndexOf(section);
            var target = Math.Clamp(index + delta, 0, sections.Count - 1);
            if (index == target) return;
            sections.RemoveAt(index);
            sections.Insert(target, section);
            Fill();
        }

        var body = Gtk.Box.New(Gtk.Orientation.Vertical, 12);
        body.SetMarginTop(12);
        body.SetMarginBottom(24);
        body.SetMarginStart(12);
        body.SetMarginEnd(12);
        var intro = Cards.Label($"Choose which cards the {provider.DisplayName} dashboard shows, and in what order.", "dim-label");
        intro.SetMaxWidthChars(60);
        body.Append(intro);
        body.Append(list);
        var reset = Gtk.Button.NewWithLabel("Reset to Default");
        reset.AddCssClass("pill");
        reset.SetHalign(Gtk.Align.Center);
        reset.SetMarginTop(12);
        reset.SetName("ResetDashboard");
        reset.OnClicked += (_, _) =>
        {
            sections = available.Select(key => new Section(key, DashboardSections.Label(key, entry.Usage))).ToList();
            Fill();
        };
        body.Append(reset);
        Fill();

        var scroll = Gtk.ScrolledWindow.New();
        scroll.SetPolicy(Gtk.PolicyType.Never, Gtk.PolicyType.Automatic);
        scroll.SetPropagateNaturalHeight(true);
        var clamp = Adw.Clamp.New();
        clamp.SetChild(body);
        scroll.SetChild(clamp);
        var view = Adw.ToolbarView.New();
        view.AddTopBar(header);
        view.SetContent(scroll);
        dialog.SetChild(view);

        save.OnClicked += (_, _) =>
        {
            state.Settings.Update(s => s.WithSections(provider.Id,
                sections.Select(x => x.Key).ToList(),
                sections.Where(x => !x.Visible).Select(x => x.Key).ToList()));
            dialog.Close();
        };
        dialog.Present(parent);
        return dialog;
    }

    private static Gtk.Button MoveButton(string icon, string name, bool enabled, Action move)
    {
        var button = Gtk.Button.NewFromIconName(icon);
        button.AddCssClass("flat");
        button.SetValign(Gtk.Align.Center);
        button.SetTooltipText(name);
        button.SetSensitive(enabled);
        button.OnClicked += (_, _) => move();
        return button;
    }
}
