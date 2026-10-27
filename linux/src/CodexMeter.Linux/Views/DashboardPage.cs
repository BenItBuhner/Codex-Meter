using CodexMeter.App.Assets;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;
using CodexMeter.Linux.Widgets;

namespace CodexMeter.Linux.Views;

/// <summary>One provider's usage: a card per window and limit, plus Codex reset credits.</summary>
public sealed class DashboardPage : IPage
{
    private readonly AppState state;
    private readonly MainWindow window;
    private readonly UsageProvider provider;
    private readonly PageScaffold scaffold;
    private readonly Gtk.Button refreshButton;
    private readonly Gtk.Spinner spinner;
    private readonly uint ticker;
    private readonly Action<UsageProvider> onProviderChanged;
    private readonly EventHandler<AppSettings> onSettingsChanged;
    private string? transientMessage;
    private string transientKind = "info";
    private bool detached;

    public DashboardPage(AppState state, MainWindow window, UsageProvider provider)
    {
        this.state = state;
        this.window = window;
        this.provider = provider;
        scaffold = new PageScaffold(provider.DisplayName, provider.Id);

        refreshButton = Gtk.Button.NewFromIconName("view-refresh-symbolic");
        refreshButton.SetTooltipText("Refresh");
        refreshButton.SetName("RefreshButton");
        refreshButton.OnClicked += async (_, _) =>
        {
            transientMessage = null;
            await state.RefreshAsync(provider, force: true);
        };
        spinner = Gtk.Spinner.New();
        scaffold.Header.PackEnd(refreshButton);
        scaffold.Header.PackEnd(spinner);

        onProviderChanged = changed => { if (changed == provider) Render(); };
        onSettingsChanged = (_, _) => Render();
        state.ProviderChanged += onProviderChanged;
        state.Settings.Changed += onSettingsChanged;
        // Countdowns and "updated 3 min ago" stay current.
        ticker = GLib.Functions.TimeoutAddSeconds(GLib.Constants.PRIORITY_DEFAULT, 30, () =>
        {
            if (detached) return false;
            Render();
            return true;
        });
        Render();
        _ = state.RefreshAsync(provider, force: false);
    }

    public string Tag => provider.Id;
    public Adw.NavigationPage Page => scaffold.Page;
    public Gtk.ScrolledWindow Scroller => scaffold.Scroller;

    public void Detach()
    {
        detached = true;
        state.ProviderChanged -= onProviderChanged;
        state.Settings.Changed -= onSettingsChanged;
        GLib.Functions.SourceRemove(ticker);
    }

    private void ShowMessage(string message, string kind)
    {
        transientMessage = message;
        transientKind = kind;
        Render();
    }

