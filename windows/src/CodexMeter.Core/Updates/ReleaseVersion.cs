using System.Numerics;

namespace CodexMeter.Core.Updates;

/// <summary>
/// A SemVer-style release version ("v2.9.0-alpha.27"), compared the way the Android updater
/// compares them (ReleaseVersion.java): numbers first, a prerelease sorts before its release,
/// and numeric prerelease parts compare as numbers (alpha.10 &gt; alpha.9).
/// </summary>
public sealed class ReleaseVersion : IComparable<ReleaseVersion>
{
    private readonly BigInteger[] numbers;
    private readonly string[] prereleaseParts;

    private ReleaseVersion(BigInteger[] numbers, string prerelease, string normalized)
    {
        this.numbers = numbers;
        Prerelease = prerelease;
        prereleaseParts = prerelease.Length == 0 ? [] : prerelease.Split('.');
        Normalized = normalized;
    }

    /// <summary>"2.9.0-alpha.27": no "v", no build metadata, lowercase prerelease.</summary>
    public string Normalized { get; }

    public string Prerelease { get; }

    public bool IsPrerelease => Prerelease.Length > 0;

    public static ReleaseVersion? Parse(string? value)
    {
        if (value is null) return null;
        var candidate = value.Trim();
        if (candidate.StartsWith('v') || candidate.StartsWith('V')) candidate = candidate[1..];
        var plus = candidate.IndexOf('+');
        if (plus >= 0) candidate = candidate[..plus];
        var prerelease = "";
        var dash = candidate.IndexOf('-');
        if (dash >= 0)
        {
            prerelease = candidate[(dash + 1)..].ToLowerInvariant();
            candidate = candidate[..dash];
            if (prerelease.Length == 0) return null;
        }
        var parts = candidate.Split('.');
        if (parts.Length is < 2 or > 4 || !ValidPrerelease(prerelease)) return null;
        var list = new List<BigInteger>();
        foreach (var part in parts)
        {
            if (part.Length == 0 || !Digits(part)) return null;
            list.Add(BigInteger.Parse(part));
        }
        while (list.Count < 3) list.Add(BigInteger.Zero);
        var normalized = string.Join('.', list) + (prerelease.Length > 0 ? "-" + prerelease : "");
        return new ReleaseVersion([.. list], prerelease, normalized);
    }

    public int CompareTo(ReleaseVersion? other)
    {
        if (other is null) return 1;
        var count = Math.Max(numbers.Length, other.numbers.Length);
        for (var i = 0; i < count; i++)
        {
            var left = i < numbers.Length ? numbers[i] : BigInteger.Zero;
            var right = i < other.numbers.Length ? other.numbers[i] : BigInteger.Zero;
            var compared = left.CompareTo(right);
            if (compared != 0) return compared;
        }
        if (IsPrerelease != other.IsPrerelease) return IsPrerelease ? -1 : 1;
        var parts = Math.Max(prereleaseParts.Length, other.prereleaseParts.Length);
        for (var i = 0; i < parts; i++)
        {
            if (i >= prereleaseParts.Length) return -1;
            if (i >= other.prereleaseParts.Length) return 1;
            var a = prereleaseParts[i];
            var b = other.prereleaseParts[i];
            var aNumeric = Digits(a);
            var bNumeric = Digits(b);
            var compared = aNumeric && bNumeric ? BigInteger.Parse(a).CompareTo(BigInteger.Parse(b))
                : aNumeric != bNumeric ? (aNumeric ? -1 : 1)
                : string.CompareOrdinal(a, b);
            if (compared != 0) return Math.Sign(compared);
        }
        return 0;
    }

    public bool IsNewerThan(ReleaseVersion? other) => other is null || CompareTo(other) > 0;

    public override string ToString() => Normalized;

    private static bool Digits(string value) => value.Length > 0 && value.All(char.IsAsciiDigit);

    private static bool ValidPrerelease(string value) =>
        value.Length == 0 || value.Split('.').All(p => p.Length > 0 && p.All(c => char.IsAsciiLetterOrDigit(c) || c == '-'));
}
