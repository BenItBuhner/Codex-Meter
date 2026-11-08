package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The reference model each provider tab shows, and its cached OpenRouter figures. Requests go to
 * OpenRouter's public model metadata only, without a key, account data or prompts.
 */
public final class ModelSpeedStore {
    private static final String PREFS = "model_speed";
    /** Endpoint figures cover the last 30 minutes; asking every 45 is plenty. */
    public static final long REFRESH_MS = TimeUnit.MINUTES.toMillis(45);
    private static final long MAX_BACKOFF_MS = TimeUnit.HOURS.toMillis(2);
    private static final long CHOICES_TTL_MS = TimeUnit.DAYS.toMillis(1);
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private ModelSpeedStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the tab shows the speed card at all (people can hide it from its picker). */
    public static boolean isVisible(Context context, UsageProvider provider) {
        return ModelSpeed.authorFor(provider) != null
                && !prefs(context).getBoolean("hidden_" + provider.id, false);
    }

    public static void setHidden(Context context, UsageProvider provider, boolean hidden) {
        prefs(context).edit().putBoolean("hidden_" + provider.id, hidden).apply();
    }

    /** The chosen model id, or "" when none was chosen yet (no default is guessed). */
    public static String selectedModel(Context context, UsageProvider provider) {
        return prefs(context).getString("model_" + provider.id, "");
    }

    public static void selectModel(Context context, UsageProvider provider, String modelId) {
        if (!ModelSpeed.isSafeModelId(modelId)) {
            return;
        }
        prefs(context).edit().putString("model_" + provider.id, modelId)
                .remove("attempt_" + provider.id).remove("failures_" + provider.id).apply();
    }

    public static ModelSpeed load(Context context, UsageProvider provider) {
        String model = selectedModel(context, provider);
        String body = prefs(context).getString("body_" + model, null);
        if (model.isEmpty() || body == null) {
            return null;
        }
        return ModelSpeed.parse(body, prefs(context).getLong("fetched_" + model, 0L));
    }

    /** Fetches the selected model's figures when due; call off the main thread. True if changed. */
    public static boolean refreshIfDue(Context context, UsageProvider provider) {
        String model = selectedModel(context, provider);
        if (!isVisible(context, provider) || !ModelSpeed.isSafeModelId(model)) {
            return false;
        }
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        long last = p.getLong("attempt_" + provider.id, 0L);
        int failures = p.getInt("failures_" + provider.id, 0);
        long wait = failures == 0 ? REFRESH_MS
                : Math.min(MAX_BACKOFF_MS, TimeUnit.MINUTES.toMillis(15) << Math.min(failures - 1, 4));
        if (last > 0L && now - last < wait) {
            return false;
        }
        p.edit().putLong("attempt_" + provider.id, now).apply();
        try {
            String body = get(ModelSpeed.endpointsUrl(model));
            if (body == null || ModelSpeed.parse(body, now) == null) {
                p.edit().putInt("failures_" + provider.id, failures + 1).apply();
                return false;
            }
            p.edit().putInt("failures_" + provider.id, 0).putString("body_" + model, body)
                    .putLong("fetched_" + model, now).apply();
            return true;
        } catch (Exception e) {
            p.edit().putInt("failures_" + provider.id, failures + 1).apply();
            DiagnosticLog.warn(context, "speed", "endpoints_failed", "error", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * The picker's models for {@code provider}'s maker, newest first, cached for a day. Blocking;
     * call off the main thread. Empty when OpenRouter can't be reached and nothing is cached.
     */
    public static List<ModelSpeed.Choice> choices(Context context, UsageProvider provider) {
        String author = ModelSpeed.authorFor(provider);
        List<ModelSpeed.Choice> out = new ArrayList<>();
        if (author == null) {
            return out;
        }
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        String cached = p.getString("choices_" + author, null);
        if (cached == null || now - p.getLong("choices_at_" + author, 0L) > CHOICES_TTL_MS) {
            try {
                String body = get(ModelSpeed.MODELS_URL);
                List<ModelSpeed.Choice> fresh = body == null ? out : ModelSpeed.modelsBy(body, author, 30);
                if (!fresh.isEmpty()) {
                    JSONArray list = new JSONArray();
                    for (ModelSpeed.Choice c : fresh) {
                        list.put(new JSONObject().put("id", c.id).put("name", c.name));
                    }
                    cached = list.toString();
                    p.edit().putString("choices_" + author, cached).putLong("choices_at_" + author, now).apply();
                }
            } catch (Exception e) {
                DiagnosticLog.warn(context, "speed", "models_failed", "error", e.getClass().getSimpleName());
            }
        }
        if (cached != null) {
            try {
                JSONArray list = new JSONArray(cached);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    out.add(new ModelSpeed.Choice(o.getString("id"), o.getString("name")));
                }
            } catch (Exception ignored) {
                // A bad cache just means an empty picker until the next fetch.
            }
        }
        return out;
    }

    /**
     * Refreshes the picker's cached lists from a full catalog body the new-model check already
     * fetched, so the two never download the same catalog twice in a row.
     */
    static void cacheChoices(Context context, String body, long now) {
        SharedPreferences.Editor edit = prefs(context).edit();
        for (UsageProvider provider : new UsageProvider[] {UsageProvider.CODEX, UsageProvider.CLAUDE_CODE,
                UsageProvider.GROK}) {
            String author = ModelSpeed.authorFor(provider);
            List<ModelSpeed.Choice> fresh = ModelSpeed.modelsBy(body, author, 30);
            if (fresh.isEmpty()) {
                continue;
            }
            JSONArray list = new JSONArray();
            try {
                for (ModelSpeed.Choice c : fresh) {
                    list.put(new JSONObject().put("id", c.id).put("name", c.name));
                }
            } catch (org.json.JSONException e) {
                continue;
            }
            edit.putString("choices_" + author, list.toString()).putLong("choices_at_" + author, now);
        }
        edit.apply();
    }

    /** An anonymous GET: no key, cookie or account header is ever attached. */
    static String get(String url) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", AppConstants.userAgent());
            if (connection.getResponseCode() / 100 != 2) {
                return null;
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new java.io.IOException("response too large");
                    }
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