    private void Render()
    {
        if (detached) return;
        var entry = state[provider];
        var settings = state.Settings.Current;
        var usage = entry.Usage;
        var now = DateTimeOffset.UtcNow;
        var body = scaffold.Body;
        PageScaffold.Clear(body);

        spinner.SetSpinning(entry.Refreshing);
        spinner.SetVisible(entry.Refreshing);
        refreshButton.SetSensitive(entry.SignedIn && !entry.Refreshing);
        refreshButton.SetVisible(entry.SignedIn);

        // Header: mark, name, plan badge, then account and freshness.
        var hero = Gtk.Box.New(Gtk.Orientation.Horizontal, 14);
        hero.AddCssClass("provider-hero");
        hero.Append(ProviderIcons.Create(provider.Id, 36));
        var titles = Gtk.Box.New(Gtk.Orientation.Vertical, 2);
        titles.SetValign(Gtk.Align.Center);
        var titleRow = Gtk.Box.New(Gtk.Orientation.Horizontal, 10);
        var title = Gtk.Label.New(provider.DisplayName);
        title.AddCssClass("title-1");
        title.SetXalign(0);
        titleRow.Append(title);
        var plan = usage is null ? "" : provider == UsageProvider.Codex ? UsageFormat.PlanLabel(usage.PlanType) : usage.PlanType;
        if (plan.Length > 0 && entry.SignedIn)
        {
            var badge = Gtk.Label.New(plan);
            badge.AddCssClass("plan-badge");
            badge.SetValign(Gtk.Align.Center);
            badge.SetName("PlanBadge");
            titleRow.Append(badge);
        }
        titles.Append(titleRow);
        var subtitle = new List<string>();
        if (entry.SignedIn)
        {
            if (entry.Account!.Email.Length > 0) subtitle.Add(entry.Account.Email);
            subtitle.Add(entry.Refreshing && usage is null ? "Loading usage…" : UsageFormat.Updated(usage?.FetchedAt, now));
        }
        else subtitle.Add("Not connected");
        var sub = Cards.Label(string.Join("  ·  ", subtitle), "dim-label");
        sub.SetName("DashboardSubtitle");
        titles.Append(sub);
        hero.Append(titles);
        body.Append(hero);

        if (StatusNotice(entry) is { } notice) body.Append(notice);

        if (!entry.SignedIn)
        {
            body.Append(SignedOut(entry));
            return;
        }
        if (usage is null) return;

        var options = new Cards.MeterOptions(settings.DisplayMode, settings.ResetDisplay, settings.Use24HourClock, settings.ShowPace);
        var meters = Gtk.FlowBox.New();
        meters.SetSelectionMode(Gtk.SelectionMode.None);
        meters.SetHomogeneous(true);
        meters.SetMinChildrenPerLine(1);
        meters.SetMaxChildrenPerLine(2);
        meters.SetColumnSpacing(12);
        meters.SetRowSpacing(12);
        meters.SetName("MeterCards");
        void AddMeter(string text, UsageWindow? usageWindow, string id, bool reached = false)
        {
            if (usageWindow is null) return;
            var card = Cards.Meter(text, Cards.Icon(usageWindow.WindowSeconds), usageWindow, usage.FetchedAt, options, reached, id);
            card.SetSizeRequest(280, -1);
            var child = Gtk.FlowBoxChild.New();
            child.SetChild(card);
            child.SetFocusable(false);
            meters.Append(child);
        }

        AddMeter("5-hour", usage.FiveHour, "Meter_fiveHour");
        AddMeter(provider == UsageProvider.Cursor ? "Included usage · weekly" : "Weekly", usage.Weekly, "Meter_weekly");
        AddMeter(provider == UsageProvider.Cursor ? "Included usage · monthly" : "Monthly", usage.Monthly, "Meter_monthly");
        foreach (var limit in usage.AdditionalLimits)
        {
            var both = limit.Primary is not null && limit.Secondary is not null;
            AddMeter(both ? $"{limit.DisplayName} · {UsageFormat.WindowName(limit.Primary!.WindowSeconds)}" : limit.DisplayName,
                limit.Primary, "Meter_" + limit.Id + "_primary", limit.LimitReached);
            AddMeter(both ? $"{limit.DisplayName} · {UsageFormat.WindowName(limit.Secondary!.WindowSeconds)}" : limit.DisplayName,
                limit.Secondary, "Meter_" + limit.Id + "_secondary", limit.LimitReached);
        }
        if (usage.SpendControlLimit is { } spend)
        {
            var spendWindow = new UsageWindow(spend.UsedPercent, 2_592_000, spend.ResetAfterSeconds, spend.ResetAt);
            AddMeter("Monthly credit limit", spendWindow, "Meter_spendControl", usage.SpendControl!.Reached);
        }
        body.Append(meters);

        var extras = new List<Gtk.Widget>();
        if (provider == UsageProvider.Codex)
        {
            if (entry.Cache?.Credits is not null || usage.ResetCreditsAvailable is not null)
                extras.Add(ResetCreditsCard(entry.Cache?.Credits, usage.ResetCreditsAvailable ?? 0));
            if (usage.UsageCredits is { ShouldDisplay: true } purchased)
                extras.Add(PurchasedCreditsCard(purchased));
        }
        if (extras.Count > 0)
        {
            var header = Cards.Label("Credits", "heading");
            header.SetMarginTop(12);
            body.Append(header);
            var flow = Gtk.FlowBox.New();
            flow.SetSelectionMode(Gtk.SelectionMode.None);
            flow.SetHomogeneous(true);
            flow.SetMinChildrenPerLine(1);
            flow.SetMaxChildrenPerLine(2);
            flow.SetColumnSpacing(12);
            flow.SetRowSpacing(12);
            foreach (var extra in extras)
            {
                var child = Gtk.FlowBoxChild.New();
                child.SetChild(extra);
                child.SetFocusable(false);
                flow.Append(child);
            }
            body.Append(flow);
        }
    }

