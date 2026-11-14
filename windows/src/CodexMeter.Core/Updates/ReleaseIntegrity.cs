using System.Security.Cryptography;

namespace CodexMeter.Core.Updates;

/// <summary>SHA256SUMS parsing and file hashing for downloaded builds (Android's ReleaseIntegrity).</summary>
public static class ReleaseIntegrity
{
    /// <summary>
    /// The lowercase SHA-256 the checksum file lists for <paramref name="fileName"/>, or empty when
    /// it lists none, or lists it twice (an ambiguous file is never trusted).
    /// </summary>
    public static string ExpectedSha256(string? checksumFile, string? fileName)
    {
        if (checksumFile is null || string.IsNullOrWhiteSpace(fileName) || fileName.Contains('/') || fileName.Contains('\\')) return "";
        var found = "";
        foreach (var raw in checksumFile.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length < 66) continue;
            var digest = line[..64];
            if (!IsSha256(digest)) continue;
            var listed = line[64..].Trim();
            if (listed.StartsWith('*')) listed = listed[1..];
            if (listed != fileName) continue;
            if (found.Length > 0) return "";
            found = digest.ToLowerInvariant();
        }
        return found;
    }

    public static async Task<string> Sha256Async(string path, CancellationToken cancellationToken = default)
    {
        await using var stream = File.OpenRead(path);
        var hash = await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false);
        return Convert.ToHexStringLower(hash);
    }

    public static bool IsSha256(string value) => value.Length == 64 && value.All(Uri.IsHexDigit);
}
