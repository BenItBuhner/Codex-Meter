using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Providers;

namespace CodexMeter.Core.Services;

/// <summary>
/// Sign-in storage and usage refresh for one provider added through Accounts → Add account.
/// Mirrors Android's <c>ProviderUsageApi</c> and iOS's <c>ProviderUsageService</c>: the provider's
/// <see cref="ProviderClient"/> knows the routes; this class runs the flows, refreshes tokens and
/// keeps the provider's own credentials and cache.
/// </summary>
public sealed class AddedProviderService(UsageProvider provider, ProviderClient client, ProviderContext context)
    : ProviderService(provider, context)
{
    private const string CallbackPath = "/callback";

    public ProviderClient Client { get; } = client;

    /// <summary>Null for Codex, Claude Code, Cursor and providers this build can't connect.</summary>
    public static AddedProviderService? Create(UsageProvider provider, ProviderContext context) =>
        provider.IsAvailable && ProviderClients.For(provider) is { } client ? new AddedProviderService(provider, client, context) : null;

    protected override async Task<AuthTokens> RunSignInAsync(SignInPrompt prompt, CancellationToken cancellationToken)
    {
        var attempt = new SignInAttempt();
        if (Client.HasDeviceFlow) return await RunDeviceAsync(attempt, prompt, cancellationToken).ConfigureAwait(false);
        if (Client.PasteUrl(attempt) is { } pasteUrl) return await RunPasteAsync(attempt, new Uri(pasteUrl), prompt, cancellationToken).ConfigureAwait(false);
        if (Provider.SignIn == SignInStyle.Loopback)
        {
            using var server = LoopbackCallbackServer.Start([]);
            var redirect = $"http://localhost:{server.Port}{CallbackPath}";
            var url = Client.LoopbackAuthUrl(redirect, attempt)
                ?? throw new ProviderException($"No browser sign-in for {Provider.DisplayName}.");
            return await RunLoopbackAsync(server, CallbackPath, attempt.State, new Uri(url), prompt.OpenBrowser,
                code => Client.FinishLoopbackAsync(attempt, code, Context, cancellationToken), cancellationToken).ConfigureAwait(false);
        }
        throw new ProviderException($"{Provider.DisplayName} connects with an API key.");
    }

    private async Task<AuthTokens> RunDeviceAsync(SignInAttempt attempt, SignInPrompt prompt, CancellationToken cancellationToken)
    {
        var code = await Client.StartDeviceAsync(attempt, Context, cancellationToken).ConfigureAwait(false);
        if (prompt.ShowDeviceCode is { } show) await show(code).ConfigureAwait(false);
        else await prompt.OpenBrowser(new Uri(code.OpenUrl)).ConfigureAwait(false);

        var interval = TimeSpan.FromSeconds(code.IntervalSeconds);
        var deadline = Now.AddSeconds(code.ExpiresInSeconds);
        while (true)
        {
            await Task.Delay(interval, cancellationToken).ConfigureAwait(false);
            if (Now >= deadline) throw new ProviderException("The code expired. Start again.");
            DevicePoll poll;
            try
            {
                poll = await Client.PollDeviceAsync(attempt, Context, cancellationToken).ConfigureAwait(false);
            }
            catch (ProviderException) when (Now < deadline)
            {
                // A dropped connection while the user is still approving; try again next tick.
                continue;
            }
            switch (poll.State)
            {
                case DevicePollState.Pending:
                    continue;
                case DevicePollState.SlowDown:
                    interval += TimeSpan.FromSeconds(5);
                    continue;
                case DevicePollState.Done when poll.Tokens is { } tokens:
                    return await Client.FinishDeviceAsync(attempt, tokens, Context, cancellationToken).ConfigureAwait(false);
                default:
                    throw new ProviderException(poll.Message.Length > 0 ? poll.Message : "Sign-in failed.");
            }
        }
    }

    private async Task<AuthTokens> RunPasteAsync(SignInAttempt attempt, Uri page, SignInPrompt prompt, CancellationToken cancellationToken)
    {
        if (prompt.RequestPaste is not { } request)
            throw new ProviderException($"Signing in to {Provider.DisplayName} needs a pasted token, which this window can't ask for.");
        var pasted = await request(page, Client.PasteHint, cancellationToken).ConfigureAwait(false);
        if (pasted is null) throw new OperationCanceledException(cancellationToken);
        return await Client.FinishPasteAsync(attempt, pasted, Context, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>What the paste dialog says under the text box.</summary>
    public string PasteHint => Client.PasteHint;

    protected override Task<AuthTokens> RunApiKeySignInAsync(string key, CancellationToken cancellationToken) =>
        Client.FromApiKeyAsync(key, Context, cancellationToken);

    protected override async Task<UsageSnapshot> FetchAsync(AuthTokens tokens)
    {
        var cancellationToken = CancellationToken.None;
        tokens = await UsableTokensAsync(tokens, cancellationToken).ConfigureAwait(false);
        ProviderFetch fetched;
        try
        {
            fetched = await Client.FetchAsync(tokens, Context, cancellationToken).ConfigureAwait(false);
        }
        catch (ProviderUnauthorizedException error)
        {
            if (!Client.CanRefresh(tokens))
                throw new ProviderUnauthorizedException(error.Message + " Sign in again from Accounts.");
            tokens = await RefreshTokensAsync(tokens, cancellationToken).ConfigureAwait(false);
            fetched = await Client.FetchAsync(tokens, Context, cancellationToken).ConfigureAwait(false);
        }

        var snapshot = fetched.Snapshot;
        // Some providers only name the plan on some calls; keep the last one seen.
        if (snapshot.PlanType.Length == 0
            && (await Context.Cache.LoadAsync(Provider.Id).ConfigureAwait(false))?.Usage?.PlanType is { Length: > 0 } previous)
            snapshot = snapshot with { PlanType = previous };
        if (tokens.Email.Length == 0 && fetched.Email.Length > 0)
            await SaveTokensAsync(tokens with { Email = fetched.Email }).ConfigureAwait(false);
        return snapshot;
    }

    private async Task<AuthTokens> UsableTokensAsync(AuthTokens tokens, CancellationToken cancellationToken)
    {
        if (!Client.CanRefresh(tokens) || !tokens.ShouldRefresh(Now)) return tokens;
        try
        {
            return await RefreshTokensAsync(tokens, cancellationToken).ConfigureAwait(false);
        }
        catch (ProviderException) when (tokens.ExpiresAt > Now)
        {
            // A token that has not expired yet still works; only fail once it has.
            return tokens;
        }
    }

    private async Task<AuthTokens> RefreshTokensAsync(AuthTokens previous, CancellationToken cancellationToken)
    {
        var refreshed = await Client.RefreshAsync(previous, Context, cancellationToken).ConfigureAwait(false);
        if (!refreshed.IsUsable)
            throw new ProviderUnauthorizedException($"Your {Provider.DisplayName} sign-in expired. Sign in again from Accounts.");
        await SaveTokensAsync(refreshed).ConfigureAwait(false);
        return refreshed;
    }
}
