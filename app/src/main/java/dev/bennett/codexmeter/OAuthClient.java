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

/* JADX INFO: loaded from: classes.dex */
public final class OAuthClient {
    private OAuthClient() {
    }

    public static AuthTokens exchangeCode(String str, String str2, String str3) throws Exception {
        LinkedHashMap linkedHashMap = new LinkedHashMap();
        linkedHashMap.put("grant_type", "authorization_code");
        linkedHashMap.put("code", str);
        linkedHashMap.put("redirect_uri", str2);
        linkedHashMap.put("client_id", AppConstants.OAUTH_CLIENT_ID);
        linkedHashMap.put("code_verifier", str3);
        return parseTokens(postForm(AppConstants.TOKEN_URL, linkedHashMap), null);
    }

    public static AuthTokens refresh(AuthTokens authTokens) throws Exception {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("grant_type", "refresh_token");
        jSONObject.put("refresh_token", authTokens.refreshToken);
        jSONObject.put("client_id", AppConstants.OAUTH_CLIENT_ID);
        return authTokens.mergeRefresh(parseTokens(postJson(AppConstants.TOKEN_URL, jSONObject), authTokens));
    }

    public static void revokeBestEffort(AuthTokens authTokens) {
        if (authTokens != null && !authTokens.refreshToken.isEmpty()) {
            JSONObject jSONObject = new JSONObject();
            try {
                jSONObject.put("token", authTokens.refreshToken);
                jSONObject.put("token_type_hint", "refresh_token");
                jSONObject.put("client_id", AppConstants.OAUTH_CLIENT_ID);
                postJson(AppConstants.REVOKE_URL, jSONObject);
            } catch (Exception e) {
                try {
                    LinkedHashMap linkedHashMap = new LinkedHashMap();
                    linkedHashMap.put("token", authTokens.refreshToken);
                    linkedHashMap.put("token_type_hint", "refresh_token");
                    linkedHashMap.put("client_id", AppConstants.OAUTH_CLIENT_ID);
                    postForm(AppConstants.REVOKE_URL, linkedHashMap);
                } catch (Exception e2) {
                }
            }
        }
    }

    private static AuthTokens parseTokens(String str, AuthTokens authTokens) throws Exception {
        String str2;
        String str3;
        JSONObject jSONObject = new JSONObject(str);
        String strOptString = jSONObject.optString("access_token", "");
        String strOptString2 = jSONObject.optString("refresh_token", "");
        String strOptString3 = jSONObject.optString("id_token", "");
        if (authTokens == null) {
            str2 = strOptString2;
        } else {
            if (strOptString2.isEmpty()) {
                strOptString2 = authTokens.refreshToken;
            }
            if (strOptString3.isEmpty()) {
                strOptString3 = authTokens.idToken;
                str2 = strOptString2;
            } else {
                str2 = strOptString2;
            }
        }
        long jMax = Math.max(60L, jSONObject.optLong("expires_in", 3600L));
        JwtClaims jwtClaimsFromTokens = JwtClaims.fromTokens(strOptString3, strOptString);
        String str4 = jwtClaimsFromTokens.accountId;
        String str5 = jwtClaimsFromTokens.email;
        if (authTokens == null) {
            str3 = str4;
        } else {
            if (str4.isEmpty()) {
                str4 = authTokens.accountId;
            }
            if (str5.isEmpty()) {
                str5 = authTokens.email;
                str3 = str4;
            } else {
                str3 = str4;
            }
        }
        AuthTokens authTokens2 = new AuthTokens(strOptString, str2, strOptString3, (jMax * 1000) + System.currentTimeMillis(), str3, str5);
        if (authTokens2.isUsable()) {
            return authTokens2;
        }
        throw new Exception("The authorization server returned incomplete credentials.");
    }

    private static String postForm(String str, Map<String, String> map) throws Exception {
        return post(str, "application/x-www-form-urlencoded", formEncode(map).getBytes(StandardCharsets.UTF_8));
    }

    private static String postJson(String str, JSONObject jSONObject) throws Exception {
        return post(str, "application/json", jSONObject.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String post(String str, String str2, byte[] bArr) throws Exception {
        HttpsURLConnection httpsURLConnection = (HttpsURLConnection) URI.create(str).toURL().openConnection();
        try {
            httpsURLConnection.setRequestMethod("POST");
            httpsURLConnection.setConnectTimeout(15000);
            httpsURLConnection.setReadTimeout(25000);
            httpsURLConnection.setDoOutput(true);
            httpsURLConnection.setUseCaches(false);
            httpsURLConnection.setRequestProperty("Content-Type", str2);
            httpsURLConnection.setRequestProperty("Accept", "application/json");
            httpsURLConnection.setRequestProperty("User-Agent", AppConstants.userAgent());
            httpsURLConnection.setFixedLengthStreamingMode(bArr.length);
            OutputStream outputStream = httpsURLConnection.getOutputStream();
            try {
                outputStream.write(bArr);
                if (outputStream != null) {
                    outputStream.close();
                }
                int responseCode = httpsURLConnection.getResponseCode();
                String body = readBody(httpsURLConnection, responseCode);
                if (responseCode < 200 || responseCode >= 300) {
                    throw new Exception(readError(body, "Authentication failed (HTTP " + responseCode + ")."));
                }
                return body;
            } finally {
            }
        } finally {
            httpsURLConnection.disconnect();
        }
    }

    static String readBody(HttpURLConnection httpURLConnection, int i) throws Exception {
        InputStream errorStream = (i < 200 || i >= 400) ? httpURLConnection.getErrorStream() : httpURLConnection.getInputStream();
        if (errorStream == null) {
            return "";
        }
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try {
                byte[] bArr = new byte[8192];
                do {
                    int i2 = errorStream.read(bArr);
                    if (i2 != -1) {
                        byteArrayOutputStream.write(bArr, 0, i2);
                    } else {
                        String string = byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
                        byteArrayOutputStream.close();
                        if (errorStream != null) {
                            errorStream.close();
                            return string;
                        }
                        return string;
                    }
                } while (byteArrayOutputStream.size() <= 2097152);
                throw new Exception("Server response was unexpectedly large.");
            } finally {
            }
        } catch (Throwable th) {
            if (errorStream != null) {
                try {
                    errorStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
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

    private static String formEncode(Map<String, String> map) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return sb.toString();
    }
}
