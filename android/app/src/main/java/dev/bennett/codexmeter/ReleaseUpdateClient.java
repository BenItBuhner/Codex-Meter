package dev.bennett.codexmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Public, unauthenticated GitHub release discovery client. */
public final class ReleaseUpdateClient {
    /**
     * Guards against a runaway response only. The listing is compacted right after it is read
     * (see {@link GitHubReleaseParser#compact}), so its raw size, which grows with every
     * build a release ships, never reaches the stored cache. Thirty releases took about
     * 650 KB raw in November 2026, which tripped the old 512 KB cap; compacted they take
     * well under 100 KB.
     */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 16 * 1024;
    private static final Object LOCK = new Object();

    private ReleaseUpdateClient() {
    }

    public static List<GitHubRelease> check(Context context) throws Exception {
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        synchronized (LOCK) {
            long started = SystemClock.elapsedRealtime();
            DiagnosticLog.info(app, "update", "release_check_started");
            try {
                List<GitHubRelease> releases = requestWithFallback(app);
                DiagnosticLog.info(app, "update", "release_check_succeeded",
                        "duration_ms", SystemClock.elapsedRealtime() - started,
                        "release_count", releases.size());
                return releases;
            } catch (Exception exception) {
                DiagnosticLog.error(app, "update", "release_check_failed", exception,
                        "duration_ms", SystemClock.elapsedRealtime() - started);
                UpdatePreferences.saveError(app, safeMessage(exception));
                throw exception;
            }
        }
    }

    /**
     * The full listing, or when that can't be read (too big, cut off, unreadable, a server
     * error) a small one: the newest few releases plus GitHub's latest stable release, which
     * is all the updater needs to offer an update on either channel. Only the listing's own
     * failure is reported if the small one fails too. Rate limits and untrusted redirects are
     * not retried, since a second request would only hit them again.
     */
    private static List<GitHubRelease> requestWithFallback(Context app) throws Exception {
        try {
            return request(app, true);
        } catch (SecurityException | RateLimitedException noRetry) {
            throw noRetry;
        } catch (Exception listingFailure) {
            DiagnosticLog.warn(app, "update", "release_listing_fallback",
                    "error", listingFailure.getClass().getSimpleName());
            try {
                return requestSmall(app);
            } catch (Exception fallbackFailure) {
                DiagnosticLog.warn(app, "update", "release_listing_fallback_failed",
                        "error", fallbackFailure.getClass().getSimpleName());
                throw listingFailure;
            }
        }
    }

    /** Newest releases plus the latest stable one, merged into one compact listing. */
    private static List<GitHubRelease> requestSmall(Context app) throws Exception {
        String endpoint = endpoint();
        boolean localDebugServer = isLocalDebugServer(endpoint);
        String base = endpoint.contains("?") ? endpoint.substring(0, endpoint.indexOf('?')) : endpoint;
        String recent = null;
        String latest = null;
        Exception failure = null;
        try {
            recent = get(app, base + "?per_page=" + GitHubReleaseParser.FALLBACK_PAGE_SIZE, localDebugServer,
                    "github_release_check_small");
        } catch (Exception e) {
            failure = e;
        }
        try {
            latest = get(app, base + "/latest", localDebugServer, "github_release_check_latest");
        } catch (Exception e) {
            if (failure == null) failure = e;
        }
        if (recent == null && latest == null) throw failure;
        String json = GitHubReleaseParser.mergeCompact(recent, latest);
        // No ETag: the next check goes back to the full listing.
        UpdatePreferences.saveSuccess(app, json, null);
        return UpdatePreferences.releases(app);
    }

