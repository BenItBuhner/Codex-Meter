package dev.bennett.codexmeter;

import org.json.JSONException;
import org.json.JSONObject;

/** A named, independently metered limit returned in additional_rate_limits. */
public final class UsageLimit {
    public final String id;
    public final String name;
    public final String meteredFeature;
    public final boolean allowed;
    public final boolean limitReached;
    public final UsageWindow primary;
    public final UsageWindow secondary;

    public UsageLimit(String id, String name, String meteredFeature, boolean allowed,
            boolean limitReached, UsageWindow primary, UsageWindow secondary) {
        this.id = clean(id);
        this.name = clean(name);
        this.meteredFeature = clean(meteredFeature);
        this.allowed = allowed;
        this.limitReached = limitReached;
        this.primary = primary;
        this.secondary = secondary;
    }

    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        }
        if (!meteredFeature.isEmpty()) {
            return titleCase(meteredFeature);
        }
        return "Additional usage";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("metered_feature", meteredFeature);
        object.put("allowed", allowed);
        object.put("limit_reached", limitReached);
        if (primary != null) {
            object.put("primary", primary.toJson());
        }
        if (secondary != null) {
            object.put("secondary", secondary.toJson());
        }
        return object;
    }

    public static UsageLimit fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        UsageWindow primary = UsageWindow.fromJson(object.optJSONObject("primary"));
        UsageWindow secondary = UsageWindow.fromJson(object.optJSONObject("secondary"));
        if (primary == null && secondary == null) {
            return null;
        }
        return new UsageLimit(
                object.optString("id", ""),
                object.optString("name", ""),
                object.optString("metered_feature", ""),
                object.optBoolean("allowed", true),
                object.optBoolean("limit_reached", false),
                primary,
                secondary);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String titleCase(String value) {
        String normalized = value.replace('_', ' ').replace('-', ' ').trim();
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isWhitespace(current)) {
                result.append(current);
                capitalize = true;
            } else {
                result.append(capitalize ? Character.toUpperCase(current) : current);
                capitalize = false;
            }
        }
        return result.toString();
    }
}
