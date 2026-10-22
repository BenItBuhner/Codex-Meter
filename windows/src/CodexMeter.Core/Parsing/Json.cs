using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace CodexMeter.Core.Parsing;

/// <summary>The response was valid JSON but not an object.</summary>
public sealed class UsageParsingException(string message) : Exception(message);

/// <summary>
/// Tolerant JSON readers that mirror the iOS <c>JSONSupport</c> and Android <c>org.json</c>
/// behavior: numbers may arrive as strings, booleans are never coerced into numbers, and a
/// missing key and an explicit null are both "absent".
/// </summary>
internal static class Json
{
    public static JsonObject ParseObject(string text)
    {
        JsonNode? node;
        try
        {
            node = JsonNode.Parse(text, documentOptions: new JsonDocumentOptions { AllowTrailingCommas = true });
        }
        catch (JsonException error)
        {
            throw new UsageParsingException("The response was not valid JSON: " + error.Message);
        }
        return node as JsonObject ?? throw new UsageParsingException("The response did not contain a JSON object.");
    }

    public static JsonObject? TryParseObject(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        try { return ParseObject(text); } catch (UsageParsingException) { return null; }
    }

    public static JsonObject? Obj(JsonNode? node) => node as JsonObject;

    public static JsonArray? Arr(JsonNode? node) => node as JsonArray;

    public static JsonNode? Get(JsonObject? obj, string key) =>
        obj is not null && obj.TryGetPropertyValue(key, out var value) ? value : null;

    public static bool Has(JsonObject? obj, string key) =>
        obj is not null && obj.TryGetPropertyValue(key, out var value) && value is not null;

    public static string Str(JsonNode? node, string fallback = "")
    {
        if (node is not JsonValue value) return fallback;
        return value.GetValueKind() switch
        {
            JsonValueKind.String => value.GetValue<string>(),
            JsonValueKind.Number => value.ToJsonString(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => fallback,
        };
    }

    public static bool Bool(JsonNode? node, bool fallback)
    {
        if (node is not JsonValue value) return fallback;
        switch (value.GetValueKind())
        {
            case JsonValueKind.True: return true;
            case JsonValueKind.False: return false;
            case JsonValueKind.String:
                var text = value.GetValue<string>().ToLowerInvariant();
                return text == "true" || (text != "false" && fallback);
            default: return fallback;
        }
    }

    public static double? Double(JsonNode? node)
    {
        if (node is not JsonValue value) return null;
        switch (value.GetValueKind())
        {
            case JsonValueKind.Number:
                return value.GetValue<double>();
            case JsonValueKind.String:
                var text = value.GetValue<string>();
                return double.TryParse(text, NumberStyles.AllowLeadingSign | NumberStyles.AllowDecimalPoint
                        | NumberStyles.AllowExponent, CultureInfo.InvariantCulture, out var parsed)
                    ? parsed : null;
            default:
                return null;
        }
    }

    /// <summary>A finite number, or null when missing, non-numeric, NaN or infinite.</summary>
    public static double? Finite(JsonNode? node)
    {
        var value = Double(node);
        return value is { } v && double.IsFinite(v) ? v : null;
    }

    public static long Int64(JsonNode? node, long fallback)
    {
        if (Double(node) is not { } value || !double.IsFinite(value)) return fallback;
        if (value >= long.MaxValue) return long.MaxValue;
        if (value <= long.MinValue) return long.MinValue;
        return (long)value;
    }

    public static int Int(JsonNode? node, int fallback)
    {
        var parsed = Int64(node, fallback);
        return (int)Math.Clamp(parsed, int.MinValue, int.MaxValue);
    }

    /// <summary>Rounds half up like Java's <c>Math.round</c> and Swift's <c>floor(x + 0.5)</c>.</summary>
    public static int RoundHalfUp(double value)
    {
        var rounded = Math.Floor(value + 0.5);
        if (rounded >= int.MaxValue) return int.MaxValue;
        if (rounded <= int.MinValue) return int.MinValue;
        return (int)rounded;
    }

    /// <summary>Decodes the payload of a JWT, or null.</summary>
    public static JsonObject? JwtPayload(string? token)
    {
        if (string.IsNullOrEmpty(token)) return null;
        var parts = token.Split('.');
        if (parts.Length != 3) return null;
        var normalized = parts[1].Replace('-', '+').Replace('_', '/');
        while (normalized.Length % 4 != 0) normalized += "=";
        try
        {
            var bytes = Convert.FromBase64String(normalized);
            return TryParseObject(System.Text.Encoding.UTF8.GetString(bytes));
        }
        catch (FormatException)
        {
            return null;
        }
    }
}
