package dev.bennett.codexmeter;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parses only release entries that match the repository's signed APK release contract. */
public final class GitHubReleaseParser {
    private static final int MAX_NOTES = 6000;
    /** Matches the page size the release endpoint requests; anything beyond it is ignored. */
    static final int MAX_RELEASES = 30;

    private GitHubReleaseParser() {
    }

    public static List<GitHubRelease> parse(String json) throws Exception {
        return parse(json, false);
    }

    static List<GitHubRelease> parse(String json, boolean allowLocalDebugServer)
            throws Exception {
        JSONArray releases = new JSONArray(json == null ? "[]" : json);
        ArrayList<GitHubRelease> parsed = new ArrayList<>();
        Set<String> versions = new HashSet<>();
        for (int index = 0; index < releases.length(); index++) {
            JSONObject release = releases.optJSONObject(index);
            if (release == null || release.optBoolean("draft", false)) {
                continue;
            }
            String tag = clean(release.optString("tag_name", ""), 100);
            ReleaseVersion version = ReleaseVersion.parse(tag);
            if (version == null || versions.contains(version.normalized())) {
                continue;
            }
            String expectedApk = "CodexMeter-" + version.normalized() + ".apk";
            JSONObject apk = null;
            JSONObject checksum = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                    JSONObject asset = assets.optJSONObject(assetIndex);
                    if (asset == null) {
                        continue;
                    }
                    String assetName = asset.optString("name", "");
                    if (expectedApk.equals(assetName)) {
                        apk = asset;
                    } else if ("SHA256SUMS.txt".equals(assetName)) {
                        checksum = asset;
                    }
                }
            }
            if (apk == null || checksum == null) {
                continue;
            }
            String apkUrl = apk.optString("browser_download_url", "");
            String checksumUrl = checksum.optString("browser_download_url", "");
            String pageUrl = release.optString("html_url", "");
            if (!isTrustedReleaseUrl(apkUrl, allowLocalDebugServer)
                    || !isTrustedReleaseUrl(checksumUrl, allowLocalDebugServer)
                    || !isTrustedReleaseUrl(pageUrl, allowLocalDebugServer)) {
                continue;
            }
            long apkSize = apk.optLong("size", -1L);
            if (apkSize <= 0L) {
                continue;
            }
            String releaseName = clean(release.optString("name", ""), 160);
            if (releaseName.isEmpty()) {
                releaseName = "Codex Meter " + version.normalized();
            }
            String notes = clean(release.optString("body", ""), MAX_NOTES);
            boolean prerelease = release.optBoolean("prerelease", false)
                    || version.isPrerelease();
            versions.add(version.normalized());
            parsed.add(new GitHubRelease(version.normalized(), tag, releaseName, notes,
                    clean(release.optString("published_at", ""), 80), pageUrl, expectedApk,
                    apkUrl, apkSize, checksumUrl, prerelease));
        }
        parsed.sort(new Comparator<GitHubRelease>() {
            @Override
            public int compare(GitHubRelease left, GitHubRelease right) {
                return -ReleaseVersion.compare(left.version, right.version);
            }
        });
        return Collections.unmodifiableList(parsed);
    }

    /**
     * Reduces GitHub's release listing to the few fields {@link #parse} reads.
     *
     * <p>Each published release lists every desktop, phone and watch build with its uploader
     * profile, digests and download counts, so the raw listing grows by roughly 30 KB per
     * release no matter how short its notes are. Only the release's own fields, its phone APK
     * and its SHA256SUMS.txt are kept, and notes are capped, so the cached result stays small
     * and its size no longer tracks how many builds a release ships.
     */
    public static String compact(String json) throws Exception {
        JSONArray releases = new JSONArray(json == null ? "[]" : json);
        JSONArray compact = new JSONArray();
        for (int index = 0; index < releases.length() && compact.length() < MAX_RELEASES;
                index++) {
            JSONObject release = releases.optJSONObject(index);
            if (release == null || release.optBoolean("draft", false)) {
                continue;
            }
            String tag = clean(release.optString("tag_name", ""), 100);
            ReleaseVersion version = ReleaseVersion.parse(tag);
            if (version == null) {
                continue;
            }
            String expectedApk = "CodexMeter-" + version.normalized() + ".apk";
            JSONArray keptAssets = new JSONArray();
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                    JSONObject asset = assets.optJSONObject(assetIndex);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name", "");
                    if (expectedApk.equals(name) || "SHA256SUMS.txt".equals(name)) {
                        keptAssets.put(new JSONObject()
                                .put("name", name)
                                .put("browser_download_url",
                                        clean(asset.optString("browser_download_url", ""), 500))
                                .put("size", asset.optLong("size", -1L)));
                    }
                }
            }
            compact.put(new JSONObject()
                    .put("tag_name", tag)
                    .put("name", clean(release.optString("name", ""), 160))
                    .put("body", clean(release.optString("body", ""), MAX_NOTES))
                    .put("draft", false)
                    .put("prerelease", release.optBoolean("prerelease", false))
                    .put("published_at", clean(release.optString("published_at", ""), 80))
                    .put("html_url", clean(release.optString("html_url", ""), 500))
                    .put("assets", keptAssets));
        }
        return compact.toString();
    }

    public static GitHubRelease latestStable(List<GitHubRelease> releases) {
        if (releases == null) {
            return null;
        }
        for (GitHubRelease release : releases) {
            if (release != null && !release.prerelease) {
                return release;
            }
        }
        return null;
    }

    public static GitHubRelease findVersion(List<GitHubRelease> releases, String version) {
        ReleaseVersion wanted = ReleaseVersion.parse(version);
        if (wanted == null || releases == null) {
            return null;
        }
        for (GitHubRelease release : releases) {
            ReleaseVersion candidate = ReleaseVersion.parse(release.version);
            if (candidate != null && candidate.compareTo(wanted) == 0) {
                return release;
            }
        }
        return null;
    }

    static boolean isGitHubHttps(String value) {
        return isTrustedReleaseUrl(value, false);
    }

    private static boolean isTrustedReleaseUrl(String value, boolean allowLocalDebugServer) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean github = "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && ("github.com".equalsIgnoreCase(host)
                    || host.toLowerCase(java.util.Locale.US).endsWith(".github.com"));
            return github || (allowLocalDebugServer
                    && "http".equalsIgnoreCase(uri.getScheme())
                    && "10.0.2.2".equals(host)
                    && uri.getPort() == 8765);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String clean(String value, int maxLength) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
