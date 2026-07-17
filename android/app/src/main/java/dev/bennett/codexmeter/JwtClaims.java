package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class JwtClaims {
    public final String accountId;
    public final String email;

    private JwtClaims(String str, String str2) {
        this.accountId = str == null ? "" : str;
        this.email = str2 == null ? "" : str2;
    }

    public static JwtClaims fromTokens(String str, String str2) {
        JwtClaims jwtClaims = parse(str);
        JwtClaims jwtClaims2 = parse(str2);
        return new JwtClaims(jwtClaims.accountId.isEmpty() ? jwtClaims2.accountId : jwtClaims.accountId, jwtClaims.email.isEmpty() ? jwtClaims2.email : jwtClaims.email);
    }

    public static JwtClaims parse(String str) {
        JSONArray jSONArrayOptJSONArray;
        JSONObject jSONObjectOptJSONObject;
        if (str == null) {
            return new JwtClaims("", "");
        }
        String[] strArrSplit = str.split("\\.");
        if (strArrSplit.length != 3) {
            return new JwtClaims("", "");
        }
        try {
            JSONObject jSONObject = new JSONObject(new String(Base64.getUrlDecoder().decode(pad(strArrSplit[1])), StandardCharsets.UTF_8));
            String strOptString = jSONObject.optString("chatgpt_account_id", "");
            JSONObject jSONObjectOptJSONObject2 = jSONObject.optJSONObject("https://api.openai.com/auth");
            if (strOptString.isEmpty() && jSONObjectOptJSONObject2 != null) {
                strOptString = jSONObjectOptJSONObject2.optString("chatgpt_account_id", "");
            }
            if (strOptString.isEmpty() && (jSONArrayOptJSONArray = jSONObject.optJSONArray("organizations")) != null && jSONArrayOptJSONArray.length() > 0 && (jSONObjectOptJSONObject = jSONArrayOptJSONArray.optJSONObject(0)) != null) {
                strOptString = jSONObjectOptJSONObject.optString("id", "");
            }
            return new JwtClaims(strOptString, jSONObject.optString("email", ""));
        } catch (Exception e) {
            return new JwtClaims("", "");
        }
    }

    private static String pad(String str) {
        int length = str.length() % 4;
        if (length != 0) {
            StringBuilder sb = new StringBuilder(str);
            while (length < 4) {
                sb.append('=');
                length++;
            }
            return sb.toString();
        }
        return str;
    }
}
