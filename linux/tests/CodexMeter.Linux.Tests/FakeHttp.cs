using System.Net;
using System.Text;
using CodexMeter.Core.Auth;
using CodexMeter.Core.Models;
using CodexMeter.Core.Services;
using CodexMeter.Linux.Services;

namespace CodexMeter.Linux.Tests;

/// <summary>Scripted HTTP for the live services: each request is answered by the first route whose method and URL prefix match.</summary>
internal sealed class FakeHttp : HttpMessageHandler
{
    private readonly List<(string Method, string Prefix, Func<(int Status, string Body)> Answer)> routes = [];

    public int Calls(string prefix) => Requests.Count(r => r.StartsWith(prefix, StringComparison.Ordinal));

    public List<string> Requests { get; } = [];

    public FakeHttp On(string method, string prefix, Func<(int, string)> answer)
    {
        routes.Add((method, prefix, answer));
        return this;
    }

    public FakeHttp On(string method, string prefix, int status, string body) => On(method, prefix, () => (status, body));

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var url = request.RequestUri!.ToString();
        lock (Requests) Requests.Add(url);
        foreach (var route in routes)
        {
            if (route.Method != request.Method.Method || !url.StartsWith(route.Prefix, StringComparison.Ordinal)) continue;
            var (status, body) = route.Answer();
            return Task.FromResult(new HttpResponseMessage((HttpStatusCode)status)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json"),
                RequestMessage = request,
            });
        }
        throw new InvalidOperationException($"Unexpected request {request.Method} {url}");
    }
}

/// <summary>Real Codex responses for the live-service state tests (shapes from ios/CodexMeterCore fixtures).</summary>
internal static class CodexReplies
{
    public static string Fixture(string name) =>
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", name + ".json"));

    /// <summary>A usage reply with the given used percentages; both windows reset far ahead.</summary>
    public static string Usage(double fiveHourUsed, double weeklyUsed, bool limitReached = false, long resetIn = 9_000) => $$"""
        {
          "plan_type": "plus",
          "rate_limit": {
            "allowed": {{(limitReached ? "false" : "true")}},
            "limit_reached": {{(limitReached ? "true" : "false")}},
            "primary_window": { "used_percent": {{fiveHourUsed}}, "limit_window_seconds": 18000, "reset_after_seconds": {{resetIn}}, "reset_at": {{DateTimeOffset.UtcNow.ToUnixTimeSeconds() + resetIn}} },
            "secondary_window": { "used_percent": {{weeklyUsed}}, "limit_window_seconds": 604800, "reset_after_seconds": 400000, "reset_at": {{DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 400_000}} }
          }
        }
        """;

    /// <summary>A live app state whose Codex account is signed in and answers from <paramref name="http"/>.</summary>
    public static async Task<AppState> LiveStateAsync(FakeHttp http, bool signedIn = true)
    {
        var tokens = new MemoryTokenStore();
        if (signedIn) await tokens.SaveAsync("codex", new AuthTokens("access", "refresh", "", DateTimeOffset.UtcNow.AddHours(1), Email: "dev@example.com"));
        var context = new ProviderContext(ProviderHttp.CreateClient(http), tokens, new ProviderCacheStore(null));
        var state = new AppState(ProviderHub.Live(context), new SettingsStore(null), new UsageHistoryStore(null));
        await state.InitializeAsync();
        state.Settings.Update(s => s with { EnabledProviders = ["codex", "claude_code"], OnboardingDone = true });
        return state;
    }
}
