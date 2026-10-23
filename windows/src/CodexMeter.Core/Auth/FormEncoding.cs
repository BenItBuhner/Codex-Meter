using System.Text;

namespace CodexMeter.Core.Auth;

/// <summary>
/// <c>application/x-www-form-urlencoded</c> encoding that matches Java's <c>URLEncoder</c>: spaces
/// become <c>+</c>, everything outside <c>A-Z a-z 0-9 - . _ *</c> is percent-encoded.
/// </summary>
public static class FormEncoding
{
    public static string Encode(IEnumerable<KeyValuePair<string, string>> parameters) =>
        string.Join("&", parameters.Select(p => Escape(p.Key) + "=" + Escape(p.Value)));

    public static string Escape(string value)
    {
        var builder = new StringBuilder();
        foreach (var b in Encoding.UTF8.GetBytes(value))
        {
            var c = (char)b;
            if (c is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or '-' or '.' or '_' or '*')
                builder.Append(c);
            else if (c == ' ')
                builder.Append('+');
            else
                builder.Append('%').Append(b.ToString("X2"));
        }
        return builder.ToString();
    }

    public static Dictionary<string, string> ParseQuery(string query)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var equals = pair.IndexOf('=');
            var key = equals >= 0 ? pair[..equals] : pair;
            var value = equals >= 0 ? pair[(equals + 1)..] : "";
            result[Unescape(key)] = Unescape(value);
        }
        return result;
    }

    private static string Unescape(string value) => Uri.UnescapeDataString(value.Replace('+', ' '));
}
