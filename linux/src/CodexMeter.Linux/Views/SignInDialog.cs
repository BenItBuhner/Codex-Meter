using CodexMeter.App.Assets;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;
using CodexMeter.Linux.Widgets;

namespace CodexMeter.Linux.Views;

/// <summary>
/// The sign-in dialogs, one per way a provider connects: a browser round trip, a code to approve
/// on the provider's site, a token to paste back, or an API key. Same flows as the Windows app.
/// </summary>
public static class SignInDialog
{
    /// <summary>Wording for where credentials live, shared with Settings → About.</summary>
    public static string StorageDescription(KeyLocation? location) => location switch
    {
        KeyLocation.PrivateFile => "Credentials are encrypted with a key in a private file in your home folder (no keyring was found) and never leave this computer.",
        _ => "Credentials are encrypted with a key kept in your login keyring and never leave this computer.",
    };

    /// <summary>Runs the provider's sign-in. Returns an error to show, or null on success or cancel.</summary>
    public static Task<string?> RunAsync(AppState state, UsageProvider provider, Gtk.Window parent, KeyLocation? location = null) =>
        provider.SignIn switch
        {
            SignInStyle.ApiKey => ApiKeyAsync(state, provider, parent, location),
            SignInStyle.DeviceCode => DeviceAsync(state, provider, parent, location),
            SignInStyle.CodePaste => PasteAsync(state, provider, parent, location),
            _ => BrowserAsync(state, provider, parent, location),
        };

    private static string Message(Exception failure) =>
        failure is ProviderException ? failure.Message : "Sign-in failed: " + failure.Message;

    private static string Privacy(KeyLocation? location) => StorageDescription(location ?? App.Current?.KeyLocation);

    /// <summary>A dialog with a header bar, the content, and a button row at the bottom.</summary>
    private sealed class Sheet
    {
        public Sheet(string title, string name)
        {
            Dialog = Adw.Dialog.New();
            Dialog.SetTitle(title);
            Dialog.SetContentWidth(440);
            Dialog.SetName(name);
            Body = Gtk.Box.New(Gtk.Orientation.Vertical, 14);
            Body.SetMarginStart(24);
            Body.SetMarginEnd(24);
            Body.SetMarginTop(6);
            Body.SetMarginBottom(24);
            Buttons = Gtk.Box.New(Gtk.Orientation.Horizontal, 12);
            Buttons.SetHomogeneous(true);
            Buttons.SetMarginTop(8);
            var view = Adw.ToolbarView.New();
            view.AddTopBar(Adw.HeaderBar.New());
            var all = Gtk.Box.New(Gtk.Orientation.Vertical, 0);
            all.Append(Body);
            view.SetContent(all);
            Dialog.SetChild(view);
            Dialog.OnClosed += (_, _) => Closed.TrySetResult();
        }

        public Adw.Dialog Dialog { get; }
        public Gtk.Box Body { get; }
        public Gtk.Box Buttons { get; }
        public TaskCompletionSource Closed { get; } = new();

        public void Present(Gtk.Widget parent)
        {
            Body.Append(Buttons);
            Dialog.Present(parent);
        }

        public Gtk.Button Button(string label, string? style = null, string? name = null)
        {
            var button = Gtk.Button.NewWithMnemonic(label);
            button.AddCssClass("pill");
            if (style is not null) button.AddCssClass(style);
            if (name is not null) button.SetName(name);
            Buttons.Append(button);
            return button;
        }
    }

    private static Gtk.Widget Intro(UsageProvider provider, string text)
    {
        var row = Gtk.Box.New(Gtk.Orientation.Horizontal, 14);
        var icon = ProviderIcons.Create(provider.Id, 32);
        icon.SetValign(Gtk.Align.Start);
        row.Append(icon);
        var label = Cards.Label(text);
        label.SetMaxWidthChars(48);
        label.SetHexpand(true);
        row.Append(label);
        return row;
    }

    private static Gtk.Label Caption(string text)
    {
        var label = Cards.Label(text, "caption", "dim-label");
        label.SetMaxWidthChars(56);
        return label;
    }

