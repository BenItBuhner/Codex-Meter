using CodexMeter.App.Assets;
using CodexMeter.App.Controls;
using CodexMeter.App.Services;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace CodexMeter.App.Views;

/// <summary>
/// The sign-in dialogs, one per way a provider connects: a browser round trip, a code to
/// approve on the provider's site, a token to paste back, or an API key.
/// </summary>
public static class SignInFlow
{
    private const string PrivacyNote = "Credentials are encrypted with your Windows account and never leave this PC.";

    /// <summary>Runs the provider's sign-in. Returns an error to show, or null on success or cancel.</summary>
    public static Task<string?> RunAsync(UsageProvider provider, XamlRoot root) => provider.SignIn switch
    {
        SignInStyle.ApiKey => ApiKeyAsync(provider, root),
        SignInStyle.DeviceCode => DeviceAsync(provider, root),
        SignInStyle.CodePaste => PasteAsync(provider, root),
        _ => BrowserAsync(provider, root),
    };

    internal static ContentDialog Dialog(XamlRoot root, string title, UIElement content, string automationId)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = content,
            CloseButtonText = "Cancel",
            XamlRoot = root,
            RequestedTheme = (root.Content as FrameworkElement)?.ActualTheme ?? ElementTheme.Default,
            Style = Cards.Style("DefaultContentDialogStyle"),
        };
        AutomationProperties.SetAutomationId(dialog, automationId);
        return dialog;
    }

    private static string Message(Exception failure) =>
        failure is ProviderException ? failure.Message : "Sign-in failed: " + failure.Message;

    private static StackPanel Intro(UsageProvider provider, string text)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14 };
        var icon = new Border { Width = 40, Height = 40, Child = ProviderIcons.Create(provider.Id, 32), VerticalAlignment = VerticalAlignment.Top };
        row.Children.Add(icon);
        row.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.WrapWholeWords, MaxWidth = 360, VerticalAlignment = VerticalAlignment.Center });
        return row;
    }

    private static TextBlock Caption(string text) => new()
    {
        Text = text,
        Style = Cards.Style("CaptionSecondaryTextStyle"),
        TextWrapping = TextWrapping.WrapWholeWords,
        MaxWidth = 420,
    };

    private static InfoBar ErrorBar()
    {
        var bar = new InfoBar { Severity = InfoBarSeverity.Error, IsClosable = false, IsOpen = false, Title = "Couldn't connect" };
        AutomationProperties.SetAutomationId(bar, "SignInError");
        return bar;
    }

    private static InfoBar InferredNote(UsageProvider provider) => new()
    {
        Severity = InfoBarSeverity.Informational,
        IsClosable = false,
        IsOpen = provider.Inferred,
        Title = "Unconfirmed",
        Message = $"This follows {provider.VendorName}'s own tools, but it hasn't been confirmed with a real account yet.",
    };

    // ------------------------------------------------------------------ Browser (loopback or poll)

    private static async Task<string?> BrowserAsync(UsageProvider provider, XamlRoot root)
    {
        var state = App.Current!.State;
        using var cancel = new CancellationTokenSource();
        Uri? opened = null;
        var link = new HyperlinkButton { Content = "Open the sign-in page again", Visibility = Visibility.Collapsed, Padding = new Thickness(0) };
        link.Click += (_, _) => { if (opened is not null) _ = Browser.OpenAsync(opened); };

        var body = new StackPanel { Spacing = 14, MinWidth = 380 };
        body.Children.Add(Intro(provider, $"Finish signing in with {provider.AccountName} in your browser. This window updates on its own."));
        var waiting = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        waiting.Children.Add(new ProgressRing { IsActive = true, Width = 16, Height = 16 });
        waiting.Children.Add(Caption("Waiting for your browser…"));
        body.Children.Add(waiting);
        body.Children.Add(Caption(provider.SignIn == SignInStyle.Loopback
            ? "Your browser returns to Codex Meter on this computer (localhost). " + PrivacyNote
            : $"Codex Meter checks with {provider.VendorName} until you approve the login. " + PrivacyNote));
        body.Children.Add(link);
        if (provider.AcceptsApiKey && provider.KeyUrl.Length > 0)
        {
            var useKey = new HyperlinkButton { Content = "Use an API key instead", Padding = new Thickness(0) };
            body.Children.Add(useKey);
            useKey.Click += (_, _) => { cancel.Cancel(); };
        }

        var dialog = Dialog(root, provider.SignInLabel, body, "SignInDialog");
        string? error = null;
        var work = state.SignInAsync(provider, new SignInPrompt(async url =>
        {
            opened = url;
            link.Visibility = Visibility.Visible;
            await Browser.OpenAsync(url);
        }), cancel.Token).ContinueWith(task =>
        {
            if (task.Exception?.InnerException is { } failure and not OperationCanceledException) error = Message(failure);
            dialog.Hide();
        }, TaskScheduler.FromCurrentSynchronizationContext());

        await dialog.ShowAsync();
        var switchedToKey = cancel.IsCancellationRequested;
        if (!work.IsCompleted)
        {
            cancel.Cancel();
            error = null;
        }
        await work;
        if (switchedToKey && provider.AcceptsApiKey) return await ApiKeyAsync(provider, root);
        return error;
    }

    // ------------------------------------------------------------------ Device code

    private static async Task<string?> DeviceAsync(UsageProvider provider, XamlRoot root)
    {
        var state = App.Current!.State;
        using var cancel = new CancellationTokenSource();
        DeviceCode? code = null;

        var body = new StackPanel { Spacing = 14, MinWidth = 400 };
        body.Children.Add(Intro(provider, $"Approve Codex Meter on {provider.VendorName}'s site. Enter this code when it asks:"));

        var codeBox = new Border
        {
            Background = Cards.Brush("ControlFillColorDefaultBrush"),
            BorderBrush = Cards.Brush("CardStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(16, 12, 16, 12),
        };
        var codeText = new TextBlock
        {
            Text = "Getting a code…",
            FontFamily = new FontFamily("Cascadia Mono, Consolas"),
            FontSize = 26,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            IsTextSelectionEnabled = true,
            HorizontalAlignment = HorizontalAlignment.Center,
            CharacterSpacing = 120,
        };
        AutomationProperties.SetAutomationId(codeText, "DeviceCode");
        codeBox.Child = codeText;
        body.Children.Add(codeBox);

        var open = new Button { Content = "Copy code and open page", Style = Cards.Style("AccentButtonStyle"), IsEnabled = false, HorizontalAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(open, "OpenDevicePage");
        open.Click += async (_, _) =>
        {
            if (code is null) return;
            if (code.UserCode.Length > 0)
            {
                var package = new DataPackage();
                package.SetText(code.UserCode);
                Clipboard.SetContent(package);
            }
            await Browser.OpenAsync(new Uri(code.OpenUrl));
        };
        body.Children.Add(open);

        var waiting = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        waiting.Children.Add(new ProgressRing { IsActive = true, Width = 16, Height = 16 });
        var waitingText = Caption("Waiting for approval…");
        waiting.Children.Add(waitingText);
        body.Children.Add(waiting);
        body.Children.Add(InferredNote(provider));
        body.Children.Add(Caption(PrivacyNote));

        var dialog = Dialog(root, provider.SignInLabel, body, "SignInDialog");
        var switchedToKey = false;
        if (provider.AcceptsApiKey)
        {
            dialog.SecondaryButtonText = "Use an API key";
            dialog.SecondaryButtonClick += (_, _) => switchedToKey = true;
        }

        string? error = null;
        var prompt = new SignInPrompt(Browser.OpenAsync)
        {
            ShowDeviceCode = shown =>
            {
                code = shown;
                codeText.Text = shown.UserCode.Length > 0 ? shown.UserCode : "No code needed";
                if (shown.UserCode.Length == 0) open.Content = "Open the approval page";
                open.IsEnabled = true;
                waitingText.Text = $"Waiting for you to approve on {new Uri(shown.OpenUrl).Host}…";
                return Task.CompletedTask;
            },
        };
        var work = state.SignInAsync(provider, prompt, cancel.Token).ContinueWith(task =>
        {
            if (task.Exception?.InnerException is { } failure and not OperationCanceledException) error = Message(failure);
            dialog.Hide();
        }, TaskScheduler.FromCurrentSynchronizationContext());

        await dialog.ShowAsync();
        if (!work.IsCompleted)
        {
            cancel.Cancel();
            error = null;
        }
        await work;
        if (switchedToKey) return await ApiKeyAsync(provider, root);
        return error;
    }

    // ------------------------------------------------------------------ Paste a token

    private static async Task<string?> PasteAsync(UsageProvider provider, XamlRoot root)
    {
        var state = App.Current!.State;
        using var cancel = new CancellationTokenSource();
        Uri? page = null;
        TaskCompletionSource<string?>? pending = null;

        var body = new StackPanel { Spacing = 12, MinWidth = 420 };
        body.Children.Add(Intro(provider, $"Sign in on {provider.VendorName}'s page, then paste what it shows you back here."));
        var open = new Button { Content = "Open sign-in page", IsEnabled = false };
        AutomationProperties.SetAutomationId(open, "OpenPastePage");
        open.Click += (_, _) => { if (page is not null) _ = Browser.OpenAsync(page); };
        body.Children.Add(open);
        var hint = Caption("");
        body.Children.Add(hint);
        var box = new TextBox
        {
            PlaceholderText = "Paste here",
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 72,
            MaxHeight = 140,
            FontFamily = new FontFamily("Cascadia Mono, Consolas"),
        };
        AutomationProperties.SetAutomationId(box, "PasteBox");
        AutomationProperties.SetName(box, "Pasted sign-in text");
        body.Children.Add(box);
        var errorBar = ErrorBar();
        body.Children.Add(errorBar);
        body.Children.Add(InferredNote(provider));
        body.Children.Add(Caption(PrivacyNote));

        var dialog = Dialog(root, provider.SignInLabel, body, "SignInDialog");
        dialog.PrimaryButtonText = "Connect";
        dialog.DefaultButton = ContentDialogButton.Primary;
        dialog.IsPrimaryButtonEnabled = false;
        box.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = box.Text.Trim().Length > 0 && pending is not null;

        Task? work = null;
        void Start()
        {
            var prompt = new SignInPrompt(Browser.OpenAsync)
            {
                RequestPaste = async (url, text, token) =>
                {
                    page = url;
                    hint.Text = text;
                    open.IsEnabled = url is not null;
                    var first = pending is null && !errorBar.IsOpen;
                    pending = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
                    dialog.IsPrimaryButtonEnabled = box.Text.Trim().Length > 0;
                    if (first && url is not null) await Browser.OpenAsync(url);
                    using var registration = token.Register(() => pending.TrySetResult(null));
                    return await pending.Task;
                },
            };
            work = state.SignInAsync(provider, prompt, cancel.Token).ContinueWith(task =>
            {
                if (task.Exception?.InnerException is { } failure and not OperationCanceledException)
                {
                    // Show what went wrong and start a fresh attempt; the dialog stays open.
                    errorBar.Message = Message(failure);
                    errorBar.IsOpen = true;
                    pending = null;
                    if (!cancel.IsCancellationRequested) Start();
                    return;
                }
                if (task.IsCompletedSuccessfully) dialog.Hide();
            }, TaskScheduler.FromCurrentSynchronizationContext());
        }

        dialog.PrimaryButtonClick += (_, args) =>
        {
            args.Cancel = true;
            if (pending is null) return;
            errorBar.IsOpen = false;
            dialog.IsPrimaryButtonEnabled = false;
            pending.TrySetResult(box.Text);
        };
        Start();
        await dialog.ShowAsync();
        cancel.Cancel();
        pending?.TrySetResult(null);
        try { if (work is not null) await work; } catch (OperationCanceledException) { }
        return null;
    }

    // ------------------------------------------------------------------ API key

    private static async Task<string?> ApiKeyAsync(UsageProvider provider, XamlRoot root)
    {
        var state = App.Current!.State;
        using var cancel = new CancellationTokenSource();
        var body = new StackPanel { Spacing = 12, MinWidth = 420 };
        body.Children.Add(Intro(provider, $"Create a key on {provider.VendorName}'s site and paste it here. Codex Meter checks it before saving."));
        if (provider.KeyUrl.Length > 0)
        {
            var get = new HyperlinkButton { Content = $"Get a {provider.DisplayName} key", NavigateUri = new Uri(provider.KeyUrl), Padding = new Thickness(0) };
            body.Children.Add(get);
        }
        var box = new PasswordBox { PlaceholderText = "API key", PasswordRevealMode = PasswordRevealMode.Peek };
        AutomationProperties.SetAutomationId(box, "ApiKeyBox");
        AutomationProperties.SetName(box, provider.DisplayName + " API key");
        body.Children.Add(box);
        var checking = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Visibility = Visibility.Collapsed };
        checking.Children.Add(new ProgressRing { IsActive = true, Width = 16, Height = 16 });
        checking.Children.Add(Caption("Checking the key…"));
        body.Children.Add(checking);
        var errorBar = ErrorBar();
        body.Children.Add(errorBar);
        body.Children.Add(InferredNote(provider));
        body.Children.Add(Caption("The key is only used to read your usage. " + PrivacyNote));

        var dialog = Dialog(root, provider.DisplayName + " API key", body, "ApiKeyDialog");
        dialog.PrimaryButtonText = "Connect";
        dialog.DefaultButton = ContentDialogButton.Primary;
        dialog.IsPrimaryButtonEnabled = false;
        box.PasswordChanged += (_, _) => dialog.IsPrimaryButtonEnabled = box.Password.Trim().Length > 0;
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            args.Cancel = true;
            var deferral = args.GetDeferral();
            dialog.IsPrimaryButtonEnabled = false;
            box.IsEnabled = false;
            checking.Visibility = Visibility.Visible;
            errorBar.IsOpen = false;
            var success = false;
            try
            {
                await state.SignInWithApiKeyAsync(provider, box.Password, cancel.Token);
                success = true;
            }
            catch (Exception failure) when (failure is not OperationCanceledException)
            {
                errorBar.Message = Message(failure);
                errorBar.IsOpen = true;
            }
            catch (OperationCanceledException) { }
            finally
            {
                checking.Visibility = Visibility.Collapsed;
                box.IsEnabled = true;
                dialog.IsPrimaryButtonEnabled = box.Password.Trim().Length > 0;
                deferral.Complete();
            }
            if (success) dialog.Hide();
        };
        await dialog.ShowAsync();
        cancel.Cancel();
        return null;
    }
}
