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

    /** Removes cached usage, errors, and identity for a non-Codex provider after sign-out. */
    public static void clearProvider(Context context, UsageProvider provider) {
        if (provider == UsageProvider.CODEX) return;
        prefs(context).edit()
                .remove(provider.id + SUFFIX_SNAPSHOT)
                .remove(provider.id + SUFFIX_ERROR)
                .remove(provider.id + SUFFIX_ERROR_AT)
                .remove(provider.id + SUFFIX_EMAIL)
                .commit();
    }
}
