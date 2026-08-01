package dev.bennett.codexmeter;

import java.util.List;

/**
 * Release-channel policy for the in-app updater.
 *
 * <p>Stable tracks the newest non-prerelease GitHub release. Alpha additionally tracks
 * prerelease builds tagged from the alpha branch. Alpha builds are signed with the same
 * release certificate as stable builds and keep the versionCode of the stable release they
 * branched from, so switching between channels is always an ordinary in-place install:
 * upgrading onto an alpha and returning to the newest stable both pass Android's signature
 * and versionCode checks without uninstalling.
 */
public final class UpdateChannel {
    public static final String STABLE = "stable";
    public static final String ALPHA = "alpha";

    private UpdateChannel() {
    }

    public static String normalize(String value) {
        return ALPHA.equalsIgnoreCase(value == null ? "" : value.trim()) ? ALPHA : STABLE;
    }

    public static boolean isAlpha(String channel) {
        return ALPHA.equals(normalize(channel));
    }

    /** Newest release the channel tracks, independent of the installed version. */
    public static GitHubRelease trackedRelease(List<GitHubRelease> releases, String channel) {
        if (releases == null || releases.isEmpty()) {
            return null;
        }
        if (isAlpha(channel)) {
            return releases.get(0);
        }
        return GitHubReleaseParser.latestStable(releases);
    }

    /** Release to offer as an update for the installed version, or null when up to date. */
    public static GitHubRelease selectUpdate(List<GitHubRelease> releases,
            String installedVersion, String channel) {
        GitHubRelease tracked = trackedRelease(releases, channel);
        if (tracked == null) {
            return null;
        }
        if (tracked.isNewerThan(installedVersion)) {
            return tracked;
        }
        if (!isAlpha(channel) && isReturnToStable(tracked, installedVersion)) {
            return tracked;
        }
        return null;
    }

    /**
     * True when installing {@code release} leaves a prerelease build for the newest stable
     * release. Alpha builds reuse the versionCode of the stable release they branched from,
     * so this install is an in-place update even though SemVer orders it as older.
     */
    public static boolean isReturnToStable(GitHubRelease release, String installedVersion) {
        if (release == null || release.prerelease) {
            return false;
        }
        ReleaseVersion installed = ReleaseVersion.parse(installedVersion);
        ReleaseVersion target = ReleaseVersion.parse(release.version);
        return installed != null && target != null && installed.isPrerelease()
                && target.compareTo(installed) < 0;
    }
}
