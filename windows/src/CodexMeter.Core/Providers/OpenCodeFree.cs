using CodexMeter.Core.Models;

namespace CodexMeter.Core.Providers;

/// <summary>
/// OpenCode's free tier: what Codex Meter can know about it without an account.
/// OpenCode's gateway counts free-model requests per network (IP address) per UTC day and
/// refuses with <c>FreeUsageLimitError</c> once a network reaches its daily number, with a
/// <c>retry-after</c> of the seconds left until 00:00 UTC. It has no route that reports how many
/// requests are used or left, and it only serves free models to OpenCode itself, so the daily
/// reset is the one thing that can be shown honestly. Port of Android's <c>OpenCodeFree</c>.
/// </summary>
public static class OpenCodeFree
{
    /// <summary>Dashboard card title.</summary>
    public const string CardTitle = "Daily free limit";

    /// <summary>What the card says under the reset time.</summary>
    public const string Explanation = "OpenCode counts free-model requests from your network each day and resets them "
        + "at midnight UTC. It doesn't share how many are left.";

    /// <summary>Sign-in copy: there is nothing to sign in to.</summary>
    public const string HowItWorks = "No sign-in needed. OpenCode's free models have a daily limit for each network, "
        + "and Codex Meter shows when it resets. OpenCode doesn't share how much of it is used, so there's no percentage.";

    /// <summary>The next 00:00 UTC strictly after <paramref name="now"/>.</summary>
    public static DateTimeOffset NextReset(DateTimeOffset now)
    {
        var utc = now.ToUniversalTime();
        return new DateTimeOffset(utc.Year, utc.Month, utc.Day, 0, 0, 0, TimeSpan.Zero).AddDays(1);
    }

    /// <summary>The snapshot the client saves: no meters, only the plan and when it was read.</summary>
    public static UsageSnapshot Snapshot(DateTimeOffset now) =>
        new("Free", true, false, null, null, null, null, null, null, null, now);
}
