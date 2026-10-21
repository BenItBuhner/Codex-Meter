package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/**
 * Which provider tab is selected, plus cached usage for providers other than Codex.
 *
 * <p>Codex keeps reading and writing {@link AppPreferences} so widgets, Now Bar, Wear,
 * alerts, and history stay exactly as before. Other providers store their state here under
 * keys prefixed with the provider id.
 */
@SuppressLint("ApplySharedPref")
public final class ProviderPreferences {
    private static final String PREFS = "codex_meter_providers_v1";
    private static final String KEY_SELECTED = "selected_provider";
    private static final String KEY_ENABLED = "enabled_providers";
    private static final String KEY_ORDER = "provider_order";
    private static final String KEY_PENDING_SIGN_IN = "pending_sign_in_provider";
    private static final String SUFFIX_SNAPSHOT = "_snapshot";
    private static final String SUFFIX_ERROR = "_error";
    private static final String SUFFIX_ERROR_AT = "_error_at";
    private static final String SUFFIX_EMAIL = "_email";
    private static final String SUFFIX_HISTORY = "_history_";
    private static final String SUFFIX_RATE_LIMITED_AT = "_rate_limited_at";
    private static final String KEY_WIDGET_PROVIDER = "widget_provider_";

    private ProviderPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The selected tab, moved to the first enabled provider if its own tab was turned off. */
    public static UsageProvider getSelected(Context context) {
        return UsageProvider.visibleSelection(
                UsageProvider.fromId(prefs(context).getString(KEY_SELECTED, null)),
                getEnabled(context));
    }

    /**
     * Providers shown as dashboard tabs (Settings → Providers), in tab order. Setup saves the
     * user's choice; an install that never chose gets {@link UsageProvider#migrationDefault}.
     */
    public static List<UsageProvider> getEnabled(Context context) {
        String saved = prefs(context).getString(KEY_ENABLED, null);
        if (saved == null || saved.trim().isEmpty()) {
            // Decided once and saved, so the tabs don't shift when a sign-in changes later.
            List<UsageProvider> signedIn = new ArrayList<>();
            for (UsageProvider provider : UsageProvider.values()) {
                if (SecureTokenStore.isSignedIn(context, provider)) signedIn.add(provider);
            }
            List<UsageProvider> initial = UsageProvider.migrationDefault(signedIn);
            prefs(context).edit().putString(KEY_ENABLED, ids(initial)).apply();
            return UsageProvider.inOrder(initial, getOrder(context));
        }
        return UsageProvider.inOrder(UsageProvider.enabledFromIds(
                new ArrayList<>(Arrays.asList(saved.split(","))),
                UsageProvider.migrationDefault(null)), getOrder(context));
    }

    /** Saves the providers picked during setup (at least one). */
    public static void setEnabledProviders(Context context, List<UsageProvider> providers) {
        if (providers == null || providers.isEmpty()) return;
        prefs(context).edit().putString(KEY_ENABLED, ids(providers)).apply();
    }

    /** Every provider in the user's tab order (Settings → Providers). Enum order by default. */
    public static List<UsageProvider> getOrder(Context context) {
        String saved = prefs(context).getString(KEY_ORDER, null);
        return UsageProvider.orderFromIds(saved == null ? null
                : new ArrayList<>(Arrays.asList(saved.split(","))));
    }

    public static void setOrder(Context context, List<UsageProvider> order) {
        prefs(context).edit().putString(KEY_ORDER, ids(order)).apply();
    }

    private static String ids(List<UsageProvider> providers) {
        StringBuilder ids = new StringBuilder();
        for (UsageProvider provider : providers) {
            if (ids.length() > 0) ids.append(',');
            ids.append(provider.id);
        }
        return ids.toString();
    }

    /**
     * Turns a provider's tab on or off. Returns false, changing nothing, when that would turn
     * off the last provider.
     */
    public static boolean setEnabled(Context context, UsageProvider provider, boolean on) {
        List<UsageProvider> current = getEnabled(context);
        List<UsageProvider> updated = UsageProvider.withEnabled(current, provider, on);
        if (updated.contains(provider) != on) {
            return false;
        }
        prefs(context).edit().putString(KEY_ENABLED, ids(updated)).apply();
        return true;
    }

    public static void setSelected(Context context, UsageProvider provider) {
        prefs(context).edit().putString(KEY_SELECTED, provider.id).apply();
    }

    /** The provider whose browser sign-in is in flight, so only its tab says "Continue". */
    public static UsageProvider getPendingSignIn(Context context) {
        return UsageProvider.fromId(prefs(context).getString(KEY_PENDING_SIGN_IN, null));
    }