    private static Gtk.Widget Waiting(string text, out Gtk.Label label)
    {
        var row = Gtk.Box.New(Gtk.Orientation.Horizontal, 10);
        var spinner = Gtk.Spinner.New();
        spinner.SetSpinning(true);
        row.Append(spinner);
        label = Caption(text);
        row.Append(label);
        return row;
    }

    private static Gtk.Widget? InferredNote(UsageProvider provider) => provider.Inferred
        ? PageScaffold.Status("info", "Unconfirmed",
            $"This follows {provider.VendorName}'s own tools, but it hasn't been confirmed with a real account yet.")
        : null;

    private static Gtk.Label ErrorLabel()
    {
        var label = Cards.Label("", "error");
        label.SetName("SignInError");
        label.SetMaxWidthChars(56);
        label.SetVisible(false);
        return label;
    }

    // ------------------------------------------------------------------ Browser (loopback or poll)

    private static async Task<string?> BrowserAsync(AppState state, UsageProvider provider, Gtk.Window parent, KeyLocation? location)
    {
        using var cancel = new CancellationTokenSource();
        Uri? opened = null;
        var sheet = new Sheet(provider.SignInLabel, "SignInDialog");
        sheet.Body.Append(Intro(provider, $"Finish signing in with {provider.AccountName} in your browser. This window updates on its own."));
        sheet.Body.Append(Waiting("Waiting for your browser…", out _));
        sheet.Body.Append(Caption((provider.SignIn == SignInStyle.Loopback
            ? "Your browser returns to Codex Meter on this computer (localhost). "
            : $"Codex Meter checks with {provider.VendorName} until you approve the login. ") + Privacy(location)));
        var again = Gtk.Button.NewWithLabel("Open the Sign-in Page Again");
        again.AddCssClass("flat");
        again.SetHalign(Gtk.Align.Start);
        again.SetVisible(false);
        again.OnClicked += (_, _) => { if (opened is not null) _ = Browser.OpenAsync(opened); };
        sheet.Body.Append(again);
        var switchedToKey = false;
        if (provider.AcceptsApiKey && provider.KeyUrl.Length > 0)
        {
            var useKey = sheet.Button("Use an _API Key");
            useKey.OnClicked += (_, _) =>
            {
                switchedToKey = true;
                sheet.Dialog.Close();
            };
        }
        sheet.Button("_Cancel").OnClicked += (_, _) => sheet.Dialog.Close();
        sheet.Present(parent);

        string? error = null;
        var work = state.SignInAsync(provider, new SignInPrompt(async url =>
        {
            opened = url;
            again.SetVisible(true);
            await Browser.OpenAsync(url);
        }), cancel.Token);
        await Task.WhenAny(work, sheet.Closed.Task);
        if (!work.IsCompleted) cancel.Cancel();
        try { await work; }
        catch (OperationCanceledException) { }
        catch (Exception failure) when (!cancel.IsCancellationRequested) { error = Message(failure); }
        catch (Exception) { }
        sheet.Dialog.ForceClose();
        if (switchedToKey) return await ApiKeyAsync(state, provider, parent, location);
        return error;
    }

    // ------------------------------------------------------------------ Device code

