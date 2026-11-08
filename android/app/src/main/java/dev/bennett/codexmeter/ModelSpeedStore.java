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
 * Public OpenRouter speed figures for each signed-in provider's newest models ({@link #TRACKED} per
 * maker), and a rolling history of them, so the Priority tab can warn when a model runs much slower
 * than it usually does. Nothing is shown on the provider pages, and nothing at all while the figures
 * are normal or too few. Requests go to OpenRouter's public model metadata only, without a key,
 * account data or prompts.
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

    /** How many of each maker's newest models are followed. */
    static final int TRACKED = 3;
    /** Samples kept per model (about a day and a half at one every 45 minutes). */
    static final int HISTORY = 48;

    /** Whether speed warnings are on for this provider (Settings › Refresh & dashboard). */
    public static boolean isVisible(Context context, UsageProvider provider) {
        return ModelSpeed.authorFor(provider) != null
                && !prefs(context).getBoolean("hidden_" + provider.id, false);
    }

    public static void setHidden(Context context, UsageProvider provider, boolean hidden) {
        prefs(context).edit().putBoolean("hidden_" + provider.id, hidden).apply();
    }

    /** The followed models: the maker's newest, from the cached catalog list (no network). */
    public static List<ModelSpeed.Choice> tracked(Context context, UsageProvider provider) {
        List<ModelSpeed.Choice> all = cachedChoices(context, provider);
        return all.size() > TRACKED ? new ArrayList<>(all.subList(0, TRACKED)) : all;
    }

    public static ModelSpeed load(Context context, String model) {
        String body = prefs(context).getString("body_" + model, null);
        return body == null ? null : ModelSpeed.parse(body, prefs(context).getLong("fetched_" + model, 0L));
    }

    /** The model's earlier samples, oldest first: {throughput, latency ms}, NaN when missing. */
    static List<double[]> history(Context context, String model) {
        List<double[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(context).getString("hist_" + model, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray x = a.getJSONArray(i);
                out.add(new double[] {x.optDouble(1, Double.NaN), x.optDouble(2, Double.NaN)});
            }
        } catch (Exception ignored) {
            // A bad history only means no baseline yet.
        }
        return out;
    }

    /** Significant slowdowns of the provider's followed models right now; empty when normal. */
    public static List<ModelSpeed.Slowdown> slowdowns(Context context, UsageProvider provider, long now) {
        List<ModelSpeed.Slowdown> out = new ArrayList<>();
        if (!isVisible(context, provider)) {
            return out;
        }
        for (ModelSpeed.Choice c : tracked(context, provider)) {
            ModelSpeed speed = load(context, c.id);
            if (speed == null || now - speed.fetchedAtMillis > 2 * REFRESH_MS) {
                continue; // too old to call current
            }
            List<double[]> h = history(context, c.id);
            // the latest sample is the current one; the baseline is everything before it
            ModelSpeed.Slowdown slow = ModelSpeed.slowdown(speed,
                    h.isEmpty() ? h : h.subList(0, h.size() - 1));
            if (slow != null) {
                out.add(slow);
            }
        }
        return out;
    }

    /**
     * Fetches the followed models' figures when due and adds them to their history; call off the
     * main thread. True if anything changed.
     */
    public static boolean refreshIfDue(Context context, UsageProvider provider) {
        if (!isVisible(context, provider)) {
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
        List<ModelSpeed.Choice> models = choices(context, provider);
        if (models.size() > TRACKED) {
            models = models.subList(0, TRACKED);
        }
        boolean changed = false;
        boolean failed = models.isEmpty();
        for (ModelSpeed.Choice c : models) {
            if (!ModelSpeed.isSafeModelId(c.id)) {
                continue;
            }
            try {
                String body = get(ModelSpeed.endpointsUrl(c.id));
                ModelSpeed speed = body == null ? null : ModelSpeed.parse(body, now);
                if (speed == null) {
                    failed = true;
                    continue;
                }
                ModelSpeed.Endpoint e = speed.featured();
                JSONArray h = new JSONArray(p.getString("hist_" + c.id, "[]"));
                h.put(new JSONArray().put(now).put(e == null || Double.isNaN(e.throughput) ? JSONObject.NULL : e.throughput)
                        .put(e == null || Double.isNaN(e.latencyMs) ? JSONObject.NULL : e.latencyMs));
                JSONArray kept = new JSONArray();
                for (int i = Math.max(0, h.length() - HISTORY); i < h.length(); i++) {
                    kept.put(h.get(i));
                }
                p.edit().putString("body_" + c.id, body).putLong("fetched_" + c.id, now)
                        .putString("hist_" + c.id, kept.toString()).apply();
                changed = true;
            } catch (Exception e) {
                failed = true;
                DiagnosticLog.warn(context, "speed", "endpoints_failed", "error", e.getClass().getSimpleName());
            }
        }
        p.edit().putInt("failures_" + provider.id, failed && !changed ? failures + 1 : 0).apply();
        return changed;
    }

    /**
     * The picker's models for {@code provider}'s maker, newest first, cached for a day. Blocking;
     * call off the main thread. Empty when OpenRouter can't be reached and nothing is cached.
     */
    static List<ModelSpeed.Choice> choices(Context context, UsageProvider provider) {
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
        return parseChoices(cached);
    }

    private static List<ModelSpeed.Choice> cachedChoices(Context context, UsageProvider provider) {
        String author = ModelSpeed.authorFor(provider);
        return author == null ? new ArrayList<>() : parseChoices(prefs(context).getString("choices_" + author, null));
    }

    private static List<ModelSpeed.Choice> parseChoices(String cached) {
        List<ModelSpeed.Choice> out = new ArrayList<>();
        if (cached != null) {
            try {
                JSONArray list = new JSONArray(cached);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    out.add(new ModelSpeed.Choice(o.getString("id"), o.getString("name")));
                }
            } catch (Exception ignored) {
                // A bad cache just means nothing followed until the next fetch.
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
