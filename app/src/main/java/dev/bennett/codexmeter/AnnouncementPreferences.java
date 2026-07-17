package dev.bennett.codexmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable local state and user controls for the @thsottiaux reset watch. */
public final class AnnouncementPreferences {
    private static final String PREFS = "codex_meter_reset_watch_v1";
    private static final String KEY_MONITORING = "monitoring";
    private static final String KEY_INTERVAL = "interval_minutes";
    private static final String KEY_NOTIFICATIONS = "notifications";
    private static final String KEY_BANNER = "banner";
    private static final String KEY_USER_ID = "x_user_id";
    private static final String KEY_LAST_SEEN_ID = "last_seen_id";
    private static final String KEY_POSTS = "posts";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_DISMISSED = "dismissed";
    private static final int MAX_POSTS = 20;
    private static final long BANNER_MAX_AGE_MS = TimeUnit.HOURS.toMillis(72);

    private AnnouncementPreferences() {
    }

    public static boolean monitoringEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MONITORING, false);
    }

    public static void setMonitoringEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MONITORING, enabled).apply();
        if (!enabled) ResetWatchScheduler.cancel(context);
    }

    public static int intervalMinutes(Context context) {
        return normalizeInterval(prefs(context).getInt(KEY_INTERVAL, 30));
    }

    public static void setIntervalMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(KEY_INTERVAL, normalizeInterval(minutes)).apply();
    }

    static int normalizeInterval(int minutes) {
        return minutes == 60 ? 60 : 30;
    }

    public static boolean notificationsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS, true);
    }

    public static void setNotificationsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply();
        if (!enabled) ResetWatchNotificationManager.clearState(context);
    }

    public static boolean bannerEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BANNER, true);
    }

    public static void setBannerEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BANNER, enabled).apply();
        broadcast(context);
    }

    public static String userId(Context context) {
        return prefs(context).getString(KEY_USER_ID, "");
    }

    public static void setUserId(Context context, String id) {
        if (!XTimelineParser.isValidPostId(id)) {
            throw new IllegalArgumentException("X returned an invalid account ID.");
        }
        prefs(context).edit().putString(KEY_USER_ID, id).apply();
    }

    public static String lastSeenId(Context context) {
        return prefs(context).getString(KEY_LAST_SEEN_ID, "");
    }

    public static boolean hasBaseline(Context context) {
        return XTimelineParser.isValidPostId(lastSeenId(context));
    }

    public static long lastCheckMillis(Context context) {
        return prefs(context).getLong(KEY_LAST_CHECK, 0L);
    }

    public static String lastError(Context context) {
        return prefs(context).getString(KEY_LAST_ERROR, "");
    }

    public static void saveSuccess(Context context, List<XPost> incoming, String newestId)
            throws Exception {
        Map<String, XPost> merged = new LinkedHashMap<>();
        if (incoming != null) {
            for (XPost post : incoming) {
                if (post != null) merged.put(post.id, post);
            }
        }
        for (XPost post : posts(context)) {
            if (!merged.containsKey(post.id)) merged.put(post.id, post);
        }
        ArrayList<XPost> sorted = new ArrayList<>(merged.values());
        sorted.sort((left, right) -> -XTimelineParser.compareIds(left.id, right.id));
        if (sorted.size() > MAX_POSTS) {
            sorted = new ArrayList<>(sorted.subList(0, MAX_POSTS));
        }
        JSONArray stored = new JSONArray();
        for (XPost post : sorted) stored.put(post.toJson());

        String currentId = lastSeenId(context);
        String nextId = XTimelineParser.compareIds(newestId, currentId) > 0
                ? newestId : currentId;
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_POSTS, stored.toString())
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR);
        if (XTimelineParser.isValidPostId(nextId)) {
            editor.putString(KEY_LAST_SEEN_ID, nextId);
        }
        editor.apply();
        broadcast(context);
    }

    public static void saveError(Context context, String error) {
        String message = error == null ? "" : error.trim();
        if (message.isEmpty()) message = "Could not check X for Codex reset updates.";
        if (message.length() > 240) message = message.substring(0, 240);
        prefs(context).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_LAST_ERROR, message)
                .apply();
        broadcast(context);
    }

    public static List<XPost> posts(Context context) {
        String json = prefs(context).getString(KEY_POSTS, "[]");
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            ArrayList<XPost> parsed = new ArrayList<>();
            for (int index = 0; index < array.length() && parsed.size() < MAX_POSTS; index++) {
                JSONObject object = array.optJSONObject(index);
                XPost post = XPost.fromJson(object);
                if (post != null) parsed.add(post);
            }
            parsed.sort((left, right) -> -XTimelineParser.compareIds(left.id, right.id));
            return Collections.unmodifiableList(parsed);
        } catch (Exception exception) {
            return Collections.emptyList();
        }
    }

    public static XPost latestBanner(Context context) {
        if (!bannerEnabled(context)) return null;
        Set<String> dismissed = prefs(context).getStringSet(KEY_DISMISSED, new HashSet<>());
        long now = System.currentTimeMillis();
        for (XPost post : posts(context)) {
            if (post.signal.isRelevant() && !dismissed.contains(post.id)
                    && post.createdAtMillis > 0L
                    && now - post.createdAtMillis <= BANNER_MAX_AGE_MS) {
                return post;
            }
        }
        return null;
    }

    public static void dismiss(Context context, String postId) {
        if (!XTimelineParser.isValidPostId(postId)) return;
        Set<String> dismissed = new HashSet<>(prefs(context)
                .getStringSet(KEY_DISMISSED, new HashSet<>()));
        dismissed.add(postId);
        while (dismissed.size() > 50) {
            dismissed.remove(dismissed.iterator().next());
        }
        prefs(context).edit().putStringSet(KEY_DISMISSED, dismissed).apply();
        broadcast(context);
    }

    public static void clearRemoteState(Context context) {
        prefs(context).edit()
                .remove(KEY_USER_ID)
                .remove(KEY_LAST_SEEN_ID)
                .remove(KEY_POSTS)
                .remove(KEY_LAST_CHECK)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_DISMISSED)
                .apply();
        broadcast(context);
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void broadcast(Context context) {
        context.sendBroadcast(new android.content.Intent(AppConstants.ACTION_ANNOUNCEMENTS_UPDATED)
                        .setPackage(context.getPackageName())
                        .addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                AppConstants.INTERNAL_PERMISSION);
    }
}
