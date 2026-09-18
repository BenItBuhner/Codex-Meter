package dev.bennett.codexmeter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Workspace spend-control state from the {@code spend_control} object of the main Codex usage
 * endpoint. Its {@code individual_limit} is the per-member monthly credit allocation that the
 * official Codex clients render as "Monthly credit limit". It is a different concept from
 * purchased usage credits ({@link UsageCredits}) and from banked rate-limit reset credits, and
 * it is absent for accounts without workspace spend controls, in which case {@link #fromJson}
 * returns null and nothing changes for them.
 *
 * <p>The endpoint reports amounts as strings ({@code "25000"}) and percentages as numbers, but
 * both shapes are accepted for every field, so a payload that switches types keeps parsing.
 * Unknown percentages are {@code -1}; unknown amounts are empty strings.
 */
public final class SpendControl {
    public static final String SOURCE_WORKSPACE = "workspace_spend_controls";

    public final boolean reached;
    public final String source;
    public final String limit;
    public final String used;
    public final String remaining;
    public final int usedPercent;
    public final int remainingPercent;
    public final long resetAfterSeconds;
    public final long resetAtEpochSeconds;

    public SpendControl(boolean reached, String source, String limit, String used,
            String remaining, int usedPercent, int remainingPercent, long resetAfterSeconds,
            long resetAtEpochSeconds) {
        this.reached = reached;
        this.source = clean(source);
        this.limit = clean(limit);
        this.used = clean(used);
        this.remaining = clean(remaining);
        this.usedPercent = clampPercent(usedPercent);
        this.remainingPercent = clampPercent(remainingPercent);
        this.resetAfterSeconds = Math.max(0L, resetAfterSeconds);
        this.resetAtEpochSeconds = Math.max(0L, resetAtEpochSeconds);
    }

    /** Whether the payload carried anything worth rendering: an amount or a percentage. */
    public boolean hasUsageData() {
        return numericLimit() != null || numericUsed() != null || numericRemaining() != null
                || usedPercent >= 0 || remainingPercent >= 0;
    }

    /**
     * Remaining share of the allocation, 0-100, or -1 when neither percentage nor a pair of
     * amounts was reported. Prefers the explicit remaining percentage, then the complement of
     * the used percentage, then a ratio of the amounts.
     */
    public int effectiveRemainingPercent() {
        if (remainingPercent >= 0) {
            return remainingPercent;
        }
        if (usedPercent >= 0) {
            return 100 - usedPercent;
        }
        BigDecimal total = numericLimit();
        BigDecimal left = numericRemaining();
        if (total == null || left == null || total.signum() <= 0) {
            return -1;
        }
        return clampPercent(left.max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(100))
                .divide(total, 0, RoundingMode.HALF_UP).intValue());
    }

    /** Used share of the allocation, 0-100, or -1 when unknown. */
    public int effectiveUsedPercent() {
        if (usedPercent >= 0) {
            return usedPercent;
        }
        int left = effectiveRemainingPercent();
        return left < 0 ? -1 : 100 - left;
    }

    /** Reported allocation as a number, or null when absent or not numeric. */
    public BigDecimal numericLimit() {
        return parseAmount(limit);
    }

    /** Reported consumption as a number, or null when absent or not numeric. */
    public BigDecimal numericUsed() {
        return parseAmount(used);
    }

    /**
     * Reported remainder as a number, falling back to limit minus used when the endpoint omits
     * it, or null when neither is available.
     */
    public BigDecimal numericRemaining() {
        BigDecimal explicit = parseAmount(remaining);
        if (explicit != null) {
            return explicit;
        }
        BigDecimal total = numericLimit();
        BigDecimal spent = numericUsed();
        return total == null || spent == null ? null : total.subtract(spent);
    }

    /** Whether OpenAI reported a reset timeline for the allocation. */
    public boolean showsResetCountdown() {
        return resetAtEpochSeconds > 0L || resetAfterSeconds > 0L;
    }

    public long resetAtMillis() {
        return resetAtEpochSeconds > 0L ? resetAtEpochSeconds * 1000L : 0L;
    }

    /** Absolute reset instant, deriving it from {@code reset_after_seconds} when needed. */
    public long effectiveResetAtMillis(long observedAtMillis) {
        long explicit = resetAtMillis();
        if (explicit > 0L) {
            return explicit;
        }
        if (resetAfterSeconds <= 0L || observedAtMillis <= 0L
                || resetAfterSeconds > (Long.MAX_VALUE - observedAtMillis) / 1000L) {
            return 0L;
        }
        return observedAtMillis + resetAfterSeconds * 1000L;
    }

    /** Human label for where the limit comes from. */
    public String sourceLabel() {
        return SOURCE_WORKSPACE.equals(source) || source.isEmpty()
                ? "Workspace spend limit" : "Spend limit";
    }

    /**
     * Headline copy matching the Codex clients: "8,000 of 25,000 credits used", falling back to
     * a percentage when amounts are missing.
     */
    public String usageText(Locale locale) {
        BigDecimal spent = numericUsed();
        BigDecimal total = numericLimit();
        if (spent != null && total != null) {
            return formatAmount(spent, locale) + " of " + formatAmount(total, locale)
                    + " credits used";
        }
        int percent = effectiveUsedPercent();
        if (percent >= 0) {
            return percent + "% of monthly credits used";
        }
        return reached ? "Limit reached" : "Usage details unavailable";
    }

    /**
     * Secondary copy: the remainder ("17,000 credits remaining"), prefixed with the reached
     * state whenever OpenAI flags the limit as hit. Empty when nothing is known.
     */
    public String remainingText(Locale locale) {
        BigDecimal left = numericRemaining();
        String amount;
        if (left != null) {
            amount = formatAmount(left.max(BigDecimal.ZERO), locale) + " credits remaining";
        } else {
            int percent = effectiveRemainingPercent();
            amount = percent >= 0 ? percent + "% remaining" : "";
        }
        if (!reached) {
            return amount;
        }
        boolean exhausted = left != null ? left.signum() <= 0 : effectiveRemainingPercent() == 0;
        return amount.isEmpty() || exhausted ? "Limit reached" : "Limit reached · " + amount;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject individual = new JSONObject();
        if (!source.isEmpty()) {
            individual.put("source", source);
        }
        if (!limit.isEmpty()) {
            individual.put("limit", limit);
        }
        if (!used.isEmpty()) {
            individual.put("used", used);
        }
        if (!remaining.isEmpty()) {
            individual.put("remaining", remaining);
        }
        if (usedPercent >= 0) {
            individual.put("used_percent", usedPercent);
        }
        if (remainingPercent >= 0) {
            individual.put("remaining_percent", remainingPercent);
        }
        if (resetAfterSeconds > 0L) {
            individual.put("reset_after_seconds", resetAfterSeconds);
        }
        if (resetAtEpochSeconds > 0L) {
            individual.put("reset_at", resetAtEpochSeconds);
        }
        JSONObject object = new JSONObject();
        object.put("reached", reached);
        object.put("individual_limit", individual);
        return object;
    }

    /**
     * Parses the endpoint's {@code spend_control} object (also the cached shape written by
     * {@link #toJson}). Returns null when the object or its {@code individual_limit} is missing,
     * null, or carries no usable amount or percentage.
     */
    public static SpendControl fromJson(JSONObject object) {
        if (object == null || object.isNull("individual_limit")) {
            return null;
        }
        JSONObject individual = object.optJSONObject("individual_limit");
        if (individual == null) {
            return null;
        }
        SpendControl control = new SpendControl(
                object.optBoolean("reached", false),
                stringValue(individual, "source"),
                stringValue(individual, "limit"),
                stringValue(individual, "used"),
                stringValue(individual, "remaining"),
                percentValue(individual, "used_percent"),
                percentValue(individual, "remaining_percent"),
                longValue(individual, "reset_after_seconds"),
                longValue(individual, "reset_at"));
        return control.hasUsageData() ? control : null;
    }

    private static String stringValue(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) {
            return "";
        }
        if (value instanceof Number) {
            try {
                return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private static int percentValue(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return Double.isNaN(number) || Double.isInfinite(number)
                    ? -1 : clampPercent((int) Math.round(number));
        }
        if (value instanceof String) {
            BigDecimal parsed = parseAmount(((String) value).replace("%", ""));
            return parsed == null ? -1
                    : clampPercent(parsed.setScale(0, RoundingMode.HALF_UP).intValue());
        }
        return -1;
    }

    private static long longValue(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            BigDecimal parsed = parseAmount((String) value);
            return parsed == null ? 0L : parsed.setScale(0, RoundingMode.HALF_UP).longValue();
        }
        return 0L;
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().replace(",", "").replace("_", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatAmount(BigDecimal amount, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(
                locale == null ? Locale.getDefault() : locale);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    private static int clampPercent(int value) {
        return value < 0 ? -1 : Math.min(100, value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
