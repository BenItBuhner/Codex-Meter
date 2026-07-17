package dev.bennett.codexmeter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class RateLimitResetCredit {
    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_REDEEMED = "redeemed";
    public static final String STATUS_REDEEMING = "redeeming";
    public final String description;
    public final long expiresAtMillis;
    public final long grantedAtMillis;
    public final String id;
    public final String resetType;
    public final String status;
    public final String title;

    public RateLimitResetCredit(String str, String str2, String str3, long j, long j2, String str4, String str5) {
        this.id = safe(str);
        this.resetType = safe(str2);
        this.status = safe(str3);
        this.grantedAtMillis = Math.max(0L, j);
        this.expiresAtMillis = Math.max(0L, j2);
        this.title = safe(str4);
        this.description = safe(str5);
    }

    public boolean isAvailable() {
        return STATUS_AVAILABLE.equalsIgnoreCase(this.status);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("id", this.id);
        jSONObject.put("reset_type", this.resetType);
        jSONObject.put("status", this.status);
        jSONObject.put("granted_at_millis", this.grantedAtMillis);
        jSONObject.put("expires_at_millis", this.expiresAtMillis);
        jSONObject.put("title", this.title);
        jSONObject.put("description", this.description);
        return jSONObject;
    }

    public static RateLimitResetCredit fromJson(JSONObject jSONObject) {
        if (jSONObject == null) {
            return null;
        }
        return new RateLimitResetCredit(jSONObject.optString("id", ""), jSONObject.optString("reset_type", ""), jSONObject.optString("status", ""), jSONObject.optLong("granted_at_millis", 0L), jSONObject.optLong("expires_at_millis", 0L), jSONObject.optString("title", ""), jSONObject.optString("description", ""));
    }

    public static RateLimitResetCredit fromApiJson(JSONObject jSONObject) {
        if (jSONObject == null) {
            return null;
        }
        return new RateLimitResetCredit(jSONObject.optString("id", ""), jSONObject.optString("reset_type", ""), jSONObject.optString("status", ""), parseTimestamp(jSONObject.optString("granted_at", "")), parseTimestamp(jSONObject.optString("expires_at", "")), jSONObject.optString("title", ""), jSONObject.optString("description", ""));
    }

    static long parseTimestamp(String str) {
        if (str == null || str.trim().isEmpty()) {
            return 0L;
        }
        String strTrim = str.trim();
        try {
            return Instant.parse(strTrim).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(strTrim).toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                return 0L;
            }
        }
    }

    private static String safe(String str) {
        return str == null ? "" : str;
    }
}
