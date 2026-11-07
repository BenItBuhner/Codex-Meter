package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The optional model-written summary for the Priority view. Off unless the person turns on
 * "Written summary"; it is never switched on by an install, update or re-release.
 *
 * <p>When on, it sends one bounded request with fixed instructions and the aggregated facts from
 * {@link PriorityView#factsForModel} only, to OpenCode's free models, trying the next free model
 * only when one gives no answer at all (an error, a refusal or a timeout). A reply that fails the
 * closed schema is dropped without a second "repair" request. If every free model fails, the
 * local summary stays; there is no paid fallback here and no shared API key in the app.
 *
 * <p>OpenCode's docs currently say its free chat models are for OpenCode itself ("Free-tier chat
 * models require OpenCode"), so other clients may be refused. The app does not imitate OpenCode
 * or send any header to get around that; a refused model is skipped for a day.
 */
public final class PrioritySummary {
    private static final String PREFS = "priority_summary";
    static final String ENDPOINT = "https://opencode.ai/inference/openai/v1/chat/completions";
    /** Different free models, tried in order. */
    static final String[] FREE_MODELS = {"big-pickle", "glm-5.3-flash-free", "deepseek-v4.1-flash-free"};
    private static final long REFUSED_COOLDOWN_MS = TimeUnit.DAYS.toMillis(1);
    private static final long FAILED_COOLDOWN_MS = TimeUnit.HOURS.toMillis(1);
    private static final int TIMEOUT_MS = 12_000;
    private static final int MAX_BYTES = 64 * 1024;

    private PrioritySummary() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Off by default. Only the Settings switch turns it on. */
    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean("enabled", false);
    }

    public static void setEnabled(Context context, boolean on) {
        SharedPreferences.Editor e = prefs(context).edit().putBoolean("enabled", on);
        if (!on) {
            e.remove("summary").remove("tip").remove("key").remove("at");
        }
        e.apply();
    }

    /** The name for the greeting. Stays on this device; never part of a request. */
    public static String name(Context context) {
        return prefs(context).getString("name", "");
    }

    public static void setName(Context context, String name) {
        prefs(context).edit().putString("name", name == null ? "" : name.trim()).apply();
    }

    /** The cached model prose for exactly these facts, or null. */
    public static PriorityView.Prose cached(Context context, List<PriorityView.Item> items) {
        SharedPreferences p = prefs(context);
        if (!isEnabled(context) || !PriorityView.factsKey(items).equals(p.getString("key", ""))) {
            return null;
        }
        String summary = p.getString("summary", null);
        return summary == null ? null : new PriorityView.Prose(summary, p.getString("tip", ""));
    }

    /**
     * Asks for prose when enabled and due. Blocking; call off the main thread. Returns true when
     * new prose was stored. Never throws.
     */
    public static boolean refreshIfDue(Context context, List<PriorityView.Item> items) {
        if (!isEnabled(context)) {
            return false;
        }
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        String key = PriorityView.factsKey(items);
        if (!PrioritySummaryRules.isDue(now, p.getLong("at", 0L), !key.equals(p.getString("key", "")))) {
            return false;
        }
        // Counts as the attempt for this set of facts, answered or not.
        p.edit().putLong("at", now).putString("key", key).remove("summary").remove("tip").apply();
        String body;
        try {
            body = PrioritySummaryRules.requestBody(PriorityView.factsForModel(items), "").toString();
        } catch (Exception e) {
            return false;
        }
        for (String model : FREE_MODELS) {
            if (p.getLong("cooldown_" + model, 0L) > now) {
                continue;
            }
            Reply reply = post(model, body);
            if (reply.content == null) {
                p.edit().putLong("cooldown_" + model, now
                        + (reply.refused ? REFUSED_COOLDOWN_MS : FAILED_COOLDOWN_MS)).apply();
                DiagnosticLog.info(context, "priority", "summary_route_failed", "model", model,
                        "status", reply.status);
                continue;
            }
            PriorityView.Prose prose = PriorityView.validateProse(reply.content);
            if (prose == null) {
                // No repair request: the local summary stays for this set of facts.
                DiagnosticLog.info(context, "priority", "summary_rejected", "model", model);
                return false;
            }
            p.edit().putString("summary", prose.summary).putString("tip", prose.tip).apply();
            return true;
        }
        return false;
    }

    private static final class Reply {
        final String content;
        final int status;
        final boolean refused;

        Reply(String content, int status, boolean refused) {
            this.content = content;
            this.status = status;
            this.refused = refused;
        }
    }

    private static Reply post(String model, String bodyWithoutModel) {
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject(bodyWithoutModel).put("model", model);
            c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", AppConstants.userAgent());
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            if (status / 100 != 2) {
                return new Reply(null, status, status == 401 || status == 403 || status == 404);
            }
            String text = read(c.getInputStream());
            JSONObject root = new JSONObject(text);
            JSONArray choices = root.optJSONArray("choices");
            JSONObject message = choices == null || choices.length() == 0 ? null
                    : choices.getJSONObject(0).optJSONObject("message");
            String content = message == null ? null : message.optString("content", null);
            return new Reply(content, status, false);
        } catch (Exception e) {
            return new Reply(null, -1, false);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new java.io.IOException("reply too large");
                }
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
