package dev.bennett.codexmeter;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Settings for showing the live monitor while a matched AI app is open. */
public final class AppSessionPreferences {
    private static final String PREFS = "codex_meter_app_sessions_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ADDED = "added";
    private static final String KEY_OFF = "off";

    private AppSessionPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Apps the user added: package name to provider id. */
    public static Map<String, String> getAdded(Context context) {
        Map<String, String> added = new LinkedHashMap<>();
        String json = prefs(context).getString(KEY_ADDED, null);
        if (json == null || json.isEmpty()) return added;
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                added.put(key, object.optString(key, UsageProvider.DEFAULT.id));
            }
        } catch (Exception ignored) {
            // A damaged value only loses the added apps; the official ones still work.
        }
        return added;
    }

    public static void addApp(Context context, String packageName, UsageProvider provider) {
        Map<String, String> added = getAdded(context);
        added.put(packageName, provider.id);
        Set<String> off = getOff(context);
        off.remove(packageName);
        prefs(context).edit()
                .putString(KEY_ADDED, new JSONObject(added).toString())
                .putStringSet(KEY_OFF, off)
                .apply();
    }

    public static void removeApp(Context context, String packageName) {
        Map<String, String> added = getAdded(context);
        added.remove(packageName);
        prefs(context).edit().putString(KEY_ADDED, new JSONObject(added).toString()).apply();
    }

    /** Packages the user switched off, official or added. */
    public static Set<String> getOff(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_OFF, new HashSet<>()));
    }

    public static void setAppOn(Context context, String packageName, boolean on) {
        Set<String> off = getOff(context);
        if (on) off.remove(packageName);
        else off.add(packageName);
        prefs(context).edit().putStringSet(KEY_OFF, off).apply();
    }

    public static boolean isAppOn(Context context, String packageName) {
        return !getOff(context).contains(packageName);
    }

    /** The app-to-provider map the detector uses right now. */
    public static Map<String, UsageProvider> mapping(Context context) {
        return AppSessionRules.resolve(getAdded(context), getOff(context));
    }

    /** Whether the user turned on Codex Meter's app detection in Accessibility settings. */
    public static boolean isDetectionAllowed(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName ours = new ComponentName(context, AppSessionService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String entry : splitter) {
            ComponentName name = ComponentName.unflattenFromString(entry);
            if (ours.equals(name)) return true;
        }
        return false;
    }
}
