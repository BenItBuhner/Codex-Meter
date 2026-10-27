using CodexMeter.Core.Providers;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Views;

/// <summary>
/// The sign-in dialog: opens the provider's page in the default browser and waits (with a Cancel
/// button) until the browser returns to the app or Cursor confirms the login.
/// </summary>
public static class SignInDialog
{
    /// <summary>Wording for where credentials live, shared with Settings → About.</summary>
    public static string StorageDescription(KeyLocation? location) => location switch
    {
        KeyLocation.PrivateFile => "Credentials are encrypted with a key in a private file in your home folder (no keyring was found) and never leave this computer.",
        _ => "Credentials are encrypted with a key kept in your login keyring and never leave this computer.",
    };

    public static async Task<string?> RunAsync(AppState state, UsageProvider provider, Gtk.Window parent, KeyLocation? location = null)
    {
        using var cancel = new CancellationTokenSource();
        Uri? opened = null;

        var body = Gtk.Box.New(Gtk.Orientation.Vertical, 14);
        var row = Gtk.Box.New(Gtk.Orientation.Horizontal, 14);
        var spinner = Gtk.Spinner.New();
        spinner.SetSpinning(true);
        spinner.SetSizeRequest(24, 24);
        row.Append(spinner);
        var text = Widgets.Cards.Label($"Finish signing in with {provider.AccountName} in your browser. This window updates on its own.");
        text.SetHexpand(true);
        row.Append(text);
        body.Append(row);
        body.Append(Widgets.Cards.Label(
            (provider.SignIn == SignInStyle.Loopback
                ? "Your browser returns to Codex Meter on this computer (localhost). "
                : "Codex Meter checks with Cursor until you approve the login. ")
            + StorageDescription(location ?? App.Current?.KeyLocation), "caption", "dim-label"));
        var link = Gtk.LinkButton.NewWithLabel("https://localhost/", "Open the sign-in page again");
        link.SetHalign(Gtk.Align.Start);
        link.SetVisible(false);
        link.OnActivateLink += (_, _) =>
        {
            if (opened is not null) _ = Browser.OpenAsync(opened);
            return true;
        };
        body.Append(link);

        var dialog = Adw.AlertDialog.New(provider.SignInLabel, null);
        dialog.SetExtraChild(body);
        dialog.AddResponse("cancel", "_Cancel");
        dialog.SetCloseResponse("cancel");
        dialog.SetName("SignInDialog");
        var finished = false;
        dialog.OnResponse += (_, _) =>
        {
            if (!finished) cancel.Cancel();
        };
        dialog.Present(parent);

        string? error = null;
        try
        {
            await state.SignInAsync(provider, async url =>
            {
                opened = url;
                link.SetVisible(true);
                await Browser.OpenAsync(url);
            }, cancel.Token);
        }
        catch (OperationCanceledException) { }
        catch (ProviderException failure) when (!cancel.IsCancellationRequested) { error = failure.Message; }
        catch (Exception failure) when (!cancel.IsCancellationRequested) { error = "Sign-in failed: " + failure.Message; }
        finally
        {
            finished = true;
            dialog.ForceClose();
        }
        return error;
    }
}
