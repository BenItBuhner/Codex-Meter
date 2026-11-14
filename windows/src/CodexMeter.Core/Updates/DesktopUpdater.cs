namespace CodexMeter.Core.Updates;

public enum UpdateStatus { Idle, Checking, UpToDate, Available, Downloading, Installing, Failed }

/// <summary>What installing a verified download did.</summary>
public enum InstallOutcome
{
    /// <summary>The installer or swap is running and needs this copy closed: quit now.</summary>
    QuitToFinish,
    /// <summary>Installed in place; the new version starts the next time the app opens.</summary>
    InstalledRestartLater,
    /// <summary>Handed to the system (a software center, a disk image): nothing more to do here.</summary>
    HandedOff,
}

/// <summary>The platform half of the updater: which build this is, and how to install a verified download.</summary>
public interface IUpdateInstaller
{
    UpdateTarget Target { get; }

    /// <summary>Why this copy can't update itself (a Flatpak, a source build), or null when it can.</summary>
    string? CannotInstallReason { get; }

    /// <summary>A folder only this user can write to, for downloads.</summary>
    string DownloadDirectory { get; }

    Task<InstallOutcome> InstallAsync(DownloadedUpdate update, CancellationToken cancellationToken);
}

/// <summary>Saved updater preferences and state; the apps keep them in AppSettings.</summary>
public sealed record UpdatePreferences
{
    /// <summary>Null until the user picks a channel: alpha builds then follow alpha, stable builds stable.</summary>
    public UpdateChannel? Channel { get; init; }
    public bool AutoCheck { get; init; } = true;
    public int CheckHours { get; init; } = 24;
    public bool Notify { get; init; } = true;
    public DateTimeOffset? LastCheck { get; init; }
    /// <summary>The newest version a notification was already shown for, so each version is announced once.</summary>
    public string? LastNotified { get; init; }
}

