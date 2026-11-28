using CodexMeter.Core.Updates;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Tests;

/// <summary>Which package the running copy came from decides how Settings → Updates installs.</summary>
[Collection(EnvironmentCollection.Name)]
public sealed class UpdateInstallerTests : IDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "codexmeter-update-" + Guid.NewGuid().ToString("N"));
    private readonly string? flatpak = Environment.GetEnvironmentVariable("FLATPAK_ID");
    private readonly string? appImage = Environment.GetEnvironmentVariable("APPIMAGE");

    public UpdateInstallerTests()
    {
        Directory.CreateDirectory(directory);
        Environment.SetEnvironmentVariable("FLATPAK_ID", null);
        Environment.SetEnvironmentVariable("APPIMAGE", null);
    }

    public void Dispose()
    {
        Environment.SetEnvironmentVariable("FLATPAK_ID", flatpak);
        Environment.SetEnvironmentVariable("APPIMAGE", appImage);
        foreach (var path in Directory.EnumerateFileSystemEntries(directory)) File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        File.SetUnixFileMode(directory, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        Directory.Delete(directory, true);
    }

    [Fact]
    public async Task ACopyBuiltFromSourceOnlyLinksToTheRelease()
    {
        var installer = new LinuxUpdateInstaller();
        Assert.Equal(PackageKind.Unmanaged, installer.Target.Kind);
        Assert.Contains("release page", installer.CannotInstallReason);
        await Assert.ThrowsAsync<UpdateException>(() => installer.InstallAsync(null!, CancellationToken.None));
    }

    [Fact]
    public void AFlatpakUpdatesThroughTheSoftwareCenter()
    {
        Environment.SetEnvironmentVariable("FLATPAK_ID", "io.github.benitbuhner.CodexMeter");
        var installer = new LinuxUpdateInstaller();
        Assert.Contains("Flatpak", installer.CannotInstallReason);
        Assert.NotEqual(PackageKind.AppImage, installer.Target.Kind);
    }

    [Fact]
    public void AnAppImageInAWritableFolderReplacesItself()
    {
        var path = Path.Combine(directory, "Codex-Meter.AppImage");
        File.WriteAllText(path, "old");
        Environment.SetEnvironmentVariable("APPIMAGE", path);
        var installer = new LinuxUpdateInstaller();
        Assert.Equal(PackageKind.AppImage, installer.Target.Kind);
        Assert.Null(installer.CannotInstallReason);
        Assert.Contains(installer.Target.Arch, new[] { "x64", "arm64" });
    }

    [Fact]
    public void AnAppImageInAReadOnlyFolderSaysSoBeforeDownloading()
    {
        if (Environment.UserName == "root") return; // root writes anywhere, so the folder can't be made read-only.
        var path = Path.Combine(directory, "Codex-Meter.AppImage");
        File.WriteAllText(path, "old");
        File.SetUnixFileMode(directory, UnixFileMode.UserRead | UnixFileMode.UserExecute);
        Environment.SetEnvironmentVariable("APPIMAGE", path);
        var installer = new LinuxUpdateInstaller();
        Assert.Equal(PackageKind.AppImage, installer.Target.Kind);
        Assert.Contains("can't write to the AppImage's folder", installer.CannotInstallReason);
    }
}

/// <summary>Tests that change process-wide environment variables run alone.</summary>
[CollectionDefinition(Name, DisableParallelization = true)]
public sealed class EnvironmentCollection
{
    public const string Name = "Environment";
}
