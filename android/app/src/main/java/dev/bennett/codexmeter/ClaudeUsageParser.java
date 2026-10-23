package dev.bennett.codexmeter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Maps Claude Code's {@code GET /api/oauth/usage} response onto the shared
 * {@link UsageSnapshot} so every dashboard card works unchanged.
 *
 * <p>The response carries a server-built {@code limits} list, the meters Claude Code itself
 * renders. Usually that is just the session (5-hour) and weekly meters; some plans add a
 * model-scoped weekly allocation such as Fable. Cards follow that list, so new meters appear
 * without an app update:
 * <pre>
 * "limits": [
 *   {"kind":"session","group":"session","percent":41,"resets_at":"2026-10-19T14:59:59.72+00:00",
 *    "scope":null},
 *   {"kind":"weekly_all","group":"weekly","percent":20,"resets_at":"...","scope":null},
 *   {"kind":"weekly_scoped","group":"weekly","percent":38,"resets_at":"...",
 *    "scope":{"model":{"id":null,"display_name":"Fable"},"surface":null}}
 * ]
 * </pre>
 * Older responses only have {@code five_hour} / {@code seven_day} objects of the form
 * {@code {"utilization": 6.0, "resets_at": "..."}}; those are the fallback. The legacy
 * per-model keys ({@code seven_day_opus}, {@code seven_day_sonnet}) and internal codename keys
 * are ignored because Claude Code no longer shows them. Percentages are percent used (0–100).
 */
public final class ClaudeUsageParser {
    public static final long FIVE_HOUR_SECONDS = 5L * 60L * 60L;
    public static final long SEVEN_DAY_SECONDS = 7L * 24L * 60L * 60L;
    public static final long MONTH_SECONDS = 30L * 24L * 60L * 60L;
    static final String LIMIT_ID_PREFIX = "claude_";
    /** Older top-level spellings of a Fable-only weekly allocation. */
    private static final String[] LEGACY_FABLE_KEYS = {
            "seven_day_fable", "fable_seven_day", "fable_weekly"};

    private ClaudeUsageParser() {
    }

    public static UsageSnapshot parse(String body, String planLabel, long fetchedAtMillis)
            throws Exception {
        JSONObject root = new JSONObject(body == null ? "" : body);
        UsageWindow fiveHour = null;
        UsageWindow weekly = null;
        List<UsageLimit> extra = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        JSONArray rows = root.optJSONArray("limits");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String kind = row.optString("kind", "").trim().toLowerCase(Locale.ROOT);
                String group = row.optString("group", "").trim().toLowerCase(Locale.ROOT);
                JSONObject scopeObject = row.optJSONObject("scope");
                // An "All models" scope repeats the weekly meter; Claude Code skips it too.
                if (scopeObject != null && scopeObject.toString().toLowerCase(Locale.ROOT)
                        .contains("\"all models\"")) continue;
                String scope = scopeName(scopeObject);
                UsageWindow window = meter(row, windowSeconds(kind, group));
                if (window == null) continue;
                if (scope.isEmpty() && "session".equals(kind) && fiveHour == null) {
                    fiveHour = window;
                } else if (scope.isEmpty() && "weekly_all".equals(kind) && weekly == null) {
                    weekly = window;
                } else {
                    String id = LIMIT_ID_PREFIX + (scope.isEmpty() ? kind
                            : group.isEmpty() ? "scoped_" + slug(scope)
                            : group + "_scoped_" + slug(scope));
                    if (!seen.add(id)) continue;
                    extra.add(new UsageLimit(id, scope.isEmpty() ? humanize(kind) : scope + " only",
                            "", true, reached(window), window, null));
                }
            }
        }

        if (fiveHour == null) fiveHour = window(root.optJSONObject("five_hour"), FIVE_HOUR_SECONDS);
        if (weekly == null) weekly = window(root.optJSONObject("seven_day"), SEVEN_DAY_SECONDS);
        String fableId = LIMIT_ID_PREFIX + "weekly_scoped_fable";
        if (!seen.contains(fableId)) {
            for (String key : LEGACY_FABLE_KEYS) {
                UsageWindow window = window(root.optJSONObject(key), SEVEN_DAY_SECONDS);
                if (window != null) {
                    extra.add(new UsageLimit(fableId, "Fable only", "", true, reached(window),
                            window, null));
                    break;
                }
            }
        }

        boolean limitReached = reached(fiveHour) || reached(weekly);
        for (UsageLimit limit : extra) {
            limitReached |= limit.limitReached;
        }

        UsageLimit extraUsage = extraUsage(root.optJSONObject("extra_usage"));
        if (extraUsage != null) {
            extra.add(extraUsage);
        }

        return new UsageSnapshot(planLabel == null ? "" : planLabel, !limitReached,
                limitReached, fiveHour, weekly, null, extra, null, -1, fetchedAtMillis);
    }

    /** One {@code limits[]} row; rows without a usable percentage are skipped. */
    static UsageWindow meter(JSONObject row, long windowSeconds) {
        if (row == null || row.isNull("percent")) return null;
        double percent = row.optDouble("percent", Double.NaN);
        if (Double.isNaN(percent) || Double.isInfinite(percent)) return null;
        return new UsageWindow((int) Math.round(percent), windowSeconds, 0L,
                parseResetSeconds(row.optString("resets_at", "")));
    }

    static long windowSeconds(String kind, String group) {
        if ("session".equals(group) || kind.startsWith("session")) return FIVE_HOUR_SECONDS;
        if (group.startsWith("month") || kind.startsWith("month")) return MONTH_SECONDS;
        return SEVEN_DAY_SECONDS;
    }

    /** Model or surface name a meter is scoped to, or "" for account-wide meters. */
    static String scopeName(JSONObject scope) {
        if (scope == null) return "";
        for (String key : new String[] {"model", "surface"}) {
            Object value = scope.opt(key);
            String name = "";
            if (value instanceof JSONObject) {
                name = ((JSONObject) value).optString("display_name", "");
                if (name.trim().isEmpty() || "null".equals(name)) {
                    name = ((JSONObject) value).optString("id", "");
                }
            } else if (value instanceof String) {
                name = (String) value;
            }
            name = name.trim();
            if (!name.isEmpty() && !"null".equals(name)) {
                return name;
            }
        }
        return "";
    }

    static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /** "weekly_all" -> "Weekly all"; used only for unscoped meters Claude adds later. */
    static String humanize(String kind) {
        String spaced = kind.replace('_', ' ').trim();
        if (spaced.isEmpty()) return "Additional usage";
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
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

    /** Organization UUID from the profile, needed to spend a saved reset. */
    public static String organizationUuid(String profileBody) {
        if (profileBody == null || profileBody.isEmpty()) return "";
        try {
            JSONObject organization = new JSONObject(profileBody).optJSONObject("organization");
            return organization == null ? "" : organization.optString("uuid", "");
        } catch (Exception ignored) {
            return "";
        }
    }
}
