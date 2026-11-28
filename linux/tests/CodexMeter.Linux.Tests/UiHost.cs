using System.Runtime.ExceptionServices;
using CodexMeter.Core.Providers;
using CodexMeter.Core.Models;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Tests;

/// <summary>
/// One real libadwaita application for every UI test, on its own thread (GTK may only be used from
/// the thread that started it). Tests post their bodies onto its main loop with <see cref="RunAsync"/>,
/// so awaits inside resume on that loop the way they do in the app. Needs a display: CI runs these
/// under xvfb-run.
/// </summary>
public sealed class UiHost : IDisposable
{
    private readonly Thread thread;
    private readonly TaskCompletionSource<SynchronizationContext> ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private Adw.Application? application;

    public UiHost()
    {
        thread = new Thread(Main) { IsBackground = true, Name = "GTK" };
        thread.Start();
        Context = ready.Task.WaitAsync(TimeSpan.FromSeconds(30)).GetAwaiter().GetResult();
    }

    private SynchronizationContext Context { get; }

    public Adw.Application Application => application!;

    private void Main()
    {
        try
        {
            if (Environment.GetEnvironmentVariable("DISPLAY") is not { Length: > 0 } && Environment.GetEnvironmentVariable("WAYLAND_DISPLAY") is not { Length: > 0 })
                throw new InvalidOperationException("The UI tests need a display. Run them under xvfb-run (see run-tests.sh).");
            Adw.Module.Initialize();
            application = Adw.Application.New("io.github.benitbuhner.CodexMeter.UiTests", Gio.ApplicationFlags.NonUnique);
            application.OnStartup += (_, _) => Widgets.Style.Install();
            application.OnActivate += (_, _) =>
            {
                application.Hold();
                ready.TrySetResult(SynchronizationContext.Current!);
            };
            application.RunWithSynchronizationContext([]);
        }
        catch (Exception error)
        {
            ready.TrySetException(error);
        }
    }

    /// <summary>Runs <paramref name="body"/> on the GTK main loop and waits for it.</summary>
    public Task RunAsync(Func<Task> body)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        Context.Post(async _ =>
        {
            try
            {
                await body();
                done.SetResult();
            }
            catch (Exception error)
            {
                done.SetException(error);
            }
        }, null);
        return done.Task.WaitAsync(TimeSpan.FromSeconds(60));
    }

    public Task RunAsync(Action body) => RunAsync(() =>
    {
        body();
        return Task.CompletedTask;
    });

    /// <summary>Lets queued GLib work (idle callbacks, posted continuations) run.</summary>
    public static async Task Settle(int milliseconds = 50)
    {
        await Task.Delay(milliseconds);
        var context = GLib.MainContext.Default();
        for (var i = 0; i < 100 && context.Pending(); i++) context.Iteration(false);
    }

    /// <summary>
    /// Keeps the main loop running until <paramref name="condition"/> holds, for work GTK does on a
    /// timer (a button's activate fires its click 250 ms later) and refreshes that resume on the loop.
    /// </summary>
    public static async Task Until(Func<bool> condition, int timeoutMilliseconds = 5000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMilliseconds);
        while (!condition())
        {
            if (DateTime.UtcNow > deadline) return;
            await Task.Delay(25);
        }
        await Settle(20);
    }

    public void Dispose()
    {
        if (application is { } app)
        {
            Context.Post(_ =>
            {
                app.Release();
                app.Quit();
            }, null);
            thread.Join(TimeSpan.FromSeconds(10));
        }
    }
}

[CollectionDefinition(Name, DisableParallelization = true)]
public sealed class UiCollection : ICollectionFixture<UiHost>
{
    public const string Name = "GTK";
}

/// <summary>Finds widgets the way a person scans the page: by name and by the text they show.</summary>
internal static class Tree
{
    public static IEnumerable<Gtk.Widget> All(Gtk.Widget root)
    {
        yield return root;
        for (var child = root.GetFirstChild(); child is not null; child = child.GetNextSibling())
        {
            foreach (var widget in All(child)) yield return widget;
        }
    }

    public static Gtk.Widget? Named(Gtk.Widget root, string name) => All(root).FirstOrDefault(w => w.GetName() == name);

    public static Gtk.Widget Require(Gtk.Widget root, string name) =>
        Named(root, name) ?? throw new Xunit.Sdk.XunitException($"No widget named {name}. Named widgets: {string.Join(", ", All(root).Select(w => w.GetName()).Where(n => !n.StartsWith("Gtk", StringComparison.Ordinal) && !n.StartsWith("Adw", StringComparison.Ordinal)).Distinct())}");

    /// <summary>Every label's text under <paramref name="root"/>, in reading order.</summary>
    public static List<string> Texts(Gtk.Widget root) =>
        All(root).Where(w => w.GetVisible()).OfType<Gtk.Label>().Select(l => l.GetText()).Where(t => t.Length > 0).ToList();

    public static string Text(Gtk.Widget root) => string.Join("\n", Texts(root));
}

/// <summary>Demo and scripted states for the UI and state tests.</summary>
internal static class States
{
    public static async Task<AppState> DemoAsync(IEnumerable<string>? signedOut = null, IEnumerable<string>? enabled = null)
    {
        var context = new ProviderContext(ProviderHttp.CreateClient(), new MemoryTokenStore(), new ProviderCacheStore(null));
        var hub = await ProviderHub.DemoAsync(context, signedOut);
        var state = new AppState(hub, new SettingsStore(null), new UsageHistoryStore(null));
        await state.InitializeAsync();
        if (enabled is not null) state.Settings.Update(s => s with { EnabledProviders = enabled.ToList(), OnboardingDone = true });
        // Each demo refresh waits 250 ms like a network call; side by side, not one after another.
        await Task.WhenAll(hub.Providers.Select(p => state.RefreshAsync(p, force: true)));
        return state;
    }
}
