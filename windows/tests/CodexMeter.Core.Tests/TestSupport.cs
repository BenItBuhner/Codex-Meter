using System.Net;
using System.Text;
using CodexMeter.Core.Services;

namespace CodexMeter.Core.Tests;

internal static class Fixtures
{
    public static string Read(string name) =>
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", name + ".json"));

    public static readonly DateTimeOffset FetchedAt = DateTimeOffset.FromUnixTimeSeconds(1_900_000_000);

    public static DateTimeOffset Utc(string iso) => DateTimeOffset.Parse(iso, System.Globalization.CultureInfo.InvariantCulture);
}

/// <summary>Scripted HTTP: each request is matched by "METHOD url-prefix" and answered in order.</summary>
internal sealed class FakeHttp : HttpMessageHandler
{
    private readonly List<(string Method, string Prefix, Func<HttpRequestMessage, string, (int, string)> Answer)> routes = [];
    public List<(HttpRequestMessage Request, string Body)> Requests { get; } = [];

    public FakeHttp On(string method, string prefix, int status, string body) =>
        On(method, prefix, (_, _) => (status, body));

    public FakeHttp On(string method, string prefix, Func<HttpRequestMessage, string, (int, string)> answer)
    {
        routes.Add((method, prefix, answer));
        return this;
    }

    /// <summary>Answers in sequence for the same route: the first matching route is consumed if more remain.</summary>
    public FakeHttp Once(string method, string prefix, int status, string body)
    {
        var used = false;
        routes.Insert(0, (method, prefix, (_, _) =>
        {
            if (used) return (-1, "");
            used = true;
            return (status, body);
        }));
        return this;
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
        Requests.Add((request, body));
        foreach (var route in routes)
        {
            if (route.Method != request.Method.Method || !request.RequestUri!.ToString().StartsWith(route.Prefix, StringComparison.Ordinal)) continue;
            var (status, text) = route.Answer(request, body);
            if (status < 0) continue;
            return new HttpResponseMessage((HttpStatusCode)status) { Content = new StringContent(text, Encoding.UTF8, "application/json") };
        }
        throw new InvalidOperationException($"Unexpected request {request.Method} {request.RequestUri}");
    }

    public ProviderContext Context(DateTimeOffset? now = null, MemoryTokenStore? tokens = null) =>
        new(ProviderHttp.CreateClient(this), tokens ?? new MemoryTokenStore(), new ProviderCacheStore(null),
            now is null ? null : () => now.Value);
}

internal static class Jwt
{
    public static string Make(string payloadJson) =>
        "eyJhbGciOiJub25lIn0." + Convert.ToBase64String(Encoding.UTF8.GetBytes(payloadJson))
            .Replace('+', '-').Replace('/', '_').TrimEnd('=') + ".sig";
}
