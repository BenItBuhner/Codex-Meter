package dev.bennett.codexmeter;

import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure OAuth URL construction for X's native-app Authorization Code + PKCE flow. */
public final class XOAuthFlow {
    public static final String REDIRECT_URI = "codexmeter://x-auth/complete";
    public static final String SCOPE = "tweet.read users.read offline.access";

    private XOAuthFlow() {
    }

    public static String authorizeUrl(String clientId, Pkce pkce) throws Exception {
        if (clientId == null || clientId.trim().isEmpty() || pkce == null) {
            throw new IllegalArgumentException("X OAuth is not configured.");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("client_id", clientId.trim());
        parameters.put("redirect_uri", REDIRECT_URI);
        parameters.put("scope", SCOPE);
        parameters.put("state", pkce.state);
        parameters.put("code_challenge", pkce.challenge);
        parameters.put("code_challenge_method", "S256");
        StringBuilder url = new StringBuilder("https://x.com/i/oauth2/authorize?");
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (url.charAt(url.length() - 1) != '?') url.append('&');
            url.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return url.toString();
    }
}
