using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.Core.Providers;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;

namespace CodexMeter.App.Views;

/// <summary>First run: what the app does and a one-click start for the common services.</summary>
public sealed class WelcomePage : PageBase
{
    private readonly InfoBar status = new() { IsClosable = true, IsOpen = false, Severity = InfoBarSeverity.Error, Title = "Couldn't connect" };

    public WelcomePage() : base(640)
    {
        Body.Spacing = 12;
        Body.VerticalAlignment = VerticalAlignment.Center;
        Body.Padding = new Thickness(36, 48, 36, 36);
        Body.Children.Add(new Image
        {
            Source = new BitmapImage(new Uri("ms-appx:///Assets/AppIcon.png")),
            Width = 72,
            Height = 72,
            HorizontalAlignment = HorizontalAlignment.Left,
        });
        Body.Children.Add(PageTitle("Welcome to Codex Meter", "WelcomeTitle"));
        Body.Children.Add(Cards.Text("See how much of your AI coding limits you have left: 5-hour and weekly windows, monthly allowances, credits and resets, for every service you use, in one place and in the notification area.",
            "SecondaryTextStyle"));
        Body.Children.Add(status);

        var quick = new StackPanel { Spacing = 4, Margin = new Thickness(0, 12, 0, 0) };
        foreach (var provider in UsageProvider.BuiltIns.Where(State.Supports))
        {
            var button = new Button
            {
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Padding = new Thickness(14, 10, 14, 10),
            };
            var row = new Grid { ColumnSpacing = 14 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.Children.Add(new Border { Width = 28, Height = 28, Child = ProviderIcons.Create(provider.Id, 24), VerticalAlignment = VerticalAlignment.Center });
            var text = new StackPanel();
            text.Children.Add(Cards.Text(provider.SignInLabel, "BodyStrongTextBlockStyle", wrap: false));
            text.Children.Add(Cards.Text(provider.Meters, "CaptionSecondaryTextStyle", wrap: false));
            Grid.SetColumn(text, 1);
            row.Children.Add(text);
            button.Content = row;
            AutomationProperties.SetAutomationId(button, "Welcome_" + provider.Id);
            AutomationProperties.SetName(button, provider.SignInLabel);
            button.Click += async (_, _) =>
            {
                var error = await SignInFlow.RunAsync(provider, XamlRoot);
                if (error is not null) Show(error);
                else if (State[provider].SignedIn) App.Current?.Window?.Navigate(provider.Id);
            };
            quick.Children.Add(button);
        }
        Body.Children.Add(quick);

        var more = new HyperlinkButton { Content = "More services: Copilot, Kimi, Z.ai, OpenRouter and others" };
        AutomationProperties.SetAutomationId(more, "WelcomeMore");
        more.Click += async (_, _) =>
        {
            if (App.Current?.Window is { } window && await window.AddAccountAsync() is { } error) Show(error);
        };
        Body.Children.Add(more);
        Body.Children.Add(Cards.Text("Credentials are encrypted with your Windows account and never leave this PC.", "CaptionSecondaryTextStyle"));
    }

    protected override void Attach() { }

    protected override void Detach() { }

    private void Show(string message)
    {
        status.Message = message;
        status.IsOpen = true;
    }
}
