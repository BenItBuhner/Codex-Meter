using System.Diagnostics;
using System.IO.Compression;
using System.Runtime.InteropServices;
using CodexMeter.Core.Updates;

namespace CodexMeter.App.Services;

/// <summary>
/// Installs a verified update on Windows. A copy installed with the setup (an Inno Setup
/// uninstaller sits next to the app) runs the new setup silently for the same scope, which closes
/// this copy, upgrades in place and reopens it. A portable copy unpacks the new zip and a small
/// PowerShell step copies it over the app's folder once this copy has quit, then starts it.
/// The download's SHA-256 has already been checked against SHA256SUMS-windows.txt; if the
/// installer also carries an Authenticode signature, Windows must accept it too.
/// </summary>
public sealed class WindowsUpdateInstaller : IUpdateInstaller
{
    private readonly string appDirectory = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);

    public WindowsUpdateInstaller()
    {
        var arch = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "arm64" : "x64";
        var installed = Directory.EnumerateFiles(appDirectory, "unins*.exe").Any();
        Target = new UpdateTarget(installed ? PackageKind.WindowsInstaller : PackageKind.WindowsPortable, arch);
    }

    public UpdateTarget Target { get; }

    public string? CannotInstallReason => Target.Kind == PackageKind.WindowsPortable && !CanWrite(appDirectory)
        ? "Codex Meter can't write to its own folder. Download the new zip from the release page instead."
        : null;

    public string DownloadDirectory { get; } = Path.Combine(AppPaths.DataDirectory, "updates");

    public Task<InstallOutcome> InstallAsync(DownloadedUpdate update, CancellationToken cancellationToken) =>
        Task.Run(() => Target.Kind == PackageKind.WindowsInstaller ? RunSetup(update) : SwapPortable(update), cancellationToken);

    private InstallOutcome RunSetup(DownloadedUpdate update)
    {
        var trust = Authenticode.Check(update.Path);
        if (trust == Authenticode.Result.Invalid)
            throw new UpdateException("The installer's digital signature isn't valid, so it wasn't run.");
        // Same scope as this install: per user under %LOCALAPPDATA%\Programs, otherwise all users (Windows asks).
        var perUser = appDirectory.StartsWith(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), StringComparison.OrdinalIgnoreCase);
        var arguments = $"/SILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS /RELAUNCH=1 {(perUser ? "/CURRENTUSER" : "/ALLUSERS")} /LOG=\"{update.Path}.log\"";
        App.Log($"Update: running {Path.GetFileName(update.Path)} ({update.Sha256}, signature {trust}) {arguments}");
        Process.Start(new ProcessStartInfo(update.Path, arguments) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(update.Path)! });
        return InstallOutcome.QuitToFinish;
    }

    private InstallOutcome SwapPortable(DownloadedUpdate update)
    {
        var staging = Path.Combine(DownloadDirectory, "staging-" + update.Release.Version.Normalized);
        if (Directory.Exists(staging)) Directory.Delete(staging, recursive: true);
        ZipFile.ExtractToDirectory(update.Path, staging);
        // The zip holds the app's folder contents; refuse anything that doesn't look like it.
        if (!File.Exists(Path.Combine(staging, "CodexMeter.exe")))
            throw new UpdateException("The downloaded zip doesn't contain Codex Meter, so nothing was changed.");
        var exe = Path.GetFileName(Environment.ProcessPath ?? "CodexMeter.exe");
        var script = Path.Combine(DownloadDirectory, "apply-update.ps1");
        File.WriteAllText(script, """
            param([int]$WaitFor, [string]$Source, [string]$Target, [string]$Exe)
            Wait-Process -Id $WaitFor -Timeout 60 -ErrorAction SilentlyContinue
            robocopy $Source $Target /E /R:5 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
            if ($LASTEXITCODE -ge 8) { exit 1 }
            Start-Process -FilePath (Join-Path $Target $Exe)
            Remove-Item -LiteralPath $Source -Recurse -Force -ErrorAction SilentlyContinue
            """);
        var arguments = $"-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{script}\" -WaitFor {Environment.ProcessId} " +
            $"-Source \"{staging}\" -Target \"{appDirectory}\" -Exe \"{exe}\"";
        App.Log($"Update: replacing the portable copy in {appDirectory} with {update.Release.Version.Normalized} ({update.Sha256})");
        Process.Start(new ProcessStartInfo("powershell.exe", arguments) { UseShellExecute = false, CreateNoWindow = true });
        return InstallOutcome.QuitToFinish;
    }

    private static bool CanWrite(string directory)
    {
        try
        {
            var probe = Path.Combine(directory, ".write-test-" + Environment.ProcessId);
            File.WriteAllText(probe, "");
            File.Delete(probe);
            return true;
        }
        catch (Exception) { return false; }
    }
}

/// <summary>WinVerifyTrust on a file: signed and trusted, not signed, or signed but rejected.</summary>
internal static class Authenticode
{
    public enum Result { Trusted, Unsigned, Invalid }

    private static readonly Guid GenericVerifyV2 = new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");
    private const uint TrustENoSignature = 0x800B0100;

    public static Result Check(string path)
    {
        var file = new WinTrustFileInfo { cbStruct = (uint)Marshal.SizeOf<WinTrustFileInfo>(), pcwszFilePath = path };
        var filePtr = Marshal.AllocHGlobal(Marshal.SizeOf<WinTrustFileInfo>());
        try
        {
            Marshal.StructureToPtr(file, filePtr, false);
            var data = new WinTrustData
            {
                cbStruct = (uint)Marshal.SizeOf<WinTrustData>(),
                dwUIChoice = 2, // WTD_UI_NONE
                fdwRevocationChecks = 0,
                dwUnionChoice = 1, // WTD_CHOICE_FILE
                pFile = filePtr,
                dwStateAction = 0,
                dwProvFlags = 0x00000010, // WTD_REVOCATION_CHECK_NONE: no network trip
            };
            var action = GenericVerifyV2;
            var status = (uint)WinVerifyTrust(IntPtr.Zero, ref action, ref data);
            return status == 0 ? Result.Trusted : status == TrustENoSignature ? Result.Unsigned : Result.Invalid;
        }
        catch (Exception)
        {
            // No WinTrust (unexpected on Windows): the checksum check still stands.
            return Result.Unsigned;
        }
        finally
        {
            Marshal.FreeHGlobal(filePtr);
        }
    }

    [DllImport("wintrust.dll", ExactSpelling = true, SetLastError = false, CharSet = CharSet.Unicode)]
    private static extern int WinVerifyTrust(IntPtr hwnd, ref Guid action, ref WinTrustData data);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WinTrustFileInfo
    {
        public uint cbStruct;
        [MarshalAs(UnmanagedType.LPWStr)] public string pcwszFilePath;
        public IntPtr hFile;
        public IntPtr pgKnownSubject;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WinTrustData
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pFile;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
        public IntPtr pSignatureSettings;
    }
}
