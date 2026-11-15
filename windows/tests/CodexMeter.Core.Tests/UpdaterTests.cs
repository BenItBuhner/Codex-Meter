using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CodexMeter.Core.Updates;

namespace CodexMeter.Core.Tests;

public class ReleaseVersionTests
{
    [Theory]
    [InlineData("v2.9.0-alpha.10", "2.9.0-alpha.9", 1)]
    [InlineData("2.9.0", "2.9.0-alpha.27", 1)]
    [InlineData("2.9.0-alpha.27", "2.8.0", 1)]
    [InlineData("2.9", "2.9.0", 0)]
    [InlineData("2.9.0-beta.1", "2.9.0-alpha.30", 1)]
    [InlineData("2.9.0+sha.abc", "2.9.0", 0)]
    public void ComparesLikeTheAndroidUpdater(string left, string right, int expected) =>
        Assert.Equal(expected, Math.Sign(ReleaseVersion.Parse(left)!.CompareTo(ReleaseVersion.Parse(right))));

    [Theory]
    [InlineData("")]
    [InlineData("latest")]
    [InlineData("2")]
    [InlineData("2.x.0")]
    [InlineData("2.9.0-")]
    public void RejectsNonVersions(string value) => Assert.Null(ReleaseVersion.Parse(value));
}

public class ReleaseFeedTests
{
    internal static readonly UpdateTarget WinX64 = new(PackageKind.WindowsInstaller, "x64");

    internal static object Release(string tag, bool prerelease, bool desktop = true, bool draft = false, string? published = null)
    {
        var v = tag.TrimStart('v');
        var assets = new List<object> { Asset($"CodexMeter-{v}.apk"), Asset("SHA256SUMS.txt") };
        if (desktop)
        {
            assets.AddRange([
                Asset($"CodexMeter-{v}-Setup-x64.exe"), Asset($"CodexMeter-{v}-Setup-arm64.exe"),
                Asset($"CodexMeter-{v}-windows-x64.zip"), Asset("SHA256SUMS-windows.txt"),
                Asset($"CodexMeter-{v}-x86_64.AppImage"), Asset($"codex-meter_{v.Replace('-', '.')}_amd64.deb"),
                Asset("SHA256SUMS-linux-x64.txt"),
            ]);
        }
        return new Dictionary<string, object?>
        {
            ["tag_name"] = tag, ["name"] = "Codex Meter " + v, ["prerelease"] = prerelease, ["draft"] = draft,
            ["html_url"] = $"https://github.com/BenItBuhner/Codex-Meter/releases/tag/{tag}",
            ["published_at"] = published ?? "2026-11-13T10:00:00Z", ["body"] = "Notes for " + v, ["assets"] = assets,
            // Fields the updater doesn't read, like the ones that made the Android listing huge.
            ["author"] = new { login = "someone", avatar_url = "https://example.invalid/a.png" },
            ["reactions"] = new { total_count = 3 },
        };

        object Asset(string name) => new
        {
            name, size = 1234,
            browser_download_url = $"https://github.com/BenItBuhner/Codex-Meter/releases/download/{tag}/{name}",
            uploader = new { login = "github-actions[bot]" }, content_type = "application/octet-stream",
        };
    }

    [Fact]
    public void KeepsOnlyReleasesWithThisPlatformsBuildAndChecksums()
    {
        var json = JsonSerializer.Serialize(new[]
        {
            Release("v2.9.0-alpha.28", true, desktop: false), // Android finished; desktop builds not attached yet
            Release("v2.9.0-alpha.27", true),
            Release("v2.9.0-alpha.26", true, draft: true),
            Release("v2.8.0", false, desktop: false),
        });
        var client = new UpdateClient("test");
        var releases = ReleaseFeed.Parse(json, WinX64, client.IsTrustedReleaseUrl);
        Assert.Equal(["2.9.0-alpha.28", "2.9.0-alpha.27", "2.8.0"], releases.Select(r => r.Version.Normalized));
        Assert.False(releases[0].HasDownload);
        Assert.True(releases[1].HasDownload);
        Assert.Equal("CodexMeter-2.9.0-alpha.27-Setup-x64.exe", releases[1].Download!.Name);
        Assert.Equal("SHA256SUMS-windows.txt", releases[1].Checksums!.Name);
        Assert.Equal("2.9.0-alpha.27", UpdateChannels.Tracked(releases, UpdateChannel.Alpha)!.Version.Normalized);
        Assert.Null(UpdateChannels.Tracked(releases, UpdateChannel.Stable));
    }

