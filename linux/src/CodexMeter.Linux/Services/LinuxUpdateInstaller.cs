using System.Diagnostics;
using System.Runtime.InteropServices;
using CodexMeter.Core.Updates;

namespace CodexMeter.Linux.Services;

/// <summary>
/// Installs a verified update on Linux. An AppImage writes the new AppImage next to itself and
/// renames it over the old file (the running copy keeps its mounted image), then reopens. A copy
/// installed from the .deb installs the new package with <c>pkexec apt-get install</c>, which asks
/// for the administrator password, and reopens; without pkexec the .deb opens in the software
/// center. Flatpaks and builds from source only link to the release. The download's SHA-256 has
/// already been checked against SHA256SUMS-linux-&lt;arch&gt;.txt.
/// </summary>
public sealed class LinuxUpdateInstaller : IUpdateInstaller
{
    private readonly string? appImage = Environment.GetEnvironmentVariable("APPIMAGE");

    public LinuxUpdateInstaller()
    {
        var arch = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "arm64" : "x64";
        var kind = PackageKind.Unmanaged;
        if (Environment.GetEnvironmentVariable("FLATPAK_ID") is { Length: > 0 })
            CannotInstallReason = "This copy is a Flatpak: it updates through your software center.";
        else if (appImage is { Length: > 0 } path && File.Exists(path))
        {
            kind = PackageKind.AppImage;
            if (!CanWrite(Path.GetDirectoryName(path)!))
                CannotInstallReason = "Codex Meter can't write to the AppImage's folder. Download the new AppImage from the release page.";
        }
        else if (AppContext.BaseDirectory.StartsWith("/usr/lib/codex-meter", StringComparison.Ordinal) && File.Exists("/usr/bin/dpkg"))
            kind = PackageKind.Deb;
        else
            CannotInstallReason = "This copy wasn't installed from a release package, so it can't replace itself. Get the new version from the release page.";
        Target = new UpdateTarget(kind, arch);
    }

    public UpdateTarget Target { get; }

    public string? CannotInstallReason { get; }

    public string DownloadDirectory { get; } = Path.Combine(AppPaths.CacheDirectory, "updates");

    public async Task<InstallOutcome> InstallAsync(DownloadedUpdate update, CancellationToken cancellationToken)
    {
        switch (Target.Kind)
        {
            case PackageKind.AppImage:
                ReplaceAppImage(update);
                Relaunch(appImage!);
                return InstallOutcome.QuitToFinish;
            case PackageKind.Deb:
                return await InstallDebAsync(update, cancellationToken);
            default:
                throw new UpdateException(CannotInstallReason ?? "This copy can't update itself.");
        }
    }

    private void ReplaceAppImage(DownloadedUpdate update)
    {
        var target = appImage!;
        var directory = Path.GetDirectoryName(target)!;
        // Same folder, so the final rename is atomic: the old file is replaced in one step or not at all.
        var temp = Path.Combine(directory, $".{Path.GetFileName(target)}.update-{Environment.ProcessId}");
        File.Copy(update.Path, temp, overwrite: true);
        File.SetUnixFileMode(temp, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute
            | UnixFileMode.GroupRead | UnixFileMode.GroupExecute | UnixFileMode.OtherRead | UnixFileMode.OtherExecute);
        File.Move(temp, target, overwrite: true);
        AppLog.Write($"Update: replaced {target} with {update.Release.Version.Normalized} ({update.Sha256})");
    }

    private async Task<InstallOutcome> InstallDebAsync(DownloadedUpdate update, CancellationToken cancellationToken)
    {
        // apt reads the package as root; a world-readable copy also keeps its sandboxed download user happy.
        var shared = Path.Combine(Path.GetTempPath(), Path.GetFileName(update.Path));
        File.Copy(update.Path, shared, overwrite: true);
        File.SetUnixFileMode(shared, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead);
        if (!File.Exists("/usr/bin/pkexec"))
        {
            Process.Start(new ProcessStartInfo("xdg-open", shared) { UseShellExecute = false });
            return InstallOutcome.HandedOff;
        }
        AppLog.Write($"Update: installing {Path.GetFileName(shared)} ({update.Sha256}) with apt-get");
        using var process = Process.Start(new ProcessStartInfo("/usr/bin/pkexec")
        {
            ArgumentList = { "/usr/bin/apt-get", "install", "-y", "--allow-downgrades", shared },
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        })!;
        var errors = process.StandardError.ReadToEndAsync(cancellationToken);
        await process.StandardOutput.ReadToEndAsync(cancellationToken);
        await process.WaitForExitAsync(cancellationToken);
        if (process.ExitCode is 126 or 127)
            throw new UpdateException("Installing the update needs your administrator password. Try again, or install the .deb from the release page.");
        if (process.ExitCode != 0)
        {
            AppLog.Write("Update: apt-get failed: " + await errors);
            throw new UpdateException($"apt-get couldn't install the update (exit code {process.ExitCode}).");
        }
        Relaunch("/usr/bin/codex-meter");
        return InstallOutcome.QuitToFinish;
    }

    /// <summary>Starts the updated app once this process has exited (it's single-instance, so not before).</summary>
    private static void Relaunch(string executable)
    {
        var start = new ProcessStartInfo("/bin/sh")
        {
            ArgumentList = { "-c", "while kill -0 \"$0\" 2>/dev/null; do sleep 0.5; done; exec setsid \"$1\" >/dev/null 2>&1", Environment.ProcessId.ToString(), executable },
            UseShellExecute = false,
        };
        // The new AppImage mounts itself; it must not inherit this one's mount point.
        start.Environment.Remove("APPDIR");
        start.Environment.Remove("APPIMAGE");
        start.Environment.Remove("ARGV0");
        start.Environment.Remove("OWD");
        Process.Start(start);
    }

    private static bool CanWrite(string directory)
    {
        try
        {
            var probe = Path.Combine(directory, $".codex-meter-write-test-{Environment.ProcessId}");
            File.WriteAllText(probe, "");
            File.Delete(probe);
            return true;
        }
        catch (Exception) { return false; }
    }
}
