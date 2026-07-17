package dev.bennett.codexmeter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parses the bounded X API v2 user-posts response used by the reset watch. */
public final class XTimelineParser {
    private XTimelineParser() {
    }

    public static List<XPost> parse(String json) throws Exception {
        JSONObject root = new JSONObject(json == null || json.trim().isEmpty() ? "{}" : json);
        JSONArray data = root.optJSONArray("data");
        if (data == null) return Collections.emptyList();

        ArrayList<XPost> posts = new ArrayList<>();
        for (int index = 0; index < data.length() && posts.size() < 100; index++) {
            JSONObject item = data.optJSONObject(index);
            if (item == null) continue;
            String id = item.optString("id", "");
            String text = clean(item.optString("text", ""), 2000);
            if (!isValidPostId(id) || text.isEmpty()) continue;
            long createdAt = parseInstant(item.optString("created_at", ""));
            posts.add(new XPost(id, text, createdAt,
                    ResetAnnouncementClassifier.classify(text)));
        }
        posts.sort(new Comparator<XPost>() {
            @Override
            public int compare(XPost left, XPost right) {
                int time = Long.compare(right.createdAtMillis, left.createdAtMillis);
                return time != 0 ? time : -compareIds(left.id, right.id);
            }
        });
        return Collections.unmodifiableList(posts);
    }

    static String newestId(String json, List<XPost> posts) {
        try {
            JSONObject meta = new JSONObject(json == null ? "{}" : json).optJSONObject("meta");
            String newest = meta == null ? "" : meta.optString("newest_id", "");
            if (isValidPostId(newest)) return newest;
        } catch (Exception ignored) {
        }
        String newest = "";
        if (posts != null) {
            for (XPost post : posts) {
                if (post != null && compareIds(post.id, newest) > 0) newest = post.id;
            }
        }
        return newest;
    }

    static int compareIds(String left, String right) {
        String a = isValidPostId(left) ? stripLeadingZeroes(left) : "";
        String b = isValidPostId(right) ? stripLeadingZeroes(right) : "";
        if (a.length() != b.length()) return Integer.compare(a.length(), b.length());
        return a.compareTo(b);
    }

    static boolean isValidPostId(String value) {
        if (value == null || value.isEmpty() || value.length() > 32) return false;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
        }
        return true;
    }

    private static String stripLeadingZeroes(String value) {
        int index = 0;
        while (index + 1 < value.length() && value.charAt(index) == '0') index++;
        return value.substring(index);
    }

    private static long parseInstant(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String clean(String value, int max) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
