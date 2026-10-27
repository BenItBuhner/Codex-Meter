using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>Everything a provider service needs from the app.</summary>
public sealed class ProviderContext(HttpClient http, ITokenStore tokens, ProviderCacheStore cache, Func<DateTimeOffset>? now = null)
{
    public HttpClient Http { get; } = http;
    public ITokenStore Tokens { get; } = tokens;
    public ProviderCacheStore Cache { get; } = cache;
    public Func<DateTimeOffset> Now { get; } = now ?? (() => DateTimeOffset.UtcNow);
    /// <summary>Optional diagnostic sink: (category, message). Never receives secrets.</summary>
    public Action<string, string>? Log { get; init; }
}

/// <summary>
/// Sign-in, refresh and sign-out for one provider. Each provider keeps its own credentials and
/// cache, so signing in or out of one never touches the others.
/// </summary>
public abstract class ProviderService(UsageProvider provider, ProviderContext context)
{
    private readonly SemaphoreSlim refreshGate = new(1, 1);
    private Task<UsageSnapshot>? activeRefresh;

    public UsageProvider Provider { get; } = provider;
    protected ProviderContext Context { get; } = context;
    protected DateTimeOffset Now => Context.Now();

    /// <summary>Opening a dashboard only re-fetches when the cache is older than this.</summary>
    public virtual TimeSpan StaleAfter => TimeSpan.FromMinutes(5);

    /// <summary>Shortest spacing for background refresh (the tray keeps meters current).</summary>
    public virtual TimeSpan BackgroundInterval => TimeSpan.FromMinutes(15);

    /// <summary>Whether this provider has banked resets the user can redeem.</summary>
    public virtual bool SupportsResetCredits => false;

    public Task<AuthTokens?> AccountAsync() => Context.Tokens.LoadAsync(Provider.Id);

    public Task<ProviderCacheEntry?> CachedAsync() => Context.Cache.LoadAsync(Provider.Id);

    /// <summary>Runs the provider's browser sign-in. <paramref name="openBrowser"/> shows the URL.</summary>
    public async Task<AuthTokens> SignInAsync(Func<Uri, Task> openBrowser, CancellationToken cancellationToken)
    {
        Context.Log?.Invoke("auth", $"{Provider.Id} sign-in started");
        var tokens = await RunSignInAsync(openBrowser, cancellationToken).ConfigureAwait(false);
        await Context.Tokens.SaveAsync(Provider.Id, tokens).ConfigureAwait(false);
        // A different account may have been signed in before; never show its numbers.
        await Context.Cache.ClearAsync(Provider.Id).ConfigureAwait(false);
        Context.Log?.Invoke("auth", $"{Provider.Id} sign-in finished");
        return tokens;
    }

    protected abstract Task<AuthTokens> RunSignInAsync(Func<Uri, Task> openBrowser, CancellationToken cancellationToken);

