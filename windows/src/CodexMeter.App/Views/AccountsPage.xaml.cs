using CodexMeter.App.Assets;
using CodexMeter.App.Services;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CommunityToolkit.WinUI.Controls;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace CodexMeter.App.Views;

/// <summary>Sign providers in and out, like Settings → Accounts on the phone apps.</summary>
public sealed partial class AccountsPage : Page
{
    private readonly AppState state = App.Current!.State;

    public AccountsPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        state.ProviderChanged += OnChanged;
        Render();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => state.ProviderChanged -= OnChanged;

    private void OnChanged(UsageProvider _) => Render();

    private void Render()
    {
        ConnectedList.Children.Clear();
        AvailableList.Children.Clear();
        foreach (var entry in state.Entries)
        {
            var provider = entry.Provider;
            var card = new SettingsCard
            {
                Header = provider.DisplayName,
                HeaderIcon = ProviderIcons.Create(provider.Id, 20),
            };
            AutomationProperties.SetAutomationId(card, "Account_" + provider.Id);
            if (entry.SignedIn)
            {
                var plan = entry.Usage is { } usage
                    ? (provider == UsageProvider.Codex ? UsageFormat.PlanLabel(usage.PlanType) : usage.PlanType)
                    : "";
                var who = entry.Account!.Email.Length > 0 ? entry.Account.Email : $"Signed in with {provider.AccountName}";
                card.Description = plan.Length > 0 ? $"{who} · {plan}" : who;
                var signOut = new Button { Content = "Sign out" };
                AutomationProperties.SetAutomationId(signOut, "SignOut_" + provider.Id);
                signOut.Click += async (_, _) => await ConfirmSignOutAsync(provider);
                card.Content = signOut;
                ConnectedList.Children.Add(card);
            }
            else
            {
                card.Description = $"{provider.Meters} · {provider.SignInSummary}";
                var signIn = new Button
                {
                    Content = entry.SigningIn ? "Signing in…" : provider.SignInLabel,
                    Style = (Style)Application.Current.Resources["AccentButtonStyle"],
                    IsEnabled = !entry.SigningIn,
                };
                AutomationProperties.SetAutomationId(signIn, "SignIn_" + provider.Id);
                signIn.Click += async (_, _) =>
                {
                    var error = await SignInFlow.RunAsync(provider, XamlRoot);
                    if (error is not null) Show(error, InfoBarSeverity.Error);
                };
                card.Content = signIn;
                AvailableList.Children.Add(card);
            }
        }
        if (ConnectedList.Children.Count == 0)
        {
            ConnectedList.Children.Add(new TextBlock
            {
                Text = "No accounts yet. Add one below to see its limits.",
                Style = (Style)Application.Current.Resources["SecondaryTextStyle"],
                Margin = new Thickness(1, 0, 0, 0),
            });
        }
        AvailableHeader.Visibility = AvailableList.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private async Task ConfirmSignOutAsync(UsageProvider provider)
    {
        var dialog = new ContentDialog
        {
            Title = $"Sign out of {provider.DisplayName}?",
            Content = $"Codex Meter forgets this {provider.AccountName} sign-in and its saved usage on this PC. Your other accounts stay connected.",
            PrimaryButtonText = "Sign out",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot,
            RequestedTheme = ActualTheme,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        await state.SignOutAsync(provider);
        Show($"Signed out of {provider.DisplayName}.", InfoBarSeverity.Success);
    }

    private void Show(string message, InfoBarSeverity severity)
    {
        StatusBar.Message = message;
        StatusBar.Severity = severity;
        StatusBar.IsOpen = true;
    }
}