    [Fact]
    public void PicksTheRightFileForEachDesktopBuild()
    {
        var v = ReleaseVersion.Parse("2.9.0-alpha.27")!;
        Assert.Equal("CodexMeter-2.9.0-alpha.27-windows-arm64.zip", new UpdateTarget(PackageKind.WindowsPortable, "arm64").AssetName(v));
        Assert.Equal("CodexMeter-2.9.0-alpha.27-x86_64.AppImage", new UpdateTarget(PackageKind.AppImage, "x64").AssetName(v));
        var deb = new UpdateTarget(PackageKind.Deb, "arm64");
        Assert.Equal("codex-meter_2.9.0.alpha.27_arm64.deb", deb.AssetName(v));
        Assert.Equal("codex-meter_2.9.0~alpha.27_arm64.deb", deb.ChecksumEntryName(v));
        Assert.Equal("SHA256SUMS-linux-arm64.txt", deb.ChecksumFileName);
        Assert.Null(new UpdateTarget(PackageKind.Unmanaged, "x64").AssetName(v));
    }

    [Fact]
    public void RejectsDownloadsOutsideTheRepositorysReleases()
    {
        var client = new UpdateClient("test");
        Assert.True(client.IsTrustedReleaseUrl("https://github.com/BenItBuhner/Codex-Meter/releases/download/v1.0.0/x.exe"));
        Assert.False(client.IsTrustedReleaseUrl("http://github.com/BenItBuhner/Codex-Meter/releases/download/v1.0.0/x.exe"));
        Assert.False(client.IsTrustedReleaseUrl("https://github.com/someone/Codex-Meter/releases/download/v1.0.0/x.exe"));
        Assert.False(client.IsTrustedReleaseUrl("https://evil.example/BenItBuhner/Codex-Meter/releases/x.exe"));
        Assert.Throws<ArgumentException>(() => new UpdateClient("test", testFeed: "https://example.com/releases"));
    }
}

public class UpdateChannelTests
{
    private static List<ReleaseInfo> Releases(params (string Tag, bool Pre)[] tags) =>
        ReleaseFeed.Parse(JsonSerializer.Serialize(tags.Select(t => ReleaseFeedTests.Release(t.Tag, t.Pre))), ReleaseFeedTests.WinX64,
            new UpdateClient("test").IsTrustedReleaseUrl);

    [Fact]
    public void AlphaBuildsFollowAlphaUntilTheUserPicks()
    {
        Assert.Equal(UpdateChannel.Alpha, UpdateChannels.Effective(null, ReleaseVersion.Parse("2.9.0-alpha.27")));
        Assert.Equal(UpdateChannel.Stable, UpdateChannels.Effective(null, ReleaseVersion.Parse("2.9.0")));
        Assert.Equal(UpdateChannel.Stable, UpdateChannels.Effective(UpdateChannel.Stable, ReleaseVersion.Parse("2.9.0-alpha.27")));
    }

    [Fact]
    public void OffersNewerReleasesAndReturnToStableOnlyWhenChosen()
    {
        var releases = Releases(("v2.9.0-alpha.28", true), ("v2.9.0-alpha.27", true), ("v2.8.5", false));
        var installed = ReleaseVersion.Parse("2.9.0-alpha.27");
        Assert.Equal("2.9.0-alpha.28", UpdateChannels.SelectUpdate(releases, installed, UpdateChannel.Alpha, false)!.Version.Normalized);
        Assert.Null(UpdateChannels.SelectUpdate(releases, ReleaseVersion.Parse("2.9.0-alpha.28"), UpdateChannel.Alpha, false));
        Assert.Null(UpdateChannels.SelectUpdate(releases, installed, UpdateChannel.Stable, userChoseChannel: false));
        var back = UpdateChannels.SelectUpdate(releases, installed, UpdateChannel.Stable, userChoseChannel: true)!;
        Assert.Equal("2.8.5", back.Version.Normalized);
        Assert.True(UpdateChannels.IsReturnToStable(back, installed));
    }

