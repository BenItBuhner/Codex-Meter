package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Notices models that newly appear in public model catalogs, for the Priority view's "New models"
 * card and the optional "New models" notification. Pure Java so the self-tests cover it.
 *
 * <p>Every catalog is public and fetched without a key. A model shows only when an explicit
 * allowlist ties it to a connected provider (see {@link #scopesFor}); there is no fuzzy matching.
 * "New" means newly listed in a catalog, not newly released, and never that the user's own
 * subscription can use it, so every label says so.
 *
 * <p>Safeguards: the first complete catalog from a source is a silent baseline; a model must be
 * seen in two complete responses before it counts; alias renames, batch or free variants and
 * long-existing models are recorded but not announced; failed or partial fetches change nothing,
 * and models missing from a response are never treated as retired.
 */
public final class ModelDiscovery {
    private ModelDiscovery() {}

    /** A public catalog. */
    public enum Source {
        OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/models"),
        OPENCODE_GO("opencode_go", "OpenCode Go", "https://opencode.ai/zen/go/v1/models"),
        KILO("kilo", "Kilo", "https://api.kilo.ai/api/gateway/models"),
        CHUTES("chutes", "Chutes", "https://llm.chutes.ai/v1/models"),
        SYNTHETIC("synthetic", "Synthetic", "https://api.synthetic.new/openai/v1/models");

        public final String id;
        public final String label;
        public final String url;

        Source(String id, String label, String url) {
            this.id = id;
            this.label = label;
            this.url = url;
        }

        static Source byId(String id) {
            for (Source s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return null;
        }

        /** "New on OpenRouter", "New in OpenCode Go". */
        public String newOn() {
            return this == OPENCODE_GO ? "New in " + label : "New on " + label;
        }
    }

    /** One allowlist entry: a whole catalog, or one author's models in it ("openai/…"). */
    public static final class Scope {
        public final Source source;
        /** OpenRouter author slug, or null for the whole catalog. */
        public final String author;

        Scope(Source source, String author) {
            this.source = source;
            this.author = author;
        }

        boolean covers(Source s, String modelId) {
            return source == s && (author == null || modelId.startsWith(author + "/"));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Scope && ((Scope) o).source == source
                    && (author == null ? ((Scope) o).author == null : author.equals(((Scope) o).author));
        }

        @Override
        public int hashCode() {
            return source.hashCode() * 31 + (author == null ? 0 : author.hashCode());
        }
    }

    /**
     * The explicit mapping from a connected provider to the catalog models it makes relevant: a
     * model maker's own subscription covers that maker's models (OpenRouter author slugs), and a
     * tool that offers several makers' models covers exactly the makers it documents offering.
     * Nothing is matched by name, and a provider missing here unlocks nothing.
     */
    public static List<Scope> scopesFor(UsageProvider provider) {
        if (provider == null) {
            return Collections.emptyList();
        }
        switch (provider) {
            case OPENROUTER:
                return Collections.singletonList(new Scope(Source.OPENROUTER, null));
            case CODEX:
                return Collections.singletonList(new Scope(Source.OPENROUTER, "openai"));
            case CLAUDE_CODE:
                return Collections.singletonList(new Scope(Source.OPENROUTER, "anthropic"));
            case GROK:
                return Collections.singletonList(new Scope(Source.OPENROUTER, "x-ai"));
            case CURSOR:
            case COPILOT:
            case WINDSURF:
            case CLINE:
                return authors("openai", "anthropic", "google", "x-ai");
            case FACTORY:
            case AMP:
            case CODEBUFF:
            case WARP:
                return authors("openai", "anthropic", "google");
            case ANTIGRAVITY:
                return authors("google", "anthropic");
            case DEVIN:
            case AUGMENT:
                return authors("anthropic", "openai");
            case KIRO:
                return authors("anthropic");
            case MINIMAX:
                return Collections.singletonList(new Scope(Source.OPENROUTER, "minimax"));
            case KIMI:
                return Collections.singletonList(new Scope(Source.OPENROUTER, "moonshotai"));
            case ZAI:
                return Collections.singletonList(new Scope(Source.OPENROUTER, "z-ai"));
            case OPENCODE_GO:
                return Collections.singletonList(new Scope(Source.OPENCODE_GO, null));
            case KILO:
                return Collections.singletonList(new Scope(Source.KILO, null));
            case CHUTES:
                return Collections.singletonList(new Scope(Source.CHUTES, null));
            case SYNTHETIC:
                return Collections.singletonList(new Scope(Source.SYNTHETIC, null));
            default:
                return Collections.emptyList();
        }
    }

    private static List<Scope> authors(String... slugs) {
        List<Scope> out = new ArrayList<>();
        for (String slug : slugs) {
            out.add(new Scope(Source.OPENROUTER, slug));
        }
        return out;
    }

    /** The union of the connected providers' scopes; empty means nothing is fetched or shown. */
    public static List<Scope> allowlist(Collection<UsageProvider> connected) {
        Set<Scope> out = new java.util.LinkedHashSet<>();
        if (connected != null) {
            for (UsageProvider p : connected) {
                out.addAll(scopesFor(p));
            }
        }
        return new ArrayList<>(out);
    }

    public static boolean relevant(List<Scope> allow, Source source, String modelId) {
        for (Scope s : allow) {
            if (s.covers(source, modelId)) {
                return true;
            }
        }
        return false;
    }

    public static Set<Source> sourcesNeeded(List<Scope> allow) {
        Set<Source> out = EnumSet.noneOf(Source.class);
        for (Scope s : allow) {
            out.add(s.source);
        }
        return out;
    }

    // ---- Parsing ---------------------------------------------------------------------------------

    /** One listed model. */
    public static final class Model {
        public final String id;
        public final String name;
        /** The catalog's stable identity behind the id (OpenRouter canonical_slug and similar). */
        public final String canonical;
        /** Seconds since the epoch, or 0 when the catalog doesn't say. */
        public final long created;
        /** Explicit lifecycle metadata ("preview", "beta"), or "" when the catalog has none. */
        public final String stage;

        public Model(String id, String name, String canonical, long created, String stage) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? id : name;
            this.canonical = canonical == null ? "" : canonical;
            this.created = created;
            this.stage = stage == null ? "" : stage;
        }
    }

    /** One response page. Null {@link #models} means it wasn't a catalog at all. */
    public static final class Page {
        public final List<Model> models;
        /** OpenRouter's next-page link, or null on the last page. */
        public final String next;
        /** The catalog's own total, or -1 when it doesn't give one. */
        public final int total;

        Page(List<Model> models, String next, int total) {
            this.models = models;
            this.next = next;
            this.total = total;
        }
    }

    public static Page parse(Source source, String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null) {
                return new Page(null, null, -1);
            }
            List<Model> models = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                String id = m == null ? "" : m.optString("id", "").trim();
                if (id.isEmpty()) {
                    continue;
                }
                String name = m.optString("name", "");
                if (name.isEmpty() || name.equals(id)) {
                    name = m.optString("display_name", name);
                }
                int colon = name.indexOf(": ");
                if (colon > 0 && (source == Source.OPENROUTER || source == Source.KILO)) {
                    name = name.substring(colon + 2);
                }
                String canonical = firstNonEmpty(m.optString("canonical_slug", ""),
                        m.optString("canonical_id", ""), m.optString("hugging_face_id", ""),
                        m.optString("root", ""));
                String stage = "";
                for (String key : new String[] {"status", "stage", "lifecycle"}) {
                    String v = m.optString(key, "").toLowerCase(Locale.ROOT);
                    if (v.equals("preview") || v.equals("beta") || v.equals("experimental")) {
                        stage = v;
                    }
                }
                models.add(new Model(id, name, canonical, Math.max(0L, m.optLong("created", 0L)), stage));
            }
            String next = null;
            JSONObject links = root.optJSONObject("links");
            if (links != null && !links.isNull("next")) {
                next = links.optString("next", null);
            }
            int total = root.has("total_count") ? root.optInt("total_count", -1) : -1;
            return new Page(models, next == null || next.isEmpty() ? null : next, total);
        } catch (JSONException | NullPointerException e) {
            return new Page(null, null, -1);
        }
    }

    /**
     * The next page's absolute URL, only when it stays on the catalog's own host over HTTPS;
     * anything else ends paging (the response then counts as partial if the total isn't reached).
     */
    public static String nextUrl(Source source, String next) {
        if (next == null || next.isEmpty()) {
            return null;
        }
        String host = source.url.substring(0, source.url.indexOf('/', "https://".length()));
        if (next.startsWith("/") && !next.startsWith("//")) {
            return host + next;
        }
        return next.startsWith(host + "/") ? next : null;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * Router aliases ("~openai/…"), routing variants ("…:free", "…:nitro") and batch variants are
     * the same model under another name, so they never count as launches. Synthetic's own ids use
     * colons as part of the name, so the variant rule only applies to OpenRouter-style catalogs.
     */
    public static boolean isVariant(Source source, String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("~") || lower.contains("batch") || lower.endsWith("-latest")
                || lower.endsWith("~latest")) {
            return true;
        }
        return (source == Source.OPENROUTER || source == Source.KILO) && lower.indexOf(':') >= 0;
    }

    // ---- State -----------------------------------------------------------------------------------

    /** How a model entered the record. Only {@link #NEW} is ever shown. */
    public enum Kind { BASELINE, PENDING, NEW, RENAME, VARIANT, OLD }

    public static final class Entry {
        public final Source source;
        public final String id;
        public String name;
        public String canonical;
        public String stage;
        public long created;
        public long firstSeen;
        public long lastSeen;
        public int sightings;
        public long confirmedAt;
        public Kind kind;

        Entry(Source source, String id) {
            this.source = source;
            this.id = id;
        }

        String key() {
            return source.id + "|" + id;
        }

        /** "New on OpenRouter: GPT-6 — subscription availability not verified". */
        public String label() {
            return source.newOn() + ": " + name + (stage.isEmpty() ? "" : " (" + stage + ")")
                    + " — subscription availability not verified";
        }
    }

    /** The card's footnote: listing isn't release, and nothing says the plan includes it. */
    public static final String CARD_NOTE = "Newly listed in a public catalog, not necessarily newly "
            + "released. Subscription availability not verified.";

    /** Announced launches stay on the card for a week unless dismissed. */
    public static final long CARD_DAYS_MS = TimeUnit.DAYS.toMillis(7);
    /** A model the catalog says existed this long before we first saw it isn't a launch. */
    static final long OLD_MODEL_MS = TimeUnit.DAYS.toMillis(14);
    /** Records of models gone this long from complete responses are forgotten. */
    static final long FORGET_MS = TimeUnit.DAYS.toMillis(30);

    public static final class State {
        /** Baseline per source and account ("openrouter|public") → when it was seeded. */
        public final Map<String, Long> baselines = new HashMap<>();
        /** Last complete response per source. */
        public final Map<String, Long> lastComplete = new HashMap<>();
        public final Map<String, Entry> entries = new LinkedHashMap<>();
        /** Card surface: entry keys people dismissed. */
        public final Set<String> dismissed = new HashSet<>();
        /** Notification surface: entry keys already notified (or skipped as backlog). */
        public final Set<String> notified = new HashSet<>();

        public static String baselineKey(Source source, String account) {
            return source.id + "|" + account;
        }

        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("v", 1);
                o.put("baselines", new JSONObject(baselines));
                o.put("complete", new JSONObject(lastComplete));
                JSONArray list = new JSONArray();
                for (Entry e : entries.values()) {
                    list.put(new JSONObject().put("s", e.source.id).put("id", e.id).put("n", e.name)
                            .put("c", e.canonical).put("g", e.stage).put("cr", e.created)
                            .put("f", e.firstSeen).put("l", e.lastSeen).put("x", e.sightings)
                            .put("k", e.confirmedAt).put("t", e.kind.name()));
                }
                o.put("entries", list);
                o.put("dismissed", new JSONArray(dismissed));
                o.put("notified", new JSONArray(notified));
                return o.toString();
            } catch (JSONException e) {
                return "{}";
            }
        }

        public static State fromJson(String json) {
            State s = new State();
            if (json == null || json.isEmpty()) {
                return s;
            }
            try {
                JSONObject o = new JSONObject(json);
                readLongs(o.optJSONObject("baselines"), s.baselines);
                readLongs(o.optJSONObject("complete"), s.lastComplete);
                JSONArray list = o.optJSONArray("entries");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject j = list.getJSONObject(i);
                    Source src = Source.byId(j.optString("s"));
                    if (src == null) {
                        continue;
                    }
                    Entry e = new Entry(src, j.getString("id"));
                    e.name = j.optString("n", e.id);
                    e.canonical = j.optString("c", "");
                    e.stage = j.optString("g", "");
                    e.created = j.optLong("cr", 0L);
                    e.firstSeen = j.optLong("f", 0L);
                    e.lastSeen = j.optLong("l", 0L);
                    e.sightings = j.optInt("x", 0);
                    e.confirmedAt = j.optLong("k", 0L);
                    try {
                        e.kind = Kind.valueOf(j.optString("t", "BASELINE"));
                    } catch (IllegalArgumentException bad) {
                        e.kind = Kind.BASELINE;
                    }
                    s.entries.put(e.key(), e);
                }
                readStrings(o.optJSONArray("dismissed"), s.dismissed);
                readStrings(o.optJSONArray("notified"), s.notified);
            } catch (JSONException | NullPointerException e) {
                return new State();
            }
            return s;
        }

        private static void readLongs(JSONObject o, Map<String, Long> out) {
            if (o == null) {
                return;
            }
            for (java.util.Iterator<String> it = o.keys(); it.hasNext();) {
                String k = it.next();
                out.put(k, o.optLong(k, 0L));
            }
        }

        private static void readStrings(JSONArray a, Set<String> out) {
            for (int i = 0; a != null && i < a.length(); i++) {
                out.add(a.optString(i));
            }
        }
    }

    /**
     * Records one fetch. A partial or failed response ({@code complete} false) changes nothing:
     * the catalog is just stale. The first complete response for a source and account seeds the
     * baseline silently. Returns the models confirmed as new by this response.
     */
    public static List<Entry> observe(State state, Source source, String account, List<Model> models,
            boolean complete, long now) {
        List<Entry> confirmed = new ArrayList<>();
        if (!complete || models == null || models.isEmpty()) {
            return confirmed;
        }
        String baseline = State.baselineKey(source, account);
        boolean seeding = !state.baselines.containsKey(baseline);
        Map<String, Entry> byCanonical = new HashMap<>();
        for (Entry e : state.entries.values()) {
            if (e.source == source && !e.canonical.isEmpty()) {
                byCanonical.put(e.canonical, e);
            }
        }
        for (Model m : models) {
            String key = source.id + "|" + m.id;
            Entry e = state.entries.get(key);
            if (e == null) {
                e = new Entry(source, m.id);
                e.firstSeen = now;
                e.canonical = m.canonical;
                Entry sameModel = m.canonical.isEmpty() ? null : byCanonical.get(m.canonical);
                if (seeding) {
                    e.kind = Kind.BASELINE;
                } else if (isVariant(source, m.id)) {
                    e.kind = Kind.VARIANT;
                } else if (sameModel != null && !sameModel.id.equals(m.id)) {
                    e.kind = Kind.RENAME;
                } else if (m.created > 0L && now - m.created * 1000L > OLD_MODEL_MS) {
                    e.kind = Kind.OLD;
                } else {
                    e.kind = Kind.PENDING;
                }
                state.entries.put(key, e);
            }
            // Pricing, names and other edits update the record but are never launches.
            e.name = m.name;
            e.stage = m.stage;
            e.created = m.created;
            if (!m.canonical.isEmpty()) {
                e.canonical = m.canonical;
            }
            e.lastSeen = now;
            e.sightings++;
            if (e.kind == Kind.PENDING && e.sightings >= 2) {
                e.kind = Kind.NEW;
                e.confirmedAt = now;
                confirmed.add(e);
            }
        }
        if (seeding) {
            state.baselines.put(baseline, now);
        }
        state.lastComplete.put(source.id, now);
        // Forget records long gone from complete responses; this is bookkeeping, not a retirement.
        java.util.Iterator<Entry> it = state.entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.source == source && now - e.lastSeen > FORGET_MS) {
                it.remove();
                state.dismissed.remove(e.key());
                state.notified.remove(e.key());
            }
        }
        return confirmed;
    }

    /** The card's models: announced, relevant now, recent and not dismissed. Newest first. */
    public static List<Entry> cardItems(State state, List<Scope> allow, long now) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : state.entries.values()) {
            if (e.kind == Kind.NEW && now - e.confirmedAt < CARD_DAYS_MS
                    && !state.dismissed.contains(e.key()) && relevant(allow, e.source, e.id)) {
                out.add(e);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.confirmedAt, a.confirmedAt));
        return out;
    }

    public static void dismiss(State state, List<Entry> shown) {
        for (Entry e : shown) {
            state.dismissed.add(e.key());
        }
    }

    /**
     * Turning notifications on marks everything already announced as handled, so no backlog is
     * replayed; only models confirmed afterwards can notify.
     */
    public static void markAllNotified(State state) {
        for (Entry e : state.entries.values()) {
            if (e.kind == Kind.NEW) {
                state.notified.add(e.key());
            }
        }
    }

    /** Relevant announced models not yet notified; marks them notified (one digest per batch). */
    public static List<Entry> takeDigest(State state, List<Scope> allow, long now) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : cardItems(state, allow, now)) {
            if (state.notified.add(e.key())) {
                out.add(e);
            }
        }
        // Irrelevant ones are marked too, so connecting a provider later doesn't replay them.
        markAllNotified(state);
        return out;
    }

    /** One grouped notification line: "GPT-6 on OpenRouter, and 2 more". */
    public static String digestText(List<Entry> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int shown = Math.min(3, items.size());
        for (int i = 0; i < shown; i++) {
            b.append(i == 0 ? "" : "\n").append(items.get(i).source.newOn()).append(": ")
                    .append(items.get(i).name);
        }
        if (items.size() > shown) {
            b.append("\nand ").append(items.size() - shown).append(" more");
        }
        b.append("\nNewly listed, not necessarily newly released. Subscription availability not verified.");
        return b.toString();
    }

    // ---- Cadence ---------------------------------------------------------------------------------

    /** Opening or resuming the app checks at most this often. */
    public static final long MIN_GAP_MS = TimeUnit.MINUTES.toMillis(30);

    /**
     * How long to wait between catalog checks: about 30 minutes while the app is used a lot,
     * about 60 otherwise, about 120 overnight (1 to 6 a.m.) when it isn't, and much longer after
     * days without opening the app. Independent of usage-refresh jobs, however often they run.
     */
    public static long intervalMs(double engagement, int localHour, long sinceOpenMs) {
        if (sinceOpenMs > TimeUnit.DAYS.toMillis(7)) {
            return TimeUnit.HOURS.toMillis(24);
        }
        if (sinceOpenMs > TimeUnit.DAYS.toMillis(2)) {
            return TimeUnit.HOURS.toMillis(6);
        }
        boolean engaged = engagement >= 1.0d;
        if (!engaged && localHour >= 1 && localHour < 6) {
            return TimeUnit.MINUTES.toMillis(120);
        }
        return engaged ? MIN_GAP_MS : TimeUnit.MINUTES.toMillis(60);
    }

    /** Failed checks back off from 15 minutes to 2 hours, never faster than the normal gap. */
    public static boolean isDue(long now, long lastSuccess, long lastAttempt, int failures,
            long intervalMs, boolean appOpen) {
        long gap = appOpen ? MIN_GAP_MS : Math.max(MIN_GAP_MS, intervalMs);
        if (lastSuccess > 0L && now - lastSuccess < gap) {
            return false;
        }
        if (failures > 0 && lastAttempt > 0L) {
            long backoff = Math.min(TimeUnit.HOURS.toMillis(2),
                    TimeUnit.MINUTES.toMillis(15) << Math.min(failures - 1, 3));
            return now - lastAttempt >= backoff;
        }
        return lastAttempt <= 0L || now - lastAttempt >= TimeUnit.MINUTES.toMillis(5);
    }
}
