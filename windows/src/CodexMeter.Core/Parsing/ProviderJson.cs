using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace CodexMeter.Core.Parsing;

/// <summary>
/// Lookups over <see cref="JsonObject"/> that mirror Android's <c>org.json</c> <c>opt*</c> methods,
/// which the added providers' readers were written against. <see cref="JsonObject"/> keeps keys in
/// the order the server sent them, so "first match wins" picks the same windows as Android and iOS.
/// </summary>
public static class ProviderJson
{
    private static readonly JsonNodeOptions NodeOptions = new() { PropertyNameCaseInsensitive = false };
    private static readonly JsonDocumentOptions DocumentOptions = new() { AllowTrailingCommas = true, MaxDepth = 256 };

    /// <summary>The root object of <paramref name="text"/>, or an empty object when it isn't one.</summary>
    public static JsonObject Object(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return new JsonObject();
        try
        {
            return JsonNode.Parse(text.Trim(), NodeOptions, DocumentOptions) as JsonObject ?? new JsonObject();
        }
        catch (Exception error) when (error is JsonException or ArgumentException or InvalidOperationException)
        {
            return new JsonObject();
        }
    }

    public static JsonNode? Get(JsonObject? o, string key)
    {
        if (o is null) return null;
        try { return o.TryGetPropertyValue(key, out var value) ? value : null; }
        catch (ArgumentException) { return null; }
    }

    public static bool Has(JsonObject? o, string key) => Get(o, key) is not null;

    public static JsonObject? Obj(JsonObject? o, string key) => Get(o, key) as JsonObject;

    public static JsonArray? Arr(JsonObject? o, string key) => Get(o, key) as JsonArray;

    public static bool IsNull(JsonNode? node) => node is null || (node is JsonValue v && v.GetValueKind() == JsonValueKind.Null);

    /// <summary>Text form, as <c>String.valueOf</c> prints an <c>org.json</c> value.</summary>
    public static string Text(JsonNode? node)
    {
        if (node is null) return "null";
        if (node is JsonValue value)
        {
            return value.GetValueKind() switch
            {
                JsonValueKind.String => value.GetValue<string>(),
                JsonValueKind.True => "true",
                JsonValueKind.False => "false",
                JsonValueKind.Null => "null",
                _ => value.ToJsonString(),
            };
        }
        return node.ToJsonString();
    }

    /// <summary><c>optString(key, fallback)</c>: any non-null value as text.</summary>
    public static string String(JsonObject? o, string key, string fallback = "")
    {
        var node = Get(o, key);
        return IsNull(node) ? fallback : Text(node);
    }

    /// <summary><c>optBoolean(key, fallback)</c>: a JSON bool or the strings "true" / "false".</summary>
    public static bool Bool(JsonObject? o, string key, bool fallback)
    {
        if (Get(o, key) is not JsonValue value) return fallback;
        switch (value.GetValueKind())
        {
            case JsonValueKind.True: return true;
            case JsonValueKind.False: return false;
            case JsonValueKind.String:
                var text = value.GetValue<string>();
                if (text.Equals("true", StringComparison.OrdinalIgnoreCase)) return true;
                if (text.Equals("false", StringComparison.OrdinalIgnoreCase)) return false;
                return fallback;
            default: return fallback;
        }
    }

    /// <summary><c>optLong</c>: a number or numeric string, truncated.</summary>
    public static long Int(JsonObject? o, string key, long fallback)
    {
        var number = Number(Get(o, key));
        if (double.IsNaN(number) || double.IsInfinity(number)) return fallback;
        if (number >= long.MaxValue) return long.MaxValue;
        if (number <= long.MinValue) return long.MinValue;
        return (long)number;
    }

    /// <summary>A JSON number or numeric string, or NaN.</summary>
    public static double Number(JsonNode? node)
    {
        if (node is not JsonValue value) return double.NaN;
        switch (value.GetValueKind())
        {
            case JsonValueKind.Number:
                return value.TryGetValue<double>(out var d) ? d : double.Parse(value.ToJsonString(), CultureInfo.InvariantCulture);
            case JsonValueKind.String:
                return double.TryParse(value.GetValue<string>().Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed)
                    ? parsed : double.NaN;
            default:
                return double.NaN;
        }
    }

    /// <summary>The <c>exp</c> claim of a JWT access token, or null.</summary>
    public static DateTimeOffset? JwtExpiry(string? token)
    {
        var payload = Json.JwtPayload(token);
        var exp = Number(Get(payload, "exp"));
        return double.IsNaN(exp) || exp <= 0 ? null : DateTimeOffset.FromUnixTimeSeconds((long)exp);
    }

    /// <summary>An email claim from a JWT, or empty.</summary>
    public static string JwtEmail(string? token)
    {
        var payload = Json.JwtPayload(token);
        var email = String(payload, "email");
        return email.Contains('@') ? email : "";
    }
}
