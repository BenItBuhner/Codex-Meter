using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CodexMeter.Core.Services;

/// <summary>
/// Privacy filters for diagnostic logs, a port of the Android <c>DiagnosticSanitizer</c>: bearer
/// and basic credentials, JWTs, email addresses, secret-looking assignments
/// (<c>access_token=…</c>, <c>client_secret: …</c>) and secret query parameters are replaced.
/// </summary>
public static class DiagnosticSanitizer
{
    private const string Redacted = "[REDACTED]";
    private static readonly Regex AuthScheme = new(@"\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex Email = new(@"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex Jwt = new(@"\b[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{8,}\b", RegexOptions.CultureInvariant);
    private static readonly Regex SensitiveAssignment = new(
        @"\b(authorization|proxy-authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|id[_-]?token|"
        + @"client[_-]?secret|code[_-]?verifier|password|passwd|session(?:id)?|api[_-]?key)\b\s*[""']?\s*[:=]\s*[""']?([^\s,;&}\]]+)",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex SensitiveQuery = new(
        @"([?&](?:code|state|token|access_token|refresh_token|id_token|code_verifier|client_secret|password|session|key)=)[^&#\s]+",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    /// <summary>Long opaque keys (sk-…, ghp_…, 40+ character tokens) that aren't in an assignment.</summary>
    private static readonly Regex LongSecret = new(@"\b(?:sk|pk|ghp|gho|ghu|xai|sess)[-_][A-Za-z0-9_-]{16,}\b|\b[A-Za-z0-9_-]{40,}\b", RegexOptions.CultureInvariant);

    public static string Redact(string? value)
    {
        if (string.IsNullOrEmpty(value)) return "";
        var result = AuthScheme.Replace(value, Redacted);
        result = Jwt.Replace(result, Redacted);
        result = Email.Replace(result, "[REDACTED_EMAIL]");
        result = SensitiveAssignment.Replace(result, m => m.Groups[1].Value + "=" + Redacted);
        result = SensitiveQuery.Replace(result, "$1" + Redacted);
        return LongSecret.Replace(result, Redacted);
    }

    /// <summary>A URL as scheme, host, port and path only: no query, fragment or user info.</summary>
    public static string SafeUrl(string? value)
    {
        if (string.IsNullOrEmpty(value)) return "";
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Host.Length == 0) return Redact(value);
        var port = uri.IsDefaultPort ? "" : ":" + uri.Port;
        return Redact($"{uri.Scheme.ToLowerInvariant()}://{uri.Host.ToLowerInvariant()}{port}{uri.AbsolutePath}");
    }
}

/// <summary>
/// Opt-in, privacy-filtered, rotating JSONL diagnostics kept only in the app's own folder, as on
/// Android (Privacy &amp; data → Diagnostic logs). Off by default; nothing is ever sent anywhere.
/// Every message goes through <see cref="DiagnosticSanitizer"/>, the current file rotates at 1 MB
/// with two older files kept, and Export writes one plain file the user chooses where to save.
/// </summary>
public sealed class DiagnosticLog(string? directory)
{
    public const long MaxFileBytes = 1024 * 1024;
    public const int MaxArchives = 2;
    private const string CurrentFile = "events.jsonl";
    private readonly object gate = new();
    private long sequence;
    private readonly string session = Guid.NewGuid().ToString("N")[..12];

    public bool Enabled { get; set; }

    private string? Folder => directory is null ? null : Path.Combine(directory, "diagnostic-logs");

    private IEnumerable<string> Files()
    {
        if (Folder is not { } folder) yield break;
        for (var i = MaxArchives; i >= 1; i--)
        {
            var archive = Path.Combine(folder, $"events.{i}.jsonl");
            if (File.Exists(archive)) yield return archive;
        }
        var current = Path.Combine(folder, CurrentFile);
        if (File.Exists(current)) yield return current;
    }

    /// <summary>Total size of the saved logs.</summary>
    public long Bytes
    {
        get
        {
            lock (gate)
            {
                try { return Files().Sum(f => new FileInfo(f).Length); }
                catch (IOException) { return 0; }
            }
        }
    }

    public bool HasLogs => Bytes > 0;

    /// <summary>Appends one event when logging is on. Never throws.</summary>
    public void Write(string category, string message, string level = "info")
    {
        if (!Enabled || Folder is not { } folder) return;
        var line = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["time"] = DateTimeOffset.UtcNow.ToString("O"),
            ["seq"] = Interlocked.Increment(ref sequence),
            ["session"] = session,
            ["level"] = level,
            ["category"] = DiagnosticSanitizer.Redact(category),
            ["message"] = DiagnosticSanitizer.Redact(message.Length > 2000 ? message[..2000] : message),
        });
        lock (gate)
        {
            try
            {
                Directory.CreateDirectory(folder);
                var path = Path.Combine(folder, CurrentFile);
                if (File.Exists(path) && new FileInfo(path).Length + line.Length > MaxFileBytes) Rotate(folder);
                File.AppendAllText(path, line + "\n", Encoding.UTF8);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private static void Rotate(string folder)
    {
        var oldest = Path.Combine(folder, $"events.{MaxArchives}.jsonl");
        if (File.Exists(oldest)) File.Delete(oldest);
        for (var i = MaxArchives - 1; i >= 1; i--)
        {
            var from = Path.Combine(folder, $"events.{i}.jsonl");
            if (File.Exists(from)) File.Move(from, Path.Combine(folder, $"events.{i + 1}.jsonl"), overwrite: true);
        }
        File.Move(Path.Combine(folder, CurrentFile), Path.Combine(folder, "events.1.jsonl"), overwrite: true);
    }

    /// <summary>Every saved event, oldest first, with a header naming the app, as one file's text.</summary>
    public string Export(string appDescription)
    {
        var text = new StringBuilder();
        text.Append("# Codex Meter diagnostic log\n# ").Append(DiagnosticSanitizer.Redact(appDescription))
            .Append("\n# Exported ").Append(DateTimeOffset.UtcNow.ToString("O"))
            .Append(". Privacy-filtered: tokens, keys and email addresses are removed.\n");
        lock (gate)
        {
            foreach (var file in Files())
            {
                try { text.Append(File.ReadAllText(file, Encoding.UTF8)); }
                catch (IOException) { }
            }
        }
        return text.ToString();
    }

    /// <summary>Deletes every saved log file.</summary>
    public void Clear()
    {
        lock (gate)
        {
            foreach (var file in Files().ToList())
            {
                try { File.Delete(file); }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
    }

    /// <summary>"No logs saved", "12 KB saved", "1.4 MB saved".</summary>
    public static string SizeLabel(long bytes) =>
        bytes <= 0 ? "No logs saved"
        : bytes < 1024 * 1024 ? $"{Math.Max(1, (bytes + 512) / 1024)} KB saved"
        : (bytes / (1024d * 1024d)).ToString("0.0", System.Globalization.CultureInfo.InvariantCulture) + " MB saved";
}
