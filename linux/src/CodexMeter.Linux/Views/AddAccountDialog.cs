using CodexMeter.App.Assets;
using CodexMeter.Core.Providers;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Views;

/// <summary>
/// Add account: every service not yet connected, searchable by name, company, what it meters or
/// how it signs in, like the phone and Windows apps. Picking one starts its sign-in.
/// </summary>
public static class AddAccountDialog
{
    /// <summary>Shows the picker; completes with the chosen provider, or null if it was closed.</summary>
    public static Task<UsageProvider?> PickAsync(AppState state, Gtk.Window parent, Action<Adw.Dialog>? opened = null)
    {
        var done = new TaskCompletionSource<UsageProvider?>();
        UsageProvider? chosen = null;
        var connected = state.SignedIn;

        var dialog = Adw.Dialog.New();
        dialog.SetTitle("Add Account");
        dialog.SetName("AddAccountDialog");
        dialog.SetContentWidth(480);
        dialog.SetContentHeight(620);

        var search = Gtk.SearchEntry.New();
        search.SetPlaceholderText("Search by name, company or sign-in");
        search.SetName("AddAccountSearch");
        search.SetHexpand(true);

        var list = Gtk.ListBox.New();
        list.AddCssClass("boxed-list");
        list.SetSelectionMode(Gtk.SelectionMode.None);
        list.SetName("AddAccountList");
        var empty = Adw.StatusPage.New();
        empty.SetIconName("edit-find-symbolic");
        empty.SetTitle("No Services Found");
        empty.SetDescription("Try a company name like GitHub or Moonshot.");
        empty.AddCssClass("compact");
        empty.SetVisible(false);

        var rowProviders = new Dictionary<Gtk.ListBoxRow, UsageProvider>();
        void Fill()
        {
            while (list.GetFirstChild() is { } child) list.Remove(child);
            rowProviders.Clear();
            var providers = UsageProvider.PickerProviders(connected, search.GetText()).Where(state.Supports).ToList();
            foreach (var provider in providers)
            {
                var row = Row(provider);
                rowProviders[row] = provider;
                list.Append(row);
            }
            list.SetVisible(providers.Count > 0);
            empty.SetVisible(providers.Count == 0);
        }

        void Choose(UsageProvider provider)
        {
            chosen = provider;
            dialog.Close();
        }

        list.OnRowActivated += (_, args) =>
        {
            if (rowProviders.TryGetValue(args.Row, out var provider)) Choose(provider);
        };
        search.OnSearchChanged += (_, _) => Fill();
        search.OnActivate += (_, _) =>
        {
            if (rowProviders.Count == 1) Choose(rowProviders.Values.First());
        };

        var body = Gtk.Box.New(Gtk.Orientation.Vertical, 12);
        body.SetMarginTop(6);
        body.SetMarginBottom(24);
        body.SetMarginStart(12);
        body.SetMarginEnd(12);
        body.Append(list);
        body.Append(empty);
        var clamp = Adw.Clamp.New();
        clamp.SetChild(body);
        var scroll = Gtk.ScrolledWindow.New();
        scroll.SetPolicy(Gtk.PolicyType.Never, Gtk.PolicyType.Automatic);
        scroll.SetChild(clamp);
        scroll.SetVexpand(true);

        // The search field sits in its own bar under the header, like GNOME Settings' search.
        var searchBar = Gtk.Box.New(Gtk.Orientation.Horizontal, 0);
        searchBar.SetMarginTop(6);
        searchBar.SetMarginBottom(6);
        searchBar.SetMarginStart(12);
        searchBar.SetMarginEnd(12);
        searchBar.Append(search);
        var searchClamp = Adw.Clamp.New();
        searchClamp.SetChild(searchBar);

        var view = Adw.ToolbarView.New();
        view.AddTopBar(Adw.HeaderBar.New());
        view.AddTopBar(searchClamp);
        view.SetContent(scroll);
        dialog.SetChild(view);
        Fill();

        dialog.OnClosed += (_, _) => done.TrySetResult(chosen);
        dialog.Present(parent);
        search.GrabFocus();
        opened?.Invoke(dialog);
        return done.Task;
    }

    private static Gtk.ListBoxRow Row(UsageProvider provider)
    {
        var row = Adw.ActionRow.New();
        row.SetTitle(provider.DisplayName);
        row.SetSubtitle(provider.VendorName == provider.DisplayName
            ? provider.PickerSummary
            : $"{provider.VendorName} · {provider.PickerSummary}");
        row.SetSubtitleLines(2);
        row.SetActivatable(true);
        row.SetName("Pick_" + provider.Id);
        var icon = ProviderIcons.Create(provider.Id, 24);
        icon.SetMarginEnd(4);
        row.AddPrefix(icon);
        if (provider.Inferred)
        {
            var tag = Gtk.Label.New("Unconfirmed");
            tag.AddCssClass("unconfirmed-tag");
            tag.SetValign(Gtk.Align.Center);
            tag.SetTooltipText("The sign-in follows this provider's own tools but hasn't been confirmed with a real account yet.");
            row.AddSuffix(tag);
        }
        row.AddSuffix(Gtk.Image.NewFromIconName("go-next-symbolic"));
        return row;
    }
}
