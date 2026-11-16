package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parses Codex's {@code rate_limit_reset_credits} response. */
public final class ResetCreditsParser {
    private ResetCreditsParser() {
    }

    public static ResetCreditsSnapshot parse(String body, long fetchedAtMillis) throws JSONException {
        JSONObject json = new JSONObject(body == null ? "{}" : body);
        List<RateLimitResetCredit> credits = new ArrayList<>();
        JSONArray list = json.optJSONArray("credits");
        for (int i = 0; list != null && i < list.length(); i++) {
            RateLimitResetCredit credit = RateLimitResetCredit.fromApiJson(list.optJSONObject(i));
            if (credit != null) {
                credits.add(credit);
            }
        }
        return new ResetCreditsSnapshot(json.optInt("available_count", countAvailable(credits)), credits, fetchedAtMillis);
    }

    private static int countAvailable(List<RateLimitResetCredit> credits) {
        int available = 0;
        for (RateLimitResetCredit credit : credits) {
            if (credit != null && credit.isAvailable()) {
                available++;
            }
        }
        return available;
    }
}