    /// <summary>The code-to-approve dialog. Public so the screenshot run can show it.</summary>
    public static async Task<string?> DeviceAsync(AppState state, UsageProvider provider, Gtk.Window parent, KeyLocation? location = null,
        Action<Adw.Dialog>? opened = null)
    {
        using var cancel = new CancellationTokenSource();
        DeviceCode? code = null;
        var sheet = new Sheet(provider.SignInLabel, "SignInDialog");
        sheet.Body.Append(Intro(provider, $"Approve Codex Meter on {provider.VendorName}'s site. Enter this code when it asks:"));
        var codeLabel = Gtk.Label.New("Getting a code…");
        codeLabel.AddCssClass("title-1");
        codeLabel.AddCssClass("monospace");
        codeLabel.AddCssClass("device-code");
        codeLabel.SetName("DeviceCode");
        sheet.Body.Append(codeLabel);
        var open = Gtk.Button.NewWithLabel("Copy Code and Open Page");
        open.AddCssClass("suggested-action");
        open.AddCssClass("pill");
        open.SetName("OpenDevicePage");
        open.SetSensitive(false);
        open.OnClicked += async (_, _) =>
        {
            if (code is null) return;
            if (code.UserCode.Length > 0) Gdk.Display.GetDefault()?.GetClipboard().SetText(code.UserCode);
            await Browser.OpenAsync(new Uri(code.OpenUrl));
        };
        sheet.Body.Append(open);
        sheet.Body.Append(Waiting("Waiting for approval…", out var waiting));
        if (InferredNote(provider) is { } note) sheet.Body.Append(note);
        sheet.Body.Append(Caption(Privacy(location)));
        var switchedToKey = false;
        if (provider.AcceptsApiKey)
        {
            sheet.Button("Use an _API Key").OnClicked += (_, _) =>
            {
                switchedToKey = true;
                sheet.Dialog.Close();
            };
        }
        sheet.Button("_Cancel").OnClicked += (_, _) => sheet.Dialog.Close();
        sheet.Present(parent);
        opened?.Invoke(sheet.Dialog);

        var prompt = new SignInPrompt(Browser.OpenAsync)
        {
            ShowDeviceCode = shown =>
            {
                code = shown;
                codeLabel.SetText(shown.UserCode.Length > 0 ? shown.UserCode : "No code needed");
                if (shown.UserCode.Length == 0) open.SetLabel("Open the Approval Page");
                open.SetSensitive(true);
                waiting.SetText($"Waiting for you to approve on {new Uri(shown.OpenUrl).Host}…");
                return Task.CompletedTask;
            },
        };
        string? error = null;
        var work = state.SignInAsync(provider, prompt, cancel.Token);
        await Task.WhenAny(work, sheet.Closed.Task);
        if (!work.IsCompleted) cancel.Cancel();
        try { await work; }
        catch (OperationCanceledException) { }
        catch (Exception failure) when (!cancel.IsCancellationRequested) { error = Message(failure); }
        catch (Exception) { }
        sheet.Dialog.ForceClose();
        if (switchedToKey) return await ApiKeyAsync(state, provider, parent, location);
        return error;
    }

    // ------------------------------------------------------------------ Paste a token

    private static async Task<string?> PasteAsync(AppState state, UsageProvider provider, Gtk.Window parent, KeyLocation? location)
    {
        using var cancel = new CancellationTokenSource();
        Uri? page = null;
        TaskCompletionSource<string?>? pending = null;
        var sheet = new Sheet(provider.SignInLabel, "SignInDialog");
        sheet.Body.Append(Intro(provider, $"Sign in on {provider.VendorName}'s page, then paste what it shows you back here."));
        var open = Gtk.Button.NewWithLabel("Open Sign-in Page");
        open.SetHalign(Gtk.Align.Start);
        open.SetName("OpenPastePage");
        open.SetSensitive(false);
        open.OnClicked += (_, _) => { if (page is not null) _ = Browser.OpenAsync(page); };
        sheet.Body.Append(open);
        var hint = Caption("");
        sheet.Body.Append(hint);
        var entry = Gtk.Entry.New();
        entry.SetPlaceholderText("Paste here");
        entry.SetName("PasteBox");
        sheet.Body.Append(entry);
        var errorLabel = ErrorLabel();
        sheet.Body.Append(errorLabel);
        if (InferredNote(provider) is { } note) sheet.Body.Append(note);
        sheet.Body.Append(Caption(Privacy(location)));
        sheet.Button("_Cancel").OnClicked += (_, _) => sheet.Dialog.Close();
        var connect = sheet.Button("_Connect", "suggested-action", "ConnectButton");
        connect.SetSensitive(false);
        void Update() => connect.SetSensitive(entry.GetText().Trim().Length > 0 && pending is not null);
        entry.OnChanged += (_, _) => Update();
        void Submit()
        {
            if (pending is null || entry.GetText().Trim().Length == 0) return;
            errorLabel.SetVisible(false);
            connect.SetSensitive(false);
            pending.TrySetResult(entry.GetText());
        }
        connect.OnClicked += (_, _) => Submit();
        entry.OnActivate += (_, _) => Submit();
        sheet.Present(parent);

        var first = true;
        var prompt = new SignInPrompt(Browser.OpenAsync)
        {
            RequestPaste = async (url, text, token) =>
            {
                page = url;
                hint.SetText(text);
                open.SetSensitive(url is not null);
                pending = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
                Update();
                if (first && url is not null) await Browser.OpenAsync(url);
                first = false;
                using var registration = token.Register(() => pending.TrySetResult(null));
                return await pending.Task;
            },
        };
        while (true)
        {
            var work = state.SignInAsync(provider, prompt, cancel.Token);
            await Task.WhenAny(work, sheet.Closed.Task);
            if (!work.IsCompleted)
            {
                cancel.Cancel();
                pending?.TrySetResult(null);
            }
            try
            {
                await work;
                break;
            }
            catch (Exception) when (cancel.IsCancellationRequested) { break; }
            catch (Exception failure)
            {
                // Show what went wrong and start a fresh attempt; the dialog stays open.
                errorLabel.SetText(Message(failure));
                errorLabel.SetVisible(true);
                pending = null;
            }
        }
        sheet.Dialog.ForceClose();
        return null;
    }

