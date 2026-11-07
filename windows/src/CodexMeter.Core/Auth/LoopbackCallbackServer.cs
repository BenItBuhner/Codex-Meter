using System.Net;
using System.Net.Sockets;
using System.Text;

namespace CodexMeter.Core.Auth;

/// <summary>
/// The tiny HTTP server a browser sign-in returns to (<c>http://localhost:PORT/path?code=…</c>),
/// the same loopback pattern the Codex and Claude Code CLIs use. It listens on 127.0.0.1 and, when
/// available, ::1 on the same port so <c>localhost</c> resolves either way. Nothing is reachable
/// from other machines.
/// </summary>
public sealed class LoopbackCallbackServer : IDisposable
{
    private readonly List<TcpListener> listeners = [];
    private readonly System.Threading.Channels.Channel<TcpClient> accepted =
        System.Threading.Channels.Channel.CreateUnbounded<TcpClient>();
    private readonly CancellationTokenSource stopping = new();

    private LoopbackCallbackServer(int port) => Port = port;

    private void StartAccepting(TcpListener listener)
    {
        listeners.Add(listener);
        _ = Task.Run(async () =>
        {
            try
            {
                while (!stopping.IsCancellationRequested)
                {
                    var client = await listener.AcceptTcpClientAsync(stopping.Token).ConfigureAwait(false);
                    await accepted.Writer.WriteAsync(client, stopping.Token).ConfigureAwait(false);
                }
            }
            catch (Exception error) when (error is OperationCanceledException or SocketException or ObjectDisposedException)
            {
                // The server stopped.
            }
        });
    }

    public int Port { get; }

    /// <summary>
    /// Binds the first free port from <paramref name="ports"/>, or an ephemeral port when the list
    /// is empty.
    /// </summary>
    public static LoopbackCallbackServer Start(IReadOnlyList<int> ports)
    {
        Exception? last = null;
        foreach (var candidate in ports.Count == 0 ? [0] : ports)
        {
            TcpListener? v4 = null;
            try
            {
                v4 = new TcpListener(IPAddress.Loopback, candidate);
                v4.Start();
                var port = ((IPEndPoint)v4.LocalEndpoint).Port;
                var server = new LoopbackCallbackServer(port);
                server.StartAccepting(v4);
                if (Socket.OSSupportsIPv6)
                {
                    try
                    {
                        var v6 = new TcpListener(IPAddress.IPv6Loopback, port);
                        v6.Start();
                        server.StartAccepting(v6);
                    }
                    catch (SocketException)
                    {
                        // IPv4 alone is enough; browsers fall back from ::1.
                    }
                }
                return server;
            }
            catch (SocketException error)
            {
                v4?.Stop();
                last = error;
            }
        }
        throw new InvalidOperationException(
            $"Could not open the local sign-in port ({string.Join(" or ", ports)}). Close other apps using it and try again.",
            last);
    }

    /// <summary>Waits for the next browser request. Requests that are not a GET come back with an empty path.</summary>
    public async Task<LoopbackRequest> AcceptAsync(CancellationToken cancellationToken)
    {
        var client = await accepted.Reader.ReadAsync(cancellationToken).ConfigureAwait(false);
        return await LoopbackRequest.ReadAsync(client, cancellationToken).ConfigureAwait(false);
    }

    public void Dispose()
    {
        stopping.Cancel();
        accepted.Writer.TryComplete();
        while (accepted.Reader.TryRead(out var pending)) pending.Dispose();
        foreach (var listener in listeners)
        {
            try { listener.Stop(); } catch (SocketException) { }
        }
        listeners.Clear();
    }
}

/// <summary>One request the browser sent to the loopback server.</summary>
public sealed class LoopbackRequest : IDisposable
{
    private readonly TcpClient client;

    private LoopbackRequest(TcpClient client, string path, IReadOnlyDictionary<string, string> parameters)
    {
        this.client = client;
        Path = path;
        Parameters = parameters;
    }

