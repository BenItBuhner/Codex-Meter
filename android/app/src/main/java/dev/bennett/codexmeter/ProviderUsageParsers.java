package dev.bennett.codexmeter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Turns each added provider's usage response into a {@link UsageSnapshot}. Pure Java (org.json
 * only) so the self-tests cover every shape. Synthetic and v0 document their routes; Chutes
 * publishes its API source; the rest follow the providers' own CLIs and editors and
 * open-source trackers (CodexBar, OpenClaw). Every reader is defensive: a missing field leaves
 * a window out rather than failing the whole refresh.
 *
 * <p>Windows land in the snapshot by length: up to a day is the short window
 * ({@code fiveHour}, labelled on the dashboard by its real length), about a week is {@code weekly}, about a
 * month is {@code monthly}, and anything else becomes an additional limit.
 */
public final class ProviderUsageParsers {
    public static final long HOUR = 3_600L;
    public static final long DAY = 86_400L;
    public static final long WEEK = 604_800L;
    public static final long MONTH = 2_592_000L;

    private ProviderUsageParsers() {
    }

    // ---------------------------------------------------------------- shared building blocks

    /** Collects windows while a response is read, then builds the snapshot. */
    static final class Builder {
        final long now;
        String plan = "";
        UsageWindow shortWindow;
        UsageWindow weekly;
        UsageWindow monthly;
        final List<UsageLimit> extras = new ArrayList<>();
        UsageCredits credits;

        Builder(long nowMillis) {
            this.now = nowMillis;
        }

        /** Places a window by its length; the first window of each length wins. */
        void add(String name, UsageWindow window) {
            if (window == null) return;
            long seconds = window.windowSeconds;
            if (seconds > 0 && seconds <= DAY + 60 && shortWindow == null) {
                shortWindow = window;
            } else if (seconds >= 6 * DAY && seconds <= 8 * DAY && weekly == null) {
                weekly = window;
            } else if (seconds >= 27 * DAY && seconds <= 32 * DAY && monthly == null) {
                monthly = window;
            } else {
                extra(name, window);
            }
        }

        void extra(String name, UsageWindow window) {
            if (window == null) return;
            String id = slug(name);
            for (UsageLimit existing : extras) {
                if (existing.id.equals(id)) return;
            }
            extras.add(new UsageLimit(id, name, "", window.usedPercent < 100,
                    window.usedPercent >= 100, window, null));
        }

        boolean isEmpty() {
            return shortWindow == null && weekly == null && monthly == null && extras.isEmpty()
                    && (credits == null || !credits.shouldDisplay());
        }

        UsageSnapshot build() {
            boolean reached = reached(shortWindow) || reached(weekly) || reached(monthly);
            return new UsageSnapshot(plan, !reached, reached, shortWindow, weekly, monthly,
                    extras, credits, -1, now);
        }

        private static boolean reached(UsageWindow window) {
            return window != null && window.usedPercent >= 100;
        }
    }

    /** A window from percent used (0–100), its length, and when it resets (epoch seconds). */
    static UsageWindow window(double usedPercent, long windowSeconds, long resetAtEpochSeconds,
            long nowMillis) {
        if (Double.isNaN(usedPercent)) return null;
        long nowSeconds = nowMillis / 1000L;
        long after = resetAtEpochSeconds > nowSeconds ? resetAtEpochSeconds - nowSeconds : 0L;
        return new UsageWindow((int) Math.round(Math.max(0.0, Math.min(100.0, usedPercent))),
                windowSeconds, after, Math.max(0L, resetAtEpochSeconds));
    }

    /** Percent used from a limit and either how much is used or how much remains. */
    static double percentUsed(double limit, double used, double remaining) {
        if (Double.isNaN(limit) || limit <= 0) return Double.NaN;
        if (!Double.isNaN(used)) return used * 100.0 / limit;
        if (!Double.isNaN(remaining)) return (limit - remaining) * 100.0 / limit;
        return Double.NaN;
    }