    private Gtk.Widget? StatusNotice(ProviderEntry entry)
    {
        if (transientMessage is not null)
            return PageScaffold.Status(transientKind, transientKind == "error" ? "Something went wrong" : "Done", transientMessage, "StatusNotice");
        if (entry.SigningIn)
            return PageScaffold.Status("info", "Signing in", $"Finish signing in with {provider.AccountName} in your browser.", "StatusNotice");
        var cache = entry.Cache;
        if (entry.SignedIn && cache?.LastError is { } error && (entry.Usage is null || cache.LastErrorAt > entry.Usage.FetchedAt))
            return PageScaffold.Status(entry.Usage is null ? "error" : "warning",
                entry.Usage is null ? "Couldn't load usage" : "Showing the last update", error, "StatusNotice");
        if (entry.Usage is { LimitReached: true })
        {
            var message = entry.Usage.NextReset(DateTimeOffset.UtcNow) is { } reset
                ? $"{provider.DisplayName} usage is paused until a window resets ({UsageFormat.Relative(reset, DateTimeOffset.UtcNow)})."
                : $"{provider.DisplayName} usage is paused until a window resets.";
            return PageScaffold.Status("warning", "Limit reached", message, "StatusNotice");
        }
        return null;
    }

    /// <summary>Like libadwaita's status page: mark, title, explanation and a pill button.</summary>
    private Gtk.Widget SignedOut(ProviderEntry entry)
    {
        var box = Gtk.Box.New(Gtk.Orientation.Vertical, 12);
        box.SetHalign(Gtk.Align.Center);
        box.SetValign(Gtk.Align.Center);
        box.SetVexpand(true);
        box.SetMarginTop(48);
        box.SetName("SignedOutPanel");
        var icon = ProviderIcons.Create(provider.Id, 72);
        icon.AddCssClass("dim-label");
        icon.SetMarginBottom(12);
        box.Append(icon);
        var title = Gtk.Label.New($"Connect your {provider.AccountName} account");
        title.AddCssClass("title-1");
        title.SetWrap(true);
        title.SetJustify(Gtk.Justification.Center);
        box.Append(title);
        var description = Gtk.Label.New(
            $"See your {provider.DisplayName} limits here: {provider.Meters.ToLowerInvariant()}. Codex Meter reads the same usage {provider.VendorName} shows in {provider.DisplayName}.");
        description.SetWrap(true);
        description.SetJustify(Gtk.Justification.Center);
        description.SetMaxWidthChars(52);
        box.Append(description);
        var button = Gtk.Button.NewWithLabel(entry.SigningIn ? "Signing in…" : provider.SignInLabel);
        button.AddCssClass("suggested-action");
        button.AddCssClass("pill");
        button.SetHalign(Gtk.Align.Center);
        button.SetMarginTop(12);
        button.SetSensitive(!entry.SigningIn);
        button.SetName("SignInButton");
        button.OnClicked += async (_, _) =>
        {
            var error = await SignInDialog.RunAsync(state, provider, window.Window);
            if (error is not null) ShowMessage(error, "error");
        };
        box.Append(button);
        var hint = Gtk.Label.New("Sign-in opens in your default browser.");
        hint.AddCssClass("caption");
        hint.AddCssClass("dim-label");
        box.Append(hint);
        return box;
    }

