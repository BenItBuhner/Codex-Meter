using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.Core.Formatting;
using CodexMeter.Core.Providers;
using CommunityToolkit.WinUI.Controls;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Views;

/// <summary>
/// Providers, like Settings → Providers on the phone apps: every connected or shown service with its
/// account, the switch for its sidebar page, its place in the order, Sign out, and Add account.
/// (Accounts and the sidebar's provider list are one page.) Its tag stays "accounts".
/// </summary>
public sealed class AccountsPage : PageBase
{
    private readonly InfoBar status = new() { IsClosable = true, IsOpen = false, Margin = new Thickness(0, 0, 0, 8) };
    private readonly StackPanel connected = new() { Spacing = 4 };
    private bool dirty;

    public AccountsPage() : base(1000)
    {
        Body.Children.Add(PageTitle("Providers", "AccountsTitle"));
        var intro = Cards.Text("Each service keeps its own sign-in, and each switch gives it a page in the sidebar. Credentials are encrypted with your Windows account and stay on this PC.", "SecondaryTextStyle");
        intro.Margin = new Thickness(0, 0, 0, 16);
        Body.Children.Add(intro);
        Body.Children.Add(status);

        var header = new Grid { Margin = new Thickness(1, 8, 0, 8) };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var label = Cards.Text("On your dashboard", "BodyStrongTextBlockStyle", wrap: false);
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
        State.ProvidersChanged += QueueRender;
        Render();
    }

    protected override void Detach()
    {
        State.ProviderChanged -= OnChanged;
        State.ProvidersChanged -= QueueRender;
    }

    private void OnChanged(UsageProvider _) => QueueRender();

    /// <summary>Rebuilds once after a burst of changes, not inside the toggle that caused them.</summary>
    private void QueueRender()
    {
        if (dirty) return;
        dirty = true;
        DispatcherQueue.TryEnqueue(() =>
        {
            dirty = false;
            Render();
        });
    }

    private void Render()
    {
        connected.Children.Clear();
        var enabled = State.EnabledProviders;
        var order = State.SidebarCandidates.ToList();
        var listed = order.Concat(State.Entries.Where(e => e.SigningIn && !order.Contains(e.Provider)).Select(e => e.Provider)).ToList();
        foreach (var provider in listed)
        {
            var entry = State[provider];
            var card = new SettingsCard
            {
                Header = provider.DisplayName,
                HeaderIcon = ProviderIcons.Create(provider.Id, 20),
            };
            // Account_ marks a connected (or connecting) service; ProviderRow_ one that isn't.
            AutomationProperties.SetAutomationId(card, (entry.SignedIn || entry.SigningIn ? "Account_" : "ProviderRow_") + provider.Id);
            if (entry.SigningIn && !entry.SignedIn)
            {
                card.Description = "Signing in…";
                card.Content = new ProgressRing { IsActive = true, Width = 20, Height = 20 };
                connected.Children.Add(card);
                continue;
            }
            if (entry.SignedIn)
            {
                var plan = entry.Usage is { } usage
                    ? (provider == UsageProvider.Codex ? UsageFormat.PlanLabel(usage.PlanType) : usage.PlanType)
                    : "";
                var who = entry.Account!.Email.Length > 0 ? entry.Account.Email : $"Signed in with {provider.AccountName}";
                card.Description = plan.Length > 0 ? $"{who} · {plan}" : who;
            }
            else
            {
                card.Description = $"Not connected · {provider.Meters}";
            }
            var position = order.IndexOf(provider);
            var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
            controls.Children.Add(MoveButton("\uE70E", $"Move {provider.DisplayName} up", position > 0, () => State.Move(provider, -1)));
            controls.Children.Add(MoveButton("\uE70D", $"Move {provider.DisplayName} down", position >= 0 && position < order.Count - 1, () => State.Move(provider, 1)));
            var toggle = new ToggleSwitch { IsOn = enabled.Contains(provider), OnContent = "", OffContent = "", MinWidth = 0 };
            AutomationProperties.SetAutomationId(toggle, "ProviderToggle_" + provider.Id);
            AutomationProperties.SetName(toggle, "Show " + provider.DisplayName);
            toggle.IsEnabled = !(toggle.IsOn && enabled.Count == 1);
            toggle.Toggled += (_, _) => State.SetEnabled(provider, toggle.IsOn);
            controls.Children.Add(toggle);
            if (entry.SignedIn)
            {
                var signOut = new Button { Content = "Sign out" };
                AutomationProperties.SetAutomationId(signOut, "SignOut_" + provider.Id);
                AutomationProperties.SetName(signOut, $"Sign out of {provider.DisplayName}");
                signOut.Click += async (_, _) => await ConfirmSignOutAsync(provider);
                controls.Children.Add(signOut);
            }
            card.Content = controls;
            connected.Children.Add(card);
        }
        if (connected.Children.Count == 0)
        {
            var empty = Cards.Text("No accounts yet. Choose Add account to connect Codex, Claude Code, Cursor, Copilot and more.", "SecondaryTextStyle");
            empty.Margin = new Thickness(1, 0, 0, 0);
            connected.Children.Add(empty);
        }
    }

    internal static Button MoveButton(string glyph, string name, bool enabled, Action move)
    {
        var button = new Button { Content = new FontIcon { Glyph = glyph, FontSize = 12 }, IsEnabled = enabled, Padding = new Thickness(8) };
        AutomationProperties.SetName(button, name);
        ToolTipService.SetToolTip(button, name);
        button.Click += (_, _) => move();
        return button;
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
