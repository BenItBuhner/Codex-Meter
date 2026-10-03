package dev.bennett.codexmeter;

import org.json.JSONObject;

final class AccountIdentity {
    // An organization/account ID alone does not identify a user. Use only the subject returned
    // in the OAuth token response, paired with its account ID; never guess from email.
    static String fromTokens(AuthTokens tokens) {
        try {
            String[] parts = tokens.idToken.split("\\.");
            if (parts.length != 3) return "";
            JSONObject claims = new JSONObject(new String(java.util.Base64.getUrlDecoder()
                    .decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8));
            String subject = claims.optString("sub", "");
            String issuer = claims.optString("iss", "");
            return subject.isEmpty() || issuer.isEmpty() || tokens.accountId.isEmpty()
                    ? "" : new org.json.JSONArray().put(issuer).put(subject).put(tokens.accountId).toString();
        } catch (Exception ignored) { return ""; }
    }

}