    /** One plain GET of a small release endpoint; the body, or an exception on any failure. */
    private static String get(Context app, String url, boolean localDebugServer, String operation)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            long started = SystemClock.elapsedRealtime();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(25_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", AppConstants.updaterUserAgent());
            int status = connection.getResponseCode();
            URL finalUrl = connection.getURL();
            DiagnosticLog.info(app, "network", "request_finished",
                    "operation", operation,
                    "status", status,
                    "duration_ms", SystemClock.elapsedRealtime() - started,
                    "url", DiagnosticSanitizer.safeUrl(finalUrl.toString()));
            if (!trustedApiUrl(finalUrl, localDebugServer)) {
                throw new SecurityException("GitHub redirected the update check to an untrusted host.");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                String detail = read(connection.getErrorStream(), MAX_ERROR_BYTES);
                throw status == 403 || status == 429 ? new RateLimitedException(githubError(status, detail))
                        : new IllegalStateException(githubError(status, detail));
            }
            return read(connection.getInputStream(), MAX_RESPONSE_BYTES);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String endpoint() {
        return BuildConfig.DEBUG ? BuildConfig.UPDATE_API_URL : GitHubReleaseSource.RELEASES_API_URL;
    }

    /** GitHub's rate limit; retrying straight away would only hit it again. */
    static final class RateLimitedException extends IllegalStateException {
        RateLimitedException(String message) {
            super(message);
        }
    }

    private static List<GitHubRelease> request(Context app, boolean conditional)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            String endpoint = endpoint();
            boolean localDebugServer = isLocalDebugServer(endpoint);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            long started = SystemClock.elapsedRealtime();
            DiagnosticLog.info(app, "network", "request_started",
                    "operation", "github_release_check",
                    "method", "GET",
                    "url", DiagnosticSanitizer.safeUrl(endpoint),
                    "conditional", conditional);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(25_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", AppConstants.updaterUserAgent());
            String etag = conditional ? UpdatePreferences.etag(app) : "";
            if (!etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            int status = connection.getResponseCode();
            URL finalUrl = connection.getURL();
            DiagnosticLog.info(app, "network", "request_finished",
                    "operation", "github_release_check",
                    "status", status,
                    "duration_ms", SystemClock.elapsedRealtime() - started,
                    "url", DiagnosticSanitizer.safeUrl(finalUrl.toString()));
            if (!trustedApiUrl(finalUrl, localDebugServer)) {
                throw new SecurityException("GitHub redirected the update check to an untrusted host.");
            }
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (!UpdatePreferences.hasUsableCache(app)) {
                    UpdatePreferences.clearEtag(app);
                    return request(app, false);
                }
                UpdatePreferences.markNotModified(app);
                return UpdatePreferences.releases(app);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                String detail = read(connection.getErrorStream(), MAX_ERROR_BYTES);
                throw status == 403 || status == 429 ? new RateLimitedException(githubError(status, detail))
                        : new IllegalStateException(githubError(status, detail));
            }
            String json = GitHubReleaseParser.compact(
                    read(connection.getInputStream(), MAX_RESPONSE_BYTES));
            UpdatePreferences.saveSuccess(app, json, connection.getHeaderField("ETag"));
            return UpdatePreferences.releases(app);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isLocalDebugServer(String value) {
        try {
            URL url = new URL(value);
            return BuildConfig.DEBUG
                    && "http".equalsIgnoreCase(url.getProtocol())
                    && "10.0.2.2".equals(url.getHost())
                    && url.getPort() == 8765;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean trustedApiUrl(URL url, boolean localDebugServer) {
        if (localDebugServer) {
            return "http".equalsIgnoreCase(url.getProtocol())
                    && "10.0.2.2".equals(url.getHost())
                    && url.getPort() == 8765;
        }
        return "https".equalsIgnoreCase(url.getProtocol())
                && "api.github.com".equalsIgnoreCase(url.getHost());
    }

    private static String read(InputStream input, int limit) throws Exception {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IllegalStateException("GitHub returned too much release metadata.");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String githubError(int status, String detail) {
        String message = "";
        try {
            message = new org.json.JSONObject(detail).optString("message", "");
        } catch (Exception ignored) {
        }
        if (status == 403 || status == 429) {
            return "GitHub temporarily limited update checks. Try again later.";
        }
        if (!message.isEmpty()) {
            return "GitHub update check failed (" + status + "): " + message;
        }
        return "GitHub update check failed with HTTP " + status + ".";
    }

    static String safeMessage(Exception exception) {
        String message = exception == null ? "" : exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "Could not check GitHub releases.";
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
