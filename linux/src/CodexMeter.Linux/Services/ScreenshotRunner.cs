using CodexMeter.Core.Services;
using CodexMeter.Linux.Views;

namespace CodexMeter.Linux.Services;

/// <summary>
/// <c>--screenshots DIR</c>: walks every page in light and dark with demo data, saves a PNG of the
/// real window (header bars included, rendered by GTK itself) for each, then exits. Used by CI.
/// </summary>
public sealed class ScreenshotRunner(MainWindow window, AppState state, string directory, string prefix = "")
{
    public async Task<int> RunAsync()
    {
        Directory.CreateDirectory(directory);
        await Delay(1500);
        var pages = state.EnabledProviders.Select(p => p.Id).Concat(["accounts", "settings"]).ToList();
        var failures = 0;
        foreach (var theme in new[] { AppTheme.Light, AppTheme.Dark })
        {
            state.Settings.Update(s => s with { Theme = theme });
            var name = theme.ToString().ToLowerInvariant();
            await Resize(1100, 780);
            var shot = pages.Count + 1;
            if (state.SignedIn.Count == 0)
            {
                // First run: the welcome page.
                window.Navigate("welcome");
                await Delay(900);
                failures += Capture($"{name}-00-welcome.png") ? 0 : 1;
            }
            for (var i = 0; i < pages.Count; i++)
            {
                window.Navigate(pages[i]);
                await Delay(900);
                if (window.CurrentTag != pages[i])
                {
                    AppLog.Write($"Expected {pages[i]} but the window shows {window.CurrentTag}.");
                    failures++;
                }
                failures += Capture($"{name}-{i + 1:00}-{pages[i]}.png") ? 0 : 1;
                // The confirmations, as they appear over the page.
                Adw.AlertDialog? dialog = window.CurrentPage switch
                {
                    DashboardPage dashboard when pages[i] == "codex" && state[Core.Providers.UsageProvider.Codex].SignedIn => dashboard.ConfirmUseReset(),
                    AccountsPage accounts when state.SignedIn.Count > 0 => accounts.ConfirmSignOut(state.SignedIn[0]),
                    _ => null,
                };
                if (dialog is not null)
                {
                    await Delay(900);
                    failures += Capture($"{name}-{i + 1:00}-{pages[i]}-dialog.png") ? 0 : 1;
                    dialog.ForceClose();
                    await Delay(500);
                }
                if (pages[i] == "settings" && window.CurrentPage is { } page)
                {
                    // The rest of Settings, scrolled to the bottom.
                    var adjustment = page.Scroller.GetVadjustment();
                    adjustment.SetValue(adjustment.GetUpper());
                    await Delay(500);
                    failures += Capture($"{name}-{i + 1:00}-{pages[i]}-more.png") ? 0 : 1;
                }
            }
            failures += await ExtrasAsync(name, pages, shot);
            shot += 4;
            // Narrow window: the split view collapses into one pane with a back button.
            await Resize(400, 760);
            window.ShowContent(false);
            await Delay(700);
            failures += Capture($"{name}-{shot++:00}-compact-sidebar.png") ? 0 : 1;
            window.Navigate(pages[0]);
            window.ShowContent(true);
            await Delay(900);
            failures += Capture($"{name}-{shot:00}-compact.png") ? 0 : 1;
            window.ShowContent(false);
        }
        if (prefix.Length == 0) failures += TrayIcon.SaveSamples(directory) ? 0 : 1;
        if (state.SignedIn.Count == 0) failures += await SignInAsync();
        return failures == 0 ? 0 : 4;
    }

