using System.Security.Cryptography;
using System.Text;

namespace CodexMeter.Core.Auth;

/// <summary>An RFC 7636 PKCE pair plus an OAuth state value.</summary>
public sealed record Pkce(string Verifier, string Challenge, string State)
{
    public static Pkce Generate(int verifierBytes = 64) =>
        FromVerifier(RandomToken(verifierBytes), RandomToken(32));

    public static Pkce FromVerifier(string verifier, string state) => new(verifier, S256(verifier), state);

    /// <summary>base64url(SHA-256(verifier)) without padding.</summary>
    public static string S256(string verifier) => Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));

    public static string RandomToken(int bytes) => Base64Url(RandomNumberGenerator.GetBytes(Math.Max(1, bytes)));

    public static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).Replace('+', '-').Replace('/', '_').TrimEnd('=');

    /// <summary>Constant-time comparison for the returned state.</summary>
    public static bool SecureEquals(string? expected, string? actual) =>
        expected is not null && actual is not null
        && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(actual));
}