    [Fact]
    public void CheckFrequencyMatchesAndroid()
    {
        var now = DateTimeOffset.Parse("2026-11-14T12:00:00Z");
        Assert.Equal(24, UpdateCheckFrequency.Normalize(5));
        Assert.True(UpdateCheckFrequency.IsDue(null, 24, now));
        Assert.False(UpdateCheckFrequency.IsDue(now.AddHours(-5), 6, now));
        Assert.True(UpdateCheckFrequency.IsDue(now.AddHours(-6), 6, now));
        Assert.True(UpdateCheckFrequency.IsDue(now.AddHours(2), 24, now)); // clock moved back
        Assert.Equal("Every 12 hours", UpdateCheckFrequency.Label(12));
    }

    [Fact]
    public void ChecksumFileParsing()
    {
        var digest = new string('a', 64);
        var file = $"{digest}  CodexMeter-2.9.0-Setup-x64.exe\n{new string('b', 64)} *CodexMeter-2.9.0-windows-x64.zip\r\nnot a line\n";
        Assert.Equal(digest, ReleaseIntegrity.ExpectedSha256(file, "CodexMeter-2.9.0-Setup-x64.exe"));
        Assert.Equal(new string('b', 64), ReleaseIntegrity.ExpectedSha256(file, "CodexMeter-2.9.0-windows-x64.zip"));
        Assert.Equal("", ReleaseIntegrity.ExpectedSha256(file, "missing.exe"));
        Assert.Equal("", ReleaseIntegrity.ExpectedSha256(file, "../CodexMeter-2.9.0-Setup-x64.exe"));
        // Listed twice: never trusted.
        Assert.Equal("", ReleaseIntegrity.ExpectedSha256(file + $"{new string('c', 64)}  CodexMeter-2.9.0-Setup-x64.exe\n", "CodexMeter-2.9.0-Setup-x64.exe"));
    }
}

/// <summary>Serves canned GitHub responses by URL.</summary>
internal sealed class FakeGitHub : HttpMessageHandler
{
    public readonly Dictionary<string, Func<HttpResponseMessage>> Routes = [];
    public readonly List<string> Requests = [];

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var url = request.RequestUri!.AbsoluteUri;
        Requests.Add(url);
        var response = Routes.TryGetValue(url, out var route) ? route() : new HttpResponseMessage(HttpStatusCode.NotFound) { Content = new StringContent("{}") };
        response.RequestMessage = request;
        return Task.FromResult(response);
    }

    public void Json(string url, object body, HttpStatusCode status = HttpStatusCode.OK) =>
        Routes[url] = () => new HttpResponseMessage(status) { Content = new StringContent(JsonSerializer.Serialize(body)) };

    public void Bytes(string url, byte[] body) => Routes[url] = () => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(body) };
}

public class UpdateClientTests
{
    private const string Api = UpdateClient.DefaultApi;

    [Fact]
    public async Task StableAsksForOneRelease()
    {
        var github = new FakeGitHub();
        github.Json(Api + "/latest", ReleaseFeedTests.Release("v2.9.0", false));
        var client = new UpdateClient("test", github);
        var releases = await client.CheckAsync(UpdateChannel.Stable, ReleaseFeedTests.WinX64);
        Assert.Equal(["2.9.0"], releases.Select(r => r.Version.Normalized));
        Assert.Equal([Api + "/latest"], github.Requests);
    }

