package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.UsageSnapshot;
import java.nio.charset.StandardCharsets;
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
    /**
     * The Data Layer rejects items over 100 KB, and then the watch gets nothing at all. The
     * providers item is built to stay under this, with room left for the envelope.
     */
    static final int MAX_ITEM_BYTES = 90_000;
    /** No one provider's usage may take more than this, so a long list of named limits can't crowd out the rest. */
    static final int MAX_USAGE_BYTES = 6_000;
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

    /**
     * Every signed-in provider always carries its main windows (5-hour, weekly, monthly, credits).
     * Named limits are then added provider by provider, the phone's selected tab first and the
     * rest in tab order, for as long as the item stays under {@link #MAX_ITEM_BYTES}. A provider
     * that doesn't fit keeps its first named limits and loses the rest, rather than going blank.
     */
    public JSONObject toJson() throws JSONException {
        int count = providers.size();
        JSONObject[] items = new JSONObject[count];
        int[] sizes = new int[count];
        int total = 128; // the envelope: phone_selected, updated_at_millis, brackets
        for (int i = 0; i < count; i++) {
            items[i] = providers.get(i).toJson(0);
            sizes[i] = bytes(items[i]);
            total += sizes[i] + 1;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (providers.get(i).id.equals(phoneSelectedId)) order.add(0, i);
            else order.add(i);
        }
        for (int i : order) {
            Entry entry = providers.get(i);
            if (entry.snapshot == null || entry.snapshot.additionalLimits.isEmpty()) continue;
            int spare = MAX_ITEM_BYTES - total;
            // The most named limits that fit, found by bisection since size only grows with the count.
            int low = 0;
            int high = entry.snapshot.additionalLimits.size();
            JSONObject best = items[i];
            int bestSize = sizes[i];
            while (low < high) {
                int mid = (low + high + 1) / 2;
                JSONObject candidate = entry.toJson(mid);
                int size = bytes(candidate);
                if (candidate.has("usage") && size - sizes[i] <= spare) {
                    low = mid;
                    best = candidate;
                    bestSize = size;
                } else {
                    high = mid - 1;
                }
            }
            total += bestSize - sizes[i];
            items[i] = best;
            sizes[i] = bestSize;
        }
        JSONArray list = new JSONArray();
        for (JSONObject item : items) list.put(item);
        JSONObject json = new JSONObject();
        json.put("providers", list);
        json.put("phone_selected", phoneSelectedId);
        json.put("updated_at_millis", updatedAtMillis);
        return json;
    }

    private static int bytes(JSONObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8).length;
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

        /** This entry with its usage cut to the first {@code limits} named limits, if it fits {@link #MAX_USAGE_BYTES}. */
        JSONObject toJson(int limits) throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", displayName);
            json.put("signed_in", signedIn);
            if (snapshot != null) {
                JSONObject usage = snapshot.withFirstLimits(limits).toJson();
                if (usage.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_USAGE_BYTES) {
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
