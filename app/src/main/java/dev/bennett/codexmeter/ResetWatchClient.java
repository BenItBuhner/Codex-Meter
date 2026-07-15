package dev.bennett.codexmeter;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Authenticated X API v2 client for the fixed @thsottiaux public timeline. */
public final class ResetWatchClient {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final Object LOCK = new Object();

    private ResetWatchClient() {
    }

    public static List<XPost> check(Context context) throws Exception {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        synchronized (LOCK) {
            try {
                XOAuthTokens tokens = usableTokens(app);
                String userId = AnnouncementPreferences.userId(app);
                if (!XTimelineParser.isValidPostId(userId)) {
                    userId = resolveUserId(app, tokens);
                    AnnouncementPreferences.setUserId(app, userId);
                }
                String sinceId = AnnouncementPreferences.lastSeenId(app);
                ArrayList<XPost> posts = new ArrayList<>();
                String newestId = "";
                String paginationToken = "";
                for (int page = 0; page < 32; page++) {
                    // A 401 on the previous request may have rotated the refresh token.
                    tokens = usableTokens(app);
                    String endpoint = timelineEndpoint(userId, sinceId, paginationToken);
                    Response response = getWithRefresh(app, endpoint, tokens);
                    List<XPost> pagePosts = XTimelineParser.parse(response.body);
                    posts.addAll(pagePosts);
                    if (newestId.isEmpty()) {
                        newestId = XTimelineParser.newestId(response.body, pagePosts);
                    }
                    JSONObject meta = new JSONObject(response.body).optJSONObject("meta");
                    paginationToken = meta == null ? "" : meta.optString("next_token", "");
                    if (paginationToken.isEmpty()) break;
                    if (page == 31) {
                        throw new IllegalStateException(
                                "X returned more timeline pages than the reset watch can process.");
                    }
                }
                boolean hadBaseline = AnnouncementPreferences.hasBaseline(app);
                AnnouncementPreferences.saveSuccess(app, posts, newestId);
                ResetWatchNotificationManager.notifyNew(app, posts, hadBaseline);
                return posts;
            } catch (Exception exception) {
                if (exception instanceof XAuthorizationException) {
                    SecureXTokenStore.clear(app);
                    AnnouncementPreferences.setMonitoringEnabled(app, false);
                    ResetWatchScheduler.cancel(app);
                }
                AnnouncementPreferences.saveError(app, safeMessage(exception));
                throw exception;
            }
        }
    }

    private static XOAuthTokens usableTokens(Context context) throws Exception {
        XOAuthTokens tokens = SecureXTokenStore.load(context);
        if (tokens == null) throw new IllegalStateException("Connect X to check Codex reset posts.");
        if (tokens.shouldRefresh(System.currentTimeMillis())) {
            tokens = XOAuthClient.refresh(tokens);
            SecureXTokenStore.save(context, tokens);
        }
        return tokens;
    }

    private static String resolveUserId(Context context, XOAuthTokens tokens) throws Exception {
        Response response = getWithRefresh(context,
                "https://api.x.com/2/users/by/username/" + ResetWatchSource.HANDLE, tokens);
        JSONObject data = new JSONObject(response.body).optJSONObject("data");
        String id = data == null ? "" : data.optString("id", "");
        String username = data == null ? "" : data.optString("username", "");
        if (!XTimelineParser.isValidPostId(id)
                || (!username.isEmpty()
                && !ResetWatchSource.HANDLE.equalsIgnoreCase(username))) {
            throw new IllegalStateException("X did not return the expected @"
                    + ResetWatchSource.HANDLE + " account.");
        }
        return id;
    }

    private static Response getWithRefresh(Context context, String endpoint, XOAuthTokens tokens)
            throws Exception {
        Response response = get(endpoint, tokens.accessToken);
        if (response.status != HttpURLConnection.HTTP_UNAUTHORIZED) {
            ensureSuccess(response);
            return response;
        }
        XOAuthTokens refreshed = XOAuthClient.refresh(tokens);
        SecureXTokenStore.save(context, refreshed);
        response = get(endpoint, refreshed.accessToken);
        ensureSuccess(response);
        return response;
    }

    private static Response get(String endpoint, String accessToken) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(25_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("User-Agent", AppConstants.resetWatchUserAgent());
            int status = connection.getResponseCode();
            URL finalUrl = connection.getURL();
            if (!"https".equalsIgnoreCase(finalUrl.getProtocol())
                    || !"api.x.com".equalsIgnoreCase(finalUrl.getHost())) {
                throw new SecurityException("X redirected the reset check to an untrusted host.");
            }
            String body = read(status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream());
            return new Response(status, body);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void ensureSuccess(Response response) throws XAuthorizationException {
        if (response.status == HttpURLConnection.HTTP_OK) return;
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED
                || response.status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new XAuthorizationException("X access expired or lacks public-post read access. "
                    + "Reconnect X from Settings.");
        }
        if (response.status == 429) {
            throw new IllegalStateException("X temporarily limited reset checks. "
                    + "The next scheduled check will try again.");
        }
        String detail = "";
        try {
            JSONObject object = new JSONObject(response.body);
            detail = object.optString("detail", object.optString("title", ""));
            if (detail.isEmpty()) {
                JSONArray errors = object.optJSONArray("errors");
                JSONObject first = errors == null ? null : errors.optJSONObject(0);
                if (first != null) detail = first.optString("message", "");
            }
        } catch (Exception ignored) {
        }
        if (!detail.isEmpty()) {
            throw new IllegalStateException("X reset check failed (" + response.status + "): "
                    + safe(detail, 180));
        }
        throw new IllegalStateException(
                "X reset check failed with HTTP " + response.status + ".");
    }

    private static String timelineEndpoint(String userId, String sinceId, String paginationToken)
            throws Exception {
        StringBuilder endpoint = new StringBuilder("https://api.x.com/2/users/")
                .append(userId)
                .append("/tweets?max_results=100&exclude=retweets&tweet.fields=created_at");
        if (XTimelineParser.isValidPostId(sinceId)) {
            endpoint.append("&since_id=").append(sinceId);
        }
        if (paginationToken != null && !paginationToken.isEmpty()) {
            endpoint.append("&pagination_token=")
                    .append(URLEncoder.encode(paginationToken, "UTF-8"));
        }
        return endpoint.toString();
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("X returned too much timeline data.");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception == null ? "" : exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "Could not check X for Codex reset updates.";
        }
        return safe(message, 240);
    }

    private static String safe(String value, int limit) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