/// <summary>
/// Check, download, verify and install, shared by the Windows and Linux apps. It runs on the UI
/// thread (awaits come back to it) and raises <see cref="Changed"/> after every step so Settings
/// can redraw. Each automatic check asks GitHub for at most two small responses.
/// </summary>
public sealed class DesktopUpdater(UpdateClient client, IUpdateInstaller installer, string installedVersion,
    Func<UpdatePreferences> preferences, Action<Func<UpdatePreferences, UpdatePreferences>> savePreferences)
{
    private CancellationTokenSource? work;
    private DownloadedUpdate? downloaded;

    public ReleaseVersion? Installed { get; } = ReleaseVersion.Parse(installedVersion);
    public string InstalledText => Installed?.Normalized ?? installedVersion;
    public UpdateTarget Target => installer.Target;
    public string? CannotInstallReason => installer.CannotInstallReason;
    public bool IsTestFeed => client.IsTestFeed;

    public UpdateStatus Status { get; private set; } = UpdateStatus.Idle;
    public ReleaseInfo? Available { get; private set; }
    /// <summary>The newest release the channel follows, even when it's not newer than this copy.</summary>
    public ReleaseInfo? Latest { get; private set; }
    public double Progress { get; private set; }
    public string? Error { get; private set; }

    public bool Busy => Status is UpdateStatus.Checking or UpdateStatus.Downloading or UpdateStatus.Installing;

    public event Action? Changed;

    /// <summary>A new version worth a notification (each version once, when notifications are on).</summary>
    public event Action<ReleaseInfo>? UpdateFound;

    /// <summary>The installer is running and this copy has to close.</summary>
    public event Action? QuitRequested;

    public UpdateChannel Channel => UpdateChannels.Effective(preferences().Channel, Installed);

    public bool UserChoseChannel => preferences().Channel is not null;

    public bool IsReturnToStable => Available is { } release && UpdateChannels.IsReturnToStable(release, Installed);

    public void SetChannel(UpdateChannel channel)
    {
        savePreferences(p => p with { Channel = channel, LastNotified = null });
        Available = null;
        Latest = null;
        downloaded = null;
        Status = UpdateStatus.Idle;
        Changed?.Invoke();
    }

    /// <summary>Called every minute or so: checks when automatic checks are on and one is due.</summary>
    public Task TickAsync(DateTimeOffset now)
    {
        var p = preferences();
        if (!p.AutoCheck || Busy || !UpdateCheckFrequency.IsDue(p.LastCheck, p.CheckHours, now)) return Task.CompletedTask;
        return CheckAsync(manual: false);
    }

    public async Task CheckAsync(bool manual = true)
    {
        if (Busy) return;
        Status = UpdateStatus.Checking;
        Error = null;
        Changed?.Invoke();
        work = new CancellationTokenSource();
        try
        {
            var releases = await client.CheckAsync(Channel, Target, work.Token);
            savePreferences(p => p with { LastCheck = DateTimeOffset.UtcNow });
            Latest = UpdateChannels.Tracked(releases, Channel);
            var update = UpdateChannels.SelectUpdate(releases, Installed, Channel, UserChoseChannel);
            if (update?.Version.Normalized != downloaded?.Release.Version.Normalized) downloaded = null;
            Available = update;
            Status = update is null ? UpdateStatus.UpToDate : UpdateStatus.Available;
            if (update is not null && preferences().Notify && preferences().LastNotified != update.Version.Normalized)
            {
                savePreferences(p => p with { LastNotified = update.Version.Normalized });
                if (!manual) UpdateFound?.Invoke(update);
            }
        }
        catch (UpdateException error)
        {
            // An automatic check that fails stays quiet until the next one; Settings shows why.
            savePreferences(p => p with { LastCheck = DateTimeOffset.UtcNow });
            Fail(error.Message);
        }
        catch (OperationCanceledException)
        {
            Status = Available is null ? UpdateStatus.Idle : UpdateStatus.Available;
        }
        catch (Exception error)
        {
            Fail("Couldn't check for updates: " + error.Message);
        }
        finally
        {
            work = null;
            Changed?.Invoke();
        }
    }

    /// <summary>Downloads and verifies the available update, then installs it.</summary>
    public async Task DownloadAndInstallAsync()
    {
        if (Busy || Available is not { } release) return;
        if (CannotInstallReason is not null) { Fail(CannotInstallReason); Changed?.Invoke(); return; }
        work = new CancellationTokenSource();
        try
        {
            if (downloaded is null || !File.Exists(downloaded.Path))
            {
                Status = UpdateStatus.Downloading;
                Progress = 0;
                Error = null;
                Changed?.Invoke();
                var progress = new Progress<double>(value =>
                {
                    Progress = value;
                    Changed?.Invoke();
                });
                downloaded = await client.DownloadAsync(release, Target, installer.DownloadDirectory, progress, work.Token);
            }
            Status = UpdateStatus.Installing;
            Changed?.Invoke();
            var outcome = await installer.InstallAsync(downloaded, work.Token);
            Status = outcome == InstallOutcome.QuitToFinish ? UpdateStatus.Installing : UpdateStatus.UpToDate;
            if (outcome != InstallOutcome.QuitToFinish) Available = null;
            Changed?.Invoke();
            if (outcome == InstallOutcome.QuitToFinish) QuitRequested?.Invoke();
        }
        catch (UpdateException error)
        {
            Fail(error.Message);
        }
        catch (OperationCanceledException)
        {
            Status = UpdateStatus.Available;
        }
        catch (Exception error)
        {
            Fail("Couldn't install the update: " + error.Message);
        }
        finally
        {
            work = null;
            Changed?.Invoke();
        }
    }

    public void Cancel() => work?.Cancel();

    private void Fail(string message)
    {
        Error = message;
        Status = UpdateStatus.Failed;
    }

    /// <summary>One line for Settings: what the updater is doing or found.</summary>
    public string StatusText => Status switch
    {
        UpdateStatus.Checking => "Checking GitHub for updates…",
        UpdateStatus.Downloading => $"Downloading {Available?.Version.Normalized} · {Progress:P0}",
        UpdateStatus.Installing => "Verified. Installing the update…",
        UpdateStatus.Available when IsReturnToStable => $"Stable {Available!.Version.Normalized} is available. Installing it leaves the alpha channel.",
        UpdateStatus.Available => $"Codex Meter {Available!.Version.Normalized} is available.",
        UpdateStatus.UpToDate => Latest is null
            ? $"No {UpdateChannels.Label(Channel).ToLowerInvariant()} release for this computer yet. You're on {InstalledText}."
            : $"You're up to date ({InstalledText}).",
        UpdateStatus.Failed => Error ?? "The update check failed.",
        _ => LastCheckText,
    };

    public string LastCheckText => preferences().LastCheck is { } at
        ? $"Version {InstalledText}. Last checked {at.ToLocalTime():MMM d, h:mm tt}."
        : $"Version {InstalledText}.";
}
