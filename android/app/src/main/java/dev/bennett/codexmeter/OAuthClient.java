package dev.bennett.codexmeter;

import android.content.Context;
import android.os.SystemClock;
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

/** OpenAI's OAuth token endpoint: the code exchange after sign-in, refresh, and revoke on sign-out. */
public final class OAuthClient {
    /** Responses bigger than this are refused rather than read into memory. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private OAuthClient() {
    }

    public static AuthTokens exchangeCode(Context context, String code, String redirectUri, String codeVerifier)
            throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", AppConstants.OAUTH_CLIENT_ID);
        form.put("code_verifier", codeVerifier);
        return parseTokens(postForm(context, "oauth_code_exchange", AppConstants.TOKEN_URL, form), null);
    }

    public static AuthTokens refresh(Context context, AuthTokens current) throws Exception {
        JSONObject body = new JSONObject();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", current.refreshToken);
        body.put("client_id", AppConstants.OAUTH_CLIENT_ID);
        return current.mergeRefresh(parseTokens(postJson(context, "oauth_token_refresh",
                AppConstants.TOKEN_URL, body), current));
    }

    public static void revokeBestEffort(Context context, AuthTokens tokens) {
        if (tokens == null || tokens.refreshToken.isEmpty()) {
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("token", tokens.refreshToken);
            body.put("token_type_hint", "refresh_token");
            body.put("client_id", AppConstants.OAUTH_CLIENT_ID);
            postJson(context, "oauth_token_revoke", AppConstants.REVOKE_URL, body);
        } catch (Exception firstError) {
            DiagnosticLog.warn(context, "auth", "json_revocation_failed_trying_form",
                    "error", firstError.getClass().getSimpleName());
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("token", tokens.refreshToken);
                form.put("token_type_hint", "refresh_token");
                form.put("client_id", AppConstants.OAUTH_CLIENT_ID);
                postForm(context, "oauth_token_revoke", AppConstants.REVOKE_URL, form);
            } catch (Exception secondError) {
                DiagnosticLog.error(context, "auth", "token_revocation_failed", secondError);
            }
        }
    }

    /**
     * Reads a token response. On refresh ({@code previous} set) a field the server left out keeps
     * its previous value, since refresh responses may omit the refresh or ID token.
     */
    private static AuthTokens parseTokens(String body, AuthTokens previous) throws Exception {
        JSONObject json = new JSONObject(body);
        String accessToken = json.optString("access_token", "");
        String refreshToken = json.optString("refresh_token", "");
        String idToken = json.optString("id_token", "");
        if (previous != null) {
            if (refreshToken.isEmpty()) refreshToken = previous.refreshToken;
            if (idToken.isEmpty()) idToken = previous.idToken;
        }
        long expiresInSeconds = Math.max(60L, json.optLong("expires_in", 3600L));
        JwtClaims claims = JwtClaims.fromTokens(idToken, accessToken);
        String accountId = claims.accountId;
        String email = claims.email;
        if (previous != null) {
            if (accountId.isEmpty()) accountId = previous.accountId;
            if (email.isEmpty()) email = previous.email;
        }
        AuthTokens tokens = new AuthTokens(accessToken, refreshToken, idToken,
                System.currentTimeMillis() + expiresInSeconds * 1000L, accountId, email);
        if (tokens.isUsable()) {
            return tokens;
        }
        throw new Exception("The authorization server returned incomplete credentials.");
    }

    private static String postForm(Context context, String operation, String url,
            Map<String, String> form) throws Exception {
        return post(context, operation, url, "application/x-www-form-urlencoded",
                formEncode(form).getBytes(StandardCharsets.UTF_8));
    }

    private static String postJson(Context context, String operation, String url,
            JSONObject body) throws Exception {
        return post(context, operation, url, "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String post(Context context, String operation, String url, String contentType,
            byte[] payload) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        long started = SystemClock.elapsedRealtime();
        DiagnosticLog.info(context, "network", "request_started",
                "operation", operation,
                "method", "POST",
                "url", DiagnosticSanitizer.safeUrl(url),
                "request_bytes", payload.length);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", AppConstants.userAgent());
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            DiagnosticLog.info(context, "network", "request_finished",
                    "operation", operation,
                    "status", status,
                    "duration_ms", SystemClock.elapsedRealtime() - started,
                    "response_bytes", body.getBytes(StandardCharsets.UTF_8).length);
            if (status < 200 || status >= 300) {
                throw new Exception(readError(body, "Authentication failed (HTTP " + status + ")."));
            }
            return body;
        } catch (Exception exception) {
            DiagnosticLog.error(context, "network", "request_failed", exception,
                    "operation", operation,
                    "duration_ms", SystemClock.elapsedRealtime() - started);
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    /** The response body (the error stream for 4xx and 5xx), refusing more than 2 MB. */
    static String readBody(HttpURLConnection connection, int status) throws Exception {
        InputStream in = (status < 200 || status >= 400) ? connection.getErrorStream() : connection.getInputStream();
        if (in == null) {
            return "";
        }
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BODY_BYTES) {
                    throw new Exception("Server response was unexpectedly large.");
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String readError(String body, String fallback) {
        try {
            JSONObject object = new JSONObject(body == null ? "" : body);
            String description = object.optString("error_description", "");
            if (!description.isEmpty()) return description;
            Object error = object.opt("error");
            if (error instanceof String && !((String) error).isEmpty()) return (String) error;
            if (error instanceof JSONObject) {
                String message = ((JSONObject) error).optString("message", "");
                if (!message.isEmpty()) return message;
            }
            String message = object.optString("message", "");
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {
            // Do not surface arbitrary HTML from a proxy or gateway.
        }
        return fallback;
    }

    private static String formEncode(Map<String, String> form) throws Exception {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (encoded.length() > 0) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            encoded.append('=');
            encoded.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return encoded.toString();
    }
}
