using CodexMeter.App.Assets;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Views;

/// <summary>Sign providers in and out, like Settings → Accounts on the phone apps.</summary>
public sealed class AccountsPage : IPage
{
    private readonly AppState state;
    private readonly MainWindow window;
    private readonly Adw.PreferencesPage preferences;
    private readonly Gtk.ScrolledWindow scroller;
    private readonly Action<UsageProvider> onChanged;
    private readonly List<Adw.PreferencesGroup> groups = [];

    public AccountsPage(AppState state, MainWindow window)
    {
        this.state = state;
        this.window = window;
        preferences = Adw.PreferencesPage.New();
        preferences.SetName("AccountsPage");
        var view = Adw.ToolbarView.New();
        view.AddTopBar(Adw.HeaderBar.New());
        view.SetContent(preferences);
        Page = Adw.NavigationPage.New(view, "Accounts");
        Page.SetTag("accounts");
        scroller = FindScroller(preferences) ?? Gtk.ScrolledWindow.New();
        onChanged = _ => Render();
        state.ProviderChanged += onChanged;
        Render();
    }

    public string Tag => "accounts";
    public Adw.NavigationPage Page { get; }
    public Gtk.ScrolledWindow Scroller => scroller;

    public void Detach() => state.ProviderChanged -= onChanged;

    internal static Gtk.ScrolledWindow? FindScroller(Gtk.Widget root)
    {
        if (root is Gtk.ScrolledWindow scrolled) return scrolled;
        for (var child = root.GetFirstChild(); child is not null; child = child.GetNextSibling())
            if (FindScroller(child) is { } found) return found;
        return null;
    }

    private void Render()
    {
        foreach (var group in groups) preferences.Remove(group);
        groups.Clear();

        var connected = Adw.PreferencesGroup.New();
        connected.SetTitle("Connected");
        connected.SetDescription("Each service keeps its own sign-in. Signing out of one never touches the others.");
        var add = Gtk.Button.New();
        var addContent = Adw.ButtonContent.New();
        addContent.SetIconName("list-add-symbolic");
        addContent.SetLabel("Add Account");
        add.SetChild(addContent);
        add.AddCssClass("flat");
        add.SetValign(Gtk.Align.Center);
        add.SetName("AddAccountButton");
        add.OnClicked += async (_, _) =>
        {
            if (await window.AddAccountAsync() is { } error) window.ShowToast(error);
        };
        connected.SetHeaderSuffix(add);

        var count = 0;
        foreach (var entry in state.Entries.Where(e => e.SignedIn || e.SigningIn))
        {
            var provider = entry.Provider;
            var row = Adw.ActionRow.New();
            row.SetTitle(provider.DisplayName);
            row.SetName("Account_" + provider.Id);
            var mark = ProviderIcons.Create(provider.Id, 20);
            mark.SetMarginEnd(4);
            row.AddPrefix(mark);
            if (!entry.SignedIn)
            {
                row.SetSubtitle("Signing in…");
                var spinner = Gtk.Spinner.New();
                spinner.SetSpinning(true);
                row.AddSuffix(spinner);
            }
            else
            {
                var plan = entry.Usage is { } usage
                    ? (provider == UsageProvider.Codex ? UsageFormat.PlanLabel(usage.PlanType) : usage.PlanType)
                    : "";
                var who = entry.Account!.Email.Length > 0 ? entry.Account.Email : $"Signed in with {provider.AccountName}";
                row.SetSubtitle(plan.Length > 0 ? $"{who} · {plan}" : who);
                var signOut = Gtk.Button.NewWithLabel("Sign Out");
                signOut.SetValign(Gtk.Align.Center);
                signOut.SetName("SignOut_" + provider.Id);
                signOut.OnClicked += (_, _) => ConfirmSignOut(provider);
                row.AddSuffix(signOut);
            }
            connected.Add(row);
            count++;
        }
        if (count == 0)
        {
            var empty = Adw.ActionRow.New();
            empty.SetTitle("No accounts yet");
            empty.SetSubtitle("Use Add Account to connect Codex, Claude Code, Cursor or another service.");
            connected.Add(empty);
        }
        preferences.Add(connected);
        groups.Add(connected);

        var privacy = Adw.PreferencesGroup.New();
        privacy.SetDescription(SignInDialog.StorageDescription(App.Current?.KeyLocation));
        preferences.Add(privacy);
        groups.Add(privacy);
    }

    /// <summary>Asks before signing out. Public so the screenshot run can show it.</summary>
    public Adw.AlertDialog ConfirmSignOut(UsageProvider provider)
    {
        var dialog = Adw.AlertDialog.New($"Sign out of {provider.DisplayName}?",
            $"Codex Meter forgets this {provider.AccountName} sign-in and its saved usage on this computer. Your other accounts stay connected.");
        dialog.AddResponse("cancel", "_Cancel");
        dialog.AddResponse("sign-out", "_Sign Out");
        dialog.SetResponseAppearance("sign-out", Adw.ResponseAppearance.Destructive);
        dialog.SetDefaultResponse("cancel");
        dialog.SetCloseResponse("cancel");
        dialog.OnResponse += async (_, args) =>
        {
            if (args.Response != "sign-out") return;
            await state.SignOutAsync(provider);
            window.ShowToast($"Signed out of {provider.DisplayName}.");
        };
        dialog.Present(window.Window);
        return dialog;
    }
}
