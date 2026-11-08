package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks the public model catalogs for newly listed models (see {@link ModelDiscovery}) on its own
 * clock, separate from the model speed refresh and from usage refreshes. Requests carry no key,
 * account data or prompt: stored credentials are never sent to any catalog.
 */
public final class ModelDiscoveryStore {
    private static final String PREFS = "model_discovery";
    private static final String KEY_STATE = "state";
    private static final String KEY_SUCCESS = "last_success";
    private static final String KEY_ATTEMPT = "last_attempt";
    private static final String KEY_FAILURES = "failures";
    private static final String KEY_NOTIFY = "notify_new_models";
    /** Catalogs are anonymous, so every baseline is the public one. */
    static final String ACCOUNT = "public";
    /** OpenRouter's catalog fits in one page today; this only bounds a runaway "next" chain. */
    private static final int MAX_PAGES = 10;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final Object LOCK = new Object();

    private ModelDiscoveryStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Signed-in providers that the explicit allowlist maps to a catalog. */
    static List<UsageProvider> connected(Context context) {
        List<UsageProvider> out = new ArrayList<>();
        for (UsageProvider p : UsageProvider.values()) {
            if (!ModelDiscovery.scopesFor(p).isEmpty() && SecureTokenStore.isSignedIn(context, p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** The last state parsed for reading, and the stored text it came from. */
    private static String readRaw;
    private static ModelDiscovery.State readState;

    /**
     * The saved state for read-only use, parsed again only when it changes. The dashboard reads it
     * on every rebuild, so parsing the whole catalog each time made scrolling and refreshes slow.
     */
    private static ModelDiscovery.State peek(Context context) {
        synchronized (LOCK) {
            String raw = prefs(context).getString(KEY_STATE, null);
            if (readState == null || raw == null || !raw.equals(readRaw)) {
                readState = ModelDiscovery.State.fromJson(raw);
                readRaw = raw;
            }
            return readState;
        }
    }

    static ModelDiscovery.State load(Context context) {
        synchronized (LOCK) {
            return ModelDiscovery.State.fromJson(prefs(context).getString(KEY_STATE, null));
        }
    }

    private static void save(Context context, ModelDiscovery.State state) {
        synchronized (LOCK) {
            prefs(context).edit().putString(KEY_STATE, state.toJson()).apply();
        }
    }

    /** The Priority view's "New models" entries; empty when nothing relevant is new. */
    public static List<ModelDiscovery.Entry> cardItems(Context context) {
        List<ModelDiscovery.Scope> allow = ModelDiscovery.allowlist(connected(context));
        if (allow.isEmpty()) {
            return new ArrayList<>();
        }
        return ModelDiscovery.cardItems(peek(context), allow, System.currentTimeMillis());
    }

    public static void dismiss(Context context, List<ModelDiscovery.Entry> shown) {
        synchronized (LOCK) {
            ModelDiscovery.State state = load(context);
            ModelDiscovery.dismiss(state, shown);
            save(context, state);
        }
    }

    /** The "New models" notification switch: off unless people turn it on. */
    public static boolean notifyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY, false);
    }

    public static void setNotifyEnabled(Context context, boolean on) {
        synchronized (LOCK) {
            ModelDiscovery.State state = load(context);
            if (on) {
                // No backlog: whatever is already known stays quiet.
                ModelDiscovery.markAllNotified(state);
                save(context, state);
            }
            prefs(context).edit().putBoolean(KEY_NOTIFY, on).apply();
        }
    }

    /**
     * Checks the catalogs when due; blocking, call off the main thread. {@code appOpen} is true
     * when the app was just opened or resumed (then 30 minutes after a success is enough).
     * Only one check runs at a time. Returns true when the card's content may have changed.
     */
    public static boolean refreshIfDue(Context context, boolean appOpen) {
        List<UsageProvider> connected = connected(context);
        List<ModelDiscovery.Scope> allow = ModelDiscovery.allowlist(connected);
        if (allow.isEmpty()) {
            return false;
        }
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        long lastOpen = RefreshEngagement.lastOpenAt(context);
        long interval = ModelDiscovery.intervalMs(RefreshEngagement.score(context, now),
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY), lastOpen <= 0L ? 0L : now - lastOpen);
        if (!ModelDiscovery.isDue(now, p.getLong(KEY_SUCCESS, 0L), p.getLong(KEY_ATTEMPT, 0L),
                p.getInt(KEY_FAILURES, 0), interval, appOpen)) {
            return false;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return false;
        }
        try {
            p.edit().putLong(KEY_ATTEMPT, now).apply();
            boolean anyComplete = false;
            List<ModelDiscovery.Entry> confirmed = new ArrayList<>();
            ModelDiscovery.State state = load(context);
            for (ModelDiscovery.Source source : ModelDiscovery.sourcesNeeded(allow)) {
                List<ModelDiscovery.Model> models = new ArrayList<>();
                boolean complete = fetch(context, source, models);
                anyComplete |= complete;
                confirmed.addAll(ModelDiscovery.observe(state, source, ACCOUNT, models, complete, now));
            }
            List<ModelDiscovery.Entry> digest = new ArrayList<>();
            synchronized (LOCK) {
                // Keep dismissals and notification marks made while the fetch ran.
                ModelDiscovery.State latest = load(context);
                state.dismissed.addAll(latest.dismissed);
                state.notified.addAll(latest.notified);
                if (notifyEnabled(context)) {
                    digest = ModelDiscovery.takeDigest(state, allow, now);
                } else {
                    // Off: nothing queues up to be replayed when it's turned on later.
                    ModelDiscovery.markAllNotified(state);
                }
                save(context, state);
            }
            if (!digest.isEmpty()) {
                ModelDiscoveryNotifier.show(context, digest);
            }
            if (anyComplete) {
                p.edit().putLong(KEY_SUCCESS, now).putInt(KEY_FAILURES, 0).apply();
            } else {
                p.edit().putInt(KEY_FAILURES, p.getInt(KEY_FAILURES, 0) + 1).apply();
            }
            return !confirmed.isEmpty();
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    /** Reads every page of one catalog; true only for a complete response. */
    private static boolean fetch(Context context, ModelDiscovery.Source source,
            List<ModelDiscovery.Model> out) {
        String url = source.url;
        int total = -1;
        try {
            for (int page = 0; url != null && page < MAX_PAGES; page++) {
                String body = ModelSpeedStore.get(url);
                ModelDiscovery.Page parsed = body == null ? null : ModelDiscovery.parse(source, body);
                if (parsed == null || parsed.models == null) {
                    return false;
                }
                if (source == ModelDiscovery.Source.OPENROUTER && page == 0) {
                    // The speed picker reads the same catalog; refresh its cache for free.
                    ModelSpeedStore.cacheChoices(context, body, System.currentTimeMillis());
                }
                out.addAll(parsed.models);
                total = Math.max(total, parsed.total);
                url = ModelDiscovery.nextUrl(source, parsed.next);
                if (parsed.next != null && url == null) {
                    return false;
                }
            }
            if (url != null) {
                return false;
            }
            // A total the pages don't reach means the response was cut short.
            return !out.isEmpty() && (total < 0 || out.size() >= total * 95 / 100);
        } catch (Exception e) {
            DiagnosticLog.warn(context, "models", "catalog_failed", "source", source.id,
                    "error", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Debug and design-gallery only: puts a ready-made record in place. */
    static void seedForDemo(Context context, ModelDiscovery.State state) {
        synchronized (LOCK) {
            save(context, state);
            prefs(context).edit().putLong(KEY_SUCCESS, System.currentTimeMillis()).apply();
        }
    }
}
