package dev.bennett.codexmeter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Claude Code's saved usage-limit resets ("cedar_ember" in the CLI). Claude Code reads them from
 * {@code GET /api/oauth/usage?cedar_ember=1}, whose body gains a {@code cedar_ember} block:
 * <pre>
 * "cedar_ember": {
 *   "eligible": true, "at_limit": false,
 *   "grants": [{"id":"g_1","label":"Limit reset","resets_total":1,"resets_left":1,
 *               "starts_at":"...","ends_at":"2026-11-01T00:00:00Z","clears":["five_hour"],
 *               "paused":false,"usable_now":false,"use_requires_limit":true,
 *               "type":"session","unavailable_reason":"needs_limit"}],
 *   "next_grant_id": "g_1", "cooldown_until": null
 * }
 * </pre>
 * One is spent with {@code POST /api/organizations/{org}/reset_rate_limits}. A reset normally
 * only works at a usage limit ({@code use_requires_limit}); the server then answers
 * {@code not_limited} and nothing is used. Anthropic does not document any of this, so parsing
 * is defensive and an absent or malformed block simply means "no saved resets".
 */
public final class ClaudeSavedResets {
    public static final String PROGRAM = "cedar_ember";
    private static final Pattern GRANT_ID = Pattern.compile("^[a-z0-9_-]{1,40}$");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public final boolean eligible;
    public final String ineligibleReason;
    public final boolean atLimit;
    public final List<Grant> grants;
    public final String nextGrantId;
    public final long cooldownUntilMillis;
    public final long fetchedAtMillis;

    public ClaudeSavedResets(boolean eligible, String ineligibleReason, boolean atLimit,
            List<Grant> grants, String nextGrantId, long cooldownUntilMillis,
            long fetchedAtMillis) {
        this.eligible = eligible;
        this.ineligibleReason = safe(ineligibleReason);
        this.atLimit = atLimit;
        this.grants = Collections.unmodifiableList(
                grants == null ? new ArrayList<>() : new ArrayList<>(grants));
        this.nextGrantId = safe(nextGrantId);
        this.cooldownUntilMillis = Math.max(0L, cooldownUntilMillis);
        this.fetchedAtMillis = Math.max(0L, fetchedAtMillis);
    }

    /** One saved-reset grant. A grant can hold several resets. */
    public static final class Grant {
        public final String id;
        public final String label;
        public final int resetsTotal;
        public final int resetsLeft;
        public final long endsAtMillis;
        /** Limits this grant refills: "five_hour", "seven_day", ... */
        public final List<String> clears;
        public final boolean paused;
        public final boolean usableNow;
        public final boolean useRequiresLimit;
        /** "session", "weekly", "full", or empty. */
        public final String type;
        public final String unavailableReason;

        public Grant(String id, String label, int resetsTotal, int resetsLeft, long endsAtMillis,
                List<String> clears, boolean paused, boolean usableNow, boolean useRequiresLimit,
                String type, String unavailableReason) {
            this.id = safe(id);
            this.label = safe(label);
            this.resetsTotal = Math.max(0, resetsTotal);
            this.resetsLeft = Math.max(0, resetsLeft);
            this.endsAtMillis = Math.max(0L, endsAtMillis);
            this.clears = Collections.unmodifiableList(
                    clears == null ? new ArrayList<>() : new ArrayList<>(clears));
            this.paused = paused;
            this.usableNow = usableNow;
            this.useRequiresLimit = useRequiresLimit;
            this.type = safe(type);
            this.unavailableReason = safe(unavailableReason);
        }

        public boolean hasResets(long nowMillis) {
            return resetsLeft > 0 && !paused && (endsAtMillis <= 0L || endsAtMillis > nowMillis);
        }

        public boolean clearsFiveHour() {
            return clears.contains("five_hour") || "session".equals(type) || "full".equals(type);
        }

        public boolean clearsWeekly() {
            for (String limit : clears) {
                if (limit.startsWith("seven_day")) return true;
            }
            return "weekly".equals(type) || "full".equals(type);
        }

