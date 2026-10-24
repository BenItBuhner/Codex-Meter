package dev.bennett.codexmeter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Maps Cursor's {@code DashboardService/GetCurrentPeriodUsage} response onto the shared
 * {@link UsageSnapshot}, so every dashboard card works unchanged.
 *
 * <p>Observed response shape (Connect JSON, so zero-valued fields are omitted and 64-bit numbers
 * may arrive as strings; money is in cents, percents are already 0–100):
 * <pre>
 * {
 *   "billingCycleStart": "1791676800000", "billingCycleEnd": "1794355200000",
 *   "planUsage": {"totalSpend": 500, "includedSpend": 400, "limit": 2000,
 *                 "totalPercentUsed": 25, "autoPercentUsed": 10, "apiPercentUsed": 15},
 *   "spendLimitUsage": {"individualLimit": 1000, "individualUsed": 300,
 *                       "pooledLimit": "20000", "pooledUsed": 5000, "limitType": "team"},
 *   "enabled": true
 * }
 * </pre>
 * Cursor sells more than one kind of plan, and they answer with this shape:
 * <ul>
 *   <li>Current plans have one included pool for Cursor models that refreshes weekly. It
 *       becomes the weekly window, and spend past it is "Additional usage".
 *   <li>Legacy plans (Pro, Pro+, Ultra, Teams Standard) keep a monthly cycle with two pools,
 *       "Cursor models" and "Other models". The included total becomes the monthly window,
 *       each pool a named limit, and spend past it is "On-demand".
 *   <li>The X Subscription (Plus, Premium, Super, Ultra) is optional and shares a usage pool
 *       with Grok. Older Cursor plans keep working until the account unifies. Sign-in stays
 *       on Cursor; Grok stays its own xAI sign-in.
 * </ul>
 * The cycle length tells them apart: Cursor reports the weekly refresh as the cycle.
 */
public final class CursorUsageParser {
    public static final long DEFAULT_CYCLE_SECONDS = 30L * 24L * 60L * 60L;
    static final String LIMIT_ID_PREFIX = "cursor_";

    private CursorUsageParser() {
    }

    public static UsageSnapshot parse(String body, String planLabel, long fetchedAtMillis)
            throws Exception {
        JSONObject root = new JSONObject(body == null ? "" : body);
        long start = epochMillis(root.opt("billingCycleStart"));
        long end = epochMillis(root.opt("billingCycleEnd"));
        long cycleSeconds = start > 0L && end > start ? (end - start) / 1000L
                : DEFAULT_CYCLE_SECONDS;
        long resetSeconds = end > 0L ? end / 1000L : 0L;

        boolean weekly = isWeeklyCycle(cycleSeconds);
        UsageWindow plan = null;
        List<UsageLimit> limits = new ArrayList<>();
        JSONObject planUsage = root.optJSONObject("planUsage");
        if (planUsage != null) {
            double percent = number(planUsage, "totalPercentUsed");
            double limit = number(planUsage, "limit");
            if (Double.isNaN(percent) && limit > 0) {
                double used = number(planUsage, "includedSpend");
                if (Double.isNaN(used)) used = number(planUsage, "totalSpend");
                percent = (Double.isNaN(used) ? 0 : used) / limit * 100.0d;
            }
            if (!Double.isNaN(percent)) {
                plan = new UsageWindow(roundPercent(percent), cycleSeconds, 0L, resetSeconds);
            }
            // Only legacy monthly plans split usage into two pools. Connect JSON drops zero
            // values, so when one pool is reported the other is shown at 0%.
            if (!weekly && (!Double.isNaN(number(planUsage, "autoPercentUsed"))
                    || !Double.isNaN(number(planUsage, "apiPercentUsed")))) {
                addPool(limits, planUsage, "autoPercentUsed", "auto", "Cursor models",
                        cycleSeconds, resetSeconds);
                addPool(limits, planUsage, "apiPercentUsed", "api", "Other models",
                        cycleSeconds, resetSeconds);
            }
        }

        UsageLimit onDemand = onDemand(root.optJSONObject("spendLimitUsage"), cycleSeconds,
                resetSeconds, weekly);
        if (onDemand != null) limits.add(onDemand);

        boolean planSpent = plan != null && plan.usedPercent >= 100;
        boolean limitReached = planSpent && (onDemand == null || onDemand.limitReached);
        return new UsageSnapshot(planLabel == null ? "" : planLabel, !limitReached,
                limitReached, null, weekly ? plan : null, weekly ? null : plan, limits, null, -1,
                fetchedAtMillis);
    }

