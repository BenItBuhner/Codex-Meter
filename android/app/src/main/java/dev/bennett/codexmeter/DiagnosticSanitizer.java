package dev.bennett.codexmeter;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java privacy filters shared by diagnostic logging and its self-tests. */
public final class DiagnosticSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern JWT = Pattern.compile(
            "\\b[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(authorization|proxy-authorization|cookie|set-cookie|"
                    + "access[_-]?token|refresh[_-]?token|id[_-]?token|"
                    + "client[_-]?secret|code[_-]?verifier|password|passwd|"
                    + "session(?:id)?|api[_-]?key)\\b"
                    + "\\s*[\"']?\\s*[:=]\\s*[\"']?"
                    + "([^\\s,;&}\\]]+)");
    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)([?&](?:code|state|token|access_token|refresh_token|id_token|"
                    + "code_verifier|client_secret|password|session)=)[^&#\\s]+");

    private DiagnosticSanitizer() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String result = AUTH_SCHEME.matcher(value).replaceAll(REDACTED);
        result = JWT.matcher(result).replaceAll(REDACTED);
        result = EMAIL.matcher(result).replaceAll("[REDACTED_EMAIL]");
        result = replaceAssignments(result);
        return SENSITIVE_QUERY.matcher(result).replaceAll("$1" + REDACTED);
    }

    public static String safeUrl(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return redact(value);
            }
            StringBuilder safe = new StringBuilder()
                    .append(scheme.toLowerCase(Locale.ROOT))
                    .append("://")
                    .append(host.toLowerCase(Locale.ROOT));
            if (uri.getPort() >= 0) {
                safe.append(':').append(uri.getPort());
            }
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty()) {
                safe.append(path);
            }
            return safe.toString();
        } catch (RuntimeException ignored) {
            return redact(value);
        }
    }

    private static String replaceAssignments(String value) {
        Matcher matcher = SENSITIVE_ASSIGNMENT.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(matcher.group(1) + "=" + REDACTED));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