        /** "5-hour limit", "weekly limit", or "5-hour and weekly limits". */
        public String clearsLabel() {
            boolean session = clearsFiveHour();
            boolean weekly = clearsWeekly();
            if (session && weekly) return "5-hour and weekly limits";
            if (weekly) return "weekly limit";
            if (session) return "5-hour limit";
            return "usage limit";
        }

        /** The dashboard's name for it, matching claude.ai ("Full reset", "Session reset"). */
        public String title() {
            switch (type) {
                case "full":
                    return "Full reset";
                case "weekly":
                    return "Weekly reset";
                case "session":
                    return "Session reset";
                default:
                    return label.isEmpty() ? "Limit reset" : label;
            }
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("label", label);
            json.put("resets_total", resetsTotal);
            json.put("resets_left", resetsLeft);
            json.put("ends_at_millis", endsAtMillis);
            json.put("clears", new JSONArray(clears));
            json.put("paused", paused);
            json.put("usable_now", usableNow);
            json.put("use_requires_limit", useRequiresLimit);
            json.put("type", type);
            json.put("unavailable_reason", unavailableReason);
            return json;
        }

        static Grant fromJson(JSONObject json) {
            if (json == null) return null;
            return new Grant(json.optString("id", ""), json.optString("label", ""),
                    json.optInt("resets_total", 0), json.optInt("resets_left", 0),
                    json.optLong("ends_at_millis", 0L), strings(json.optJSONArray("clears")),
                    json.optBoolean("paused", false), json.optBoolean("usable_now", false),
                    json.optBoolean("use_requires_limit", true), json.optString("type", ""),
                    json.optString("unavailable_reason", ""));
        }
    }

