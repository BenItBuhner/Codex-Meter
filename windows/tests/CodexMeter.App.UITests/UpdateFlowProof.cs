using System.Diagnostics;
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Capturing;
using Xunit;

namespace CodexMeter.App.UITests;

/// <summary>Runs only when desktop-update-proof.yml sets CODEXMETER_UPDATE_FEED (a loopback release feed).</summary>
public sealed class UpdateProofFactAttribute : FactAttribute
{
    public UpdateProofFactAttribute()
    {
        if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("CODEXMETER_UPDATE_FEED")))
            Skip = "Needs the update-proof workflow's local release feed.";
    }
}

/// <summary>
/// The whole in-app update on a real install: the automatic check finds the newer release and
/// shows a notification, a tampered download is refused by the SHA-256 check, the honest
/// download installs through the setup, and the app reopens as the new version.
/// </summary>
public class UpdateFlowProof
{
    private static readonly string Feed = Environment.GetEnvironmentVariable("CODEXMETER_UPDATE_FEED") ?? "";
    private static readonly string NewVersion = Environment.GetEnvironmentVariable("CODEXMETER_NEW_VERSION") ?? "";
    private static readonly string Shots = Environment.GetEnvironmentVariable("CODEXMETER_PROOF_DIR") ?? Path.GetTempPath();

    private static void Shot(string name)
    {
        Directory.CreateDirectory(Shots);
        Capture.Screen().ToFile(Path.Combine(Shots, name + ".png"));
    }

    private static string ButtonText(AppSession app) => app.TryFind("UpdateActionButton")?.Name ?? "";

    private static void WaitForButton(AppSession app, string text, TimeSpan timeout, string? shotWhenSeen = null)
    {
        var until = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < until)
        {
            if (ButtonText(app) == text)
            {
                if (shotWhenSeen is not null) Shot(shotWhenSeen);
                return;
            }
            Thread.Sleep(500);
        }
        Shot("failure-waiting-for-" + text.Replace(' ', '-'));
        throw new TimeoutException($"The update button never read '{text}' (it reads '{ButtonText(app)}').");
    }

    [UpdateProofFact]
    public void UpdatesInPlaceAndReopensAsTheNewVersion()
    {
        var exe = Environment.GetEnvironmentVariable("CODEXMETER_EXE")!;
        using (var app = new AppSession("--update-feed", Feed, "--page", "settings"))
        {
            Thread.Sleep(2000);
            Shot("01-settings-before-check");

            // The background tick runs a minute after launch; its automatic check posts the notification.
            WaitForButton(app, "Download and install", TimeSpan.FromSeconds(100), "02-update-found-notification");
            Thread.Sleep(1500);
            Shot("03-update-available");

            // The feed flips one byte of the first download: the checksum check must refuse it.
            app.Find("UpdateActionButton").AsButton().Invoke();
            WaitForButton(app, "Check for updates", TimeSpan.FromSeconds(120));
            Thread.Sleep(800);
            Shot("04-tampered-download-refused");

            app.Find("UpdateActionButton").AsButton().Invoke();
            WaitForButton(app, "Download and install", TimeSpan.FromSeconds(60));
            app.Find("UpdateActionButton").AsButton().Invoke();

            var started = DateTime.UtcNow;
            var shot = 0;
            while (!app.Application.HasExited && DateTime.UtcNow - started < TimeSpan.FromMinutes(3))
            {
                if (shot < 6) Shot($"05-installing-{++shot}");
                Thread.Sleep(1500);
            }
            Assert.True(app.Application.HasExited, "The app did not quit to let the setup replace it.");
        }

        // The setup (run with /RELAUNCH=1) reopens the app once the files are replaced.
        Process? reopened = null;
        var deadline = DateTime.UtcNow + TimeSpan.FromMinutes(3);
        while (reopened is null && DateTime.UtcNow < deadline)
        {
            Shot("06-setup-running");
            Thread.Sleep(2000);
            reopened = Process.GetProcessesByName("CodexMeter").FirstOrDefault();
        }
        Assert.NotNull(reopened);
        Thread.Sleep(5000);
        Shot("07-reopened-after-update");

        var installed = FileVersionInfo.GetVersionInfo(exe).ProductVersion ?? "";
        Assert.StartsWith(NewVersion, installed);
        try { reopened.Kill(); reopened.WaitForExit(10000); } catch { }

        using (var after = new AppSession("--page", "settings"))
        {
            Thread.Sleep(2500);
            Assert.NotNull(after.Find("UpdateStatusCard"));
            Shot("08-settings-after-update");
        }
    }
}
