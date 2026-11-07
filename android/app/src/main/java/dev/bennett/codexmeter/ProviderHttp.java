package dev.bennett.codexmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Small HTTPS client for the providers added through {@link ProviderClients}. Every request is
 * logged by operation name only: URLs go through {@link DiagnosticSanitizer#safeUrl}, and
 * bodies and headers are never logged because they carry tokens and device codes.
 */
public final class ProviderHttp {
    private ProviderHttp() {
    }

    /** A finished request. Bodies are never null. */
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** The body as JSON, or an empty object when it isn't JSON. */
        public JSONObject json() {
            try {
                return new JSONObject(body);
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    /** Builder for one request; headers keep insertion order. */
    public static final class Request {
        final String url;
        String method = "GET";
        String body;
        String contentType;
        final Map<String, String> headers = new LinkedHashMap<>();

        Request(String url) {
            this.url = url;
            headers.put("Accept", "application/json");
            headers.put("User-Agent", AppConstants.userAgent());
        }

        public Request header(String name, String value) {
            if (value == null) {
                headers.remove(name);
            } else {
                headers.put(name, value);
            }
            return this;
        }

        public Request bearer(String token) {
            return header("Authorization", "Bearer " + token);
        }

        public Request json(JSONObject payload) {
            this.method = "POST";
            this.body = payload == null ? "{}" : payload.toString();
            this.contentType = "application/json";
            return this;
        }

        public Request form(Map<String, String> fields) {
            this.method = "POST";
            this.body = formEncode(fields);
            this.contentType = "application/x-www-form-urlencoded";
            return this;
        }

        public Request method(String method) {
            this.method = method;
            return this;
        }
    }

    public static Request get(String url) {
        return new Request(url);
    }

    public static Request post(String url) {
        return new Request(url).method("POST");
    }

    /** Ordered form fields: {@code fields("a", "1", "b", "2")}. */
    public static Map<String, String> fields(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    public static Response send(Context context, String operation, Request request)
            throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(request.url).toURL().openConnection();
        long started = SystemClock.elapsedRealtime();
        if (context != null) {
            DiagnosticLog.info(context, "network", "request_started", "operation", operation,
                    "method", request.method, "url", DiagnosticSanitizer.safeUrl(request.url));
        }
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(request.method);
            for (Map.Entry<String, String> header : request.headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (request.body != null) {
                byte[] payload = request.body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", request.contentType);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            } else if ("POST".equals(request.method)) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
            }
            int status = connection.getResponseCode();
            String body = OAuthClient.readBody(connection, status);
            if (context != null) {
                DiagnosticLog.info(context, "network", "request_finished",
                        "operation", operation, "status", status,
                        "duration_ms", SystemClock.elapsedRealtime() - started);
            }
            return new Response(status, body);
        } catch (Exception exception) {
            if (context != null) {
                DiagnosticLog.error(context, "network", "request_failed", exception,
                        "operation", operation,
                        "duration_ms", SystemClock.elapsedRealtime() - started);
            }
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    static String formEncode(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        try {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (out.length() > 0) out.append('&');
                out.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                out.append('=');
                out.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toString();
    }

    /** Query string from ordered pairs, for authorize URLs. */
    public static String query(String... pairs) {
        return formEncode(fields(pairs)).replace("+", "%20");
    }
}