    private static void addPool(List<UsageLimit> limits, JSONObject planUsage, String key,
            String id, String name, long cycleSeconds, long resetSeconds) {
        double percent = number(planUsage, key);
        if (Double.isNaN(percent)) percent = 0;
        UsageWindow window = new UsageWindow(roundPercent(percent), cycleSeconds, 0L,
                resetSeconds);
        limits.add(new UsageLimit(LIMIT_ID_PREFIX + id, name, "", true,
                window.usedPercent >= 100, window, null));
    }

    /** A cycle of up to ten days is the weekly refresh of a current plan. */
    static boolean isWeeklyCycle(long cycleSeconds) {
        return cycleSeconds > 0L && cycleSeconds <= 10L * 24L * 60L * 60L;
    }

    /**
     * Paid usage past the included plan: "Additional usage" on current plans, "On-demand" on
     * legacy ones. A personal cap wins over a team pool; no cap means unlimited or disabled,
     * which has nothing to meter.
     */
    static UsageLimit onDemand(JSONObject spend, long cycleSeconds, long resetSeconds,
            boolean weekly) {
        if (spend == null) return null;
        double limit = number(spend, "individualLimit");
        double used = number(spend, "individualUsed");
        String name = weekly ? "Additional usage" : "On-demand";
        if (!(limit > 0)) {
            limit = number(spend, "pooledLimit");
            used = number(spend, "pooledUsed");
            name = weekly ? "Team additional usage" : "Team on-demand";
        }
        if (!(limit > 0)) return null;
        if (Double.isNaN(used)) used = 0;
        UsageWindow window = new UsageWindow(roundPercent(used / limit * 100.0d), cycleSeconds,
                0L, resetSeconds);
        return new UsageLimit(LIMIT_ID_PREFIX + "on_demand",
                name + " (" + dollars(used) + " of " + dollars(limit) + ")", "", true,
                used >= limit, window, null);
    }

    /** Accepts epoch millis as a number or numeric string, or an ISO-8601 timestamp. */
    static long epochMillis(Object value) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) return Math.max(0L, ((Number) value).longValue());
        String text = value.toString().trim();
        if (text.isEmpty()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(text));
        } catch (NumberFormatException notNumeric) {
            try {
                return Math.max(0L, OffsetDateTime.parse(text).toInstant().toEpochMilli());
            } catch (Exception notIso) {
                return 0L;
            }
        }
    }

    /** A number that Connect JSON may encode as a string; NaN when missing or unreadable. */
    static double number(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return Double.NaN;
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isInfinite(parsed) ? Double.NaN : parsed;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    /** Cents to a short dollar label, e.g. 1250 -> "$12.50", 2000 -> "$20". */
    static String dollars(double cents) {
        double value = cents / 100.0d;
        if (Math.abs(value - Math.rint(value)) < 0.005d) {
            return String.format(Locale.US, "$%.0f", value);
        }
        return String.format(Locale.US, "$%.2f", value);
    }

    /** Any usage shows at least 1% so a light month never reads as untouched. */
    private static int roundPercent(double percent) {
        if (Double.isNaN(percent) || percent <= 0) return 0;
        return (int) Math.max(1L, Math.round(percent));
    }

    /** Plan badge from {@code auth/full_stripe_profile}. */
    public static String planLabel(String profileBody) {
        if (profileBody == null || profileBody.isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(profileBody);
            String type = root.optString("membershipType", "");
            if (type.isEmpty()) type = root.optString("membership_type", "");
            if (type.isEmpty()) type = root.optString("individualMembershipType", "");
            switch (type.trim().toLowerCase(Locale.ROOT)) {
                case "": return "";
                case "free": case "hobby": return "Hobby";
                case "free_trial": return "Pro trial";
                case "pro": return "Pro";
                case "pro_plus": return "Pro+";
                case "plus": return "Plus";
                case "premium": return "Premium";
                case "super": return "Super";
                case "ultra": return "Ultra";
                case "team": case "business": return "Teams";
                case "enterprise": return "Enterprise";
                default:
                    String spaced = type.replace('_', ' ').trim();
                    return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
            }
        } catch (Exception ignored) {
            // The profile only decorates the badge; a schema change must not block usage.
            return "";
        }
    }

    /** Email on the account card when the profile includes one. */
    public static String email(String profileBody) {
        if (profileBody == null || profileBody.isEmpty()) return "";
        try {
            String email = new JSONObject(profileBody).optString("email", "");
            return email.contains("@") ? email : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
