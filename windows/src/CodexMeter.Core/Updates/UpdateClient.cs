using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;

namespace CodexMeter.Core.Updates;

/// <summary>A failure to show as-is in Settings → Updates.</summary>
public sealed class UpdateException(string message, Exception? inner = null) : Exception(message, inner)
{
    /// <summary>GitHub's rate limit; asking again right away would only hit it again.</summary>
    public bool RateLimited { get; init; }
}

/// <summary>A verified download, ready for the platform installer.</summary>
public sealed record DownloadedUpdate(ReleaseInfo Release, string Path, string Sha256);

/// <summary>
/// Talks to GitHub's public release API without signing in, the way the Android updater does,
/// but it never reads the full release listing: stable asks for /releases/latest (one release),
/// alpha for the newest ten plus /releases/latest, and a listing that can't be read is retried
/// with three. Every response has a size cap, redirects must stay on GitHub, and a download
/// is only kept when its SHA-256 matches the release's checksum file.
/// </summary>
public sealed class UpdateClient
{
    public const string Repository = "BenItBuhner/Codex-Meter";
    public const string DefaultApi = "https://api.github.com/repos/" + Repository + "/releases";
    public const int RecentCount = 10;
    public const int FallbackCount = 3;
    /// <summary>Ten releases took about 330 KB in November 2026; this leaves room for many more builds per release.</summary>
    public const int MaxListingBytes = 4 * 1024 * 1024;
    public const int MaxReleaseBytes = 1024 * 1024;
    public const int MaxChecksumBytes = 64 * 1024;
    public const long MaxDownloadBytes = 600L * 1024 * 1024;

    private readonly HttpClient api;
    private readonly HttpClient downloads;
    private readonly string releasesUrl;
    private readonly Uri? localFeed;

    /// <param name="testFeed">A loopback release feed (http://127.0.0.1:port/releases) used by CI to show the update flow; null for GitHub.</param>
    public UpdateClient(string userAgent, HttpMessageHandler? handler = null, string? testFeed = null)
    {
        if (testFeed is not null)
        {
            if (!Uri.TryCreate(testFeed.TrimEnd('/'), UriKind.Absolute, out var feed) || !feed.IsLoopback)
                throw new ArgumentException("The test update feed must be a loopback address.", nameof(testFeed));
            localFeed = feed;
            releasesUrl = feed.AbsoluteUri.TrimEnd('/');
        }
        else
        {
            releasesUrl = DefaultApi;
        }
        api = Create(handler, userAgent, TimeSpan.FromSeconds(30));
        api.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        api.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
        downloads = Create(handler, userAgent, TimeSpan.FromMinutes(15));
    }

    public bool IsTestFeed => localFeed is not null;

    private static HttpClient Create(HttpMessageHandler? handler, string userAgent, TimeSpan timeout)
    {
        var client = handler is null
            ? new HttpClient(new SocketsHttpHandler { UseCookies = false, AllowAutoRedirect = true, MaxAutomaticRedirections = 5 })
            : new HttpClient(handler, disposeHandler: false);
        client.Timeout = timeout;
        client.DefaultRequestHeaders.UserAgent.ParseAdd(userAgent);
        return client;
    }