    /**
     * Reads the {@code cedar_ember} block out of a usage response body. Returns null when the
     * block is missing or unreadable.
     */
    public static ClaudeSavedResets fromUsageBody(String body, long fetchedAtMillis) {
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            return fromApiJson(root.optJSONObject("cedar_ember"), fetchedAtMillis);
        } catch (JSONException exception) {
            return null;
        }
    }

    public static ClaudeSavedResets fromApiJson(JSONObject block, long fetchedAtMillis) {
        if (block == null || !block.has("eligible")) return null;
        List<Grant> grants = new ArrayList<>();
        JSONArray rows = block.optJSONArray("grants");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("id", "");
                if (!GRANT_ID.matcher(id).matches() || !row.has("resets_left")) continue;
                grants.add(new Grant(id, row.optString("label", ""),
                        row.optInt("resets_total", 0), row.optInt("resets_left", 0),
                        parseTime(optString(row, "ends_at")),
                        strings(row.optJSONArray("clears")),
                        row.optBoolean("paused", false), row.optBoolean("usable_now", false),
                        row.optBoolean("use_requires_limit", true), optString(row, "type"),
                        optString(row, "unavailable_reason")));
            }
        }
        String next = optString(block, "next_grant_id");
        boolean nextKnown = false;
        for (Grant grant : grants) {
            if (grant.id.equals(next)) nextKnown = true;
        }
        return new ClaudeSavedResets(block.optBoolean("eligible", false),
                optString(block, "ineligible_reason"), block.optBoolean("at_limit", false),
                grants, nextKnown ? next : "", parseTime(optString(block, "cooldown_until")),
                fetchedAtMillis);
    }

    /** Resets that can still be spent, across all grants. */
    public int availableCount(long nowMillis) {
        int count = 0;
        for (Grant grant : grants) {
            if (grant.hasResets(nowMillis)) count += grant.resetsLeft;
        }
        return count;
    }

    public boolean shouldDisplay(long nowMillis) {
        return availableCount(nowMillis) > 0;
    }

    /** The grant the server wants spent next, else the one that expires first. */
    public Grant nextGrant(long nowMillis) {
        Grant best = null;
        for (Grant grant : grants) {
            if (!grant.hasResets(nowMillis)) continue;
            if (grant.id.equals(nextGrantId)) return grant;
            if (best == null || expiryOrder(grant) < expiryOrder(best)) best = grant;
        }
        return best;
    }

    /** The grant a claim would spend right now, or null when none can be used yet. */
    public Grant usableGrant(long nowMillis) {
        if (!eligible || cooldownUntilMillis > nowMillis) return null;
        Grant next = nextGrant(nowMillis);
        if (next != null && next.usableNow) return next;
        Grant best = null;
        for (Grant grant : grants) {
            if (!grant.hasResets(nowMillis) || !grant.usableNow) continue;
            if (best == null || expiryOrder(grant) < expiryOrder(best)) best = grant;
        }
        return best;
    }

    /**
     * A usable grant that refills the given window ("5-hour", "weekly"; "monthly" counts as the
     * long window), else null. The server's next grant wins, then the earliest to expire.
     */
    public Grant usableGrantFor(String windowLabel, long nowMillis) {
        if (!eligible || cooldownUntilMillis > nowMillis) return null;
        boolean fiveHour = "5-hour".equals(windowLabel);
        Grant best = null;
        for (Grant grant : grants) {
            if (!grant.hasResets(nowMillis) || !grant.usableNow) continue;
            if (fiveHour ? !grant.clearsFiveHour() : !grant.clearsWeekly()) continue;
            if (grant.id.equals(nextGrantId)) return grant;
            if (best == null || expiryOrder(grant) < expiryOrder(best)) best = grant;
        }
        return best;
    }

    /** Why the next reset can't be used right now, in words; empty when it can. */
    public String unavailableMessage(long nowMillis) {
        if (usableGrant(nowMillis) != null) return "";
        if (cooldownUntilMillis > nowMillis) return "Available again after a short cooldown";
        Grant next = nextGrant(nowMillis);
        String reason = next == null ? "" : next.unavailableReason;
        switch (reason) {
            case "blocked_seven_day":
                return "Your weekly limit is blocking you, and this reset doesn't clear it";
            case "blocked_five_hour":
                return "Your 5-hour limit is blocking you, and this reset doesn't clear it";
            case "blocked_opus":
            case "blocked_sonnet":
            case "blocked_other":
                return "A model limit this reset doesn't clear is blocking you";
            case "cooldown":
                return "Available again after a short cooldown";
            default:
                return "Works once you hit a usage limit";
        }
    }

    private static long expiryOrder(Grant grant) {
        return grant.endsAtMillis > 0L ? grant.endsAtMillis : Long.MAX_VALUE;
    }

    /** A copy with one reset taken off {@code grantId}, so the card updates straight away. */
    public ClaudeSavedResets afterUse(String grantId) {
        List<Grant> updated = new ArrayList<>();
        for (Grant grant : grants) {
            if (grant.id.equals(grantId)) {
                updated.add(new Grant(grant.id, grant.label, grant.resetsTotal,
                        grant.resetsLeft - 1, grant.endsAtMillis, grant.clears, grant.paused,
                        false, grant.useRequiresLimit, grant.type, grant.unavailableReason));
            } else {
                updated.add(grant);
            }
        }
        return new ClaudeSavedResets(eligible, ineligibleReason, false, updated, "",
                cooldownUntilMillis, fetchedAtMillis);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("eligible", eligible);
        json.put("ineligible_reason", ineligibleReason);
        json.put("at_limit", atLimit);
        JSONArray rows = new JSONArray();
        for (Grant grant : grants) rows.put(grant.toJson());
        json.put("grants", rows);
        json.put("next_grant_id", nextGrantId);
        json.put("cooldown_until_millis", cooldownUntilMillis);
        json.put("fetched_at", fetchedAtMillis);
        return json;
    }

    public static ClaudeSavedResets fromJson(JSONObject json) {
        if (json == null) return null;
        List<Grant> grants = new ArrayList<>();
        JSONArray rows = json.optJSONArray("grants");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                Grant grant = Grant.fromJson(rows.optJSONObject(i));
                if (grant != null) grants.add(grant);
            }
        }
        return new ClaudeSavedResets(json.optBoolean("eligible", false),
                json.optString("ineligible_reason", ""), json.optBoolean("at_limit", false),
                grants, json.optString("next_grant_id", ""),
                json.optLong("cooldown_until_millis", 0L), json.optLong("fetched_at", 0L));
    }

    /** Request body for {@code POST /api/organizations/{org}/reset_rate_limits}. */
    public static JSONObject claimBody(String grantId, String requestId) throws JSONException {
        if (!GRANT_ID.matcher(safe(grantId)).matches()
                || !REQUEST_ID.matcher(safe(requestId)).matches()) {
            throw new IllegalArgumentException("Malformed saved-reset id.");
        }
        JSONObject body = new JSONObject();
        body.put("program", PROGRAM);
        body.put("grant_id", grantId);
        body.put("request_id", requestId);
        return body;
    }

    /** Outcome of a claim. {@code result} mirrors the server's word, plus local failures. */
    public static final class ClaimResult {
        public static final String RESET = "reset";
        public static final String ALREADY_USED = "already_used";
        public static final String NOT_LIMITED = "not_limited";
        public static final String COOLDOWN = "cooldown";
        public static final String INELIGIBLE = "ineligible";
        public static final String UNAVAILABLE = "unavailable";
        public static final String RATE_LIMITED = "rate_limited";
        public static final String AUTH_ERROR = "auth_error";
        public static final String ERROR = "error";

        public final String result;
        public final String reason;
        public final int resetsLeft;
        public final List<String> cleared;

        public ClaimResult(String result, String reason, int resetsLeft, List<String> cleared) {
            this.result = safe(result).isEmpty() ? ERROR : result;
            this.reason = safe(reason);
            this.resetsLeft = resetsLeft;
            this.cleared = Collections.unmodifiableList(
                    cleared == null ? new ArrayList<>() : new ArrayList<>(cleared));
        }

        public static ClaimResult parse(String body) {
            try {
                JSONObject json = new JSONObject(body == null ? "" : body);
                String result = json.optString("result", "");
                switch (result) {
                    case RESET:
                    case ALREADY_USED:
                    case NOT_LIMITED:
                    case COOLDOWN:
                    case INELIGIBLE:
                    case UNAVAILABLE:
                        break;
                    default:
                        result = UNAVAILABLE;
                }
                return new ClaimResult(result, optString(json, "reason"),
                        json.isNull("resets_left") ? -1 : json.optInt("resets_left", -1),
                        strings(json.optJSONArray("cleared")));
            } catch (JSONException exception) {
                return new ClaimResult(ERROR, "unreadable_response", -1, null);
            }
        }

        public boolean applied() {
            return RESET.equals(result);
        }

        /** Network or server trouble that is worth retrying later. */
        public boolean retryable() {
            return ERROR.equals(result) || RATE_LIMITED.equals(result);
        }

        public String userMessage() {
            switch (result) {
                case RESET:
                    return "Your Claude Code limit was reset."
                            + (resetsLeft >= 0 ? " " + left(resetsLeft) : "");
                case NOT_LIMITED:
                    return "Your reset only works at a usage limit, and you're not at one now. "
                            + "Nothing was used.";
                case ALREADY_USED:
                    return "That reset was already used.";
                case COOLDOWN:
                    return "Saved resets are cooling down. Try again shortly. Nothing was used.";
                case INELIGIBLE:
                case UNAVAILABLE:
                    return "Your reset can't be used for this limit right now. Nothing was used.";
                case RATE_LIMITED:
                    return "Claude is rate-limiting requests. Try again in a few minutes.";
                case AUTH_ERROR:
                    return "Claude rejected the saved sign-in. Sign in with Claude again.";
                default:
                    return "Could not reach Claude to use the reset. Nothing was used.";
            }
        }

        private static String left(int count) {
            if (count == 0) return "No saved resets left.";
            return count + (count == 1 ? " saved reset left." : " saved resets left.");
        }
    }

    static long parseTime(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli();
            } catch (DateTimeParseException alsoIgnored) {
                return 0L;
            }
        }
    }

    private static List<String> strings(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private static String optString(JSONObject json, String key) {
        return json.isNull(key) ? "" : json.optString(key, "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
