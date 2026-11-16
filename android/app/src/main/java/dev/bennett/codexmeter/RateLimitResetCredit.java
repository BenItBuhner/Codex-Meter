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

    public RateLimitResetCredit(String id, String resetType, String status, long grantedAtMillis, long expiresAtMillis, String title, String description) {
        this.id = safe(id);
        this.resetType = safe(resetType);
        this.status = safe(status);
        this.grantedAtMillis = Math.max(0L, grantedAtMillis);
        this.expiresAtMillis = Math.max(0L, expiresAtMillis);
        this.title = safe(title);
        this.description = safe(description);
    }

    public boolean isAvailable() {
        return STATUS_AVAILABLE.equalsIgnoreCase(this.status);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", this.id);
        json.put("reset_type", this.resetType);
        json.put("status", this.status);
        json.put("granted_at_millis", this.grantedAtMillis);
        json.put("expires_at_millis", this.expiresAtMillis);
        json.put("title", this.title);
        json.put("description", this.description);
        return json;
    }

    public static RateLimitResetCredit fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new RateLimitResetCredit(json.optString("id", ""), json.optString("reset_type", ""), json.optString("status", ""), json.optLong("granted_at_millis", 0L), json.optLong("expires_at_millis", 0L), json.optString("title", ""), json.optString("description", ""));
    }

    public static RateLimitResetCredit fromApiJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new RateLimitResetCredit(json.optString("id", ""), json.optString("reset_type", ""), json.optString("status", ""), parseTimestamp(json.optString("granted_at", "")), parseTimestamp(json.optString("expires_at", "")), json.optString("title", ""), json.optString("description", ""));
    }

    static long parseTimestamp(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (DateTimeParseException notInstant) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli();
            } catch (DateTimeParseException notOffset) {
                return 0L;
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
