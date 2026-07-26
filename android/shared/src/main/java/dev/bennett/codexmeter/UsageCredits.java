package dev.bennett.codexmeter;

import java.math.BigDecimal;
import org.json.JSONException;
import org.json.JSONObject;

/** Purchased usage-credit state returned by the main Codex usage endpoint. */
public final class UsageCredits {
    /** Hide balances at/under this amount; near-zero scrap is not useful on the dashboard. */
    private static final BigDecimal VISIBLE_BALANCE_THRESHOLD = new BigDecimal("0.01");

    public final boolean hasCredits;
    public final boolean unlimited;
    public final String balance;

    public UsageCredits(boolean hasCredits, boolean unlimited, String balance) {
        this.hasCredits = hasCredits;
        this.unlimited = unlimited;
        String cleanBalance = balance == null ? "" : balance.trim();
        this.balance = hasCredits || unlimited ? cleanBalance : "";
    }

    /**
     * Whether the usage-credit card should appear on the dashboard. Zero, near-zero,
     * and negative balances auto-hide and are not user-configurable.
     */
    public boolean isDashboardVisible() {
        if (unlimited) {
            return true;
        }
        if (balance.isEmpty()) {
            return hasCredits;
        }
        try {
            BigDecimal amount = new BigDecimal(balance.replace(",", ""));
            return amount.compareTo(VISIBLE_BALANCE_THRESHOLD) >= 0;
        } catch (NumberFormatException ignored) {
            return hasCredits;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("has_credits", hasCredits);
        object.put("unlimited", unlimited);
        if (!balance.isEmpty()) {
            object.put("balance", balance);
        }
        return object;
    }

    public static UsageCredits fromJson(JSONObject object) {
        if (object == null || (!object.has("has_credits")
                && !object.has("unlimited") && !object.has("balance"))) {
            return null;
        }
        Object rawBalance = object.opt("balance");
        String balance = rawBalance == null || JSONObject.NULL.equals(rawBalance)
                ? "" : String.valueOf(rawBalance);
        return new UsageCredits(
                object.optBoolean("has_credits", false),
                object.optBoolean("unlimited", false),
                balance);
    }
}
