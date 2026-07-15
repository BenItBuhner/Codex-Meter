package dev.bennett.codexmeter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Minimal public-client OAuth implementation for X native apps. */
public final class XOAuthClient {
    private static final String TOKEN_URL = "https://api.x.com/2/oauth2/token";
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private XOAuthClient() {
    }

    public static XOAuthTokens exchangeCode(String code, String verifier) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("grant_type", "authorization_code");
        form.put("client_id", BuildConfig.X_CLIENT_ID);
        form.put("redirect_uri", XOAuthFlow.REDIRECT_URI);
        form.put("code_verifier", verifier);
        return parse(postForm(form), null, true);
    }

    public static XOAuthTokens refresh(XOAuthTokens current) throws Exception {
        if (current == null || current.refreshToken.isEmpty()) {
            throw new XAuthorizationException("Reconnect X to continue reset checks.");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("refresh_token", current.refreshToken);
        form.put("grant_type", "refresh_token");
        form.put("client_id", BuildConfig.X_CLIENT_ID);
        return current.mergeRefresh(parse(postForm(form), current, true));
    }

    static XOAuthTokens parse(String json, XOAuthTokens current, boolean requireRefresh)
            throws Exception {
        JSONObject object = new JSONObject(json);
        String access = object.optString("access_token", "");
        String refresh = object.optString("refresh_token", "");
        long expiresIn = object.optLong("expires_in", 0L);
        if (access.isEmpty() || (requireRefresh && refresh.isEmpty()) || expiresIn <= 0L) {
            throw new XAuthorizationException("X returned incomplete credentials. Reconnect X.");
        }
        XOAuthTokens tokens = new XOAuthTokens(access, refresh,
                System.currentTimeMillis() + expiresIn * 1000L);
        return tokens;
    }

    private static String postForm(Map<String, String> values) throws Exception {
        byte[] body = formEncode(values).getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection connection =
                (HttpsURLConnection) URI.create(TOKEN_URL).toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(25_000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded;charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", AppConstants.resetWatchUserAgent());
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())
                    || !"api.x.com".equalsIgnoreCase(connection.getURL().getHost())) {
                throw new SecurityException("X authentication redirected to an untrusted host.");
            }
            String response = read(status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                String message = error(response,
                        "X authentication failed with HTTP " + status + ".");
                if (isPermanentAuthError(status, response)) {
                    throw new XAuthorizationException(message);
                }
                throw new Exception(message);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String formEncode(Map<String, String> values) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return result.toString();
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
                    throw new Exception("X authentication returned too much data.");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String error(String body, String fallback) {
        try {
            JSONObject object = new JSONObject(body == null ? "" : body);
            String description = object.optString("error_description", "");
            if (!description.isEmpty()) return description;
            String error = object.optString("error", "");
            if (!error.isEmpty()) return error;
            String title = object.optString("title", "");
            if (!title.isEmpty()) return title;
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static boolean isPermanentAuthError(int status, String body) {
        if (status != HttpURLConnection.HTTP_BAD_REQUEST
                && status != HttpURLConnection.HTTP_UNAUTHORIZED
                && status != HttpURLConnection.HTTP_FORBIDDEN) {
            return false;
        }
        try {
            String code = new JSONObject(body == null ? "" : body)
                    .optString("error", "");
            return "invalid_grant".equals(code) || "invalid_client".equals(code)
                    || "unauthorized_client".equals(code);
        } catch (Exception ignored) {
            return status == HttpURLConnection.HTTP_UNAUTHORIZED;
        }
    }
}