    private Gtk.Widget ResetCreditsCard(ResetCreditsSnapshot? credits, int fallbackCount)
    {
        var now = DateTimeOffset.UtcNow;
        var count = credits?.AvailableCount ?? fallbackCount;
        var panel = Gtk.Box.New(Gtk.Orientation.Vertical, 6);
        var header = Gtk.Box.New(Gtk.Orientation.Horizontal, 8);
        header.Append(Gtk.Image.NewFromIconName("emblem-synchronizing-symbolic"));
        header.Append(Cards.Label("Banked resets", "heading"));
        panel.Append(header);
        var countText = Cards.Label(count == 1 ? "1 reset available" : $"{count} resets available", "title-3");
        countText.SetName("ResetCreditCount");
        panel.Append(countText);
        panel.Append(Cards.Label("A reset clears your Codex 5-hour and weekly limits right away.", "dim-label"));
        foreach (var credit in (credits?.Credits ?? []).Where(c => c.IsAvailable && (c.ExpiresAt is null || c.ExpiresAt > now))
                     .OrderBy(c => c.ExpiresAt ?? DateTimeOffset.MaxValue).Take(4))
        {
            var expiry = credit.ExpiresAt is { } at
                ? $"expires {UsageFormat.Absolute(at, now, state.Settings.Current.Use24HourClock)} ({UsageFormat.Relative(at, now)})"
                : "no expiry";
            panel.Append(Cards.Label($"• {(credit.Title.Length > 0 ? credit.Title : "Reset")}, {expiry}", "caption", "dim-label"));
        }
        var button = Gtk.Button.NewWithLabel("Use a Reset");
        button.AddCssClass("suggested-action");
        button.SetHalign(Gtk.Align.Start);
        button.SetMarginTop(8);
        button.SetSensitive(count > 0);
        button.SetName("UseResetButton");
        button.OnClicked += (_, _) => ConfirmUseReset(button);
        panel.Append(button);
        var card = Cards.Card(panel, "ResetCreditsCard");
        card.RemoveCssClass("meter-card");
        card.AddCssClass("extra-card");
        return card;
    }

    /// <summary>Asks before spending a banked reset. Public so the screenshot run can show it.</summary>
    public Adw.AlertDialog ConfirmUseReset(Gtk.Button? button = null)
    {
        var confirm = Adw.AlertDialog.New("Use a reset now?",
            "This clears your Codex 5-hour and weekly limits. The reset that expires first is used.");
        confirm.AddResponse("cancel", "_Cancel");
        confirm.AddResponse("use", "_Use Reset");
        confirm.SetResponseAppearance("use", Adw.ResponseAppearance.Suggested);
        confirm.SetDefaultResponse("use");
        confirm.SetCloseResponse("cancel");
        confirm.OnResponse += async (_, args) =>
        {
            if (args.Response != "use") return;
            button?.SetSensitive(false);
            try
            {
                var result = await state.UseResetAsync(null);
                ShowMessage(result.UserMessage, result.Applied ? "info" : "warning");
            }
            catch (Exception error)
            {
                ShowMessage(error.Message, "error");
            }
        };
        confirm.Present(window.Window);
        return confirm;
    }

    private static Gtk.Widget PurchasedCreditsCard(UsageCredits credits)
    {
        var panel = Gtk.Box.New(Gtk.Orientation.Vertical, 6);
        var header = Gtk.Box.New(Gtk.Orientation.Horizontal, 8);
        header.Append(Gtk.Image.NewFromIconName("starred-symbolic"));
        header.Append(Cards.Label("Usage credits", "heading"));
        panel.Append(header);
        var amount = credits.Unlimited ? "Unlimited"
            : credits.NumericBalance is { } value ? UsageFormat.CreditAmount(value) + " credits"
            : credits.Balance ?? "Available";
        panel.Append(Cards.Label(amount, "title-3"));
        panel.Append(Cards.Label("Purchased credits keep Codex going after a limit is reached.", "dim-label"));
        var card = Cards.Card(panel, "UsageCreditsCard");
        card.RemoveCssClass("meter-card");
        card.AddCssClass("extra-card");
        return card;
    }
}
