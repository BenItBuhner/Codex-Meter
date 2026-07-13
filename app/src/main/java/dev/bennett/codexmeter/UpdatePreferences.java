package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.List;

/** Durable updater state, isolated from account and usage preferences. */
public final class UpdatePreferences {
    private static final String PREFS = "codex_meter_updates_v1";
    private static final String KEY_AUTOMATIC = "automatic";
    private static final String KEY_ETAG = "etag";
    private static final String KEY_RELEASES = "releases";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_INSTALL_ERROR = "install_error";
    private static final int MAX_CACHE_LENGTH = 512 * 1024;
    private static final Object CACHE_LOCK = new Object();
    private static String cachedJson;
    private static List<GitHubRelease> cachedReleases;

    private UpdatePreferences() {
    }

    public static boolean automaticChecks(Context context) {
        return prefs(context).getBoolean(KEY_AUTOMATIC, true);
    }

    public static void setAutomaticChecks(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTOMATIC, enabled).apply();
    }

    public static long lastCheckMillis(Context context) {
        return prefs(context).getLong(KEY_LAST_CHECK, 0L);
    }

    public static String etag(Context context) {
        return prefs(context).getString(KEY_ETAG, "");
    }

    public static String lastError(Context context) {
        return prefs(context).getString(KEY_LAST_ERROR, "");
    }

    public static void saveSuccess(Context context, String json, String etag) throws Exception {
        if (json == null || json.length() > MAX_CACHE_LENGTH) {
            throw new IllegalArgumentException("GitHub returned too much release metadata.");
        }
        List<GitHubRelease> parsed = GitHubReleaseParser.parse(json, BuildConfig.DEBUG);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_RELEASES, json)
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR);
        if (etag == null || etag.trim().isEmpty()) {
            editor.remove(KEY_ETAG);
        } else {
            editor.putString(KEY_ETAG, etag.trim());
        }
        editor.apply();
        synchronized (CACHE_LOCK) {
            cachedJson = json;
            cachedReleases = parsed;
        }
        broadcast(context);
    }

    public static void markNotModified(Context context) {
        prefs(context).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply();
        broadcast(context);
    }

    public static void saveError(Context context, String error) {
        prefs(context).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_LAST_ERROR, safe(error, "Could not check GitHub releases."))
                .apply();
        broadcast(context);
    }

    public static List<GitHubRelease> releases(Context context) {
        String json = prefs(context).getString(KEY_RELEASES, "[]");
        synchronized (CACHE_LOCK) {
            if (json.equals(cachedJson) && cachedReleases != null) {
                return cachedReleases;
            }
        }
        try {
            List<GitHubRelease> parsed = GitHubReleaseParser.parse(json, BuildConfig.DEBUG);
            synchronized (CACHE_LOCK) {
                cachedJson = json;
                cachedReleases = parsed;
            }
            return parsed;
        } catch (Exception exception) {
            return Collections.emptyList();
        }
    }

    public static boolean hasUsableCache(Context context) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(KEY_RELEASES)) {
            return false;
        }
        String json = preferences.getString(KEY_RELEASES, "");
        try {
            GitHubReleaseParser.parse(json, BuildConfig.DEBUG);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public static void clearEtag(Context context) {
        prefs(context).edit().remove(KEY_ETAG).apply();
    }

    public static GitHubRelease latestStable(Context context) {
        return GitHubReleaseParser.latestStable(releases(context));
    }

    public static GitHubRelease findVersion(Context context, String version) {
        return GitHubReleaseParser.findVersion(releases(context), version);
    }

    public static GitHubRelease availableUpdate(Context context) {
        GitHubRelease latest = latestStable(context);
        return latest != null && latest.isNewerThan(installedVersion(context)) ? latest : null;
    }

    public static String installedVersion(Context context) {
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return version == null || version.trim().isEmpty()
                    ? AppConstants.VERSION_NAME : version;
        } catch (Exception exception) {
            return AppConstants.VERSION_NAME;
        }
    }

    public static void setInstallError(Context context, String error) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (error == null || error.trim().isEmpty()) {
            editor.remove(KEY_INSTALL_ERROR);
        } else {
            editor.putString(KEY_INSTALL_ERROR, safe(error, "Update installation failed."));
        }
        editor.apply();
        broadcast(context);
    }

    public static String installError(Context context) {
        return prefs(context).getString(KEY_INSTALL_ERROR, "");
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void broadcast(Context context) {
        context.sendBroadcast(new android.content.Intent(AppConstants.ACTION_RELEASES_UPDATED)
                .setPackage(context.getPackageName())
                .addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                AppConstants.INTERNAL_PERMISSION);
    }

    private static String safe(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) {
            result = fallback;
        }
        return result.length() <= 240 ? result : result.substring(0, 240);
    }
}