    /// <summary>Release pages and downloads must live in this repository on GitHub (or the local test feed).</summary>
    public bool IsTrustedReleaseUrl(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)) return false;
        if (localFeed is not null) return uri.IsLoopback && uri.Port == localFeed.Port;
        return uri.Scheme == Uri.UriSchemeHttps && uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase)
            && uri.AbsolutePath.StartsWith("/" + Repository + "/releases/", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Where a request may end up after redirects: GitHub's API, or its release file hosts for downloads.</summary>
    private bool IsTrustedFinalUrl(Uri? uri, bool download)
    {
        if (uri is null) return false;
        if (localFeed is not null) return uri.IsLoopback && uri.Port == localFeed.Port;
        if (uri.Scheme != Uri.UriSchemeHttps) return false;
        var host = uri.Host.ToLowerInvariant();
        if (!download) return host == "api.github.com";
        return host == "github.com" || host.EndsWith(".githubusercontent.com", StringComparison.Ordinal);
    }

    /// <summary>The releases <paramref name="channel"/> can offer for <paramref name="target"/>, newest first.</summary>
    public async Task<List<ReleaseInfo>> CheckAsync(UpdateChannel channel, UpdateTarget target, CancellationToken cancellationToken = default)
    {
        if (channel == UpdateChannel.Stable)
        {
            try
            {
                return await LatestAsync(target, cancellationToken).ConfigureAwait(false);
            }
            catch (UpdateException error) when (!error.RateLimited)
            {
                // The single-release endpoint failed; the small listing has the same release.
                var recent = await RecentAsync(target, FallbackCount, cancellationToken).ConfigureAwait(false);
                return recent.Count > 0 ? recent : throw error;
            }
        }

        List<ReleaseInfo>? listing = null;
        UpdateException? listingError = null;
        try
        {
            listing = await RecentAsync(target, RecentCount, cancellationToken).ConfigureAwait(false);
        }
        catch (UpdateException error) when (!error.RateLimited)
        {
            // Too big, cut off or unreadable: ask for fewer releases.
            listingError = error;
            try { listing = await RecentAsync(target, FallbackCount, cancellationToken).ConfigureAwait(false); }
            catch (UpdateException) { }
        }
        List<ReleaseInfo> latest = [];
        try
        {
            latest = await LatestAsync(target, cancellationToken).ConfigureAwait(false);
        }
        catch (UpdateException error)
        {
            if (listing is null) throw listingError ?? error;
        }
        return ReleaseFeed.Merge(listing ?? [], latest);
    }

    private async Task<List<ReleaseInfo>> LatestAsync(UpdateTarget target, CancellationToken cancellationToken)
    {
        var (status, body) = await GetAsync(releasesUrl + "/latest", MaxReleaseBytes, cancellationToken).ConfigureAwait(false);
        // 404: the repository has no stable release yet.
        if (status == HttpStatusCode.NotFound) return [];
        return ReleaseFeed.Parse(body, target, IsTrustedReleaseUrl);
    }

    private async Task<List<ReleaseInfo>> RecentAsync(UpdateTarget target, int count, CancellationToken cancellationToken)
    {
        var (_, body) = await GetAsync($"{releasesUrl}?per_page={count}", MaxListingBytes, cancellationToken).ConfigureAwait(false);
        return ReleaseFeed.Parse(body, target, IsTrustedReleaseUrl);
    }

    private async Task<(HttpStatusCode Status, string Body)> GetAsync(string url, int limit, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await api.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (!IsTrustedFinalUrl(response.RequestMessage?.RequestUri, download: false))
                throw new UpdateException("GitHub redirected the update check somewhere unexpected, so it was stopped.");
            var status = response.StatusCode;
            if (status == HttpStatusCode.NotFound && url.EndsWith("/latest", StringComparison.Ordinal)) return (status, "");
            if (status is HttpStatusCode.Forbidden or HttpStatusCode.TooManyRequests)
                throw new UpdateException("GitHub is limiting update checks right now. Try again in an hour.") { RateLimited = true };
            if (!response.IsSuccessStatusCode)
                throw new UpdateException($"GitHub couldn't answer the update check (HTTP {(int)status}). Try again later.");
            var bytes = await ReadCappedAsync(response.Content, limit, cancellationToken).ConfigureAwait(false);
            return (status, Encoding.UTF8.GetString(bytes));
        }
        catch (HttpRequestException error)
        {
            throw new UpdateException("Couldn't reach GitHub. Check your connection and try again.", error);
        }
        catch (TaskCanceledException error) when (!cancellationToken.IsCancellationRequested)
        {
            throw new UpdateException("GitHub took too long to answer. Try again in a moment.", error);
        }
        catch (System.Text.Json.JsonException error)
        {
            throw new UpdateException("GitHub sent release details Codex Meter couldn't read. Try again later.", error);
        }
    }

    private static async Task<byte[]> ReadCappedAsync(HttpContent content, int limit, CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength is > 0 and var length && length > limit) throw TooMuch();
        await using var stream = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        var chunk = new byte[16 * 1024];
        int read;
        while ((read = await stream.ReadAsync(chunk, cancellationToken).ConfigureAwait(false)) > 0)
        {
            if (buffer.Length + read > limit) throw TooMuch();
            buffer.Write(chunk, 0, read);
        }
        return buffer.ToArray();
    }

    private static UpdateException TooMuch() =>
        new("GitHub returned more release details than expected. Codex Meter will ask for less next time.");

    /// <summary>
    /// Downloads the release's build for this platform into <paramref name="directory"/> and keeps it
    /// only if its size and SHA-256 match the release's checksum file.
    /// </summary>
    public async Task<DownloadedUpdate> DownloadAsync(ReleaseInfo release, UpdateTarget target, string directory,
        IProgress<double>? progress = null, CancellationToken cancellationToken = default)
    {
        if (release.Download is not { } asset || release.Checksums is not { } sums)
            throw new UpdateException("This release has no download for this computer yet.");
        if (asset.Size > MaxDownloadBytes) throw new UpdateException("The download is larger than expected, so it was skipped.");
        if (!IsTrustedReleaseUrl(asset.Url) || !IsTrustedReleaseUrl(sums.Url))
            throw new UpdateException("The download isn't hosted on Codex Meter's GitHub releases, so it was skipped.");

        string sumsText;
        try
        {
            using var response = await downloads.GetAsync(sums.Url, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (!IsTrustedFinalUrl(response.RequestMessage?.RequestUri, download: true) || !response.IsSuccessStatusCode)
                throw new UpdateException("Couldn't download the release's checksum file. Try again later.");
            sumsText = Encoding.UTF8.GetString(await ReadCappedAsync(response.Content, MaxChecksumBytes, cancellationToken).ConfigureAwait(false));
        }
        catch (HttpRequestException error)
        {
            throw new UpdateException("Couldn't reach GitHub. Check your connection and try again.", error);
        }
        var expected = ReleaseIntegrity.ExpectedSha256(sumsText, target.ChecksumEntryName(release.Version));
        if (expected.Length == 0) throw new UpdateException("The release's checksum file doesn't list this download, so it wasn't installed.");

        Directory.CreateDirectory(directory);
        var final = Path.Combine(directory, asset.Name);
        var partial = final + ".partial";
        try
        {
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            using (var response = await downloads.GetAsync(asset.Url, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false))
            {
                if (!IsTrustedFinalUrl(response.RequestMessage?.RequestUri, download: true))
                    throw new UpdateException("GitHub redirected the download somewhere unexpected, so it was stopped.");
                if (!response.IsSuccessStatusCode) throw new UpdateException($"The download failed (HTTP {(int)response.StatusCode}). Try again later.");
                await using var input = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
                await using var output = new FileStream(partial, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true);
                var buffer = new byte[81920];
                long total = 0;
                int read;
                while ((read = await input.ReadAsync(buffer, cancellationToken).ConfigureAwait(false)) > 0)
                {
                    total += read;
                    if (total > asset.Size) throw new UpdateException("The download was larger than GitHub said, so it was discarded.");
                    hash.AppendData(buffer, 0, read);
                    await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
                    progress?.Report((double)total / asset.Size);
                }
                if (total != asset.Size) throw new UpdateException("The download was cut short. Try again.");
            }
            var actual = Convert.ToHexStringLower(hash.GetHashAndReset());
            if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(actual), Encoding.ASCII.GetBytes(expected)))
                throw new UpdateException("The download didn't match its published SHA-256 checksum, so it was discarded.");
            File.Move(partial, final, overwrite: true);
            return new DownloadedUpdate(release, final, actual);
        }
        catch (HttpRequestException error)
        {
            throw new UpdateException("The download was interrupted. Check your connection and try again.", error);
        }
        catch (TaskCanceledException error) when (!cancellationToken.IsCancellationRequested)
        {
            throw new UpdateException("The download stalled. Try again.", error);
        }
        finally
        {
            try { if (File.Exists(partial)) File.Delete(partial); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
}
