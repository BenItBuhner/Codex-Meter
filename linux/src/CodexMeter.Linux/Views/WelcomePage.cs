using CodexMeter.App.Assets;
using CodexMeter.Core.Providers;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Views;

/// <summary>First run: what the app does and a one-click start for the common services, as a GNOME status page.</summary>
public sealed class WelcomePage : IPage
{
    public WelcomePage(AppState state, MainWindow window)
    {
        var status = Adw.StatusPage.New();
        status.SetIconName(AppPaths.AppId);
        status.SetTitle("Welcome to Codex Meter");
        status.SetDescription("See how much of your AI coding limits you have left: 5-hour and weekly windows, monthly allowances, " +
            "credits and resets, for every service you use, in one place.");
        status.SetName("WelcomePage");

        var box = Gtk.Box.New(Gtk.Orientation.Vertical, 18);
        var list = Gtk.ListBox.New();
        list.AddCssClass("boxed-list");
        list.SetSelectionMode(Gtk.SelectionMode.None);
        var rows = new Dictionary<Gtk.ListBoxRow, UsageProvider>();
        foreach (var provider in UsageProvider.BuiltIns.Where(state.Supports))
        {
            var row = Adw.ActionRow.New();
            row.SetTitle(provider.SignInLabel);
            row.SetSubtitle(provider.Meters);
            row.SetActivatable(true);
            row.SetName("Welcome_" + provider.Id);
            var icon = ProviderIcons.Create(provider.Id, 24);
            icon.SetMarginEnd(4);
            row.AddPrefix(icon);
            row.AddSuffix(Gtk.Image.NewFromIconName("go-next-symbolic"));
            rows[row] = provider;
            list.Append(row);
        }
        list.OnRowActivated += async (_, args) =>
        {
            if (!rows.TryGetValue(args.Row, out var provider)) return;
            var error = await SignInDialog.RunAsync(state, provider, window.Window);
            if (error is not null) window.ShowToast(error);
            else if (state[provider].SignedIn) window.Navigate(provider.Id);
        };
        box.Append(list);

        var more = Gtk.Button.NewWithLabel("More Services…");
        more.AddCssClass("pill");
        more.SetHalign(Gtk.Align.Center);
        more.SetTooltipText("Copilot, Kimi, Z.ai, OpenRouter and others");
        more.SetName("WelcomeMore");
        more.OnClicked += async (_, _) =>
        {
            if (await window.AddAccountAsync() is { } error) window.ShowToast(error);
        };
        box.Append(more);
        var privacy = Gtk.Label.New(SignInDialog.StorageDescription(App.Current?.KeyLocation));
        privacy.AddCssClass("caption");
        privacy.AddCssClass("dim-label");
        privacy.SetWrap(true);
        privacy.SetJustify(Gtk.Justification.Center);
        privacy.SetMaxWidthChars(60);
        box.Append(privacy);
        var clamp = Adw.Clamp.New();
        clamp.SetMaximumSize(460);
        clamp.SetChild(box);
        status.SetChild(clamp);

        // The status page scrolls on its own.
        Scroller = AccountsPage.FindScroller(status) ?? Gtk.ScrolledWindow.New();
        var view = Adw.ToolbarView.New();
        view.AddTopBar(Adw.HeaderBar.New());
        view.SetContent(status);
        Page = Adw.NavigationPage.New(view, "Welcome");
        Page.SetTag("welcome");
    }

    public string Tag => "welcome";
    public Adw.NavigationPage Page { get; }
    public Gtk.ScrolledWindow Scroller { get; }

    public void Detach() { }
}
