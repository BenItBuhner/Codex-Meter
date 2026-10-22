using CodexMeter.App.Assets;
using CodexMeter.App.Services;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace CodexMeter.App.Views;

/// <summary>
/// The sign-in dialog: opens the provider's page in the default browser and waits (with a Cancel
/// button) until the browser returns to the app or Cursor confirms the login.
/// </summary>
public static class SignInFlow
{
    public static async Task<string?> RunAsync(UsageProvider provider, XamlRoot root)
    {
        var state = App.Current!.State;
        using var cancel = new CancellationTokenSource();
        Uri? opened = null;
        var link = new HyperlinkButton { Content = "Open the sign-in page again", Visibility = Visibility.Collapsed, Padding = new Thickness(0) };
        link.Click += (_, _) => { if (opened is not null) _ = Browser.OpenAsync(opened); };

        var body = new StackPanel { Spacing = 14, MinWidth = 360 };
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14 };
        row.Children.Add(new ProgressRing { IsActive = true, Width = 28, Height = 28 });
        row.Children.Add(new TextBlock
        {
            Text = $"Finish signing in with {provider.AccountName} in your browser. This window updates on its own.",
            TextWrapping = TextWrapping.WrapWholeWords,
            MaxWidth = 320,
            VerticalAlignment = VerticalAlignment.Center,
        });
        body.Children.Add(row);
        body.Children.Add(new TextBlock
        {
            Text = provider.SignIn == SignInStyle.Loopback
                ? "Your browser returns to Codex Meter on this computer (localhost). Credentials are encrypted with your Windows account and never leave this PC."
                : "Codex Meter checks with Cursor until you approve the login. Credentials are encrypted with your Windows account and never leave this PC.",
            Style = (Style)Application.Current.Resources["CaptionSecondaryTextStyle"],
            TextWrapping = TextWrapping.WrapWholeWords,
            MaxWidth = 380,
        });
        body.Children.Add(link);

        var dialog = new ContentDialog
        {
            Title = provider.SignInLabel,
            Content = body,
            CloseButtonText = "Cancel",
            XamlRoot = root,
            RequestedTheme = (root.Content as FrameworkElement)?.ActualTheme ?? ElementTheme.Default,
        };
        AutomationProperties.SetAutomationId(dialog, "SignInDialog");

        string? error = null;
        var work = state.SignInAsync(provider, async url =>
        {
            opened = url;
            link.Visibility = Visibility.Visible;
            await Browser.OpenAsync(url);
        }, cancel.Token).ContinueWith(task =>
        {
            if (task.Exception?.InnerException is { } failure and not OperationCanceledException)
                error = failure is ProviderException ? failure.Message : "Sign-in failed: " + failure.Message;
            dialog.Hide();
        }, TaskScheduler.FromCurrentSynchronizationContext());

        var result = await dialog.ShowAsync();
        if (!work.IsCompleted)
        {
            cancel.Cancel();
            error = null;
        }
        await work;
        return error;
    }
}
