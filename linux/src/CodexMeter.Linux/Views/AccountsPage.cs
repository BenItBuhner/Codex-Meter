using CodexMeter.App.Assets;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Views;

/// <summary>
/// Providers, like Settings → Providers on the phone apps: every connected or shown service with its
/// account, the switch for its sidebar page, its place in the order, Sign Out, and Add Account.
/// (Accounts and the sidebar's provider list are one page.) Its tag stays "accounts".
/// </summary>
public sealed class AccountsPage : IPage
{
    private readonly AppState state;
    private readonly MainWindow window;
    private readonly Adw.PreferencesPage preferences;
    private readonly Gtk.ScrolledWindow scroller;
    private readonly Action<UsageProvider> onChanged;
    private readonly Action onProvidersChanged;
    private bool dirty;
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
        Page = Adw.NavigationPage.New(view, "Providers");
        Page.SetTag("accounts");
        scroller = FindScroller(preferences) ?? Gtk.ScrolledWindow.New();
        onChanged = _ => QueueRender();
        onProvidersChanged = QueueRender;
        state.ProviderChanged += onChanged;
        state.ProvidersChanged += onProvidersChanged;
        Render();
    }

    public string Tag => "accounts";
    public Adw.NavigationPage Page { get; }
    public Gtk.ScrolledWindow Scroller => scroller;

    public void Detach()
    {
        state.ProviderChanged -= onChanged;
        state.ProvidersChanged -= onProvidersChanged;
    }

    /// <summary>Rebuilds once after a burst of changes, not inside the switch that caused them.</summary>
    private void QueueRender()
    {
        if (dirty) return;
        dirty = true;
        GLib.Functions.IdleAdd(GLib.Constants.PRIORITY_DEFAULT_IDLE, () =>
        {
            dirty = false;
            Render();
            return false;
        });
    }

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
        connected.SetTitle("On Your Dashboard");
        connected.SetDescription("Switch one off to hide its page. Each service keeps its own sign-in; signing out of one never touches the others.");
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

        var enabled = state.EnabledProviders;
        var order = state.SidebarCandidates.ToList();
        var listed = order.Concat(state.Entries.Where(e => e.SigningIn && !order.Contains(e.Provider)).Select(e => e.Provider)).ToList();
        for (var index = 0; index < listed.Count; index++)
        {
            var provider = listed[index];
            var entry = state[provider];
            var row = Adw.ActionRow.New();
            row.SetTitle(provider.DisplayName);
            // Account_ marks a connected (or connecting) service; ProviderRow_ one that isn't.
            row.SetName((entry.SignedIn || entry.SigningIn ? "Account_" : "ProviderRow_") + provider.Id);
            var mark = ProviderIcons.Create(provider.Id, 20);
            mark.SetMarginEnd(4);
            row.AddPrefix(mark);
            if (entry.SigningIn && !entry.SignedIn)
            {
                row.SetSubtitle("Signing in…");
                var spinner = Gtk.Spinner.New();
                spinner.SetSpinning(true);
                row.AddSuffix(spinner);
                connected.Add(row);
                continue;
            }
            if (entry.SignedIn)
            {
                var plan = entry.Usage is { } usage
                    ? (provider == UsageProvider.Codex ? UsageFormat.PlanLabel(usage.PlanType) : usage.PlanType)
                    : "";
                var who = entry.Account!.Email.Length > 0 ? entry.Account.Email : $"Signed in with {provider.AccountName}";
                row.SetSubtitle(plan.Length > 0 ? $"{who} · {plan}" : who);
            }
            else
            {
                row.SetSubtitle($"Not connected · {provider.Meters}");
            }
            var position = order.IndexOf(provider);
            var up = Gtk.Button.NewFromIconName("go-up-symbolic");
            up.AddCssClass("flat");
            up.SetValign(Gtk.Align.Center);
            up.SetSensitive(position > 0);
            up.SetTooltipText($"Move {provider.DisplayName} up");
            up.OnClicked += (_, _) => state.Move(provider, -1);
            var down = Gtk.Button.NewFromIconName("go-down-symbolic");
            down.AddCssClass("flat");
            down.SetValign(Gtk.Align.Center);
            down.SetSensitive(position >= 0 && position < order.Count - 1);
            down.SetTooltipText($"Move {provider.DisplayName} down");
            down.OnClicked += (_, _) => state.Move(provider, 1);
            var toggle = Gtk.Switch.New();
            toggle.SetValign(Gtk.Align.Center);
            toggle.SetActive(enabled.Contains(provider));
            toggle.SetSensitive(!(toggle.GetActive() && enabled.Count == 1));
            toggle.SetName("ProviderToggle_" + provider.Id);
            toggle.SetTooltipText("Show " + provider.DisplayName);
            toggle.OnStateSet += (_, args) =>
            {
                // The page rebuilds through ProvidersChanged; defer so the switch finishes its own update.
                var on = args.State;
                GLib.Functions.IdleAdd(GLib.Constants.PRIORITY_DEFAULT_IDLE, () => { state.SetEnabled(provider, on); return false; });
                return false;
            };
            row.AddSuffix(up);
            row.AddSuffix(down);
            row.AddSuffix(toggle);
            if (entry.SignedIn)
            {
                var signOut = Gtk.Button.NewWithLabel("Sign Out");
                signOut.SetValign(Gtk.Align.Center);
                signOut.SetName("SignOut_" + provider.Id);
                signOut.OnClicked += (_, _) => ConfirmSignOut(provider);
                row.AddSuffix(signOut);
            }
            connected.Add(row);
        }
        if (listed.Count == 0)
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
