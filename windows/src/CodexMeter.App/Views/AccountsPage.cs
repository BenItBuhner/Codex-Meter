using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CommunityToolkit.WinUI.Controls;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Views;

/// <summary>Connected accounts and Add account, like Settings → Accounts on the phone apps.</summary>
public sealed class AccountsPage : PageBase
{
    private readonly InfoBar status = new() { IsClosable = true, IsOpen = false, Margin = new Thickness(0, 0, 0, 8) };
    private readonly StackPanel connected = new() { Spacing = 4 };

    public AccountsPage() : base(1000)
    {
        Body.Children.Add(PageTitle("Accounts", "AccountsTitle"));
        var intro = Cards.Text("Connect the services you use. Credentials are encrypted with your Windows account and stay on this PC.", "SecondaryTextStyle");
        intro.Margin = new Thickness(0, 0, 0, 16);
        Body.Children.Add(intro);
        Body.Children.Add(status);

        var header = new Grid { Margin = new Thickness(1, 8, 0, 8) };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var label = Cards.Text("Connected", "BodyStrongTextBlockStyle", wrap: false);
        label.VerticalAlignment = VerticalAlignment.Center;
        header.Children.Add(label);
        var add = new Button { Style = Cards.Style("AccentButtonStyle") };
        var addContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        addContent.Children.Add(new FontIcon { Glyph = "", FontSize = 14 });
        addContent.Children.Add(new TextBlock { Text = "Add account" });
        add.Content = addContent;
        AutomationProperties.SetAutomationId(add, "AddAccountButton");
        AutomationProperties.SetName(add, "Add account");
        add.Click += async (_, _) =>
        {
            if (App.Current?.Window is { } window && await window.AddAccountAsync() is { } error) Show(error, InfoBarSeverity.Error);
        };
        Grid.SetColumn(add, 1);
        header.Children.Add(add);
        Body.Children.Add(header);
        Body.Children.Add(connected);
    }

    protected override void Attach()
    {
        State.ProviderChanged += OnChanged;
        Render();
    }

    protected override void Detach() => State.ProviderChanged -= OnChanged;

    private void OnChanged(UsageProvider _) => Render();

    private void Render()
    {
        connected.Children.Clear();
        foreach (var entry in State.Entries.Where(e => e.SignedIn || e.SigningIn))
        {
            var provider = entry.Provider;
            var card = new SettingsCard
            {
                Header = provider.DisplayName,
                HeaderIcon = ProviderIcons.Create(provider.Id, 20),
            };
            AutomationProperties.SetAutomationId(card, "Account_" + provider.Id);
            if (!entry.SignedIn)
            {
                card.Description = "Signing in…";
                card.Content = new ProgressRing { IsActive = true, Width = 20, Height = 20 };
                connected.Children.Add(card);
                continue;
            }
            var plan = entry.Usage is { } usage
                ? (provider == UsageProvider.Codex ? UsageFormat.PlanLabel(usage.PlanType) : usage.PlanType)
                : "";
            var who = entry.Account!.Email.Length > 0 ? entry.Account.Email : $"Signed in with {provider.AccountName}";
            card.Description = plan.Length > 0 ? $"{who} · {plan}" : who;
            var signOut = new Button { Content = "Sign out" };
            AutomationProperties.SetAutomationId(signOut, "SignOut_" + provider.Id);
            AutomationProperties.SetName(signOut, $"Sign out of {provider.DisplayName}");
            signOut.Click += async (_, _) => await ConfirmSignOutAsync(provider);
            card.Content = signOut;
            connected.Children.Add(card);
        }
        if (connected.Children.Count == 0)
        {
            var empty = Cards.Text("No accounts yet. Choose Add account to connect Codex, Claude Code, Cursor, Copilot and more.", "SecondaryTextStyle");
            empty.Margin = new Thickness(1, 0, 0, 0);
            connected.Children.Add(empty);
        }
    }

    private async Task ConfirmSignOutAsync(UsageProvider provider)
    {
        var dialog = SignInFlow.Dialog(XamlRoot, $"Sign out of {provider.DisplayName}?",
            Cards.Text($"Codex Meter forgets this {provider.AccountName} sign-in, its saved usage and its history on this PC. Your other accounts stay connected.",
                "BodyTextBlockStyle"), "SignOutDialog");
        dialog.PrimaryButtonText = "Sign out";
        dialog.DefaultButton = ContentDialogButton.Close;
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        await State.SignOutAsync(provider);
        Show($"Signed out of {provider.DisplayName}.", InfoBarSeverity.Success);
    }

    private void Show(string message, InfoBarSeverity severity)
    {
        status.Message = message;
        status.Severity = severity;
        status.IsOpen = true;
    }
}