    /**
     * Epoch seconds from a timestamp in any shape these APIs use: seconds, milliseconds,
     * ISO-8601 instants or offsets, or a bare date. Returns 0 when it can't be read.
     */
    public static long epochSeconds(Object value) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number <= 0) return 0L;
            if (number > 1e14) return (long) (number / 1_000_000.0); // microseconds
            if (number > 1e11) return (long) (number / 1000.0);
            return (long) number;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equals(text)) return 0L;
        if (text.matches("-?\\d+(\\.\\d+)?")) {
            try {
                return epochSeconds(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        try {
            return OffsetDateTime.parse(text).toEpochSecond();
        } catch (Exception ignored) {
            // Try the next shape.
        }
        try {
            return Instant.parse(text).getEpochSecond();
        } catch (Exception ignored) {
            // Try the next shape.
        }
        try {
            // A time with no zone (Chutes' naive timestamps) is UTC with its time of day.
            return java.time.LocalDateTime.parse(text).toEpochSecond(ZoneOffset.UTC);
        } catch (Exception ignored) {
            // Try the next shape.
        }
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text)
                    .atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * A field named a ratio ({@code used_ratio}, {@code usedRatio}) is always a fraction of the
     * limit, so 1.2 is 120% (over the limit), never 1.2%.
     */
    static double ratioPercent(double ratio) {
        return ratio * 100.0;
    }

    static double number(JSONObject object, String... keys) {
        if (object == null) return Double.NaN;
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).doubleValue();
            if (value instanceof String) {
                try {
                    return Double.parseDouble(((String) value).trim());
                } catch (NumberFormatException ignored) {
                    // Not a number; keep looking.
                }
            }
            if (value instanceof JSONObject) {
                // Grok wraps amounts as {"val": "123"}.
                double inner = number((JSONObject) value, "val", "value", "amount");
                if (!Double.isNaN(inner)) return inner;
            }
        }
        return Double.NaN;
    }

    static Object first(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            Object value = object.opt(key);
            if (value != null && value != JSONObject.NULL && !"".equals(value)) return value;
        }
        return null;
    }

    static String string(JSONObject object, String... keys) {
        Object value = first(object, keys);
        return value == null ? "" : String.valueOf(value).trim();
    }

    static JSONObject object(String body) {
        try {
            return new JSONObject(body == null ? "" : body);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /** The first nested object along {@code path}, or null. */
    static JSONObject path(JSONObject root, String... path) {
        JSONObject current = root;
        for (String key : path) {
            if (current == null) return null;
            current = current.optJSONObject(key);
        }
        return current;
    }

    static String money(double amount) {
        if (Double.isNaN(amount)) return "";
        return "$" + BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static UsageCredits balance(double dollars) {
        if (Double.isNaN(dollars)) return null;
        return new UsageCredits(true, false, money(dollars));
    }

    static String titleCase(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replace('_', ' ').replace('-', ' ').trim();
        StringBuilder out = new StringBuilder();
        for (String word : cleaned.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    static String slug(String name) {
        String slug = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "limit" : slug;
    }

    /** Length of a window from a start and end in any timestamp shape, or {@code fallback}. */
    static long span(Object start, Object end, long fallback) {
        long from = epochSeconds(start);
        long to = epochSeconds(end);
        return from > 0 && to > from ? to - from : fallback;
    }

    // ---------------------------------------------------------------- GitHub Copilot

    /** {@code api.github.com/copilot_internal/user}. Premium requests are the monthly window. */
    public static UsageSnapshot copilot(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = copilotPlan(string(root, "copilot_plan", "access_type_sku"));
        long reset = epochSeconds(first(root, "quota_reset_date_utc", "quota_reset_date",
                "limited_user_reset_date"));
        JSONObject quotas = root.optJSONObject("quota_snapshots");
        if (quotas != null) {
            String[] order = {"premium_interactions", "chat", "completions"};
            for (String key : order) {
                JSONObject quota = quotas.optJSONObject(key);
                UsageWindow window = copilotQuota(quota, reset, now);
                if (window == null) continue;
                if ("premium_interactions".equals(key) && out.monthly == null) {
                    out.monthly = window;
                } else {
                    out.extra(copilotName(key), window);
                }
            }
            Iterator<String> keys = quotas.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("premium_interactions".equals(key) || "chat".equals(key)
                        || "completions".equals(key)) continue;
                out.extra(copilotName(key), copilotQuota(quotas.optJSONObject(key), reset, now));
            }
        }
        // Token-based billing (Copilot Business since 2026) and unlimited seats have no metered
        // window; show the plan as unlimited instead of failing the refresh.
        boolean unlimited = root.optBoolean("token_based_billing", false);
        if (quotas != null && !unlimited) {
            for (String key : new String[] {"premium_interactions", "chat"}) {
                JSONObject quota = quotas.optJSONObject(key);
                if (quota != null && quota.optBoolean("unlimited", false)) unlimited = true;
            }
        }
        // Free accounts report counts instead of snapshots.
        JSONObject monthly = root.optJSONObject("monthly_quotas");
        JSONObject left = root.optJSONObject("limited_user_quotas");
        if (monthly != null && left != null) {
            for (String key : new String[] {"chat", "completions"}) {
                double total = number(monthly, key);
                double remaining = number(left, key);
                UsageWindow window = window(percentUsed(total, Double.NaN, remaining), MONTH,
                        reset, now);
                if (window == null) continue;
                if ("chat".equals(key) && out.monthly == null) {
                    out.monthly = window;
                } else {
                    out.extra(copilotName(key), window);
                }
            }
        }
        if (unlimited && out.isEmpty()) out.credits = new UsageCredits(false, true, "");
        return out.build();
    }

    private static UsageWindow copilotQuota(JSONObject quota, long reset, long now) {
        if (quota == null || quota.optBoolean("unlimited", false)) return null;
        double entitlement = number(quota, "entitlement");
        double remaining = number(quota, "remaining");
        // Placeholder rows for features the plan doesn't include.
        if (entitlement == 0 && remaining == 0) return null;
        double percentRemaining = number(quota, "percent_remaining");
        double used = !Double.isNaN(percentRemaining) ? 100.0 - percentRemaining
                : percentUsed(entitlement, Double.NaN, remaining);
        return window(used, MONTH, reset, now);
    }

    private static String copilotName(String key) {
        switch (key) {
            case "premium_interactions":
                return "Premium requests";
            case "chat":
                return "Chat";
            case "completions":
                return "Code completions";
            default:
                return titleCase(key);
        }
    }

    public static String copilotPlan(String sku) {
        String value = sku == null ? "" : sku.toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        if (value.contains("pro_plus") || value.contains("pro+") || value.contains("individual_pro")) {
            return "Pro+";
        }
        if (value.contains("free")) return "Free";
        if (value.contains("individual") || value.equals("pro")) return "Pro";
        if (value.contains("business")) return "Business";
        if (value.contains("enterprise")) return "Enterprise";
        return titleCase(value);
    }

    // ---------------------------------------------------------------- Kimi Code

    /** {@code api.kimi.com/coding/v1/usages}: 5-hour and weekly limits, as the Kimi CLI shows. */
    public static UsageSnapshot kimi(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = kimiPlan(string(path(root, "user", "membership"), "level"));
        JSONArray limits = root.optJSONArray("limits");
        if (limits != null) {
            for (int i = 0; i < limits.length(); i++) {
                JSONObject entry = limits.optJSONObject(i);
                if (entry == null) continue;
                JSONObject windowInfo = entry.optJSONObject("window");
                JSONObject detail = entry.optJSONObject("detail");
                if (detail == null) detail = entry;
                long seconds = kimiWindowSeconds(windowInfo);
                double used = percentUsed(number(detail, "limit"), number(detail, "used"),
                        number(detail, "remaining"));
                UsageWindow window = window(used, seconds,
                        epochSeconds(first(detail, "resetTime", "reset_time", "resetAt")), now);
                out.add(seconds > 0 ? durationName(seconds) + " limit" : "Limit " + (i + 1),
                        window);
            }
        }
        JSONObject usages = root.optJSONObject("usages");
        if (usages != null) {
            kimiRatio(out, usages.optJSONObject("limit_5h"), 5 * HOUR);
            kimiRatio(out, usages.optJSONObject("limit_7d"), WEEK);
            kimiRatio(out, usages.optJSONObject("limit_month_total"), MONTH);
        }
        // The top-level "usage" block is the weekly quota the Kimi CLI's /usage shows first.
        JSONObject usage = root.optJSONObject("usage");
        if (usage != null && out.weekly == null) {
            double used = percentUsed(number(usage, "limit"), number(usage, "used"),
                    number(usage, "remaining"));
            out.weekly = window(used, WEEK,
                    epochSeconds(first(usage, "resetTime", "reset_time", "resetAt")), now);
        }
        return out.build();
    }

    private static void kimiRatio(Builder out, JSONObject entry, long seconds) {
        if (entry == null) return;
        double ratio = number(entry, "used_ratio", "usedRatio");
        if (Double.isNaN(ratio)) return;
        double percent = ratioPercent(ratio);
        out.add(durationName(seconds) + " limit", window(percent, seconds,
                epochSeconds(first(entry, "reset_time", "resetTime")), out.now));
    }

    static long kimiWindowSeconds(JSONObject window) {
        if (window == null) return 0L;
        double duration = number(window, "duration");
        if (Double.isNaN(duration) || duration <= 0) return 0L;
        String unit = string(window, "timeUnit", "time_unit", "unit").toUpperCase(Locale.ROOT);
        long multiplier;
        if (unit.contains("MINUTE")) {
            multiplier = 60L;
        } else if (unit.contains("HOUR")) {
            multiplier = HOUR;
        } else if (unit.contains("DAY")) {
            multiplier = DAY;
        } else if (unit.contains("WEEK")) {
            multiplier = WEEK;
        } else if (unit.contains("MONTH")) {
            multiplier = MONTH;
        } else {
            multiplier = 1L;
        }
        return (long) duration * multiplier;
    }

    public static String kimiPlan(String level) {
        switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "LEVEL_FREE":
                return "Adagio";
            case "LEVEL_TRIAL":
                return "Andante";
            case "LEVEL_BASIC":
                return "Moderato";
            case "LEVEL_INTERMEDIATE":
                return "Allegretto";
            case "LEVEL_ADVANCED":
                return "Allegro";
            case "":
                return "";
            default:
                return titleCase(level.replaceFirst("(?i)^level_", ""));
        }
    }

    // ---------------------------------------------------------------- Factory (Droid)

    /** {@code api.factory.ai/api/billing/limits}: standard and core token limits. */
    public static UsageSnapshot factory(String body, String plan, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = plan == null ? "" : plan;
        JSONObject limits = root.optJSONObject("limits");
        if (limits != null) {
            JSONObject standard = limits.optJSONObject("standard");
            factoryWindow(out, standard, "fiveHour", 5 * HOUR, "", now);
            factoryWindow(out, standard, "weekly", WEEK, "", now);
            factoryWindow(out, standard, "monthly", MONTH, "", now);
            JSONObject core = limits.optJSONObject("core");
            factoryWindow(out, core, "fiveHour", 5 * HOUR, "Core ", now);
            factoryWindow(out, core, "weekly", WEEK, "Core ", now);
            factoryWindow(out, core, "monthly", MONTH, "Core ", now);
        }
        double cents = number(root, "extraUsageBalanceCents");
        if (!Double.isNaN(cents) && cents > 0) out.credits = balance(cents / 100.0);
        if (out.isEmpty()) factorySubscription(out, root, now);
        return out.build();
    }

    private static void factoryWindow(Builder out, JSONObject group, String key, long seconds,
            String prefix, long now) {
        JSONObject entry = group == null ? null : group.optJSONObject(key);
        if (entry == null) return;
        double used = number(entry, "usedPercent", "used_percent");
        if (Double.isNaN(used)) {
            double ratio = number(entry, "usedRatio", "used_ratio");
            if (!Double.isNaN(ratio)) used = ratioPercent(ratio);
        }
        long reset = epochSeconds(first(entry, "windowEnd", "window_end", "resetAt"));
        if (reset <= 0) {
            double remaining = number(entry, "secondsRemaining");
            if (!Double.isNaN(remaining)) reset = now / 1000L + (long) remaining;
        }
        // A window that already ended has reset; Factory keeps the old percent until next use.
        if (reset > 0 && reset <= now / 1000L) used = 0;
        UsageWindow window = window(used, seconds, reset, now);
        if (prefix.isEmpty()) {
            out.add(durationName(seconds) + " limit", window);
        } else {
            out.extra(prefix + durationName(seconds).toLowerCase(Locale.ROOT) + " limit", window);
        }
    }

    /** Older {@code app.factory.ai/api/organization/subscription/usage} token allowance. */
    private static void factorySubscription(Builder out, JSONObject root, long now) {
        JSONObject usage = root.optJSONObject("usage");
        if (usage == null) return;
        long seconds = span(usage.opt("startDate"), usage.opt("endDate"), MONTH);
        long reset = epochSeconds(usage.opt("endDate"));
        for (String key : new String[] {"standard", "premium"}) {
            JSONObject entry = usage.optJSONObject(key);
            if (entry == null) continue;
            double ratio = number(entry, "usedRatio");
            double percent = !Double.isNaN(ratio) ? ratioPercent(ratio)
                    : percentUsed(number(entry, "totalAllowance"), number(entry, "userTokens",
                            "orgTotalTokensUsed"), Double.NaN);
            UsageWindow window = window(percent, seconds, reset, now);
            if ("standard".equals(key) && out.monthly == null && seconds >= 27 * DAY) {
                out.monthly = window;
            } else {
                out.extra(titleCase(key) + " tokens", window);
            }
        }
    }

    /** Plan name from {@code app.factory.ai/api/app/auth/me}. */
    public static String factoryPlan(String body) {
        JSONObject root = object(body);
        JSONObject subscription = path(root, "organization", "subscription");
        String name = string(path(subscription, "orbSubscription", "plan"), "name");
        if (name.isEmpty()) name = string(subscription, "factoryTier", "tier");
        if (name.isEmpty()) name = string(root.optJSONObject("organization"), "factoryTier");
        return titleCase(name.replaceFirst("(?i)^factory\\s*", ""));
    }

    public static String factoryEmail(String body) {
        JSONObject root = object(body);
        String email = string(root.optJSONObject("userProfile"), "email");
        if (email.isEmpty()) email = string(root.optJSONObject("user"), "email");
        return email.contains("@") ? email : "";
    }

    // ---------------------------------------------------------------- Grok (xAI)

    /** {@code cli-chat-proxy.grok.com/v1/billing?format=credits}: credits for the period. */
    public static UsageSnapshot grok(String body, long now) {
        JSONObject root = object(body);
        JSONObject config = root.optJSONObject("config");
        if (config == null) config = root;
        Builder out = new Builder(now);
        out.plan = grokPlan(string(config, "subscriptionTier"));
        if (out.plan.isEmpty()) out.plan = grokPlan(string(root, "subscriptionTier"));
        JSONObject period = config.optJSONObject("currentPeriod");
        Object start = period != null ? first(period, "start", "startTime")
                : first(config, "billingPeriodStart");
        Object end = period != null ? first(period, "end", "endTime")
                : first(config, "billingPeriodEnd");
        long seconds = span(start, end, MONTH);
        long reset = epochSeconds(end);
        double percent = number(config, "creditUsagePercent", "usagePercent");
        if (!Double.isNaN(percent) && percent <= 1.0 && percent > 0
                && !config.has("creditUsagePercent")) {
            percent *= 100.0;
        }
        if (Double.isNaN(percent)) {
            // Pay-as-you-go accounts report spend against their on-demand cap instead.
            double cap = number(config, "onDemandCap");
            double used = number(config, "onDemandUsed");
            if (!Double.isNaN(cap) && cap > 0 && !Double.isNaN(used)) {
                percent = used / cap * 100.0;
            }
        }
        out.add("Credits", window(percent, seconds, reset, now));
        JSONArray products = config.optJSONArray("productUsage");
        if (products != null) {
            for (int i = 0; i < products.length(); i++) {
                JSONObject product = products.optJSONObject(i);
                if (product == null) continue;
                String name = titleCase(string(product, "product", "name"));
                out.extra(name.isEmpty() ? "Product " + (i + 1) : name,
                        window(number(product, "usagePercent", "creditUsagePercent"), seconds,
                                reset, now));
            }
        }
        double prepaid = number(config, "prepaidBalance");
        if (!Double.isNaN(prepaid) && prepaid > 0) out.credits = balance(prepaid / 100.0);
        return out.build();
    }

    static String grokPlan(String tier) {
        String value = tier == null ? "" : tier.toUpperCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        if (value.contains("HEAVY")) return "SuperGrok Heavy";
        if (value.contains("SUPER")) return "SuperGrok";
        if (value.contains("PREMIUM_PLUS") || value.contains("PREMIUMPLUS")) return "Premium+";
        if (value.contains("PREMIUM")) return "Premium";
        if (value.contains("FREE")) return "Free";
        return titleCase(value.replaceFirst("^SUBSCRIPTION_TIER_", ""));
    }

    // ---------------------------------------------------------------- Antigravity (Google)

    /**
     * Antigravity usage from any of Cloud Code's three quota replies:
     * {@code retrieveUserQuotaSummary} ({@code groups[].buckets[]} with {@code remaining}),
     * {@code fetchAvailableModels} ({@code models.<id>.quotaInfo}) or {@code retrieveUserQuota}
     * ({@code buckets[]} per model). Gemini is the main pair of windows; Claude and GPT
     * become one additional limit. A bucket with no fraction, or a disabled one, is unknown.
     */
    public static UsageSnapshot antigravity(String body, String plan, long now) {
        JSONObject root = object(body);
        JSONObject wrapped = root.optJSONObject("response");
        if (wrapped == null) wrapped = root.optJSONObject("summary");
        if (wrapped != null && wrapped.has("groups")) root = wrapped;
        Builder out = new Builder(now);
        out.plan = plan == null ? "" : plan;
        UsageWindow thirdShort = null;
        UsageWindow thirdWeekly = null;
        JSONArray groups = root.optJSONArray("groups");
        if (groups != null) {
            for (int g = 0; g < groups.length(); g++) {
                JSONObject group = groups.optJSONObject(g);
                if (group == null) continue;
                String groupName = string(group, "displayName", "name");
                String family = antigravityFamily(groupName);
                JSONArray buckets = group.optJSONArray("buckets");
                if (buckets == null) continue;
                for (int b = 0; b < buckets.length(); b++) {
                    JSONObject bucket = buckets.optJSONObject(b);
                    if (bucket == null || bucket.optBoolean("disabled", false)) continue;
                    String id = string(bucket, "bucketId", "id", "name");
                    double fraction = antigravityFraction(bucket);
                    if (Double.isNaN(fraction)) continue;
                    boolean weekly = antigravityWeekly(string(bucket, "window") + " " + id + " "
                            + string(bucket, "displayName"));
                    UsageWindow window = window((1.0 - fraction) * 100.0, weekly ? WEEK : 5 * HOUR,
                            epochSeconds(first(bucket, "resetTime", "reset_time")), now);
                    String bucketFamily = family.isEmpty() ? antigravityFamily(id) : family;
                    if ("gemini".equals(bucketFamily)) {
                        if (weekly && out.weekly == null) out.weekly = window;
                        else if (!weekly && out.shortWindow == null) out.shortWindow = window;
                    } else if ("third".equals(bucketFamily)) {
                        if (weekly && thirdWeekly == null) thirdWeekly = window;
                        else if (!weekly && thirdShort == null) thirdShort = window;
                    } else {
                        String label = groupName.isEmpty() ? titleCase(id) : groupName;
                        out.extra(label + (weekly ? " weekly" : " 5-hour"), window);
                    }
                }
            }
        } else {
            // Per-model quotas: each family shows its most used model.
            List<String[]> rows = new ArrayList<>();
            JSONObject models = root.optJSONObject("models");
            if (models != null) {
                Iterator<String> keys = models.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject model = models.optJSONObject(id);
                    JSONObject quota = model == null ? null : model.optJSONObject("quotaInfo");
                    if (quota == null) continue;
                    rows.add(new String[] {id, string(model, "displayName", "label"),
                            String.valueOf(number(quota, "remainingFraction")),
                            string(quota, "resetTime")});
                }
            }
            JSONArray buckets = root.optJSONArray("buckets");
            if (buckets != null) {
                for (int i = 0; i < buckets.length(); i++) {
                    JSONObject bucket = buckets.optJSONObject(i);
                    if (bucket == null) continue;
                    rows.add(new String[] {string(bucket, "modelId", "bucketId"), "",
                            String.valueOf(number(bucket, "remainingFraction")),
                            string(bucket, "resetTime")});
                }
            }
            double gemini = Double.NaN;
            double third = Double.NaN;
            String geminiReset = "";
            String thirdReset = "";
            for (String[] row : rows) {
                double fraction = Double.parseDouble(row[2]);
                if (Double.isNaN(fraction)) continue;
                String family = antigravityFamily(row[0] + " " + row[1]);
                if ("gemini".equals(family) && (Double.isNaN(gemini) || fraction < gemini)) {
                    gemini = fraction;
                    geminiReset = row[3];
                } else if ("third".equals(family) && (Double.isNaN(third) || fraction < third)) {
                    third = fraction;
                    thirdReset = row[3];
                }
            }
            if (!Double.isNaN(gemini)) {
                out.shortWindow = window((1.0 - gemini) * 100.0, 5 * HOUR,
                        epochSeconds(geminiReset), now);
            }
            if (!Double.isNaN(third)) {
                thirdShort = window((1.0 - third) * 100.0, 5 * HOUR, epochSeconds(thirdReset), now);
            }
        }
        if (thirdShort != null || thirdWeekly != null) {
            UsageWindow primary = thirdShort != null ? thirdShort : thirdWeekly;
            UsageWindow secondary = thirdShort != null ? thirdWeekly : null;
            boolean reached = primary.usedPercent >= 100
                    || (secondary != null && secondary.usedPercent >= 100);
            out.extras.add(0, new UsageLimit("third_party", "Claude and GPT models", "",
                    !reached, reached, primary, secondary));
        }
        return out.build();
    }

    /** "gemini", "third" (Claude, GPT and other partner models) or "" for anything else. */
    private static String antigravityFamily(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (value.contains("claude") || value.contains("gpt") || value.startsWith("3p")
                || value.contains("third")) {
            return "third";
        }
        // A versioned family such as "Gemini 4 Argon" is a separate quota with its own name.
        if (value.contains("gemini") && !value.contains("argon")) return "gemini";
        return "";
    }

    private static boolean antigravityWeekly(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return value.contains("week") || value.contains("7d") || value.contains("7-day");
    }

    /** {@code remaining} is {@code {remainingFraction}} or a oneof {@code {case, value}}. */
    private static double antigravityFraction(JSONObject bucket) {
        double fraction = number(bucket, "remainingFraction", "remaining_fraction");
        if (!Double.isNaN(fraction)) return fraction;
        JSONObject remaining = bucket.optJSONObject("remaining");
        if (remaining == null) return Double.NaN;
        fraction = number(remaining, "remainingFraction", "remaining_fraction");
        if (!Double.isNaN(fraction)) return fraction;
        return "remainingFraction".equals(remaining.optString("case"))
                ? number(remaining, "value") : Double.NaN;
    }

    /** True when the account's OAuth client can't read consumer quota (sign in again). */
    public static boolean antigravityClientRejected(String body) {
        JSONObject root = object(body);
        if (root.has("currentTier") || !antigravityProject(body).isEmpty()) return false;
        JSONArray ineligible = root.optJSONArray("ineligibleTiers");
        if (ineligible == null) return false;
        for (int i = 0; i < ineligible.length(); i++) {
            JSONObject tier = ineligible.optJSONObject(i);
            if (tier != null && "GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT".equals(
                    tier.optString("reasonCode"))) {
                return true;
            }
        }
        return false;
    }

    /** The tier to onboard an account that has no Code Assist project yet. */
    public static String antigravityOnboardTier(String body) {
        JSONObject root = object(body);
        JSONArray allowed = root.optJSONArray("allowedTiers");
        if (allowed != null) {
            for (int i = 0; i < allowed.length(); i++) {
                JSONObject tier = allowed.optJSONObject(i);
                if (tier != null && tier.optBoolean("isDefault") && !string(tier, "id").isEmpty()) {
                    return string(tier, "id");
                }
            }
            for (int i = 0; i < allowed.length(); i++) {
                String id = string(allowed.optJSONObject(i), "id");
                if (!id.isEmpty()) return id;
            }
        }
        String paid = string(root.optJSONObject("paidTier"), "id");
        return paid.isEmpty() ? string(root.optJSONObject("currentTier"), "id") : paid;
    }

    /** Plan from {@code v1internal:loadCodeAssist}. */
    public static String antigravityPlan(String body) {
        JSONObject root = object(body);
        JSONObject paid = root.optJSONObject("paidTier");
        String id = string(paid, "id").toLowerCase(Locale.ROOT);
        if (id.contains("ultra-lite") || id.contains("ultra_lite")) return "Ultra Lite";
        if (id.contains("ultra")) return "Ultra";
        if (id.contains("pro")) return "Pro";
        String name = string(paid, "name");
        if (name.isEmpty()) name = string(root.optJSONObject("currentTier"), "name");
        return name.replaceFirst("(?i)^google\\s+(ai\\s+)?", "");
    }

    /** Google Cloud project the account's Code Assist quota is billed to, when given. */
    public static String antigravityProject(String body) {
        JSONObject root = object(body);
        // onboardUser wraps its answer in "response".
        if (!root.has("cloudaicompanionProject") && root.optJSONObject("response") != null) {
            root = root.optJSONObject("response");
        }
        Object project = root.opt("cloudaicompanionProject");
        if (project instanceof JSONObject) {
            return string((JSONObject) project, "id", "projectId", "name");
        }
        return project == null || project == JSONObject.NULL ? "" : String.valueOf(project);
    }

    // ---------------------------------------------------------------- Devin and Windsurf

    /** Codeium {@code GetUserStatus}: daily and weekly quota for Devin and Windsurf plans. */
    public static UsageSnapshot codeium(String body, long now) {
        JSONObject root = object(body);
        JSONObject status = root.optJSONObject("userStatus");
        if (status == null) status = root;
        JSONObject plan = status.optJSONObject("planStatus");
        if (plan == null) plan = status;
        Builder out = new Builder(now);
        out.plan = string(plan.optJSONObject("planInfo"), "planName");
        if (out.plan.isEmpty()) out.plan = string(status, "teamsTier", "planName");
        boolean hideDaily = plan.optJSONObject("planInfo") != null
                && plan.optJSONObject("planInfo").optBoolean("hideDailyQuota", false);
        if (!hideDaily) {
            UsageWindow daily = codeiumWindow(plan, "dailyQuotaRemainingPercent",
                    "dailyQuotaResetAtUnix", DAY, now);
            out.add("Daily quota", daily);
        }
        out.weekly = codeiumWindow(plan, "weeklyQuotaRemainingPercent", "weeklyQuotaResetAtUnix",
                WEEK, now);
        double micros = number(plan, "overageBalanceMicros");
        if (!Double.isNaN(micros) && micros > 0) out.credits = balance(micros / 1_000_000.0);
        // Older plans meter prompt credits per month instead.
        if (out.weekly == null && out.extras.isEmpty()) {
            double available = number(plan, "availablePromptCredits");
            double used = number(plan, "usedPromptCredits");
            if (!Double.isNaN(available) && available > 0) {
                out.monthly = window(percentUsed(available, used, Double.NaN), MONTH,
                        epochSeconds(first(plan, "planEnd")), now);
            }
        }
        return out.build();
    }

    private static UsageWindow codeiumWindow(JSONObject plan, String percentKey, String resetKey,
            long seconds, long now) {
        double remaining = number(plan, percentKey);
        long reset = epochSeconds(plan.opt(resetKey));
        // proto3 omits zero values: a reset time with no percent means nothing is left.
        if (Double.isNaN(remaining) && reset > 0) remaining = 0;
        if (Double.isNaN(remaining)) return null;
        return window(100.0 - remaining, seconds, reset, now);
    }

    // ---------------------------------------------------------------- Augment

    /** {@code <tenant>/get-billing-summary}: credits included per billing cycle. */
    public static UsageSnapshot augment(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = string(root, "plan_name", "planName");
        double included = number(root, "amount_included_per_cycle", "credits_included");
        double remaining = number(root, "amount_remaining", "credits_remaining");
        long reset = epochSeconds(first(root, "billing_cycle_end_date_iso",
                "billing_cycle_end_date", "cycle_end"));
        UsageWindow window = window(percentUsed(included, Double.NaN, remaining), MONTH, reset,
                now);
        out.monthly = window;
        if (window == null && !Double.isNaN(remaining)) {
            String unit = string(root, "usage_unit");
            out.credits = new UsageCredits(true, false, BigDecimal.valueOf(remaining)
                    .setScale(0, RoundingMode.DOWN).toPlainString()
                    + (unit.isEmpty() ? " credits" : " " + unit.toLowerCase(Locale.ROOT)));
        }
        return out.build();
    }

    // ---------------------------------------------------------------- Cline

    /** {@code api.cline.bot/api/v1/users/me/plan/usage-limits}. */
    public static UsageSnapshot cline(String body, long now) {
        JSONObject root = object(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;
        Builder out = new Builder(now);
        out.plan = titleCase(string(data, "planName", "plan"));
        JSONArray limits = data.optJSONArray("limits");
        if (limits != null) {
            for (int i = 0; i < limits.length(); i++) {
                JSONObject entry = limits.optJSONObject(i);
                if (entry == null) continue;
                String type = string(entry, "type", "window").toLowerCase(Locale.ROOT);
                long seconds = type.contains("five") || type.contains("5h") ? 5 * HOUR
                        : type.contains("week") ? WEEK : type.contains("month") ? MONTH
                        : type.contains("day") ? DAY : 0L;
                out.add(seconds > 0 ? durationName(seconds) + " limit" : titleCase(type),
                        window(number(entry, "percentUsed", "usedPercent"), seconds,
                                epochSeconds(first(entry, "resetsAt", "resetAt")), now));
            }
        }
        return out.build();
    }

    // ---------------------------------------------------------------- OpenCode Go

    /** {@code opencode.ai/zen/go/v1/usage}: rolling, weekly and monthly percentages. */
    public static UsageSnapshot opencodeGo(String body, long now) {
        JSONObject root = object(body);
        JSONObject usage = root.optJSONObject("usage");
        if (usage == null) usage = root;
        Builder out = new Builder(now);
        out.plan = titleCase(string(root, "plan", "tier"));
        String[][] windows = {{"rolling", "5h", "fiveHour"}, {"weekly", "week", "7d"},
                {"monthly", "month", "30d"}};
        long[] lengths = {5 * HOUR, WEEK, MONTH};
        for (int i = 0; i < windows.length; i++) {
            JSONObject entry = null;
            for (String key : windows[i]) {
                entry = usage.optJSONObject(key);
                if (entry != null) break;
            }
            if (entry == null) continue;
            double percent = number(entry, PERCENT_KEYS);
            if (!Double.isNaN(percent) && percent <= 1.0 && entry.has("utilization")) {
                percent *= 100.0;
            }
            long reset = resetFrom(entry, now);
            out.add(durationName(lengths[i]) + " limit", window(percent, lengths[i], reset, now));
        }
        return out.build();
    }

    static final String[] PERCENT_KEYS = {"usagePercent", "usedPercent", "percentUsed",
            "percent", "usage_percent", "used_percent", "percent_used", "utilization"};
    static final String[] RESET_IN_KEYS = {"resetInSec", "resetInSeconds", "reset_in_sec",
            "reset_in_seconds", "resetsInSeconds", "secondsUntilReset", "secondsRemaining"};
    static final String[] RESET_AT_KEYS = {"resetAt", "resetsAt", "reset_at", "resets_at",
            "nextReset", "next_reset", "renewAt", "renew_at", "resetTime", "reset_time",
            "windowEnd", "endTime", "end_time", "nextResetTime"};

    static long resetFrom(JSONObject entry, long now) {
        long at = epochSeconds(first(entry, RESET_AT_KEYS));
        if (at > 0) return at;
        double in = number(entry, RESET_IN_KEYS);
        return Double.isNaN(in) || in <= 0 ? 0L : now / 1000L + (long) in;
    }

    // ---------------------------------------------------------------- MiniMax

    /**
     * {@code api.minimax.io/v1/token_plan/remains}: prompts left in the current interval for
     * each coding-plan model. MiniMax names the remaining count {@code usage_count}.
     */
    public static UsageSnapshot minimax(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        JSONObject base = root.optJSONObject("base_resp");
        if (base != null && base.optInt("status_code", 0) != 0) {
            return out.build();
        }
        // The token plan API wraps its answer in "data"; the coding plan one doesn't.
        JSONObject data = root.optJSONObject("data");
        if (data != null && !root.has("model_remains")) root = data;
        out.plan = minimaxPlan(root);
        JSONArray models = root.optJSONArray("model_remains");
        if (models == null) return out.build();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model == null) continue;
            String name = string(model, "model_name");
            double percentLeft = number(model, "current_interval_remaining_percent");
            double used = !Double.isNaN(percentLeft) ? 100.0 - percentLeft
                    : percentUsed(number(model, "current_interval_total_count"), Double.NaN,
                            number(model, "current_interval_usage_count"));
            long seconds = span(model.opt("start_time"), model.opt("end_time"), 5 * HOUR);
            long reset = epochSeconds(model.opt("end_time"));
            if (reset <= 0) {
                double remainsMillis = number(model, "remains_time");
                if (!Double.isNaN(remainsMillis)) reset = (now + (long) remainsMillis) / 1000L;
            }
            UsageWindow interval = window(used, seconds, reset, now);
            double weeklyLeft = number(model, "current_weekly_remaining_percent");
            double weeklyUsed = !Double.isNaN(weeklyLeft) ? 100.0 - weeklyLeft
                    : percentUsed(number(model, "current_weekly_total_count"), Double.NaN,
                            number(model, "current_weekly_usage_count"));
            UsageWindow weekly = window(weeklyUsed, WEEK,
                    epochSeconds(model.opt("weekly_end_time")), now);
            if (i == 0) {
                out.add("Prompts", interval);
                if (weekly != null) out.weekly = weekly;
            } else {
                out.extra(name.isEmpty() ? "Model " + (i + 1) : name, interval);
            }
        }
        return out.build();
    }

    // ---------------------------------------------------------------- Z.ai

    static String minimaxPlan(JSONObject root) {
        String plan = string(root, "current_subscribe_title", "plan_name", "combo_title",
                "current_plan_title", "current_plan");
        if (plan.isEmpty()) {
            plan = string(root.optJSONObject("current_subscribe"), "current_subscribe_title");
        }
        if (plan.isEmpty()) plan = string(root.optJSONObject("current_combo_card"), "title");
        return plan;
    }

    /** {@code api.z.ai/api/monitor/usage/quota/limit}: token windows plus the web-tool quota. */
    public static UsageSnapshot zai(String body, long now) {
        JSONObject root = object(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;
        Builder out = new Builder(now);
        out.plan = titleCase(string(data, "planName", "plan", "level"));
        JSONArray limits = data.optJSONArray("limits");
        if (limits == null) return out.build();
        for (int i = 0; i < limits.length(); i++) {
            JSONObject entry = limits.optJSONObject(i);
            if (entry == null) continue;
            String type = string(entry, "type").toUpperCase(Locale.ROOT);
            long seconds = zaiWindowSeconds(entry.optInt("unit", 0), entry.optInt("number", 0));
            double percent = number(entry, "percentage");
            if (Double.isNaN(percent)) {
                percent = percentUsed(number(entry, "usage", "total"), number(entry,
                        "currentValue"), number(entry, "remaining"));
            }
            UsageWindow window = window(percent, seconds, epochSeconds(entry.opt("nextResetTime")),
                    now);
            if (type.contains("TIME")) {
                out.extra("Web search and reader", window);
            } else {
                out.add(seconds > 0 ? durationName(seconds) + " limit" : titleCase(type), window);
            }
        }
        return out.build();
    }

    /** Z.ai's unit codes: 1 day, 3 hour, 5 minute, 6 week. */
    static long zaiWindowSeconds(int unit, int number) {
        long base;
        switch (unit) {
            case 1:
                base = DAY;
                break;
            case 3:
                base = HOUR;
                break;
            case 5:
                base = 60L;
                break;
            case 6:
                base = WEEK;
                break;
            default:
                return 0L;
        }
        return base * Math.max(1, number);
    }

    // ---------------------------------------------------------------- Kiro

    /** CodeWhisperer {@code GetUsageLimits}: monthly credits, plus any other metered resource. */
    public static UsageSnapshot kiro(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = string(root.optJSONObject("subscriptionInfo"), "subscriptionTitle", "type");
        out.plan = out.plan.replaceFirst("(?i)^kiro\\s+", "");
        JSONArray list = root.optJSONArray("usageBreakdownList");
        if (list == null) list = root.optJSONArray("limits");
        long fallbackReset = epochSeconds(first(root, "nextDateReset", "resetDate"));
        if (list != null) {
            // Kiro's credits (resourceType CREDIT) are the plan's main meter; list them first.
            // Only the first CREDIT entry moves, as on iOS and Windows.
            List<JSONObject> entries = new ArrayList<>();
            boolean creditFirst = false;
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry == null) continue;
                if (!creditFirst && "CREDIT".equalsIgnoreCase(string(entry, "resourceType"))) {
                    entries.add(0, entry);
                    creditFirst = true;
                } else {
                    entries.add(entry);
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                JSONObject entry = entries.get(i);
                double limit = number(entry, "usageLimitWithPrecision", "usageLimit", "limit");
                double used = number(entry, "currentUsageWithPrecision", "currentUsage", "used");
                long reset = epochSeconds(first(entry, "nextDateReset", "resetDate"));
                UsageWindow window = window(percentUsed(limit, used, Double.NaN), MONTH,
                        reset > 0 ? reset : fallbackReset, now);
                String name = titleCase(string(entry, "displayName", "resourceType"));
                if (out.monthly == null) {
                    out.monthly = window;
                } else {
                    out.extra(name.isEmpty() ? "Limit " + (i + 1) : name, window);
                }
                JSONObject trial = entry.optJSONObject("freeTrialInfo");
                if (trial != null) {
                    out.extra("Free trial", window(percentUsed(number(trial,
                            "usageLimitWithPrecision", "usageLimit"), number(trial,
                            "currentUsageWithPrecision", "currentUsage"), Double.NaN), MONTH,
                            epochSeconds(trial.opt("freeTrialExpiry")), now));
                }
            }
        }
        return out.build();
    }

    // ---------------------------------------------------------------- OpenRouter

    /** {@code /api/v1/key} (spend limit) plus {@code /api/v1/credits} (balance). */
    public static UsageSnapshot openrouter(String keyBody, String creditsBody, long now) {
        JSONObject key = object(keyBody);
        JSONObject data = key.optJSONObject("data");
        if (data == null) data = key;
        Builder out = new Builder(now);
        out.plan = data.optBoolean("is_free_tier", false) ? "Free" : "";
        double limit = number(data, "limit");
        if (!Double.isNaN(limit) && limit > 0) {
            String resetPeriod = string(data, "limit_reset").toLowerCase(Locale.ROOT);
            long seconds = resetPeriod.contains("day") ? DAY : resetPeriod.contains("week") ? WEEK
                    : resetPeriod.contains("month") ? MONTH : 0L;
            double used = seconds == DAY ? number(data, "usage_daily", "usage")
                    : seconds == WEEK ? number(data, "usage_weekly", "usage")
                    : seconds == MONTH ? number(data, "usage_monthly", "usage")
                    : number(data, "usage");
            double remaining = number(data, "limit_remaining");
            out.add("Key limit", window(percentUsed(limit, used, remaining), seconds,
                    nextPeriodStart(seconds, now), now));
        }
        JSONObject credits = object(creditsBody).optJSONObject("data");
        if (credits != null) {
            double total = number(credits, "total_credits");
            double used = number(credits, "total_usage");
            if (!Double.isNaN(total)) {
                out.credits = balance(total - (Double.isNaN(used) ? 0 : used));
            }
        }
        return out.build();
    }

    /** OpenRouter limits reset at midnight UTC (daily), Monday (weekly) or the 1st (monthly). */
    static long nextPeriodStart(long seconds, long now) {
        if (seconds <= 0) return 0L;
        LocalDate today = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate next;
        if (seconds == DAY) {
            next = today.plusDays(1);
        } else if (seconds == WEEK) {
            next = today.plusDays(8 - today.getDayOfWeek().getValue());
        } else {
            next = today.withDayOfMonth(1).plusMonths(1);
        }
        return next.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- Amp

    private static final String AMOUNT = "([0-9][0-9,]*(?:\\.[0-9]+)?)";
    private static final Pattern AMP_FREE = Pattern.compile("Amp Free:\\s*\\$\\s*" + AMOUNT
            + "\\s*/\\s*\\$\\s*" + AMOUNT + "\\s*remaining", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMP_FREE_PERCENT = Pattern.compile("Amp Free:\\s*" + AMOUNT
            + "\\s*%\\s*remaining", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMP_TIER = Pattern.compile("Amp\\s+(.+?)\\s+(?:Tier|Subscription)"
            + ":\\s*agent usage\\s*\\$\\s*" + AMOUNT + "\\s*of\\s*\\$\\s*" + AMOUNT
            + "\\s*remaining", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMP_ORB = Pattern.compile("orb usage\\s*" + AMOUNT
            + "\\s*h\\s*of\\s*" + AMOUNT + "\\s*h", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMP_PERIOD = Pattern.compile(
            "period\\s+(\\d{4}-\\d{2}-\\d{2})\\s+to\\s+(\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMP_CREDITS = Pattern.compile("(?:Individual credits:\\s*\\$\\s*"
            + AMOUNT + "\\s*remaining|\\$\\s*" + AMOUNT + "\\s*(?:in\\s+)?(?:individual\\s+)?"
            + "credits)", Pattern.CASE_INSENSITIVE);

    /**
     * {@code ampcode.com/api/internal?userDisplayBalanceInfo}: Amp returns the text the CLI's
     * {@code amp usage} prints. Amp Free is a daily allowance that resets at 8 PM New York
     * time (older replies give dollars, newer ones a percentage); paid tiers report agent
     * dollars and orb hours for the billing period; individual credits are a dollar balance.
     */
    public static UsageSnapshot amp(String body, long now) {
        JSONObject root = object(body);
        JSONObject result = root.optJSONObject("result");
        String text = result != null ? string(result, "displayText", "text") : "";
        if (text.isEmpty()) text = string(root, "displayText", "result");
        if (text.isEmpty()) text = body == null ? "" : body;
        Builder out = new Builder(now);
        Matcher free = AMP_FREE.matcher(text);
        Matcher freePercent = AMP_FREE_PERCENT.matcher(text);
        if (free.find()) {
            out.plan = "Free";
            out.add("Free usage", window(percentUsed(parseAmount(free.group(2)), Double.NaN,
                    parseAmount(free.group(1))), DAY, ampFreeReset(now), now));
        } else if (freePercent.find()) {
            out.plan = "Free";
            out.add("Free usage", window(100.0 - parseAmount(freePercent.group(1)), DAY,
                    ampFreeReset(now), now));
        }
        Matcher tier = AMP_TIER.matcher(text);
        if (tier.find()) {
            out.plan = titleCase(tier.group(1));
            long seconds = MONTH;
            long reset = 0L;
            Matcher period = AMP_PERIOD.matcher(text);
            if (period.find()) {
                seconds = span(period.group(1), period.group(2), MONTH);
                reset = epochSeconds(period.group(2));
            }
            UsageWindow agent = window(percentUsed(parseAmount(tier.group(3)), Double.NaN,
                    parseAmount(tier.group(2))), seconds, reset, now);
            if (seconds >= 27 * DAY && seconds <= 32 * DAY) {
                out.monthly = agent;
            } else {
                out.extra("Agent usage", agent);
            }
            Matcher orb = AMP_ORB.matcher(text);
            if (orb.find()) {
                out.extra("Orb hours", window(percentUsed(parseAmount(orb.group(2)), Double.NaN,
                        parseAmount(orb.group(1))), seconds, reset, now));
            }
        }
        Matcher credits = AMP_CREDITS.matcher(text);
        if (credits.find()) {
            String amount = credits.group(1) != null ? credits.group(1) : credits.group(2);
            out.credits = balance(parseAmount(amount));
        }
        return out.build();
    }

    /** Amp Free refills at 8 PM in New York. */
    static long ampFreeReset(long now) {
        java.time.ZoneId newYork = java.time.ZoneId.of("America/New_York");
        java.time.ZonedDateTime current = Instant.ofEpochMilli(now).atZone(newYork);
        java.time.ZonedDateTime reset = current.toLocalDate().atTime(20, 0).atZone(newYork);
        if (!reset.isAfter(current)) reset = reset.plusDays(1);
        return reset.toEpochSecond();
    }

    private static double parseAmount(String text) {
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------- balance-only providers

    /**
     * Kilo's tRPC batch, as the web app and CodexBar read it: {@code user.getCreditBlocks} gives
     * the credit balance, {@code kiloPass.getState} the Kilo Pass month (base plus bonus credits).
     */
    public static UsageSnapshot kiloTrpc(String body, long now) {
        Builder out = new Builder(now);
        JSONArray entries;
        try {
            entries = new JSONArray(body == null ? "" : body);
        } catch (Exception notArray) {
            entries = new JSONArray().put(object(body));
        }
        JSONObject blocks = kiloPayload(entries.optJSONObject(0));
        JSONObject pass = kiloPayload(entries.optJSONObject(1));
        if (blocks != null) {
            JSONArray list = blocks.optJSONArray("creditBlocks");
            double micros = Double.NaN;
            if (list != null && list.length() > 0) {
                micros = 0;
                for (int i = 0; i < list.length(); i++) {
                    double value = number(list.optJSONObject(i), "balance_mUsd");
                    if (!Double.isNaN(value)) micros += value;
                }
            }
            if (Double.isNaN(micros)) micros = number(blocks, "totalBalance_mUsd");
            if (!Double.isNaN(micros)) out.credits = balance(Math.max(0, micros) / 1_000_000.0);
        }
        JSONObject subscription = pass == null ? null : pass.optJSONObject("subscription");
        if (subscription != null) {
            String tier = string(subscription, "tier");
            out.plan = "tier_19".equals(tier) ? "Starter" : "tier_49".equals(tier) ? "Pro"
                    : "tier_199".equals(tier) ? "Expert" : tier.isEmpty() ? "Kilo Pass" : tier;
            double used = number(subscription, "currentPeriodUsageUsd");
            double base = number(subscription, "currentPeriodBaseCreditsUsd");
            double bonus = number(subscription, "currentPeriodBonusCreditsUsd");
            double total = Double.isNaN(base) ? Double.NaN
                    : base + (Double.isNaN(bonus) ? 0 : Math.max(0, bonus));
            out.monthly = window(percentUsed(total, used, Double.NaN), MONTH, epochSeconds(
                    first(subscription, "nextBillingAt", "nextRenewalAt", "renewsAt", "renewAt")),
                    now);
        }
        return out.build();
    }

    /** True when a Kilo tRPC entry is an auth error. */
    public static boolean kiloUnauthorized(String body) {
        String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return text.contains("\"unauthorized\"") || text.contains("\"forbidden\"");
    }

    private static JSONObject kiloPayload(JSONObject entry) {
        JSONObject result = entry == null ? null : entry.optJSONObject("result");
        if (result == null) return null;
        JSONObject data = result.optJSONObject("data");
        if (data != null) {
            JSONObject json = data.optJSONObject("json");
            return json != null ? json : data.has("json") ? null : data;
        }
        return result.optJSONObject("json");
    }

    /** Kilo {@code /api/profile/balance}: a dollar balance. */
    public static UsageSnapshot kilo(String body, long now) {
        JSONObject root = object(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;
        Builder out = new Builder(now);
        double dollars = number(data, "balance", "balanceUsd", "credits");
        if (Double.isNaN(dollars)) {
            double micros = number(data, "balance_mUsd", "microdollars");
            if (!Double.isNaN(micros)) dollars = micros / 1_000_000.0;
        }
        if (!Double.isNaN(dollars)) out.credits = balance(dollars);
        return out.build();
    }

    /** Codebuff {@code /api/v1/usage}: credits used against the cycle quota. */
    public static UsageSnapshot codebuff(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = titleCase(string(root, "subscriptionTier", "plan", "tier"));
        double quota = number(root, "quota", "limit");
        double used = number(root, "usage", "used");
        long reset = epochSeconds(first(root, "next_quota_reset", "nextQuotaReset", "resetAt"));
        out.monthly = window(percentUsed(quota, used, Double.NaN), MONTH, reset, now);
        double balance = number(root, "remainingBalance", "balance");
        if (!Double.isNaN(balance) && out.monthly == null) {
            out.credits = new UsageCredits(true, false,
                    BigDecimal.valueOf(balance).setScale(0, RoundingMode.DOWN).toPlainString()
                            + " credits");
        }
        return out.build();
    }

    /** {@code /api/user/subscription}: the weekly rate limit and tier, added to the usage. */
    public static UsageSnapshot codebuffSubscription(UsageSnapshot usage, String body, long now) {
        JSONObject root = object(body);
        JSONObject subscription = root.optJSONObject("subscription");
        JSONObject limit = root.optJSONObject("rateLimit");
        String plan = string(subscription, "displayName");
        if (plan.isEmpty()) plan = string(root, "displayName");
        if (plan.isEmpty()) plan = titleCase(string(subscription, "tier"));
        UsageWindow weekly = window(percentUsed(number(limit, "weeklyLimit", "limit"),
                number(limit, "weeklyUsed", "used"), Double.NaN), WEEK,
                epochSeconds(first(limit, "weeklyResetsAt")), now);
        if (weekly == null && plan.isEmpty()) return usage;
        Builder out = new Builder(now);
        out.plan = plan.isEmpty() || plan.matches("\\d+") ? usage.planType : plan;
        out.shortWindow = usage.fiveHour;
        out.weekly = weekly != null ? weekly : usage.weekly;
        out.monthly = usage.monthly;
        out.extras.addAll(usage.additionalLimits);
        out.credits = usage.usageCredits;
        return out.build();
    }

    // ---------------------------------------------------------------- Synthetic

    /**
     * {@code api.synthetic.new/v2/quotas} (documented at dev.synthetic.new/docs/synthetic/quotas).
     * Current plans meter weekly credits in {@code planWeeklyLimit}, whose
     * {@code percentRemaining} is in percentage points and regenerates continuously, so
     * {@code nextRegenAt} is when the next slice comes back. Older plans report
     * {@code rollingFiveHourLimit} and {@code weeklyTokenLimit} as fractions, and the retired
     * {@code subscription} request counter is read only when nothing else is there. The search
     * tool has its own hourly allowance.
     */
    public static UsageSnapshot synthetic(String body, long now) {
        JSONObject root = object(body);
        if (root.length() == 0 && body != null && body.trim().startsWith("[")) {
            try {
                root = new JSONObject().put("quotas", new JSONArray(body.trim()));
            } catch (Exception ignored) {
                // Not JSON at all.
            }
        }
        JSONObject data = root.optJSONObject("data");
        JSONObject source = data != null && !root.has("planWeeklyLimit")
                && !root.has("rollingFiveHourLimit") && !root.has("weeklyTokenLimit") ? data : root;
        Builder out = new Builder(now);
        out.plan = titleCase(string(source, "plan", "planName", "plan_name", "tier"));
        if (out.plan.isEmpty()) out.plan = titleCase(string(root, "plan", "planName", "tier"));
        JSONObject planWeekly = source.optJSONObject("planWeeklyLimit");
        if (planWeekly != null) {
            out.weekly = window(quotaUsed(planWeekly, true), WEEK, quotaReset(planWeekly, now),
                    now);
        } else {
            JSONObject rolling = source.optJSONObject("rollingFiveHourLimit");
            if (rolling != null) {
                out.shortWindow = window(quotaUsed(rolling, false), 5 * HOUR,
                        quotaReset(rolling, now), now);
            }
            JSONObject weekly = source.optJSONObject("weeklyTokenLimit");
            if (weekly != null) {
                out.weekly = window(quotaUsed(weekly, false), WEEK, quotaReset(weekly, now), now);
            }
        }
        JSONObject search = path(source, "search", "hourly");
        if (search != null) {
            out.extra("Search (hourly)", window(quotaUsed(search, false), HOUR,
                    quotaReset(search, now), now));
        }
        JSONObject subscription = source.optJSONObject("subscription");
        if (out.shortWindow == null && out.weekly == null && subscription != null) {
            out.shortWindow = window(quotaUsed(subscription, false), 5 * HOUR,
                    quotaReset(subscription, now), now);
        }
        return out.build();
    }

    static final String[] QUOTA_LIMIT_KEYS = {"limit", "max", "total", "quota", "cap",
            "requestLimit", "messageLimit", "maxRequests"};
    static final String[] QUOTA_USED_KEYS = {"used", "usage", "requests", "requestCount",
            "consumed", "spent", "messagesUsed"};
    static final String[] QUOTA_LEFT_KEYS = {"remaining", "left", "available"};
    static final String[] QUOTA_LEFT_PERCENT_KEYS = {"percentRemaining", "remainingPercent",
            "remaining_percent", "percent_remaining"};
    static final String[] QUOTA_RESET_KEYS = {"nextRegenAt", "next_regen_at", "renewsAt",
            "renews_at", "periodEnd", "period_end"};

    static long quotaReset(JSONObject quota, long now) {
        long at = epochSeconds(first(quota, QUOTA_RESET_KEYS));
        return at > 0 ? at : resetFrom(quota, now);
    }

    /**
     * Percent used of one quota object. Percent fields win over counts; {@code points} says
     * the percentages are already 0–100 (otherwise values up to 1 are read as fractions).
     */
    static double quotaUsed(JSONObject quota, boolean points) {
        double used = number(quota, "percentUsed", "usedPercent", "usagePercent", "used_percent");
        double left = number(quota, QUOTA_LEFT_PERCENT_KEYS);
        if (Double.isNaN(used) && !Double.isNaN(left)) {
            used = 100.0 - (!points && left <= 1.0 ? left * 100.0 : left);
        } else if (!Double.isNaN(used) && !points && used <= 1.0) {
            used *= 100.0;
        }
        if (!Double.isNaN(used)) return used;
        double limit = number(quota, QUOTA_LIMIT_KEYS);
        double count = number(quota, QUOTA_USED_KEYS);
        double remaining = number(quota, QUOTA_LEFT_KEYS);
        if (Double.isNaN(limit) && !Double.isNaN(count) && !Double.isNaN(remaining)) {
            limit = count + remaining;
        }
        return percentUsed(limit, count, remaining);
    }

    // ---------------------------------------------------------------- Chutes

    /**
     * {@code api.chutes.ai/users/me/subscription_usage}, from Chutes' own API source
     * ({@code api/user/router.py}): a rolling 4-hour cap and a monthly cap, both in dollars of
     * pay-as-you-go value, or {@code {"subscription": false}} without a plan. Custom plans
     * only enforce the 4-hour cap and report {@code monthly.uncapped}.
     */
    public static UsageSnapshot chutes(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        if (!root.optBoolean("subscription", false)) return out.build();
        double price = number(root, "monthly_price");
        if (root.optBoolean("custom", false)) {
            out.plan = "Custom";
        } else if (!Double.isNaN(price) && price > 0) {
            out.plan = money(price).replace(".00", "") + "/month";
        }
        JSONObject fourHour = root.optJSONObject("four_hour");
        if (fourHour != null) {
            out.shortWindow = window(percentUsed(number(fourHour, "cap"),
                    number(fourHour, "usage"), number(fourHour, "remaining")), 4 * HOUR,
                    epochSeconds(fourHour.opt("reset_at")), now);
        }
        JSONObject monthly = root.optJSONObject("monthly");
        if (monthly != null && !monthly.optBoolean("uncapped", false)) {
            out.monthly = window(percentUsed(number(monthly, "cap"), number(monthly, "usage"),
                    number(monthly, "remaining")), MONTH, epochSeconds(monthly.opt("reset_at")),
                    now);
        }
        return out.build();
    }

    // ---------------------------------------------------------------- v0

    /**
     * The v0 Platform API ({@code v0.app/docs/api/v1}): {@code /v1/user/billing} gives the
     * credit balance for the billing cycle (token billing) or a legacy allowance,
     * {@code /v1/user/plan} names the plan, and {@code /v1/rate-limits} gives the request
     * limit plus, for free accounts, the daily message limit.
     */
    public static UsageSnapshot v0(String billingBody, String planBody, String rateBody,
            long now) {
        JSONObject billing = object(billingBody);
        JSONObject plan = object(planBody);
        JSONObject rate = object(rateBody);
        Builder out = new Builder(now);
        out.plan = titleCase(string(plan, "plan"));
        JSONObject data = billing.optJSONObject("data");
        UsageWindow credits = null;
        if (data != null && data.optJSONObject("balance") != null) {
            JSONObject balance = data.optJSONObject("balance");
            JSONObject cycle = data.optJSONObject("billingCycle");
            credits = window(percentUsed(number(balance, "total"), Double.NaN,
                    number(balance, "remaining")), cycle == null ? MONTH
                    : span(cycle.opt("start"), cycle.opt("end"), MONTH),
                    cycle == null ? 0L : epochSeconds(cycle.opt("end")), now);
            JSONObject onDemand = data.optJSONObject("onDemand");
            double onDemandBalance = number(onDemand, "balance");
            if (!Double.isNaN(onDemandBalance) && onDemandBalance > 0) {
                out.credits = balance(onDemandBalance);
            }
        } else if (data != null) {
            credits = window(percentUsed(number(data, "limit"), Double.NaN,
                    number(data, "remaining")), MONTH, epochSeconds(data.opt("reset")), now);
        }
        if (credits == null) {
            JSONObject balance = plan.optJSONObject("balance");
            JSONObject cycle = plan.optJSONObject("billingCycle");
            credits = window(percentUsed(number(balance, "total"), Double.NaN,
                    number(balance, "remaining")), cycle == null ? MONTH
                    : span(cycle.opt("start"), cycle.opt("end"), MONTH),
                    cycle == null ? 0L : epochSeconds(cycle.opt("end")), now);
        }
        if (credits != null) {
            if (credits.windowSeconds >= 27 * DAY && credits.windowSeconds <= 32 * DAY) {
                out.monthly = credits;
            } else {
                out.extra("Credits", credits);
            }
        }
        JSONObject daily = rate.optJSONObject("dailyLimit");
        if (daily != null && !daily.optBoolean("isWithinGracePeriod", false)) {
            out.shortWindow = window(percentUsed(number(daily, "limit"), Double.NaN,
                    number(daily, "remaining")), DAY, epochSeconds(daily.opt("reset")), now);
        }
        out.extra("API requests", window(percentUsed(number(rate, "limit"), Double.NaN,
                number(rate, "remaining")), DAY, epochSeconds(rate.opt("reset")), now));
        return out.build();
    }

    // ---------------------------------------------------------------- Warp

    /**
     * Warp's {@code GetRequestLimitInfo} GraphQL query: monthly credits (Warp still names them
     * requests) and add-on credit grants for the user and their workspaces.
     */
    public static UsageSnapshot warp(String body, long now) {
        JSONObject user = path(object(body), "data", "user", "user");
        Builder out = new Builder(now);
        if (user == null) return out.build();
        JSONObject info = user.optJSONObject("requestLimitInfo");
        if (info != null && info.optBoolean("isUnlimited", false)) {
            out.plan = "Unlimited";
            out.credits = new UsageCredits(true, true, "");
        } else if (info != null) {
            out.monthly = window(percentUsed(number(info, "requestLimit"),
                    number(info, "requestsUsedSinceLastRefresh"), Double.NaN), MONTH,
                    epochSeconds(info.opt("nextRefreshTime")), now);
        }
        double remaining = 0;
        boolean anyGrant = false;
        List<JSONArray> grantLists = new ArrayList<>();
        if (user.optJSONArray("bonusGrants") != null) {
            grantLists.add(user.optJSONArray("bonusGrants"));
        }
        JSONArray workspaces = user.optJSONArray("workspaces");
        if (workspaces != null) {
            for (int i = 0; i < workspaces.length(); i++) {
                JSONObject grantInfo = path(workspaces.optJSONObject(i), "bonusGrantsInfo");
                JSONArray grants = grantInfo == null ? null : grantInfo.optJSONArray("grants");
                if (grants != null) grantLists.add(grants);
            }
        }
        for (JSONArray grants : grantLists) {
            for (int i = 0; i < grants.length(); i++) {
                double left = number(grants.optJSONObject(i), "requestCreditsRemaining");
                if (!Double.isNaN(left) && left > 0) {
                    remaining += left;
                    anyGrant = true;
                }
            }
        }
        if (anyGrant && out.credits == null) {
            out.credits = new UsageCredits(true, false, BigDecimal.valueOf(remaining)
                    .setScale(0, RoundingMode.DOWN).toPlainString() + " add-on credits");
        }
        return out.build();
    }

    /** The first GraphQL error message in a Warp reply, or "". */
    public static String warpError(String body) {
        JSONArray errors = object(body).optJSONArray("errors");
        JSONObject first = errors == null ? null : errors.optJSONObject(0);
        return first == null ? "" : first.optString("message", "");
    }

    // ---------------------------------------------------------------- Devin

    /**
     * {@code app.devin.ai/api/<org>/billing/quota/usage}: daily and weekly quota percentages
     * used, their reset times, and the extra-usage balance.
     */
    public static UsageSnapshot devin(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = titleCase(string(root, "plan_name", "planName", "plan", "tier"));
        if (!root.optBoolean("hide_daily_quota", false)) {
            out.add("Daily quota", window(number(root, "daily_percentage", "dailyPercentage"),
                    DAY, epochSeconds(first(root, "daily_reset_at", "dailyResetAt")), now));
        }
        out.weekly = window(number(root, "weekly_percentage", "weeklyPercentage"), WEEK,
                epochSeconds(first(root, "weekly_reset_at", "weeklyResetAt")), now);
        double overage = number(root, "overage_balance");
        if (Double.isNaN(overage)) {
            double cents = number(root, "overage_balance_cents");
            if (!Double.isNaN(cents)) overage = cents / 100.0;
        }
        if (!Double.isNaN(overage) && overage > 0) out.credits = balance(overage);
        return out.build();
    }

    /** "5-hour", "Weekly", "Daily", "Monthly" or "N-day" for a window length. */
    static String durationName(long seconds) {
        if (seconds <= 0) return "Usage";
        if (seconds % WEEK == 0 && seconds == WEEK) return "Weekly";
        if (seconds >= 27 * DAY && seconds <= 32 * DAY) return "Monthly";
        if (seconds == DAY) return "Daily";
        if (seconds % DAY == 0) return (seconds / DAY) + "-day";
        if (seconds % HOUR == 0) return (seconds / HOUR) + "-hour";
        return Math.max(1, seconds / 60) + "-minute";
    }
}
