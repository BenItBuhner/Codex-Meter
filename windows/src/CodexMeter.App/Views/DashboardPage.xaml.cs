using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.App.Services;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace CodexMeter.App.Views;

/// <summary>One provider's usage: a card per window and limit, plus Codex reset credits.</summary>
public sealed partial class DashboardPage : Page
{
    private readonly AppState state = App.Current!.State;
    private UsageProvider provider = UsageProvider.Codex;
    private DispatcherTimer? ticker;
    private string? transientMessage;
    private InfoBarSeverity transientSeverity;

    public DashboardPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        provider = UsageProvider.Resolve(e.Parameter as string);
        state.ProviderChanged += OnProviderChanged;
        state.Settings.Changed += OnSettingsChanged;
        ticker = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        ticker.Tick += (_, _) => Render();
        ticker.Start();
        Render();
        _ = state.RefreshAsync(provider, force: false);
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        state.ProviderChanged -= OnProviderChanged;
        state.Settings.Changed -= OnSettingsChanged;
        ticker?.Stop();
    }

    private void OnProviderChanged(UsageProvider changed)
    {
        if (changed == provider) Render();
    }

    private void OnSettingsChanged(object? sender, CodexMeter.Core.Services.AppSettings settings) => Render();

    private async void OnRefreshClick(object sender, RoutedEventArgs e)
    {
        transientMessage = null;
        await state.RefreshAsync(provider, force: true);
    }

    private async void OnSignInClick(object sender, RoutedEventArgs e)
    {
        var error = await SignInFlow.RunAsync(provider, XamlRoot);
        if (error is not null) ShowMessage(error, InfoBarSeverity.Error);
    }

    private void ShowMessage(string message, InfoBarSeverity severity)
    {
        transientMessage = message;
        transientSeverity = severity;
        Render();
    }

    private void Render()
    {
        var entry = state[provider];
        var settings = state.Settings.Current;
        var usage = entry.Usage;
        var now = DateTimeOffset.UtcNow;

        HeaderIconHost.Child = ProviderIcons.Create(provider.Id, 32);
        TitleText.Text = provider.DisplayName;
        var plan = usage is null ? "" : provider == UsageProvider.Codex ? UsageFormat.PlanLabel(usage.PlanType) : usage.PlanType;
        PlanText.Text = plan;
        PlanBadge.Visibility = plan.Length > 0 && entry.SignedIn ? Visibility.Visible : Visibility.Collapsed;
        RefreshingRing.IsActive = entry.Refreshing;
        RefreshButton.IsEnabled = entry.SignedIn && !entry.Refreshing;

        var subtitle = new List<string>();
        if (entry.SignedIn)
        {
            if (entry.Account!.Email.Length > 0) subtitle.Add(entry.Account.Email);
            subtitle.Add(entry.Refreshing && usage is null ? "Loading usage…" : UsageFormat.Updated(usage?.FetchedAt, now));
        }
        else subtitle.Add("Not connected");
        SubtitleText.Text = string.Join("  ·  ", subtitle);

        RenderStatus(entry);

        SignedOutPanel.Visibility = entry.SignedIn ? Visibility.Collapsed : Visibility.Visible;
        if (!entry.SignedIn)
        {
            SignedOutIconHost.Child = ProviderIcons.Create(provider.Id, 48);
            SignedOutTitle.Text = $"Connect your {provider.AccountName} account";
            SignedOutBody.Text = $"See your {provider.DisplayName} limits here: {provider.Meters.ToLowerInvariant()}. Codex Meter reads the same usage {provider.VendorName} shows in {provider.DisplayName}.";
            SignInButton.Content = provider.SignInLabel;
            SignInButton.IsEnabled = !entry.SigningIn;
            SignInHint.Text = "Sign-in opens in your default browser.";
        }

        MeterCards.Children.Clear();
        ExtraCards.Children.Clear();
        ExtrasHeader.Visibility = Visibility.Collapsed;
        if (!entry.SignedIn || usage is null) return;

        var options = new Cards.MeterOptions(settings.DisplayMode, settings.ResetDisplay, settings.Use24HourClock, settings.ShowPace);
        void AddMeter(string title, UsageWindow? window, string id, bool reached = false)
        {
            if (window is null) return;
            MeterCards.Children.Add(Cards.Meter(title, Cards.Glyph(window.WindowSeconds), window, usage.FetchedAt, options, reached, id));
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
            var window = new UsageWindow(spend.UsedPercent, 2_592_000, spend.ResetAfterSeconds, spend.ResetAt);
            AddMeter("Monthly credit limit", window, "Meter_spendControl", usage.SpendControl!.Reached);
        }

        if (provider == UsageProvider.Codex)
        {
            if (entry.Cache?.Credits is { } credits || usage.ResetCreditsAvailable is not null)
                ExtraCards.Children.Add(ResetCreditsCard(entry.Cache?.Credits, usage.ResetCreditsAvailable ?? 0));
            if (usage.UsageCredits is { ShouldDisplay: true } purchased)
                ExtraCards.Children.Add(PurchasedCreditsCard(purchased));
        }
        ExtrasHeader.Visibility = ExtraCards.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RenderStatus(ProviderEntry entry)
    {
        if (transientMessage is not null)
        {
            StatusBar.Title = transientSeverity == InfoBarSeverity.Success ? "Done" : "Something went wrong";
            StatusBar.Message = transientMessage;
            StatusBar.Severity = transientSeverity;
            StatusBar.IsOpen = true;
            return;
        }
        if (entry.SigningIn)
        {
            StatusBar.Title = "Signing in";
            StatusBar.Message = $"Finish signing in with {provider.AccountName} in your browser.";
            StatusBar.Severity = InfoBarSeverity.Informational;
            StatusBar.IsOpen = true;
            return;
        }
        var cache = entry.Cache;
        if (entry.SignedIn && cache?.LastError is { } error && (entry.Usage is null || cache.LastErrorAt > entry.Usage.FetchedAt))
        {
            StatusBar.Title = entry.Usage is null ? "Couldn't load usage" : "Showing the last update";
            StatusBar.Message = error;
            StatusBar.Severity = entry.Usage is null ? InfoBarSeverity.Error : InfoBarSeverity.Warning;
            StatusBar.IsOpen = true;
            return;
        }
        if (entry.Usage is { LimitReached: true })
        {
            StatusBar.Title = "Limit reached";
            StatusBar.Message = entry.Usage.NextReset(DateTimeOffset.UtcNow) is { } reset
                ? $"{provider.DisplayName} usage is paused until a window resets ({UsageFormat.Relative(reset, DateTimeOffset.UtcNow)})."
                : $"{provider.DisplayName} usage is paused until a window resets.";
            StatusBar.Severity = InfoBarSeverity.Warning;
            StatusBar.IsOpen = true;
            return;
        }
        StatusBar.IsOpen = false;
    }

    private Border ResetCreditsCard(ResetCreditsSnapshot? credits, int fallbackCount)
    {
        var now = DateTimeOffset.UtcNow;
        var count = credits?.AvailableCount ?? fallbackCount;
        var panel = new StackPanel { Spacing = 6 };
        var header = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        header.Children.Add(new FontIcon { Glyph = "", FontSize = 16 });
        header.Children.Add(Cards.Text("Banked resets", "BodyStrongTextBlockStyle"));
        panel.Children.Add(header);
        var countText = Cards.Text(count == 1 ? "1 reset available" : $"{count} resets available", "SubtitleTextBlockStyle");
        AutomationProperties.SetAutomationId(countText, "ResetCreditCount");
        panel.Children.Add(countText);
        panel.Children.Add(Cards.Text("A reset clears your Codex 5-hour and weekly limits right away.", "SecondaryTextStyle"));
        foreach (var credit in (credits?.Credits ?? []).Where(c => c.IsAvailable && (c.ExpiresAt is null || c.ExpiresAt > now))
                     .OrderBy(c => c.ExpiresAt ?? DateTimeOffset.MaxValue).Take(4))
        {
            var expiry = credit.ExpiresAt is { } at
                ? $"expires {UsageFormat.Absolute(at, now, state.Settings.Current.Use24HourClock)} ({UsageFormat.Relative(at, now)})"
                : "no expiry";
            panel.Children.Add(Cards.Text($"• {(credit.Title.Length > 0 ? credit.Title : "Reset")}, {expiry}", "CaptionSecondaryTextStyle"));
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
            var confirm = new ContentDialog
            {
                Title = "Use a reset now?",
                Content = "This clears your Codex 5-hour and weekly limits. The reset that expires first is used.",
                PrimaryButtonText = "Use reset",
                CloseButtonText = "Cancel",
                DefaultButton = ContentDialogButton.Primary,
                XamlRoot = XamlRoot,
                RequestedTheme = ActualTheme,
            };
            if (await confirm.ShowAsync() != ContentDialogResult.Primary) return;
            button.IsEnabled = false;
            try
            {
                var result = await state.UseResetAsync(null);
                ShowMessage(result.UserMessage, result.Applied ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
            }
            catch (Exception error)
            {
                ShowMessage(error.Message, InfoBarSeverity.Error);
            }
        };
        panel.Children.Add(button);
        var card = Cards.Card(panel, "ResetCreditsCard");
        AutomationProperties.SetName(card, "Banked resets");
        return card;
    }

    private static Border PurchasedCreditsCard(UsageCredits credits)
    {
        var panel = new StackPanel { Spacing = 6 };
        var header = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        header.Children.Add(new FontIcon { Glyph = "", FontSize = 16 });
        header.Children.Add(Cards.Text("Usage credits", "BodyStrongTextBlockStyle"));
        panel.Children.Add(header);
        var amount = credits.Unlimited ? "Unlimited"
            : credits.NumericBalance is { } value ? UsageFormat.CreditAmount(value) + " credits"
            : credits.Balance ?? "Available";
        panel.Children.Add(Cards.Text(amount, "SubtitleTextBlockStyle"));
        panel.Children.Add(Cards.Text("Purchased credits keep Codex going after a limit is reached.", "SecondaryTextStyle"));
        var card = Cards.Card(panel, "UsageCreditsCard");
        AutomationProperties.SetName(card, "Usage credits");
        return card;
    }
}