    /// <summary>Fetches usage; concurrent callers share one request.</summary>
    public async Task<UsageSnapshot> RefreshAsync(CancellationToken cancellationToken = default)
    {
        Task<UsageSnapshot> task;
        await refreshGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (activeRefresh is null || activeRefresh.IsCompleted) activeRefresh = Task.Run(RefreshCoreAsync);
            task = activeRefresh;
        }
        finally { refreshGate.Release(); }
        try
        {
            return await task.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            if (task.IsCompleted)
            {
                await refreshGate.WaitAsync(CancellationToken.None).ConfigureAwait(false);
                if (activeRefresh == task) activeRefresh = null;
                refreshGate.Release();
            }
        }
    }

    private async Task<UsageSnapshot> RefreshCoreAsync()
    {
        try
        {
            var tokens = await Context.Tokens.LoadAsync(Provider.Id).ConfigureAwait(false);
            if (tokens is null || !tokens.IsUsable)
                throw new ProviderException(Provider.SignInLabel + " first.", signedOut: true);
            var snapshot = (await FetchAsync(tokens).ConfigureAwait(false)).Resolved();
            await Context.Cache.UpdateAsync(Provider.Id, entry => entry with
            {
                Usage = snapshot,
                LastError = null,
                LastErrorAt = null,
                UpdatedAt = Now,
            }).ConfigureAwait(false);
            Context.Log?.Invoke("usage", $"{Provider.Id} refreshed");
            return snapshot;
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            var message = error is ProviderException ? error.Message : "Usage refresh failed: " + error.Message;
            await Context.Cache.UpdateAsync(Provider.Id, entry => entry with { LastError = message, LastErrorAt = Now })
                .ConfigureAwait(false);
            Context.Log?.Invoke("usage", $"{Provider.Id} refresh failed: {error.GetType().Name}");
            if (error is ProviderException) throw;
            throw new ProviderException(message, inner: error);
        }
    }

    /// <summary>Loads usage with stored tokens, refreshing them when needed (and saving the result).</summary>
    protected abstract Task<UsageSnapshot> FetchAsync(AuthTokens tokens);

    public virtual async Task SignOutAsync()
    {
        var tokens = await Context.Tokens.LoadAsync(Provider.Id).ConfigureAwait(false);
        await Context.Tokens.DeleteAsync(Provider.Id).ConfigureAwait(false);
        await Context.Cache.ClearAsync(Provider.Id).ConfigureAwait(false);
        if (tokens is not null)
        {
            try { await RevokeAsync(tokens).ConfigureAwait(false); }
            catch (Exception) { /* Best effort; local credentials are already gone. */ }
        }
    }

    protected virtual Task RevokeAsync(AuthTokens tokens) => Task.CompletedTask;

    protected Task SaveTokensAsync(AuthTokens tokens) => Context.Tokens.SaveAsync(Provider.Id, tokens);

    protected Task<HttpPayload> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken = default) =>
        ProviderHttp.SendAsync(Context.Http, request, cancellationToken);

    /// <summary>
    /// Shared loopback sign-in: waits for the browser to return to <paramref name="path"/>, checks the
    /// state, and hands the code to <paramref name="exchange"/>. Wrong paths (favicon) get a 404.
    /// </summary>
    protected async Task<AuthTokens> RunLoopbackAsync(LoopbackCallbackServer server, string path, string state,
        Uri authorizeUrl, Func<Uri, Task> openBrowser, Func<string, Task<AuthTokens>> exchange, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromMinutes(5));
        await openBrowser(authorizeUrl).ConfigureAwait(false);
        while (true)
        {
            LoopbackRequest request;
            try
            {
                request = await server.AcceptAsync(timeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                throw new ProviderException("Sign-in timed out. Start again from the app.");
            }
            using (request)
            {
                if (request.Path != path)
                {
                    await request.RespondAsync(404, "Not found.", false).ConfigureAwait(false);
                    continue;
                }
                if (!Pkce.SecureEquals(state, request["state"]))
                {
                    await request.RespondAsync(400, "The sign-in state did not match. Return to Codex Meter and try again.", false)
                        .ConfigureAwait(false);
                    continue;
                }
                if (request["error"] is { Length: > 0 } error)
                {
                    var message = request["error_description"] is { Length: > 0 } description ? description : error;
                    await request.RespondAsync(400, message, false).ConfigureAwait(false);
                    throw new ProviderException("Sign-in failed: " + message);
                }
                if (request["code"] is not { Length: > 0 } code)
                {
                    await request.RespondAsync(400, "The authorization response did not include a code.", false).ConfigureAwait(false);
                    throw new ProviderException("Sign-in failed because no authorization code was returned.");
                }
                try
                {
                    var tokens = await exchange(code).ConfigureAwait(false);
                    await request.RespondAsync(200, $"Your {Provider.AccountName} account is connected.", true).ConfigureAwait(false);
                    return tokens;
                }
                catch (Exception failure)
                {
                    await request.RespondAsync(500, failure is ProviderException ? failure.Message : "Sign-in failed.", false)
                        .ConfigureAwait(false);
                    throw;
                }
            }
        }
    }
}
