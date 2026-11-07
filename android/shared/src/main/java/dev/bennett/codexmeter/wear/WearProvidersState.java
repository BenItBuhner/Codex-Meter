package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.UsageSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Every provider the phone shows as a tab, in tab order, with its cached usage. The phone
 * sends this on {@link WearSyncPaths#PATH_PROVIDERS}; the watch keeps the latest copy so its
 * app, tiles and complications can follow any provider, not only Codex.
 *
 * <p>Tokens and account emails never leave the phone: an entry carries only the provider id,
 * its display name, whether it is signed in, the sanitized usage snapshot and the last error.
 */
public final class WearProvidersState {
    /** Snapshots bigger than this are dropped so the Data Layer item stays well under 100 KB. */
    static final int MAX_SNAPSHOT_CHARS = 6_000;
    static final int MAX_ERROR_CHARS = 160;
    public static final int MAX_PROVIDERS = 24;

    public final List<Entry> providers;
    /** The tab selected on the phone, used until the watch picks its own provider. */
    public final String phoneSelectedId;
    public final long updatedAtMillis;

    public WearProvidersState(List<Entry> providers, String phoneSelectedId,
            long updatedAtMillis) {
        List<Entry> copy = new ArrayList<>();
        if (providers != null) {
            for (Entry entry : providers) {
                if (entry != null && copy.size() < MAX_PROVIDERS && find(copy, entry.id) == null) {
                    copy.add(entry);
                }
            }
        }
        this.providers = Collections.unmodifiableList(copy);
        this.phoneSelectedId = normalizeId(phoneSelectedId);
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    /** The entry for {@code id}, or null when the phone did not send it. */
    public Entry find(String id) {
        return find(providers, normalizeId(id));
    }

    /**
     * The provider the watch should show: the watch's own choice when the phone still sends
     * it, otherwise the phone's selected tab, otherwise the first signed-in provider, otherwise
     * the first one. Null only when the list is empty.
     */
    public Entry resolve(String watchChoiceId) {
        Entry chosen = find(watchChoiceId);
        if (chosen != null) return chosen;
        chosen = find(phoneSelectedId);
        if (chosen != null) return chosen;
        for (Entry entry : providers) {
            if (entry.signedIn) return entry;
        }
        return providers.isEmpty() ? null : providers.get(0);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        JSONArray list = new JSONArray();
        for (Entry entry : providers) {
            list.put(entry.toJson());
        }
        json.put("providers", list);
        json.put("phone_selected", phoneSelectedId);
        json.put("updated_at_millis", updatedAtMillis);
        return json;
    }

    public static WearProvidersState fromJson(JSONObject json) {
        if (json == null) return null;
        List<Entry> entries = new ArrayList<>();
        JSONArray list = json.optJSONArray("providers");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                Entry entry = Entry.fromJson(list.optJSONObject(i));
                if (entry != null) entries.add(entry);
            }
        }
        return new WearProvidersState(entries, json.optString("phone_selected", ""),
                json.optLong("updated_at_millis", 0L));
    }

    public static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    private static Entry find(List<Entry> entries, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Entry entry : entries) {
            if (entry.id.equals(id)) return entry;
        }
        return null;
    }

    /** One provider tab as the watch sees it. */
    public static final class Entry {
        public final String id;
        public final String displayName;
        public final boolean signedIn;
        /** Latest cached usage, or null before the first refresh / after sign-out. */
        public final UsageSnapshot snapshot;
        public final String lastError;

        public Entry(String id, String displayName, boolean signedIn, UsageSnapshot snapshot,
                String lastError) {
            this.id = normalizeId(id);
            String name = displayName == null ? "" : displayName.trim();
            this.displayName = name.isEmpty() ? this.id : name;
            this.signedIn = signedIn;
            this.snapshot = signedIn ? snapshot : null;
            this.lastError = trim(lastError, MAX_ERROR_CHARS);
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", displayName);
            json.put("signed_in", signedIn);
            if (snapshot != null) {
                JSONObject usage = snapshot.toJson();
                if (usage.toString().length() <= MAX_SNAPSHOT_CHARS) {
                    json.put("usage", usage);
                }
            }
            if (!lastError.isEmpty()) json.put("error", lastError);
            return json;
        }

        static Entry fromJson(JSONObject json) {
            if (json == null) return null;
            String id = normalizeId(json.optString("id", ""));
            if (id.isEmpty()) return null;
            return new Entry(id, json.optString("name", id),
                    json.optBoolean("signed_in", false),
                    UsageSnapshot.fromJson(json.optJSONObject("usage")),
                    json.optString("error", ""));
        }

        private static String trim(String value, int max) {
            if (value == null) return "";
            String clean = value.trim();
            return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
        }
    }
}
