package dev.bennett.codexmeter;

import org.json.JSONException;
import org.json.JSONObject;

/** Purchased usage-credit state returned by the main Codex usage endpoint. */
public final class UsageCredits {
    public final boolean hasCredits;
    public final boolean unlimited;
    public final String balance;

    public UsageCredits(boolean hasCredits, boolean unlimited, String balance) {
        this.hasCredits = hasCredits;
        this.unlimited = unlimited;
        this.balance = balance == null ? "" : balance.trim();
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
