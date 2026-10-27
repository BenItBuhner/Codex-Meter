using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.App.Services;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace CodexMeter.App.Views;

/// <summary>
/// One provider's usage, like the phone apps' dashboard: a card per window and limit, credits,
/// the usage history chart and Codex reset credits, in the order chosen in Edit dashboard.
/// </summary>
public sealed class DashboardPage : PageBase
{
    private readonly UsageProvider provider;
    private readonly Border iconHost = new() { Width = 40, Height = 40, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock title = Cards.Text("", "TitleTextBlockStyle", wrap: false);
    private readonly TextBlock planText = Cards.Text("", "CaptionTextBlockStyle", wrap: false);
    private readonly Border planBadge;
    private readonly TextBlock subtitle = Cards.Text("", "CaptionSecondaryTextStyle", wrap: false);
    private readonly ProgressRing refreshing = new() { Width = 18, Height = 18, IsActive = false, VerticalAlignment = VerticalAlignment.Center };
    private readonly AppBarButton refreshButton;
    private readonly AppBarButton editButton;
    private readonly InfoBar status = new() { IsClosable = true, IsOpen = false, Margin = new Thickness(0, 8, 0, 4) };
    private readonly StackPanel signedOutPanel = new() { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, MaxWidth = 460 };
    private readonly Border signedOutCard;
    private readonly CardPanel cards = new() { MinColumnWidth = 300, Spacing = 12, Margin = new Thickness(0, 8, 0, 0) };
    private DispatcherTimer? ticker;
    private string? transientMessage;
    private InfoBarSeverity transientSeverity;
    private (ProviderHistory History, IReadOnlyList<string> Overrides, bool Clock, AppTheme Theme, HistoryCard Card)? historyCard;

    public DashboardPage(UsageProvider provider) : base(1240)
    {
        this.provider = provider;
        Body.Spacing = 0;

        planBadge = new Border
        {
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(8, 1, 8, 3),
            VerticalAlignment = VerticalAlignment.Center,
            Visibility = Visibility.Collapsed,
            Child = planText,
        };
        planText.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        AutomationProperties.SetAutomationId(planText, "PlanBadge");
        AutomationProperties.SetAutomationId(title, "DashboardTitle");
        AutomationProperties.SetHeadingLevel(title, AutomationHeadingLevel.Level1);
        AutomationProperties.SetAutomationId(subtitle, "DashboardSubtitle");
        AutomationProperties.SetAutomationId(status, "StatusBar");

        refreshButton = new AppBarButton { Label = "Refresh", Icon = new SymbolIcon(Symbol.Refresh) };
        refreshButton.KeyboardAccelerators.Add(new KeyboardAccelerator { Key = VirtualKey.F5 });
        AutomationProperties.SetAutomationId(refreshButton, "RefreshButton");
        ToolTipService.SetToolTip(refreshButton, "Refresh (F5)");
        refreshButton.Click += async (_, _) =>
        {
            transientMessage = null;
            await State.RefreshAsync(provider, force: true);
        };
        editButton = new AppBarButton { Label = "Edit dashboard", Icon = new FontIcon { Glyph = "" } };
        AutomationProperties.SetAutomationId(editButton, "EditDashboardButton");
        ToolTipService.SetToolTip(editButton, "Choose and reorder the cards on this page");
        editButton.Click += async (_, _) => await EditDashboardDialog.ShowAsync(provider, XamlRoot);

        var commands = new CommandBar
        {
            DefaultLabelPosition = CommandBarDefaultLabelPosition.Right,
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            IsDynamicOverflowEnabled = true,
            VerticalAlignment = VerticalAlignment.Center,
            OverflowButtonVisibility = CommandBarOverflowButtonVisibility.Auto,
        };
        commands.PrimaryCommands.Add(refreshButton);
        commands.PrimaryCommands.Add(editButton);

        var header = new Grid { ColumnSpacing = 14 };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(iconHost);
        var titles = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        var titleRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        titleRow.Children.Add(title);
        titleRow.Children.Add(planBadge);
        titles.Children.Add(titleRow);
        titles.Children.Add(subtitle);
        Grid.SetColumn(titles, 1);
        header.Children.Add(titles);
        Grid.SetColumn(refreshing, 2);
        header.Children.Add(refreshing);
        Grid.SetColumn(commands, 3);
        header.Children.Add(commands);
        Body.Children.Add(header);
        Body.Children.Add(status);

        signedOutCard = Cards.Card(signedOutPanel);
        signedOutCard.Padding = new Thickness(36);
        signedOutCard.Margin = new Thickness(0, 12, 0, 0);
        Body.Children.Add(signedOutCard);
        Body.Children.Add(cards);
    }

    public UsageProvider Provider => provider;

    protected override void Attach()
    {
        State.ProviderChanged += OnProviderChanged;
        State.Settings.Changed += OnSettingsChanged;
        ticker = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        ticker.Tick += (_, _) => Render();
        ticker.Start();
        Render();
        _ = State.RefreshAsync(provider, force: false);
    }

    protected override void Detach()
    {
        State.ProviderChanged -= OnProviderChanged;
        State.Settings.Changed -= OnSettingsChanged;
        ticker?.Stop();
        ticker = null;
    }

    private void OnProviderChanged(UsageProvider changed)
    {
        if (changed == provider) Render();
    }

    private void OnSettingsChanged(object? sender, AppSettings settings) => Render();

    private void ShowMessage(string message, InfoBarSeverity severity)
    {
        transientMessage = message;
        transientSeverity = severity;
        Render();
    }

    private void Render()
    {
        var entry = State[provider];
        var settings = State.Settings.Current;
        var usage = entry.Usage;
        var now = DateTimeOffset.UtcNow;

        if (iconHost.Child is null) iconHost.Child = ProviderIcons.Create(provider.Id, 32);
        title.Text = provider.DisplayName;
        var plan = usage is null ? "" : provider == UsageProvider.Codex ? UsageFormat.PlanLabel(usage.PlanType) : usage.PlanType;
        planText.Text = plan;
        planBadge.Background = Cards.Brush("AccentFillColorDefaultBrush");
        planText.Foreground = Cards.Brush("TextOnAccentFillColorPrimaryBrush");
        planBadge.Visibility = plan.Length > 0 && entry.SignedIn ? Visibility.Visible : Visibility.Collapsed;
        refreshing.IsActive = entry.Refreshing;
        refreshButton.IsEnabled = entry.SignedIn && !entry.Refreshing;
        editButton.Visibility = entry.SignedIn ? Visibility.Visible : Visibility.Collapsed;

        var parts = new List<string>();
        if (entry.SignedIn)
        {
            if (entry.Account!.Email.Length > 0) parts.Add(entry.Account.Email);
            parts.Add(entry.Refreshing && usage is null ? "Loading usage…" : UsageFormat.Updated(usage?.FetchedAt, now));
        }
        else parts.Add("Not connected");
        subtitle.Text = string.Join("  ·  ", parts);

        RenderStatus(entry);
        signedOutCard.Visibility = entry.SignedIn ? Visibility.Collapsed : Visibility.Visible;
        if (!entry.SignedIn) RenderSignedOut(entry);

        cards.Children.Clear();
        if (!entry.SignedIn || usage is null) return;

        var options = new Cards.MeterOptions(settings.DisplayMode, settings.ResetDisplay, settings.Use24HourClock, settings.ShowPace);
        void AddMeter(string name, UsageWindow? window, string id, bool reached = false)
        {
            if (window is null) return;
            cards.Children.Add(Cards.Meter(name, Cards.Glyph(window.WindowSeconds), window, usage.FetchedAt, options, reached, id));
        }

        foreach (var key in State.VisibleSections(provider))
        {
            switch (key)
            {
                case DashboardSections.FiveHour:
                    AddMeter(usage.FiveHour is { } five ? UsageFormat.WindowName(five.WindowSeconds) : "5-hour", usage.FiveHour, "Meter_fiveHour");
                    break;
                case DashboardSections.Weekly:
                    AddMeter(provider == UsageProvider.Cursor ? "Included usage · weekly" : "Weekly", usage.Weekly, "Meter_weekly");
                    break;
                case DashboardSections.Monthly:
                    AddMeter(provider == UsageProvider.Cursor ? "Included usage · monthly" : "Monthly", usage.Monthly, "Meter_monthly");
                    break;
                case DashboardSections.SpendControl when usage.SpendControlLimit is { } spend:
                    AddMeter("Monthly credit limit", new UsageWindow(spend.UsedPercent, 2_592_000, spend.ResetAfterSeconds, spend.ResetAt),
                        "Meter_spendControl", usage.SpendControl!.Reached);
                    break;
                case DashboardSections.UsageCredits when usage.UsageCredits is { } credits:
                    cards.Children.Add(PurchasedCreditsCard(credits));
                    break;
                case DashboardSections.UsageHistory:
                    cards.Children.Add(History(entry, settings).Root);
                    break;
                case DashboardSections.ResetCredits:
                    cards.Children.Add(ResetCreditsCard(entry.Cache?.Credits, usage.ResetCreditsAvailable ?? 0));
                    break;
                default:
                    if (!DashboardSections.IsLimitKey(key)) break;
                    foreach (var limit in usage.AdditionalLimits.Where(l => DashboardSections.LimitKey(l) == key))
                    {
                        var both = limit.Primary is not null && limit.Secondary is not null;
                        AddMeter(both ? $"{limit.DisplayName} · {UsageFormat.WindowName(limit.Primary!.WindowSeconds)}" : limit.DisplayName,
                            limit.Primary, "Meter_" + limit.Id + "_primary", limit.LimitReached);
                        AddMeter(both ? $"{limit.DisplayName} · {UsageFormat.WindowName(limit.Secondary!.WindowSeconds)}" : limit.DisplayName,
                            limit.Secondary, "Meter_" + limit.Id + "_secondary", limit.LimitReached);
                    }
                    break;
            }
        }
    }

    /// <summary>The chart keeps its selected window and hover while nothing it shows has changed.</summary>
    private HistoryCard History(ProviderEntry entry, AppSettings settings)
    {
        if (historyCard is { } cached && ReferenceEquals(cached.History, entry.History) && ReferenceEquals(cached.Overrides, settings.HistoryOverrides)
            && cached.Clock == settings.Use24HourClock && cached.Theme == settings.Theme)
        {
            if (cached.Card.Root.Parent is Panel parent) parent.Children.Remove(cached.Card.Root);
            return cached.Card;
        }
        var card = new HistoryCard(entry.History, settings.HistoryOverrides, settings.Use24HourClock,
            (key, on) => State.Settings.Update(s => s with { HistoryOverrides = HistorySections.SetVisible(s.HistoryOverrides, key, on) }),
            () => _ = ConfirmClearHistoryAsync());
        historyCard = (entry.History, settings.HistoryOverrides, settings.Use24HourClock, settings.Theme, card);
        return card;
    }

    private async Task ConfirmClearHistoryAsync()
    {
        var dialog = SignInFlow.Dialog(XamlRoot, $"Clear {provider.DisplayName} history?",
            Cards.Text("The chart starts over from the next refresh. Your current usage isn't affected.", "BodyTextBlockStyle"), "ClearHistoryDialog");
        dialog.PrimaryButtonText = "Clear history";
        dialog.DefaultButton = ContentDialogButton.Close;
        if (await dialog.ShowAsync() == ContentDialogResult.Primary) await State.ClearHistoryAsync(provider);
    }

    private void RenderSignedOut(ProviderEntry entry)
    {
        signedOutPanel.Children.Clear();
        signedOutPanel.Children.Add(new Border { Width = 56, Height = 56, HorizontalAlignment = HorizontalAlignment.Center, Child = ProviderIcons.Create(provider.Id, 48) });
        var heading = Cards.Text($"Connect your {provider.AccountName} account", "SubtitleTextBlockStyle");
        heading.TextAlignment = TextAlignment.Center;
        signedOutPanel.Children.Add(heading);
        var body = Cards.Text($"See your {provider.DisplayName} limits here: {provider.Meters.ToLowerInvariant()}. Codex Meter reads the same usage {provider.VendorName} shows you.", "SecondaryTextStyle");
        body.TextAlignment = TextAlignment.Center;
        signedOutPanel.Children.Add(body);
        var button = new Button
        {
            Content = entry.SigningIn ? "Signing in…" : provider.SignInLabel,
            Style = Cards.Style("AccentButtonStyle"),
            HorizontalAlignment = HorizontalAlignment.Center,
            Padding = new Thickness(20, 6, 20, 7),
            IsEnabled = !entry.SigningIn,
        };
        AutomationProperties.SetAutomationId(button, "SignInButton");
        button.Click += async (_, _) =>
        {
            var error = await SignInFlow.RunAsync(provider, XamlRoot);
            if (error is not null) ShowMessage(error, InfoBarSeverity.Error);
        };
        signedOutPanel.Children.Add(button);
        var hint = Cards.Text(provider.SignInSummary, "CaptionSecondaryTextStyle");
        hint.TextAlignment = TextAlignment.Center;
        signedOutPanel.Children.Add(hint);
    }

    private void RenderStatus(ProviderEntry entry)
    {
        void Show(string heading, string message, InfoBarSeverity severity)
        {
            status.Title = heading;
            status.Message = message;
            status.Severity = severity;
            status.IsOpen = true;
        }

        if (transientMessage is not null)
        {
            Show(transientSeverity == InfoBarSeverity.Success ? "Done" : "Something went wrong", transientMessage, transientSeverity);
            return;
        }
        if (entry.SigningIn)
        {
            Show("Signing in", $"Finish signing in with {provider.AccountName}.", InfoBarSeverity.Informational);
            return;
        }
        if (entry.HasError)
        {
            Show(entry.Usage is null ? "Couldn't load usage" : "Showing the last update", entry.Cache!.LastError!,
                entry.Usage is null ? InfoBarSeverity.Error : InfoBarSeverity.Warning);
            return;
        }
        if (entry.Usage is { LimitReached: true })
        {
            Show("Limit reached", entry.Usage.NextReset(DateTimeOffset.UtcNow) is { } reset
                ? $"{provider.DisplayName} usage is paused until a window resets ({UsageFormat.Relative(reset, DateTimeOffset.UtcNow)})."
                : $"{provider.DisplayName} usage is paused until a window resets.", InfoBarSeverity.Warning);
            return;
        }
        status.IsOpen = false;
    }

    private Border ResetCreditsCard(ResetCreditsSnapshot? credits, int fallbackCount)
    {
        var now = DateTimeOffset.UtcNow;
        var count = credits?.AvailableCount ?? fallbackCount;
        var panel = new StackPanel { Spacing = 6 };
        panel.Children.Add(Cards.Header("", "Reset credits"));
        var countText = Cards.Text(count == 1 ? "1 reset available" : $"{count} resets available", "SubtitleTextBlockStyle");
        countText.Margin = new Thickness(0, 4, 0, 0);
        AutomationProperties.SetAutomationId(countText, "ResetCreditCount");
        panel.Children.Add(countText);
        panel.Children.Add(Cards.Text("A reset clears your Codex 5-hour and weekly limits right away.", "SecondaryTextStyle"));
        foreach (var credit in (credits?.Credits ?? []).Where(c => c.IsAvailable && (c.ExpiresAt is null || c.ExpiresAt > now))
                     .OrderBy(c => c.ExpiresAt ?? DateTimeOffset.MaxValue).Take(4))
        {
            var expiry = credit.ExpiresAt is { } at
                ? $"expires {UsageFormat.Absolute(at, now, State.Settings.Current.Use24HourClock)} ({UsageFormat.Relative(at, now)})"
                : "no expiry";
            panel.Children.Add(Cards.Text($"• {(credit.Title.Length > 0 ? credit.Title : "Reset")}, {expiry}", "CaptionSecondaryTextStyle"));
        }
        var settings = State.Settings.Current;
        if (settings.AutoUseExpiringResets || settings.AutoUseWhenLow)
        {
            var rules = new List<string>();
            if (settings.AutoUseExpiringResets) rules.Add($"before one expires ({CreditExpiryAutoApply.LeadLabel(settings.AutoUseLeadMinutes)})");
            if (settings.AutoUseWhenLow) rules.Add($"when a limit falls to {settings.AutoUseThreshold}% left");
            panel.Children.Add(Cards.Status("Auto-use is on: " + string.Join(" and ", rules) + ".", "CaptionSecondaryTextStyle", "", "AccentTextFillColorPrimaryBrush"));
        }
        var button = new Button
        {
            Content = "Use a reset",
            Style = Cards.Style("AccentButtonStyle"),
            Margin = new Thickness(0, 8, 0, 0),
            IsEnabled = count > 0,
        };
        AutomationProperties.SetAutomationId(button, "UseResetButton");
        button.Click += async (_, _) =>
        {
            var confirm = SignInFlow.Dialog(XamlRoot, "Use a reset now?",
                Cards.Text("This clears your Codex 5-hour and weekly limits. The reset that expires first is used.", "BodyTextBlockStyle"), "UseResetDialog");
            confirm.PrimaryButtonText = "Use reset";
            confirm.DefaultButton = ContentDialogButton.Primary;
            if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
            button.IsEnabled = false;
            try
            {
                var result = await State.UseResetAsync(null);
                ShowMessage(result.UserMessage, result.Applied ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
            }
            catch (Exception error)
            {
                ShowMessage(error.Message, InfoBarSeverity.Error);
            }
        };
        panel.Children.Add(button);
        var card = Cards.Card(panel, "ResetCreditsCard");
        AutomationProperties.SetName(card, "Reset credits");
        return card;
    }

    private static Border PurchasedCreditsCard(UsageCredits credits)
    {
        var panel = new StackPanel { Spacing = 6 };
        panel.Children.Add(Cards.Header("", "Credits"));
        var amount = credits.Unlimited ? "Unlimited"
            : credits.NumericBalance is { } value ? UsageFormat.CreditAmount(value) + " credits"
            : credits.Balance ?? "Available";
        var figure = Cards.Text(amount, "SubtitleTextBlockStyle");
        figure.Margin = new Thickness(0, 4, 0, 0);
        panel.Children.Add(figure);
        panel.Children.Add(Cards.Text("Purchased credits keep you going after a limit is reached.", "SecondaryTextStyle"));
        var card = Cards.Card(panel, "UsageCreditsCard");
        AutomationProperties.SetName(card, "Credits");
        return card;
    }
}
