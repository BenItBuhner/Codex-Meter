package dev.bennett.codexmeter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Maps Claude Code's {@code GET /api/oauth/usage} response onto the shared
 * {@link UsageSnapshot} so every dashboard card works unchanged.
 *
 * <p>Observed response shape (fields may be null or missing per plan):
 * <pre>
 * {
 *   "five_hour":        {"utilization": 6.0,  "resets_at": "2026-10-19T18:59:59.9+00:00"},
 *   "seven_day":        {"utilization": 35.0, "resets_at": "2026-10-23T03:00:00+00:00"},
 *   "seven_day_opus":   {"utilization": 0.0,  "resets_at": null},
 *   "seven_day_sonnet": {"utilization": 12.0, "resets_at": "..."},
 *   "extra_usage": {"is_enabled": true, "monthly_limit": 5000, "used_credits": 120,
 *                   "utilization": 2.4}
 * }
 * </pre>
 * {@code utilization} is percent used (0–100), matching the "% used" Claude Code prints.
 */
public final class ClaudeUsageParser {
    public static final long FIVE_HOUR_SECONDS = 5L * 60L * 60L;
    public static final long SEVEN_DAY_SECONDS = 7L * 24L * 60L * 60L;
    public static final long MONTH_SECONDS = 30L * 24L * 60L * 60L;
    static final String LIMIT_ID_PREFIX = "claude_";

    private ClaudeUsageParser() {
    }

    public static UsageSnapshot parse(String body, String planLabel, long fetchedAtMillis)
            throws Exception {
        JSONObject root = new JSONObject(body == null ? "" : body);
        UsageWindow fiveHour = window(root.optJSONObject("five_hour"), FIVE_HOUR_SECONDS);
        UsageWindow weekly = window(root.optJSONObject("seven_day"), SEVEN_DAY_SECONDS);
        List<UsageLimit> extra = new ArrayList<>();
        boolean limitReached = reached(fiveHour) || reached(weekly);

        // Model- and surface-specific weekly caps (seven_day_opus, seven_day_sonnet, ...). New
        // keys Anthropic adds later appear automatically as long as they follow the prefix.
        Iterator<String> keys = root.keys();
        List<String> sorted = new ArrayList<>();
        while (keys.hasNext()) sorted.add(keys.next());
        java.util.Collections.sort(sorted);
        for (String key : sorted) {
            long seconds;
            String suffix;
            if (key.startsWith("seven_day_")) {
                seconds = SEVEN_DAY_SECONDS;
                suffix = key.substring("seven_day_".length());
            } else if (key.startsWith("five_hour_")) {
                seconds = FIVE_HOUR_SECONDS;
                suffix = key.substring("five_hour_".length());
            } else {
                continue;
            }
            UsageWindow window = window(root.optJSONObject(key), seconds);
            if (window == null) continue;
            limitReached |= reached(window);
            extra.add(new UsageLimit(LIMIT_ID_PREFIX + key, limitName(suffix), "",
                    true, reached(window), window, null));
        }

        UsageLimit extraUsage = extraUsage(root.optJSONObject("extra_usage"));
        if (extraUsage != null) {
            extra.add(extraUsage);
        }

        return new UsageSnapshot(planLabel == null ? "" : planLabel, !limitReached,
                limitReached, fiveHour, weekly, null, extra, null, -1, fetchedAtMillis);
    }

    /** One {@code {utilization, resets_at}} object, or null when Claude reports no data. */
    static UsageWindow window(JSONObject object, long windowSeconds) {
        if (object == null || object.isNull("utilization")) return null;
        double utilization = object.optDouble("utilization", Double.NaN);
        if (Double.isNaN(utilization) || Double.isInfinite(utilization)) return null;
        return new UsageWindow((int) Math.round(utilization), windowSeconds, 0L,
                parseResetSeconds(object.optString("resets_at", "")));
    }

    /** Paid overage ("extra usage") behaves like a monthly spending cap when it is enabled. */
    static UsageLimit extraUsage(JSONObject object) {
        if (object == null || !object.optBoolean("is_enabled", false)) return null;
        if (object.isNull("utilization")) return null;
        double utilization = object.optDouble("utilization", Double.NaN);
        if (Double.isNaN(utilization) || Double.isInfinite(utilization)) return null;
        UsageWindow window = new UsageWindow((int) Math.round(utilization), MONTH_SECONDS, 0L,
                parseResetSeconds(object.optString("resets_at", "")));
        return new UsageLimit(LIMIT_ID_PREFIX + "extra_usage", "Extra usage", "", true,
                window.usedPercent >= 100, window, null);
    }

    static long parseResetSeconds(String value) {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) return 0L;
        try {
            return Math.max(0L, OffsetDateTime.parse(value.trim()).toEpochSecond());
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }

    static String limitName(String suffix) {
        switch (suffix) {
            case "opus": return "Opus";
            case "sonnet": return "Sonnet";
            case "haiku": return "Haiku";
            case "oauth_apps": return "Connected apps";
            default:
                String spaced = suffix.replace('_', ' ').trim();
                if (spaced.isEmpty()) return "Additional usage";
                return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
        }
    }

    private static boolean reached(UsageWindow window) {
        return window != null && window.usedPercent >= 100;
    }

    /**
     * Plan badge from {@code GET /api/oauth/profile}. Reads the rate-limit tier first because it
     * is the only field that separates Max 5x from Max 20x.
     */
    public static String planLabel(String profileBody) {
        if (profileBody == null || profileBody.isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(profileBody);
            JSONObject account = root.optJSONObject("account");
            JSONObject organization = root.optJSONObject("organization");
            String tier = organization == null ? ""
                    : organization.optString("rate_limit_tier", "").toLowerCase(Locale.ROOT);
            String type = organization == null ? ""
                    : organization.optString("organization_type", "").toLowerCase(Locale.ROOT);
            if (tier.contains("max_20x")) return "Max 20x";
            if (tier.contains("max_5x")) return "Max 5x";
            if (type.contains("enterprise")) return "Enterprise";
            if (type.contains("team")) return "Team";
            if (type.contains("max") || (account != null && account.optBoolean("has_claude_max"))) {
                return "Max";
            }
            if (type.contains("pro") || (account != null && account.optBoolean("has_claude_pro"))) {
                return "Pro";
            }
            if (type.contains("free")) return "Free";
        } catch (Exception ignored) {
            // The profile only decorates the badge; a schema change must not block usage.
        }
        return "";
    }

    /** Email shown on the account card; Anthropic has used both field names. */
    public static String email(String profileBody) {
        if (profileBody == null || profileBody.isEmpty()) return "";
        try {
            JSONObject account = new JSONObject(profileBody).optJSONObject("account");
            if (account == null) return "";
            String email = account.optString("email", "");
            return email.isEmpty() ? account.optString("email_address", "") : email;
        } catch (Exception ignored) {
            return "";
        }
    }
}
