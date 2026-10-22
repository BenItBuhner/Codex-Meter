using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Parsing;

namespace CodexMeter.Core.Services;

/// <summary>A failure worth showing to the user as-is.</summary>
public class ProviderException(string message, bool signedOut = false, Exception? inner = null)
    : Exception(message, inner)
{
    /// <summary>True when the saved sign-in no longer works and the user has to sign in again.</summary>
    public bool SignedOut { get; } = signedOut;
}

public sealed record HttpPayload(int Status, string Body)
{
    public bool Ok => Status is >= 200 and < 300;
}

/// <summary>Shared HTTP plumbing: one client, bounded bodies, readable errors.</summary>
public static class ProviderHttp
{
    public const string UserAgent = "codex-meter-windows/2.9.0";
    private const int MaxBody = 2 * 1024 * 1024;

    public static HttpClient CreateClient(HttpMessageHandler? handler = null)
    {
        var client = handler is null ? new HttpClient(new SocketsHttpHandler
        {
            UseCookies = false,
            AutomaticDecompression = System.Net.DecompressionMethods.All,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        }) : new HttpClient(handler);
        client.Timeout = TimeSpan.FromSeconds(30);
        client.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        return client;
    }

    public static async Task<HttpPayload> SendAsync(HttpClient client, HttpRequestMessage request, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
            var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
            if (bytes.Length > MaxBody) throw new ProviderException("Server response was unexpectedly large.");
            return new HttpPayload((int)response.StatusCode, Encoding.UTF8.GetString(bytes));
        }
        catch (HttpRequestException error)
        {
            throw new ProviderException("Couldn't reach the server. Check your connection and try again.", inner: error);
        }
        catch (TaskCanceledException error) when (!cancellationToken.IsCancellationRequested)
        {
            throw new ProviderException("The server took too long to answer. Try again in a moment.", inner: error);
        }
    }

    public static HttpRequestMessage Json(HttpMethod method, string url, object body)
    {
        var request = new HttpRequestMessage(method, url)
        {
            Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json"),
        };
        request.Content.Headers.ContentType!.CharSet = null;
        return request;
    }

    public static HttpRequestMessage Form(string url, IEnumerable<KeyValuePair<string, string>> body) =>
        new(HttpMethod.Post, url)
        {
            Content = new StringContent(FormEncoding.Encode(body), Encoding.UTF8, "application/x-www-form-urlencoded"),
        };

    /// <summary>
    /// The server's own message (<c>error_description</c>, <c>error</c>, <c>error.message</c>,
    /// <c>message</c>, <c>detail</c>), or <paramref name="fallback"/>. HTML from a proxy is never shown.
    /// </summary>
    public static string ServerMessage(string? body, string fallback)
    {
        var root = Parsing.Json.TryParseObject(body);
        if (root is null) return fallback;
        foreach (var key in new[] { "error_description", "message", "detail" })
        {
            var text = Parsing.Json.Str(Parsing.Json.Get(root, key)).Trim();
            if (text.Length > 0) return Truncate(text);
        }
        var error = Parsing.Json.Get(root, "error");
        if (Parsing.Json.Obj(error) is { } errorObject)
        {
            var text = Parsing.Json.Str(Parsing.Json.Get(errorObject, "message")).Trim();
            if (text.Length > 0) return Truncate(text);
        }
        var plain = Parsing.Json.Str(error).Trim();
        return plain.Length > 0 ? Truncate(plain) : fallback;
    }

    private static string Truncate(string text) => text.Length <= 240 ? text : text[..240];
}

/// <summary>Where a provider's credentials live.</summary>
public interface ITokenStore
{
    Task<AuthTokens?> LoadAsync(string providerId);
    Task SaveAsync(string providerId, AuthTokens tokens);
    Task DeleteAsync(string providerId);
}

/// <summary>Encrypts token files at rest. On Windows this is DPAPI (current user).</summary>
public interface IDataProtector
{
    byte[] Protect(byte[] data);
    byte[] Unprotect(byte[] data);
}

/// <summary>No encryption; for tests and demo data only.</summary>
public sealed class PassthroughProtector : IDataProtector
{
    public byte[] Protect(byte[] data) => data;
    public byte[] Unprotect(byte[] data) => data;
}

/// <summary>One protected file per provider: <c>tokens-{id}.bin</c>.</summary>
public sealed class FileTokenStore(string directory, IDataProtector protector) : ITokenStore
{
    private readonly SemaphoreSlim gate = new(1, 1);

