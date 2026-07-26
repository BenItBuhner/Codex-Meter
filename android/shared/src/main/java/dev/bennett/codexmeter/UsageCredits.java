package dev.bennett.codexmeter;

import java.math.BigDecimal;
import org.json.JSONException;
import org.json.JSONObject;

/** Purchased usage-credit state returned by the main Codex usage endpoint. */
public final class UsageCredits {
    /**
     * Balances below this render as "0" with the two fraction digits used across the app,
     * so they are treated as exhausted and never displayed.
     */
    private static final BigDecimal NEAR_ZERO_BALANCE = new BigDecimal("0.005");

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
     * Whether the balance is worth surfacing anywhere in the UI. This is intentionally not
     * configurable: zero, effectively-zero, and negative balances always hide the card, as does
     * an account without purchased credits. Unlimited plans and unparseable non-empty balances
     * remain visible.
     */
    public boolean shouldDisplay() {
        if (unlimited) {
            return true;
        }
        if (!hasCredits) {
            return false;
        }
        if (balance.isEmpty()) {
            return true;
        }
        BigDecimal amount = numericBalance();
        return amount == null || amount.compareTo(NEAR_ZERO_BALANCE) >= 0;
    }

    /** Numeric balance, or null when the reported balance is empty or not a number. */
    public BigDecimal numericBalance() {
        if (balance.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(balance.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
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