    public static void setPendingSignIn(Context context, UsageProvider provider) {
        prefs(context).edit().putString(KEY_PENDING_SIGN_IN, provider.id).apply();
    }

    public static boolean isSignInPending(Context context, UsageProvider provider) {
        return AppPreferences.isOAuthPending(context) && getPendingSignIn(context) == provider;
    }

    public static UsageSnapshot loadSnapshot(Context context, UsageProvider provider) {
        if (provider == UsageProvider.CODEX) {
            return AppPreferences.loadSnapshot(context);
        }
        String json = prefs(context).getString(provider.id + SUFFIX_SNAPSHOT, null);
        if (json == null || json.isEmpty()) return null;
        try {
            return UsageSnapshot.fromJson(new JSONObject(json));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean saveSnapshot(Context context, UsageProvider provider,
            UsageSnapshot snapshot) {
        if (provider == UsageProvider.CODEX) {
            return AppPreferences.saveSnapshot(context, snapshot);
        }
        try {
            return prefs(context).edit()
                    .putString(provider.id + SUFFIX_SNAPSHOT, snapshot.toJson().toString())
                    .remove(provider.id + SUFFIX_ERROR)
                    .remove(provider.id + SUFFIX_ERROR_AT)
                    .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String getLastError(Context context, UsageProvider provider) {
        if (provider == UsageProvider.CODEX) {
            return AppPreferences.getLastError(context);
        }
        return prefs(context).getString(provider.id + SUFFIX_ERROR, "");
    }

    public static void setLastError(Context context, UsageProvider provider, String message) {
        if (provider == UsageProvider.CODEX) {
            AppPreferences.setLastError(context, message);
            return;
        }
        prefs(context).edit()
                .putString(provider.id + SUFFIX_ERROR, message == null ? "" : message)
                .putLong(provider.id + SUFFIX_ERROR_AT, System.currentTimeMillis())
                .apply();
    }

    public static String getEmail(Context context, UsageProvider provider) {
        return prefs(context).getString(provider.id + SUFFIX_EMAIL, "");
    }

    public static void setEmail(Context context, UsageProvider provider, String email) {
        if (email == null || email.isEmpty()) return;
        prefs(context).edit().putString(provider.id + SUFFIX_EMAIL, email).apply();
    }

    /** When the provider last answered HTTP 429, so background refresh can back off. */
    public static long getRateLimitedAt(Context context, UsageProvider provider) {
        return prefs(context).getLong(provider.id + SUFFIX_RATE_LIMITED_AT, 0L);
    }

    public static void setRateLimitedAt(Context context, UsageProvider provider, long at) {
        prefs(context).edit().putLong(provider.id + SUFFIX_RATE_LIMITED_AT, at).apply();
    }

    /** First provider a widget shows; see {@link #getWidgetProviders}. */
    public static UsageProvider getWidgetProvider(Context context, int appWidgetId) {
        return getWidgetProviders(context, appWidgetId).get(0);
    }

    /**
     * Providers a home or lock-screen widget shows, in order; never empty. A widget that never
     * saved a choice shows {@link WidgetProviders#fallback}, which keeps older widgets on Codex.
     */
    public static List<UsageProvider> getWidgetProviders(Context context, int appWidgetId) {
        List<UsageProvider> saved = WidgetProviders.parse(
                prefs(context).getString(KEY_WIDGET_PROVIDER + appWidgetId, null));
        if (!saved.isEmpty()) {
            return saved;
        }
        List<UsageProvider> signedIn = new ArrayList<>();
        for (UsageProvider provider : UsageProvider.values()) {
            if (SecureTokenStore.isSignedIn(context, provider)) signedIn.add(provider);
        }
        List<UsageProvider> fallback = new ArrayList<>();
        fallback.add(WidgetProviders.fallback(getOrder(context), signedIn));
        return fallback;
    }

    /** Whether a widget has saved its own provider choice. */
    public static boolean hasSavedWidgetProviders(Context context, int appWidgetId) {
        return !WidgetProviders.parse(prefs(context).getString(KEY_WIDGET_PROVIDER + appWidgetId,
                null)).isEmpty();
    }

    /**
     * Providers a lock-screen widget shows, at most {@code max}. One UI rarely opens a lock
     * widget's own settings, so an unsaved widget shows the signed-in providers in tab order,
     * the same ones the home widgets and dashboard tabs show.
     */
    public static List<UsageProvider> getLockWidgetProviders(Context context, int appWidgetId,
            int max) {
        List<UsageProvider> saved = WidgetProviders.parse(
                prefs(context).getString(KEY_WIDGET_PROVIDER + appWidgetId, null));
        if (!saved.isEmpty()) {
            return saved.size() > max ? new ArrayList<>(saved.subList(0, max)) : saved;
        }
        List<UsageProvider> signedIn = new ArrayList<>();
        for (UsageProvider provider : UsageProvider.values()) {
            if (SecureTokenStore.isSignedIn(context, provider)) signedIn.add(provider);
        }
        return WidgetProviders.lockDefault(getEnabled(context), signedIn, max);
    }

    public static void setWidgetProvider(Context context, int appWidgetId,
            UsageProvider provider) {
        prefs(context).edit().putString(KEY_WIDGET_PROVIDER + appWidgetId, provider.id).apply();
    }

    public static void setWidgetProviders(Context context, int appWidgetId,
            List<UsageProvider> providers) {
        String csv = WidgetProviders.serialize(providers);
        if (csv.isEmpty()) return;
        prefs(context).edit().putString(KEY_WIDGET_PROVIDER + appWidgetId, csv).apply();
    }

    /** Moves a restored widget's saved providers to its new id. */
    public static void moveWidgetProviders(Context context, int oldId, int newId) {
        String saved = prefs(context).getString(KEY_WIDGET_PROVIDER + oldId, null);
        SharedPreferences.Editor editor = prefs(context).edit()
                .remove(KEY_WIDGET_PROVIDER + oldId);
        if (saved != null && !saved.isEmpty()) {
            editor.putString(KEY_WIDGET_PROVIDER + newId, saved);
        }
        editor.apply();
    }

    public static void clearWidgetProvider(Context context, int appWidgetId) {
        prefs(context).edit().remove(KEY_WIDGET_PROVIDER + appWidgetId).apply();
    }

    /** Whether any widget has saved this provider among the ones it shows. */
    public static boolean hasWidgetFor(Context context, UsageProvider provider) {
        for (java.util.Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(KEY_WIDGET_PROVIDER)
                    && entry.getValue() instanceof String
                    && WidgetProviders.parse((String) entry.getValue()).contains(provider)) {
                return true;
            }
        }
        return false;
    }

    public static UsageHistory loadUsageHistory(Context context, UsageProvider provider,
            String kind) {
        if (provider == UsageProvider.CODEX) {
            return AppPreferences.loadUsageHistory(context, kind);
        }
        String stored = prefs(context).getString(provider.id + SUFFIX_HISTORY + kind, null);
        if (stored == null || stored.isEmpty()) return UsageHistory.empty(kind);
        try {
            return UsageHistory.fromJson(new JSONObject(stored), kind);
        } catch (Exception ignored) {
            return UsageHistory.empty(kind);
        }
    }

    public static boolean saveUsageHistory(Context context, UsageProvider provider,
            UsageHistory history) {
        if (history == null) return false;
        if (provider == UsageProvider.CODEX) {
            return AppPreferences.saveUsageHistory(context, history);
        }
        try {
            return prefs(context).edit()
                    .putString(provider.id + SUFFIX_HISTORY + history.kind,
                            history.toJson().toString())
                    .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void clearUsageHistory(Context context, UsageProvider provider) {
        if (provider == UsageProvider.CODEX) {
            AppPreferences.clearUsageHistory(context);
            return;
        }
        prefs(context).edit()
                .remove(provider.id + SUFFIX_HISTORY + UsageHistory.FIVE_HOUR)
                .remove(provider.id + SUFFIX_HISTORY + UsageHistory.WEEKLY)
                .remove(provider.id + SUFFIX_HISTORY + UsageHistory.MONTHLY)
                .apply();
    }

    /** Removes cached usage, history, errors, and identity for a non-Codex provider. */
    public static void clearProvider(Context context, UsageProvider provider) {
        if (provider == UsageProvider.CODEX) return;
        prefs(context).edit()
                .remove(provider.id + SUFFIX_HISTORY + UsageHistory.FIVE_HOUR)
                .remove(provider.id + SUFFIX_HISTORY + UsageHistory.WEEKLY)
                .remove(provider.id + SUFFIX_HISTORY + UsageHistory.MONTHLY)
                .remove(provider.id + SUFFIX_SNAPSHOT)
                .remove(provider.id + SUFFIX_ERROR)
                .remove(provider.id + SUFFIX_ERROR_AT)
                .remove(provider.id + SUFFIX_EMAIL)
                .remove(provider.id + SUFFIX_RATE_LIMITED_AT)
                .commit();
    }
}
