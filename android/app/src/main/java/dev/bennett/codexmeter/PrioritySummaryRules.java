package dev.bennett.codexmeter;

import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** The written summary's timing and request shape, kept free of Android for the self-tests. */
final class PrioritySummaryRules {
    private PrioritySummaryRules() {}

    /** Ask again when the facts change, but not more often than this. */
    static final long MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    /** Even with unchanged facts, a fresh summary at most this often. */
    static final long MAX_AGE_MS = TimeUnit.HOURS.toMillis(12);

    /** True when a request is allowed now (a TTL plus change detection: about one or two a day). */
    static boolean isDue(long now, long lastAt, boolean factsChanged) {
        if (lastAt <= 0L || now < lastAt) {
            return true;
        }
        long age = now - lastAt;
        return factsChanged ? age >= MIN_INTERVAL_MS : age >= MAX_AGE_MS;
    }

    /** The whole request: fixed instructions, the aggregated facts, a small token budget. */
    static JSONObject requestBody(JSONObject facts, String model) throws Exception {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", PriorityView.INSTRUCTIONS))
                .put(new JSONObject().put("role", "user").put("content", facts.toString()));
        return new JSONObject().put("model", model).put("messages", messages)
                .put("max_tokens", 120).put("temperature", 0.2);
    }
}