    private string PathFor(string id) => System.IO.Path.Combine(directory, $"tokens-{id}.bin");

    public async Task<AuthTokens?> LoadAsync(string providerId)
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            var path = PathFor(providerId);
            if (!File.Exists(path)) return null;
            try
            {
                var bytes = protector.Unprotect(await File.ReadAllBytesAsync(path).ConfigureAwait(false));
                return JsonSerializer.Deserialize<AuthTokens>(bytes, Storage.Options);
            }
            catch (Exception error) when (error is JsonException or CryptographicException)
            {
                // Unreadable (another user, another machine, corrupted): treat as signed out.
                return null;
            }
        }
        finally { gate.Release(); }
    }

    public async Task SaveAsync(string providerId, AuthTokens tokens)
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            Directory.CreateDirectory(directory);
            var bytes = protector.Protect(JsonSerializer.SerializeToUtf8Bytes(tokens, Storage.Options));
            await Storage.WriteAtomicAsync(PathFor(providerId), bytes).ConfigureAwait(false);
        }
        finally { gate.Release(); }
    }

    public async Task DeleteAsync(string providerId)
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try { File.Delete(PathFor(providerId)); }
        finally { gate.Release(); }
    }
}

/// <summary>In-memory credentials for tests and demo mode.</summary>
public sealed class MemoryTokenStore : ITokenStore
{
    private readonly Dictionary<string, AuthTokens> tokens = new();
    public Task<AuthTokens?> LoadAsync(string providerId)
    {
        lock (tokens) return Task.FromResult(tokens.GetValueOrDefault(providerId));
    }
    public Task SaveAsync(string providerId, AuthTokens value)
    {
        lock (tokens) tokens[providerId] = value;
        return Task.CompletedTask;
    }
    public Task DeleteAsync(string providerId)
    {
        lock (tokens) tokens.Remove(providerId);
        return Task.CompletedTask;
    }
}

/// <summary>What the dashboard shows before the network answers.</summary>
public sealed record ProviderCacheEntry
{
    public UsageSnapshot? Usage { get; init; }
    public ResetCreditsSnapshot? Credits { get; init; }
    public string? LastError { get; init; }
    public DateTimeOffset? LastErrorAt { get; init; }
    public string? CreditsError { get; init; }
    public DateTimeOffset UpdatedAt { get; init; }
}

/// <summary>JSON cache per provider in the app's data folder (<c>cache-{id}.json</c>).</summary>
public sealed class ProviderCacheStore(string? directory)
{
    private readonly Dictionary<string, ProviderCacheEntry> memory = new();
    private readonly SemaphoreSlim gate = new(1, 1);

    private string? PathFor(string id) => directory is null ? null : System.IO.Path.Combine(directory, $"cache-{id}.json");

    public async Task<ProviderCacheEntry?> LoadAsync(string providerId)
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (memory.TryGetValue(providerId, out var cached)) return cached;
            var path = PathFor(providerId);
            if (path is null || !File.Exists(path)) return null;
            try
            {
                var entry = JsonSerializer.Deserialize<ProviderCacheEntry>(await File.ReadAllBytesAsync(path).ConfigureAwait(false), Storage.Options);
                if (entry is not null) memory[providerId] = entry;
                return entry;
            }
            catch (JsonException) { return null; }
        }
        finally { gate.Release(); }
    }

    public async Task<ProviderCacheEntry> UpdateAsync(string providerId, Func<ProviderCacheEntry, ProviderCacheEntry> change)
    {
        var current = await LoadAsync(providerId).ConfigureAwait(false) ?? new ProviderCacheEntry();
        var updated = change(current);
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            memory[providerId] = updated;
            if (PathFor(providerId) is { } path)
            {
                Directory.CreateDirectory(directory!);
                await Storage.WriteAtomicAsync(path, JsonSerializer.SerializeToUtf8Bytes(updated, Storage.Options)).ConfigureAwait(false);
            }
        }
        finally { gate.Release(); }
        return updated;
    }

    public async Task ClearAsync(string providerId)
    {
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            memory.Remove(providerId);
            if (PathFor(providerId) is { } path) File.Delete(path);
        }
        finally { gate.Release(); }
    }
}

public static class Storage
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = false,
    };

    /// <summary>Writes to a temp file then replaces, so a crash never leaves half a file.</summary>
    public static async Task WriteAtomicAsync(string path, byte[] bytes)
    {
        var temp = path + ".tmp";
        await File.WriteAllBytesAsync(temp, bytes).ConfigureAwait(false);
        File.Move(temp, path, overwrite: true);
    }
}