    public string Path { get; }
    public IReadOnlyDictionary<string, string> Parameters { get; }

    public string? this[string key] => Parameters.TryGetValue(key, out var value) ? value : null;

    internal static async Task<LoopbackRequest> ReadAsync(TcpClient client, CancellationToken cancellationToken)
    {
        client.ReceiveTimeout = 30_000;
        var stream = client.GetStream();
        var buffer = new byte[16 * 1024];
        var total = 0;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        while (total < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(total), timeout.Token).ConfigureAwait(false);
            if (read == 0) break;
            total += read;
            if (Encoding.ASCII.GetString(buffer, 0, total).Contains("\r\n\r\n", StringComparison.Ordinal)) break;
        }
        var head = Encoding.ASCII.GetString(buffer, 0, total);
        var requestLine = head.Split("\r\n", 2)[0];
        var parts = requestLine.Split(' ');
        if (parts.Length < 2 || parts[0] != "GET")
            return new LoopbackRequest(client, "", new Dictionary<string, string>());
        var target = parts[1];
        var query = target.IndexOf('?');
        var path = query >= 0 ? target[..query] : target;
        var parameters = query >= 0 ? FormEncoding.ParseQuery(target[(query + 1)..]) : new Dictionary<string, string>();
        return new LoopbackRequest(client, path, parameters);
    }

    public async Task RespondAsync(int status, string message, bool success)
    {
        try
        {
            var body = Encoding.UTF8.GetBytes(BrowserPage.Render(message, success));
            var header = $"HTTP/1.1 {status} {(status is >= 200 and < 300 ? "OK" : "Error")}\r\n" +
                         "Content-Type: text/html; charset=utf-8\r\n" +
                         $"Content-Length: {body.Length}\r\n" +
                         "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
            var stream = client.GetStream();
            await stream.WriteAsync(Encoding.ASCII.GetBytes(header)).ConfigureAwait(false);
            await stream.WriteAsync(body).ConfigureAwait(false);
            await stream.FlushAsync().ConfigureAwait(false);
        }
        catch (Exception error) when (error is IOException or SocketException or ObjectDisposedException)
        {
            // The browser may already have closed the tab.
        }
    }

    public void Dispose() => client.Dispose();
}

/// <summary>The page the browser shows after returning to the app.</summary>
public static class BrowserPage
{
    public static string Render(string message, bool success)
    {
        var title = success ? "You're signed in" : "Sign-in didn't finish";
        var accent = success ? "#0F7B0F" : "#C42B1C";
        var glyph = success ? "&#10003;" : "!";
        return $$$"""
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Codex Meter</title>
            <style>
            :root{color-scheme:light dark}
            body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:"Segoe UI Variable Text","Segoe UI",system-ui,sans-serif;background:#F3F3F3;color:#1A1A1A}
            @media (prefers-color-scheme:dark){body{background:#202020;color:#FFF}.card{background:#2B2B2B!important;border-color:#1D1D1D!important}p{color:#C5C5C5!important}}
            .card{background:#FFF;border:1px solid #E5E5E5;border-radius:8px;padding:32px 40px;max-width:420px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.08)}
            .badge{width:48px;height:48px;border-radius:50%;margin:0 auto 16px;display:grid;place-items:center;color:#FFF;font-size:24px;font-weight:600;background:{{{accent}}}}
            h1{font-family:"Segoe UI Variable Display","Segoe UI",system-ui,sans-serif;font-size:20px;font-weight:600;margin:0 0 8px}
            p{margin:0;color:#5C5C5C;font-size:14px;line-height:20px}
            </style></head><body><div class="card"><div class="badge">{{{glyph}}}</div>
            <h1>{{{title}}}</h1><p>{{{WebUtility.HtmlEncode(message)}}}</p>
            <p style="margin-top:12px">You can close this tab and return to Codex Meter.</p></div></body></html>
            """;
    }
}
