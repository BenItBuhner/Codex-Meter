package dev.bennett.codexmeter;

/** Validated metadata for one installable GitHub release. */
public final class GitHubRelease {
    public final String version;
    public final String tag;
    public final String name;
    public final String notes;
    public final String publishedAt;
    public final String pageUrl;
    public final String apkName;
    public final String apkUrl;
    public final long apkSize;
    public final String checksumUrl;
    public final boolean prerelease;

    GitHubRelease(String version, String tag, String name, String notes, String publishedAt,
            String pageUrl, String apkName, String apkUrl, long apkSize, String checksumUrl,
            boolean prerelease) {
        this.version = version;
        this.tag = tag;
        this.name = name;
        this.notes = notes;
        this.publishedAt = publishedAt;
        this.pageUrl = pageUrl;
        this.apkName = apkName;
        this.apkUrl = apkUrl;
        this.apkSize = apkSize;
        this.checksumUrl = checksumUrl;
        this.prerelease = prerelease;
    }

    public boolean isNewerThan(String currentVersion) {
        ReleaseVersion release = ReleaseVersion.parse(version);
        ReleaseVersion current = ReleaseVersion.parse(currentVersion);
        return release != null && current != null && release.compareTo(current) > 0;
    }
}
