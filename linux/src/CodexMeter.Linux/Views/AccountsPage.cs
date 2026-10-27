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
        connected.SetDescription("Each provider keeps its own sign-in. Signing out of one never touches the others.");
        var available = Adw.PreferencesGroup.New();
        available.SetTitle("Add an account");
        var connectedCount = 0;
        var availableCount = 0;

        foreach (var entry in state.Entries)
        {
            var provider = entry.Provider;
            var row = Adw.ActionRow.New();
            row.SetTitle(provider.DisplayName);
            row.SetName("Account_" + provider.Id);
            var mark = ProviderIcons.Create(provider.Id, 20);
            mark.SetMarginEnd(4);
            row.AddPrefix(mark);
            if (entry.SignedIn)
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
                connected.Add(row);
                connectedCount++;
            }
            else
            {
                row.SetSubtitle($"{provider.Meters} · {provider.SignInSummary}");
                var signIn = Gtk.Button.NewWithLabel(entry.SigningIn ? "Signing in…" : provider.SignInLabel);
                signIn.AddCssClass("suggested-action");
                signIn.SetValign(Gtk.Align.Center);
                signIn.SetSensitive(!entry.SigningIn);
                signIn.SetName("SignIn_" + provider.Id);
                signIn.OnClicked += async (_, _) =>
                {
                    var error = await SignInDialog.RunAsync(state, provider, window.Window);
                    if (error is not null) window.ShowToast(error);
                };
                row.AddSuffix(signIn);
                available.Add(row);
                availableCount++;
            }
        }
        if (connectedCount == 0)
        {
            var empty = Adw.ActionRow.New();
            empty.SetTitle("No accounts yet");
            empty.SetSubtitle("Add one below to see its limits.");
            connected.Add(empty);
        }
        preferences.Add(connected);
        groups.Add(connected);
        if (availableCount > 0)
        {
            preferences.Add(available);
            groups.Add(available);
        }
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
