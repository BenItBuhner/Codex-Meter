package dev.bennett.codexmeter;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Public, unauthenticated GitHub release discovery client. */
public final class ReleaseUpdateClient {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final Object LOCK = new Object();

    private ReleaseUpdateClient() {
    }

    public static List<GitHubRelease> check(Context context) throws Exception {
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        synchronized (LOCK) {
            try {
                return request(app, true);
            } catch (Exception exception) {
                UpdatePreferences.saveError(app, safeMessage(exception));
                throw exception;
            }
        }
    }

    private static List<GitHubRelease> request(Context app, boolean conditional)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            String endpoint = BuildConfig.DEBUG
                    ? BuildConfig.UPDATE_API_URL : GitHubReleaseSource.RELEASES_API_URL;
            boolean localDebugServer = isLocalDebugServer(endpoint);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
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
                String detail = read(connection.getErrorStream(), 16 * 1024);
                throw new IllegalStateException(githubError(status, detail));
            }
            String json = read(connection.getInputStream(), MAX_RESPONSE_BYTES);
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
