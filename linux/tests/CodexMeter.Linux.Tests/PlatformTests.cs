using System.Security.Cryptography;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Tests;

public sealed class PlatformTests : IDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "codexmeter-tests-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        if (Directory.Exists(directory)) Directory.Delete(directory, true);
    }

    [Fact]
    public void ProtectorRoundTripsAndUsesAFreshNonce()
    {
        var protector = new AesKeyProtector(RandomNumberGenerator.GetBytes(32), KeyLocation.PrivateFile);
        var plain = "{\"accessToken\":\"secret\"}"u8.ToArray();
        var first = protector.Protect(plain);
        var second = protector.Protect(plain);
        Assert.NotEqual(first, second);
        Assert.Equal(plain, protector.Unprotect(first));
        Assert.DoesNotContain("secret", System.Text.Encoding.UTF8.GetString(first));
    }

    [Fact]
    public void TamperedOrForeignDataIsRejected()
    {
        var protector = new AesKeyProtector(RandomNumberGenerator.GetBytes(32), KeyLocation.PrivateFile);
        var sealedBytes = protector.Protect([1, 2, 3, 4]);
        sealedBytes[^1] ^= 0xFF;
        Assert.ThrowsAny<CryptographicException>(() => protector.Unprotect(sealedBytes));
        var other = new AesKeyProtector(RandomNumberGenerator.GetBytes(32), KeyLocation.PrivateFile);
        Assert.ThrowsAny<CryptographicException>(() => other.Unprotect(protector.Protect([5])));
        Assert.ThrowsAny<CryptographicException>(() => protector.Unprotect("plain json"u8.ToArray()));
    }

    [Fact]
    public void KeyFileIsPrivateAndReused()
    {
        var key = AesKeyProtector.FileKey(directory);
        Assert.Equal(32, key.Length);
        var path = Path.Combine(directory, "token-key");
        Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(path));
        Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute, File.GetUnixFileMode(directory));
        Assert.Equal(key, AesKeyProtector.FileKey(directory));
    }

    [Fact]
    public async Task TokenStoreRoundTripsThroughTheProtector()
    {
        var protector = new AesKeyProtector(AesKeyProtector.FileKey(directory), KeyLocation.PrivateFile);
        var store = new FileTokenStore(directory, protector);
        var tokens = new AuthTokens("access", "refresh", "id", DateTimeOffset.UnixEpoch.AddDays(30000), "acct", "me@example.com");
        await store.SaveAsync("codex", tokens);
        Assert.Equal(tokens, await store.LoadAsync("codex"));
        // A different key (keyring reset, another machine) reads as signed out instead of crashing.
        var stranger = new FileTokenStore(directory, new AesKeyProtector(RandomNumberGenerator.GetBytes(32), KeyLocation.PrivateFile));
        Assert.Null(await stranger.LoadAsync("codex"));
    }

    [Fact]
    public void AutostartEntryLaunchesMinimized()
    {
        var entry = Autostart.Entry("/opt/Codex Meter/codex-meter --minimized");
        Assert.Contains("[Desktop Entry]", entry);
        Assert.Contains("Exec=/opt/Codex Meter/codex-meter --minimized", entry);
        Assert.Contains("Icon=io.github.benitbuhner.CodexMeter", entry);
        Assert.Contains("--minimized", Autostart.LaunchCommand);
    }

    [Theory]
    [InlineData("/usr/bin/codex-meter", "/usr/bin/codex-meter")]
    [InlineData("/home/a b/Codex.AppImage", "\"/home/a b/Codex.AppImage\"")]
    [InlineData("/tmp/$x\"y", "\"/tmp/\\$x\\\"y\"")]
    public void AutostartQuotesPaths(string path, string expected) => Assert.Equal(expected, Autostart.Quote(path));

    [Fact]
    public void LaunchOptionsParseLikeWindows()
    {
        var options = LaunchOptions.Parse(["--screenshots", "/tmp/shots", "--theme", "dark", "--page", "cursor"]);
        Assert.True(options.Demo);
        Assert.True(options.AllowMultipleInstances);
        Assert.Equal("/tmp/shots", options.ScreenshotDirectory);
        Assert.Equal("dark", options.Theme);
        Assert.Equal("cursor", options.Page);
        var plain = LaunchOptions.Parse(["--minimized"]);
        Assert.False(plain.Demo);
        Assert.True(plain.StartHidden);
        Assert.False(plain.AllowMultipleInstances);
        Assert.True(LaunchOptions.Parse(["--demo-signed-out"]).SignedOutDemo);
    }

    [Fact]
    public void PathsFollowXdg()
    {
        Assert.EndsWith("codex-meter", AppPaths.DataDirectory);
        Assert.EndsWith("codex-meter", AppPaths.ConfigDirectory);
        Assert.EndsWith("codex-meter", AppPaths.CacheDirectory);
        Assert.EndsWith(".desktop", Autostart.EntryPath);
    }
}
