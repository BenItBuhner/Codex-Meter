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
 * only) so the self-tests cover every shape. None of these routes is documented publicly; the
 * shapes follow the providers' own CLIs and editors and open-source trackers (CodexBar,
 * quotabar), so every reader is defensive: a missing field leaves a window out rather than
 * failing the whole refresh.
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
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text)
                    .atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return 0L;
        }
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
        double percent = ratio <= 1.0 ? ratio * 100.0 : ratio;
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
            if (!Double.isNaN(ratio)) used = ratio <= 1.0 ? ratio * 100.0 : ratio;
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
            double percent = !Double.isNaN(ratio) ? (ratio <= 1.0 ? ratio * 100.0 : ratio)
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
        // Old SuperGrok before The X Subscription. Check before a bare SUPER (X Super).
        if (value.contains("SUPER_GROK") || value.contains("SUPERGROK")) return "SuperGrok";
        if (value.contains("ULTRA")) return "Ultra";
        if (value.contains("SUPER")) return "Super";
        if (value.contains("PREMIUM_PLUS") || value.contains("PREMIUMPLUS")) return "Premium+";
        if (value.contains("PREMIUM")) return "Premium";
        if (value.contains("PLUS")) return "Plus";
        if (value.contains("FREE")) return "Free";
        return titleCase(value.replaceFirst("^SUBSCRIPTION_TIER_", ""));
    }

    // ---------------------------------------------------------------- Antigravity (Google)

    /**
     * {@code v1internal:retrieveUserQuotaSummary}: buckets per model family. Gemini is the
     * main pair of windows; Claude and other third-party models become an additional limit.
     */
    public static UsageSnapshot antigravity(String body, String plan, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = plan == null ? "" : plan;
        List<JSONObject> buckets = new ArrayList<>();
        collectBuckets(root, buckets);
        UsageWindow thirdShort = null;
        UsageWindow thirdWeekly = null;
        for (JSONObject bucket : buckets) {
            String id = string(bucket, "bucketId", "id", "name").toLowerCase(Locale.ROOT);
            double fraction = number(bucket, "remainingFraction", "remaining_fraction");
            if (Double.isNaN(fraction) && (bucket.has("resetTime") || bucket.has("reset_time"))) {
                // proto3 omits zero: a bucket with a reset but no fraction is used up.
                fraction = 0.0;
            }
            if (Double.isNaN(fraction)) continue;
            boolean weekly = id.contains("week") || id.contains("7d");
            long seconds = weekly ? WEEK : 5 * HOUR;
            UsageWindow window = window((1.0 - fraction) * 100.0, seconds,
                    epochSeconds(first(bucket, "resetTime", "reset_time")), now);
            if (id.startsWith("gemini") && !id.contains("argon")) {
                if (weekly && out.weekly == null) {
                    out.weekly = window;
                } else if (!weekly && out.shortWindow == null) {
                    out.shortWindow = window;
                }
            } else if (id.startsWith("3p") || id.contains("third")) {
                if (weekly) {
                    thirdWeekly = window;
                } else {
                    thirdShort = window;
                }
            } else {
                String label = string(bucket, "displayName", "label");
                out.extra(label.isEmpty() ? titleCase(id) : label, window);
            }
        }
        if (thirdShort != null || thirdWeekly != null) {
            UsageWindow primary = thirdShort != null ? thirdShort : thirdWeekly;
            UsageWindow secondary = thirdShort != null ? thirdWeekly : null;
            boolean reached = primary.usedPercent >= 100
                    || (secondary != null && secondary.usedPercent >= 100);
            out.extras.add(0, new UsageLimit("third_party", "Claude and other models", "",
                    !reached, reached, primary, secondary));
        }
        return out.build();
    }

    private static void collectBuckets(Object node, List<JSONObject> into) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has("bucketId") || object.has("remainingFraction")) {
                into.add(object);
                return;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectBuckets(object.opt(keys.next()), into);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectBuckets(array.opt(i), into);
        }
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
        Object project = root.opt("cloudaicompanionProject");
        if (project instanceof JSONObject) return string((JSONObject) project, "id", "name");
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
        out.plan = string(root, "plan_name", "current_plan");
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
            double weeklyTotal = number(model, "current_weekly_total_count");
            UsageWindow weekly = window(percentUsed(weeklyTotal, Double.NaN,
                    number(model, "current_weekly_usage_count")), WEEK,
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

    /** CodeWhisperer {@code GetUsageLimits}: monthly agentic requests or credits. */
    public static UsageSnapshot kiro(String body, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = string(root.optJSONObject("subscriptionInfo"), "subscriptionTitle", "type");
        out.plan = out.plan.replaceFirst("(?i)^kiro\\s+", "");
        JSONArray list = root.optJSONArray("usageBreakdownList");
        if (list == null) list = root.optJSONArray("limits");
        long fallbackReset = epochSeconds(first(root, "nextDateReset", "resetDate"));
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry == null) continue;
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

    private static final Pattern AMP_FREE = Pattern.compile(
            "\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*/\\s*\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*"
                    + "remaining", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMP_CREDITS = Pattern.compile(
            "\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:in\\s+)?(?:individual\\s+)?credits",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@code ampcode.com/api/internal?userDisplayBalanceInfo}: Amp returns the same sentence
     * the CLI prints, e.g. "Amp Free: $7.12/$10 remaining (replenishes +$0.42/hour) · $5.00
     * credits". The free tier refills continuously, so it is shown as a daily window.
     */
    public static UsageSnapshot amp(String body, long now) {
        JSONObject root = object(body);
        JSONObject result = root.optJSONObject("result");
        String text = result != null ? string(result, "displayText", "text") : "";
        if (text.isEmpty()) text = string(root, "displayText", "result");
        if (text.isEmpty()) text = body == null ? "" : body;
        Builder out = new Builder(now);
        Matcher free = AMP_FREE.matcher(text);
        if (free.find()) {
            double remaining = parseAmount(free.group(1));
            double total = parseAmount(free.group(2));
            out.plan = "Free";
            out.add("Free usage", window(percentUsed(total, Double.NaN, remaining), DAY, 0L,
                    now));
        }
        Matcher credits = AMP_CREDITS.matcher(text);
        if (credits.find()) out.credits = balance(parseAmount(credits.group(1)));
        return out.build();
    }

    private static double parseAmount(String text) {
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------- balance-only providers

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

    // ---------------------------------------------------------------- key-only heuristics

    /**
     * Synthetic, Chutes, v0 and Warp don't publish one stable shape, so this walks the response
     * for anything that looks like a quota: an object with a limit and a used or remaining
     * count, or a percent, plus a reset time. Window length comes from its name ("5h",
     * "weekly", "daily", "monthly") or, failing that, from {@code defaultSeconds}.
     */
    public static UsageSnapshot keyQuota(String body, long defaultSeconds, long now) {
        JSONObject root = object(body);
        Builder out = new Builder(now);
        out.plan = titleCase(string(root, "plan", "planName", "tier", "subscription"));
        walkQuotas(root, "", defaultSeconds, out, 0);
        return out.build();
    }

    private static void walkQuotas(Object node, String name, long defaultSeconds, Builder out,
            int depth) {
        if (depth > 6) return;
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                String itemName = item == null ? "" : string(item, "name", "type", "window",
                        "period", "label", "id");
                walkQuotas(array.opt(i), itemName.isEmpty() ? name : itemName, defaultSeconds,
                        out, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject object = (JSONObject) node;
        double percent = number(object, PERCENT_KEYS);
        if (!Double.isNaN(percent) && percent <= 1.0 && object.has("utilization")) {
            percent *= 100.0;
        }
        if (Double.isNaN(percent)) {
            percent = percentUsed(number(object, "limit", "max", "total", "quota", "allowed",
                    "requestLimit"), number(object, "used", "usage", "count", "consumed",
                    "requestsUsedSinceLastRefresh", "requestsUsed"),
                    number(object, "remaining", "left", "available"));
        }
        if (!Double.isNaN(percent)) {
            long seconds = secondsFromName(name);
            if (seconds <= 0) seconds = secondsFromObject(object);
            if (seconds <= 0) seconds = defaultSeconds;
            String label = name.isEmpty() ? durationName(seconds) + " limit" : titleCase(name);
            out.add(label, window(percent, seconds, resetFrom(object, out.now), out.now));
            return;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            walkQuotas(object.opt(key), key, defaultSeconds, out, depth + 1);
        }
    }

    static long secondsFromName(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return 0L;
        if (value.contains("five") || value.contains("5h") || value.contains("5_hour")
                || value.contains("rolling")) return 5 * HOUR;
        if (value.contains("hour")) return HOUR;
        if (value.contains("week") || value.contains("7d")) return WEEK;
        if (value.contains("day") || value.contains("daily") || value.contains("24h")) return DAY;
        if (value.contains("month") || value.contains("30d")) return MONTH;
        return 0L;
    }

    private static long secondsFromObject(JSONObject object) {
        double seconds = number(object, "windowSeconds", "window_seconds", "periodSeconds",
                "durationSeconds");
        if (!Double.isNaN(seconds) && seconds > 0) return (long) seconds;
        return secondsFromName(string(object, "window", "period", "interval", "refreshDuration"));
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
