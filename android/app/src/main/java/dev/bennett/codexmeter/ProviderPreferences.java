package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
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

    public static UsageProvider getSelected(Context context) {
        return UsageProvider.fromId(prefs(context).getString(KEY_SELECTED, null));
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

    /** Provider a home-screen widget shows; widgets made before providers existed show Codex. */
    public static UsageProvider getWidgetProvider(Context context, int appWidgetId) {
        return UsageProvider.fromId(prefs(context).getString(KEY_WIDGET_PROVIDER + appWidgetId,
                null));
    }

    public static void setWidgetProvider(Context context, int appWidgetId,
            UsageProvider provider) {
        prefs(context).edit().putString(KEY_WIDGET_PROVIDER + appWidgetId, provider.id).apply();
    }

    public static void clearWidgetProvider(Context context, int appWidgetId) {
        prefs(context).edit().remove(KEY_WIDGET_PROVIDER + appWidgetId).apply();
    }

    /** Whether any home-screen widget is set to this provider. */
    public static boolean hasWidgetFor(Context context, UsageProvider provider) {
        for (java.util.Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(KEY_WIDGET_PROVIDER)
                    && provider.id.equals(entry.getValue())) {
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