    // ------------------------------------------------------------------ API key

    private static async Task<string?> ApiKeyAsync(AppState state, UsageProvider provider, Gtk.Window parent, KeyLocation? location)
    {
        using var cancel = new CancellationTokenSource();
        var sheet = new Sheet(provider.DisplayName + " API Key", "ApiKeyDialog");
        sheet.Body.Append(Intro(provider, $"Create a key on {provider.VendorName}'s site and paste it here. Codex Meter checks it before saving."));
        if (provider.KeyUrl.Length > 0)
        {
            var get = Gtk.Button.NewWithLabel($"Get a {provider.DisplayName} Key");
            get.AddCssClass("flat");
            get.SetHalign(Gtk.Align.Start);
            get.OnClicked += (_, _) => _ = Browser.OpenAsync(new Uri(provider.KeyUrl));
            sheet.Body.Append(get);
        }
        var key = Gtk.PasswordEntry.New();
        key.SetShowPeekIcon(true);
        key.SetName("ApiKeyBox");
        sheet.Body.Append(key);
        var checking = Waiting("Checking the key…", out _);
        checking.SetVisible(false);
        sheet.Body.Append(checking);
        var errorLabel = ErrorLabel();
        sheet.Body.Append(errorLabel);
        if (InferredNote(provider) is { } note) sheet.Body.Append(note);
        sheet.Body.Append(Caption("The key is only used to read your usage. " + Privacy(location)));
        sheet.Button("_Cancel").OnClicked += (_, _) => sheet.Dialog.Close();
        var connect = sheet.Button("_Connect", "suggested-action", "ConnectButton");
        connect.SetSensitive(false);
        key.OnChanged += (_, _) => connect.SetSensitive(key.GetText().Trim().Length > 0);
        async Task Submit()
        {
            if (key.GetText().Trim().Length == 0) return;
            connect.SetSensitive(false);
            key.SetSensitive(false);
            checking.SetVisible(true);
            errorLabel.SetVisible(false);
            try
            {
                await state.SignInWithApiKeyAsync(provider, key.GetText(), cancel.Token);
                sheet.Dialog.ForceClose();
            }
            catch (OperationCanceledException) { }
            catch (Exception failure)
            {
                errorLabel.SetText(Message(failure));
                errorLabel.SetVisible(true);
            }
            finally
            {
                checking.SetVisible(false);
                key.SetSensitive(true);
                connect.SetSensitive(key.GetText().Trim().Length > 0);
            }
        }
        connect.OnClicked += async (_, _) => await Submit();
        key.OnActivate += async (_, _) => await Submit();
        sheet.Present(parent);
        await sheet.Closed.Task;
        cancel.Cancel();
        return null;
    }
}