    [Fact]
    public async Task AlphaFallsBackToAShorterListingWhenTheFullOneIsTooBig()
    {
        var github = new FakeGitHub();
        // A listing over the cap, like the one that broke the Android updater.
        github.Routes[$"{Api}?per_page={UpdateClient.RecentCount}"] = () => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent("[" + new string(' ', UpdateClient.MaxListingBytes + 10) + "]"),
        };
        github.Json($"{Api}?per_page={UpdateClient.FallbackCount}", new[] { ReleaseFeedTests.Release("v2.9.0-alpha.28", true) });
        github.Json(Api + "/latest", new { message = "Not Found" }, HttpStatusCode.NotFound);
        var releases = await new UpdateClient("test", github).CheckAsync(UpdateChannel.Alpha, ReleaseFeedTests.WinX64);
        Assert.Equal(["2.9.0-alpha.28"], releases.Select(r => r.Version.Normalized));
    }

    [Fact]
    public async Task RateLimitsAreReportedWithoutRetrying()
    {
        var github = new FakeGitHub();
        github.Json($"{Api}?per_page={UpdateClient.RecentCount}", new { message = "API rate limit exceeded" }, HttpStatusCode.Forbidden);
        var error = await Assert.ThrowsAsync<UpdateException>(() => new UpdateClient("test", github).CheckAsync(UpdateChannel.Alpha, ReleaseFeedTests.WinX64));
        Assert.True(error.RateLimited);
        Assert.Contains("limiting", error.Message);
        Assert.Single(github.Requests);
    }

    [Fact]
    public async Task DownloadsAreKeptOnlyWhenTheChecksumMatches()
    {
        var payload = Encoding.UTF8.GetBytes(new string('x', 1234));
        var digest = Convert.ToHexStringLower(SHA256.HashData(payload));
        var github = new FakeGitHub();
        github.Json(Api + "/latest", ReleaseFeedTests.Release("v2.9.0", false));
        var client = new UpdateClient("test", github);
        var release = (await client.CheckAsync(UpdateChannel.Stable, ReleaseFeedTests.WinX64)).Single();
        github.Bytes(release.Download!.Url, payload);
        var dir = Path.Combine(Path.GetTempPath(), "cm-update-" + Guid.NewGuid().ToString("N"));
        try
        {
            github.Bytes(release.Checksums!.Url, Encoding.UTF8.GetBytes($"{new string('0', 64)}  {release.Download.Name}\n"));
            var bad = await Assert.ThrowsAsync<UpdateException>(() => client.DownloadAsync(release, ReleaseFeedTests.WinX64, dir));
            Assert.Contains("SHA-256", bad.Message);
            Assert.Empty(Directory.GetFiles(dir));

            github.Bytes(release.Checksums.Url, Encoding.UTF8.GetBytes($"{digest}  {release.Download.Name}\n"));
            var good = await client.DownloadAsync(release, ReleaseFeedTests.WinX64, dir);
            Assert.Equal(digest, good.Sha256);
            Assert.Equal(payload, await File.ReadAllBytesAsync(good.Path));
        }
        finally
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
        }
    }

    private sealed class Installer(string dir) : IUpdateInstaller
    {
        public UpdateTarget Target => ReleaseFeedTests.WinX64;
        public string? CannotInstallReason => null;
        public string DownloadDirectory => dir;
        public DownloadedUpdate? Installed;
        public Task<InstallOutcome> InstallAsync(DownloadedUpdate update, CancellationToken cancellationToken)
        {
            Installed = update;
            return Task.FromResult(InstallOutcome.QuitToFinish);
        }
    }

    [Fact]
    public async Task UpdaterNotifiesOncePerVersionAndInstalls()
    {
        var payload = new byte[1234];
        var github = new FakeGitHub();
        github.Json($"{Api}?per_page={UpdateClient.RecentCount}", new[] { ReleaseFeedTests.Release("v2.9.0-alpha.28", true) });
        github.Json(Api + "/latest", new { message = "Not Found" }, HttpStatusCode.NotFound);
        var dir = Path.Combine(Path.GetTempPath(), "cm-update-" + Guid.NewGuid().ToString("N"));
        var prefs = new UpdatePreferences();
        var installer = new Installer(dir);
        var updater = new DesktopUpdater(new UpdateClient("test", github), installer, "2.9.0-alpha.27", () => prefs, f => prefs = f(prefs));
        var found = new List<string>();
        var quit = false;
        updater.UpdateFound += r => found.Add(r.Version.Normalized);
        updater.QuitRequested += () => quit = true;
        var now = DateTimeOffset.UtcNow;
        await updater.TickAsync(now);
        Assert.Equal(UpdateStatus.Available, updater.Status);
        Assert.Equal(["2.9.0-alpha.28"], found);
        await updater.CheckAsync(manual: false);
        Assert.Single(found);
        await updater.TickAsync(now.AddMinutes(1)); // not due
        Assert.Equal(4, github.Requests.Count); // two small requests per check, none when not due

        var release = updater.Available!;
        github.Bytes(release.Download!.Url, payload);
        github.Bytes(release.Checksums!.Url, Encoding.UTF8.GetBytes($"{Convert.ToHexStringLower(SHA256.HashData(payload))}  {release.Download.Name}\n"));
        try
        {
            await updater.DownloadAndInstallAsync();
            Assert.True(quit);
            Assert.NotNull(installer.Installed);
            Assert.Equal(UpdateStatus.Installing, updater.Status);
        }
        finally
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
        }
    }
}