    /// <summary>The usage history chart, Add account, Edit dashboard and a device-code sign-in.</summary>
    private async Task<int> ExtrasAsync(string name, List<string> pages, int shot)
    {
        var failures = 0;
        var dashboard = pages.FirstOrDefault(id => Core.Providers.UsageProvider.Find(id) is { } p && state[p].SignedIn && state[p].Usage is not null);
        if (dashboard is not null)
        {
            window.Navigate(dashboard);
            await Delay(900);
            if (window.CurrentPage is { } page && Find(page.Page, "HistoryCard") is { } card && card.GetParent() is { } parent)
            {
                // Scroll so the chart sits near the top.
                var content = page.Scroller.GetChild()!;
                card.TranslateCoordinates(content, 0, 0, out _, out var y);
                page.Scroller.GetVadjustment().SetValue(Math.Max(0, y - 24));
                await Delay(600);
                failures += Capture($"{name}-{shot:00}-history.png") ? 0 : 1;
                page.Scroller.GetVadjustment().SetValue(0);
            }
        }

        Adw.Dialog? opened = null;
        _ = window.AddAccountAsync(d => opened = d);
        await Delay(900);
        failures += Capture($"{name}-{shot + 1:00}-add-account.png") ? 0 : 1;
        opened?.ForceClose();
        await Delay(500);

        if (dashboard is not null)
        {
            window.Navigate(dashboard);
            await Delay(500);
            var edit = window.EditDashboard();
            await Delay(900);
            failures += Capture($"{name}-{shot + 2:00}-edit-dashboard.png") ? 0 : 1;
            edit?.ForceClose();
            await Delay(500);
        }

        if (state.Entries.FirstOrDefault(e => !e.SignedIn && e.Provider.SignIn == Core.Providers.SignInStyle.DeviceCode) is { } device)
        {
            opened = null;
            var run = SignInDialog.DeviceAsync(state, device.Provider, window.Window, KeyLocation.Keyring, d => opened = d);
            await Delay(1500);
            failures += Capture($"{name}-{shot + 3:00}-sign-in-code.png") ? 0 : 1;
            opened?.Close();
            await run;
            await Delay(500);
        }
        return failures;
    }

    private static Gtk.Widget? Find(Gtk.Widget root, string name)
    {
        if (root.GetName() == name) return root;
        for (var child = root.GetFirstChild(); child is not null; child = child.GetNextSibling())
            if (Find(child, name) is { } found) return found;
        return null;
    }

    /// <summary>Signed-out run: the whole sign-in flow with the demo service (dialog, then the filled dashboard).</summary>
    private async Task<int> SignInAsync()
    {
        var failures = 0;
        state.Settings.Update(s => s with { Theme = AppTheme.Light });
        await Resize(1100, 780);
        var provider = Core.Providers.UsageProvider.Codex;
        window.Navigate(provider.Id);
        await Delay(500);
        var signIn = SignInDialog.RunAsync(state, provider, window.Window, KeyLocation.Keyring);
        await Delay(250);
        failures += Capture("light-20-signing-in.png") ? 0 : 1;
        var error = await signIn;
        if (error is not null || !state[provider].SignedIn)
        {
            AppLog.Write("Demo sign-in failed: " + (error ?? "still signed out"));
            failures++;
        }
        await Delay(900);
        failures += Capture("light-21-after-sign-in.png") ? 0 : 1;
        return failures;
    }

    private static Task Delay(int milliseconds)
    {
        var done = new TaskCompletionSource();
        GLib.Functions.TimeoutAdd(GLib.Constants.PRIORITY_DEFAULT, (uint)milliseconds, () =>
        {
            done.TrySetResult();
            return false;
        });
        return done.Task;
    }

    private async Task Resize(int width, int height)
    {
        window.Window.SetDefaultSize(width, height);
        window.ShowAndActivate();
        await Delay(800);
    }

    private bool Capture(string file)
    {
        var path = Path.Combine(directory, prefix + file);
        try
        {
            var widget = window.Window;
            int width = widget.GetWidth(), height = widget.GetHeight();
            if (width <= 0 || height <= 0) return false;
            var paintable = Gtk.WidgetPaintable.New(widget);
            var snapshot = Gtk.Snapshot.New();
            paintable.Snapshot(snapshot, width, height);
            var node = snapshot.ToNode();
            if (node is null) return false;
            var renderer = widget.GetNative()?.GetRenderer();
            if (renderer is null) return false;
            var texture = renderer.RenderTexture(node, null);
            return texture.SaveToPng(path);
        }
        catch (Exception error)
        {
            AppLog.Write($"Capture of {file} failed: {error.Message}");
            return false;
        }
    }
}
