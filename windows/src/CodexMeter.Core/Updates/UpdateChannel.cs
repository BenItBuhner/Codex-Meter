namespace CodexMeter.Core.Updates;

/// <summary>
/// The two update channels, as on Android (Settings → Updates → Update channel): stable follows
/// the newest regular release, alpha also follows prereleases tagged from the alpha branch.
/// </summary>
public enum UpdateChannel { Stable, Alpha }

public static class UpdateChannels
{
    /// <summary>
    /// The channel to use when the user hasn't picked one: alpha for an alpha build, so a copy
    /// installed from a prerelease keeps getting prereleases, stable otherwise.
    /// </summary>
    public static UpdateChannel Effective(UpdateChannel? chosen, ReleaseVersion? installed) =>
        chosen ?? (installed is { IsPrerelease: true } ? UpdateChannel.Alpha : UpdateChannel.Stable);

    public static string Label(UpdateChannel channel) => channel == UpdateChannel.Alpha ? "Alpha" : "Stable";

    /// <summary>The newest release the channel follows that has a build for this platform.</summary>
    public static ReleaseInfo? Tracked(IEnumerable<ReleaseInfo> releases, UpdateChannel channel) =>
        releases.Where(r => r.HasDownload && (channel == UpdateChannel.Alpha || !r.Prerelease))
            .OrderByDescending(r => r.Version)
            .FirstOrDefault();

    /// <summary>
    /// The release to offer, or null when up to date. On stable, an alpha build is offered the
    /// newest stable even though it's an older version ("Return to stable"), but only after the
    /// user picked stable themselves.
    /// </summary>
    public static ReleaseInfo? SelectUpdate(IEnumerable<ReleaseInfo> releases, ReleaseVersion? installed, UpdateChannel channel,
        bool userChoseChannel)
    {
        var tracked = Tracked(releases, channel);
        if (tracked is null) return null;
        if (tracked.Version.IsNewerThan(installed)) return tracked;
        if (channel == UpdateChannel.Stable && userChoseChannel && IsReturnToStable(tracked, installed)) return tracked;
        return null;
    }

    public static bool IsReturnToStable(ReleaseInfo release, ReleaseVersion? installed) =>
        !release.Prerelease && installed is { IsPrerelease: true } && release.Version.CompareTo(installed) < 0;
}

/// <summary>How often to look for updates automatically (Android's UpdateCheckFrequency).</summary>
public static class UpdateCheckFrequency
{
    public static readonly int[] Hours = [1, 6, 12, 24, 168];

    public static int Normalize(int hours) => Hours.Contains(hours) ? hours : 24;

    public static string Label(int hours) => Normalize(hours) switch
    {
        1 => "Every hour",
        6 => "Every 6 hours",
        12 => "Every 12 hours",
        168 => "Weekly",
        _ => "Daily",
    };

    /// <summary>True when an automatic check is due.</summary>
    public static bool IsDue(DateTimeOffset? lastCheck, int hours, DateTimeOffset now) =>
        lastCheck is not { } last || last > now || now - last >= TimeSpan.FromHours(Normalize(hours));
}
