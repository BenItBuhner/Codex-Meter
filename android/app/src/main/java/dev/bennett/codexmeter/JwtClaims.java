package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** The account id and email inside an OpenAI token's JWT payload. Unverified: display only. */
public final class JwtClaims {
    public final String accountId;
    public final String email;

    private JwtClaims(String accountId, String email) {
        this.accountId = accountId == null ? "" : accountId;
        this.email = email == null ? "" : email;
    }

    /** Claims from the id token, with anything it lacks taken from the access token. */
    public static JwtClaims fromTokens(String idToken, String accessToken) {
        JwtClaims primary = parse(idToken);
        JwtClaims fallback = parse(accessToken);
        return new JwtClaims(
                primary.accountId.isEmpty() ? fallback.accountId : primary.accountId,
                primary.email.isEmpty() ? fallback.email : primary.email);
    }

    public static JwtClaims parse(String token) {
        if (token == null) return new JwtClaims("", "");
        String[] parts = token.split("\\.");
        if (parts.length != 3) return new JwtClaims("", "");
        try {
            JSONObject payload = new JSONObject(new String(
                    Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8));
            String accountId = payload.optString("chatgpt_account_id", "");
            JSONObject auth = payload.optJSONObject("https://api.openai.com/auth");
            if (accountId.isEmpty() && auth != null) {
                accountId = auth.optString("chatgpt_account_id", "");
            }
            if (accountId.isEmpty()) {
                JSONArray organizations = payload.optJSONArray("organizations");
                JSONObject first = organizations == null ? null : organizations.optJSONObject(0);
                if (first != null) accountId = first.optString("id", "");
            }
            return new JwtClaims(accountId, payload.optString("email", ""));
        } catch (Exception malformed) {
            return new JwtClaims("", "");
        }
    }

    /** Base64url without padding, padded back to a multiple of four. */
    private static String pad(String base64) {
        StringBuilder padded = new StringBuilder(base64);
        while (padded.length() % 4 != 0) padded.append('=');
        return padded.toString();
    }
}
