package dev.bennett.codexmeter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A provider's service status, read from its official status feed and limited to the components
 * that provider depends on (Claude Code, not the Claude API; Grok Bot, not the whole SpaceXSI
 * feed). Pure Java so the self-tests cover it.
 *
 * <p>Rules the app keeps:
 * <ul>
 *   <li>No feed, a failed fetch, or a feed that doesn't list the provider is {@link State#UNKNOWN},
 *       never an outage.</li>
 *   <li>One degraded component never reads as the whole provider being down: the state is the
 *       worst <em>matching</em> component, and the copy names the component.</li>
 *   <li>An incident feed without component states (RSS) can only say "no active incidents
 *       reported", not "operational".</li>
 *   <li>Official incidents are kept apart from the app's own failed requests, which stay in the
 *       provider's last error.</li>
 * </ul>
 */
public final class ProviderStatus {

    public enum State {
        /** Every matching component reports operational. */
        OPERATIONAL(0),
        /** An incident-only feed (RSS) has no unresolved incident for the provider's components. */
        NO_ACTIVE_INCIDENTS(0),
        /** Maintenance in progress on a matching component. */
        MAINTENANCE(1),
        DEGRADED(2),
        PARTIAL_OUTAGE(3),
        MAJOR_OUTAGE(4),
        /** No feed, no data, or the feed doesn't list this provider. Never shown as an outage. */
        UNKNOWN(-1);

        public final int severity;

        State(int severity) {
            this.severity = severity;
        }

        public boolean isProblem() {
            return severity >= 1;
        }

        public static State fromId(String id) {
            if (id != null) {
                for (State state : values()) {
                    if (state.name().equalsIgnoreCase(id)) {
                        return state;
                    }
                }
            }
            return UNKNOWN;
        }
    }

    /** One affected component or incident, as the feed names it. */
    public static final class Issue {
        public final String component;
        public final State state;
        /** Incident title, or "" when the feed only gave a component state. */
        public final String title;
        public final String link;
        public final long startedAtMillis;

        public Issue(String component, State state, String title, String link, long startedAtMillis) {
            this.component = component == null ? "" : component;
            this.state = state;
            this.title = title == null ? "" : title;
            this.link = link == null ? "" : link;
            this.startedAtMillis = startedAtMillis;
        }
    }

    public final String providerId;
    public final State state;
    public final List<Issue> issues;
    /** The status page people can open, e.g. https://status.claude.com. */
    public final String pageUrl;
    public final long checkedAtMillis;

    public ProviderStatus(String providerId, State state, List<Issue> issues, String pageUrl,
            long checkedAtMillis) {
        this.providerId = providerId == null ? "" : providerId;
        this.state = state == null ? State.UNKNOWN : state;
        this.issues = issues == null ? Collections.<Issue>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(issues));
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.checkedAtMillis = checkedAtMillis;
    }

    public static ProviderStatus unknown(String providerId, String pageUrl, long now) {
        return new ProviderStatus(providerId, State.UNKNOWN, null, pageUrl, now);
    }

    /** True when the dashboard should show a status card (an official problem, not "unknown"). */
    public boolean shouldShowCard() {
        return state.isProblem();
    }

    /** Card title, naming the component so one component never reads as the whole provider. */
    public String headline(String providerName) {
        String where = issues.isEmpty() || issues.get(0).component.isEmpty()
                ? providerName : issues.get(0).component;
        if (issues.size() > 1) {
            where = where + " and " + (issues.size() - 1) + " more";
        }
        switch (state) {
            case MAJOR_OUTAGE:
                return where + ": major outage";
            case PARTIAL_OUTAGE:
                return where + ": partial outage";
            case DEGRADED:
                return where + ": degraded performance";
            case MAINTENANCE:
                return where + ": maintenance in progress";
            case OPERATIONAL:
                return providerName + ": operational";
            case NO_ACTIVE_INCIDENTS:
                return providerName + ": no active incidents reported";
            case UNKNOWN:
            default:
                return providerName + ": status unknown";
        }
    }

    /** One line under the headline: the incident title when there is one. */
    public String detail() {
        for (Issue issue : issues) {
            if (!issue.title.isEmpty()) {
                return issue.title;
            }
        }
        return state == State.UNKNOWN ? "Couldn't read the official status page." : "";
    }

    // ---- Statuspage-style summary.json (OpenAI, Claude, Cursor) -------------------------------

    /**
     * Reads a Statuspage-compatible {@code /api/v2/summary.json}. Only components whose name
     * contains one of {@code componentNames} (case-insensitive) count. An unresolved incident that
     * lists a matching component raises the state and supplies the title; incidents that list no
     * components are ignored, since they can't be tied to this provider.
     */
    public static ProviderStatus parseSummary(String providerId, String body, List<String> componentNames,
            String pageUrl, long now) {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException | NullPointerException e) {
            return unknown(providerId, pageUrl, now);
        }
        JSONArray components = root.optJSONArray("components");
        if (components == null) {
            return unknown(providerId, pageUrl, now);
        }
        List<Issue> issues = new ArrayList<>();
        boolean matched = false;
        State worst = State.OPERATIONAL;
        for (int i = 0; i < components.length(); i++) {
            JSONObject component = components.optJSONObject(i);
            if (component == null || component.optBoolean("group", false)) {
                continue;
            }
            String name = component.optString("name", "");
            if (!matches(name, componentNames)) {
                continue;
            }
            matched = true;
            State state = componentState(component.optString("status", ""));
            if (state == State.UNKNOWN) {
                continue;
            }
            if (state.isProblem()) {
                issues.add(new Issue(name, state, "", "", 0L));
            }
            if (state.severity > worst.severity) {
                worst = state;
            }
        }
        if (!matched) {
            return unknown(providerId, pageUrl, now);
        }
        addIncidents(root.optJSONArray("incidents"), componentNames, issues, false);
        addIncidents(root.optJSONArray("scheduled_maintenances"), componentNames, issues, true);
        for (Issue issue : issues) {
            if (issue.state.severity > worst.severity) {
                worst = issue.state;
            }
        }
        sortWorstFirst(issues);
        return new ProviderStatus(providerId, worst, mergeByComponent(issues), pageUrl, now);
    }

    private static void addIncidents(JSONArray incidents, List<String> names, List<Issue> out,
            boolean maintenance) {
        if (incidents == null) {
            return;
        }
        for (int i = 0; i < incidents.length(); i++) {
            JSONObject incident = incidents.optJSONObject(i);
            if (incident == null) {
                continue;
            }
            String status = incident.optString("status", "").toLowerCase(Locale.ROOT);
            if (status.equals("resolved") || status.equals("postmortem") || status.equals("completed")
                    || (maintenance && !status.equals("in_progress") && !status.equals("verifying"))) {
                continue;
            }
            JSONArray affected = incident.optJSONArray("components");
            if (affected == null) {
                continue;
            }
            State state = maintenance ? State.MAINTENANCE : impactState(incident.optString("impact", ""));
            for (int j = 0; j < affected.length(); j++) {
                JSONObject component = affected.optJSONObject(j);
                String name = component == null ? "" : component.optString("name", "");
                if (matches(name, names)) {
                    State componentState = component == null ? State.UNKNOWN
                            : componentState(component.optString("status", ""));
                    State use = componentState.severity > state.severity ? componentState : state;
                    if (use.severity < State.MAINTENANCE.severity) {
                        // "monitoring" with impact none: the component is back; nothing to show.
                        continue;
                    }
                    out.add(new Issue(name, use, incident.optString("name", ""),
                            incident.optString("shortlink", ""),
                            parseIso(incident.optString("started_at",
                                    incident.optString("created_at", "")))));
                }
            }
        }
    }

    static State componentState(String status) {
        switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "operational":
                return State.OPERATIONAL;
            case "degraded_performance":
                return State.DEGRADED;
            case "partial_outage":
                return State.PARTIAL_OUTAGE;
            case "major_outage":
                return State.MAJOR_OUTAGE;
            case "under_maintenance":
                return State.MAINTENANCE;
            default:
                return State.UNKNOWN;
        }
    }

    static State impactState(String impact) {
        switch (impact == null ? "" : impact.toLowerCase(Locale.ROOT)) {
            case "critical":
                return State.MAJOR_OUTAGE;
            case "major":
                return State.PARTIAL_OUTAGE;
            case "minor":
                return State.DEGRADED;
            case "maintenance":
                return State.MAINTENANCE;
            default:
                return State.OPERATIONAL;
        }
    }

    // ---- SpaceXSI RSS (status.x.ai/feed.xml) --------------------------------------------------

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern PUB_DATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);
    private static final Pattern CATEGORY = Pattern.compile("<category>(.*?)</category>", Pattern.DOTALL);
    private static final Pattern BRACKET = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*(.*)$", Pattern.DOTALL);

    /**
     * Reads the SpaceXSI incident feed. Each item's title starts with the component in brackets
     * ("[Grok Bot] …") and carries a severity category (outage, disruption, info) and a status
     * category (resolved when over). Unresolved items for a matching component are the issues; with
     * none the state is {@link State#NO_ACTIVE_INCIDENTS}, because the feed lists incidents only.
     */
    public static ProviderStatus parseRss(String providerId, String body, List<String> componentNames,
            String pageUrl, long now) {
        if (body == null || !body.contains("<rss")) {
            return unknown(providerId, pageUrl, now);
        }
        List<Issue> issues = new ArrayList<>();
        Matcher items = ITEM.matcher(body);
        while (items.find()) {
            String item = items.group(1);
            Matcher title = TITLE.matcher(item);
            if (!title.find()) {
                continue;
            }
            Matcher bracket = BRACKET.matcher(unescape(title.group(1)));
            if (!bracket.find()) {
                continue;
            }
            String component = bracket.group(1).trim();
            if (!exactMatch(component, componentNames)) {
                continue;
            }
            boolean resolved = false;
            State state = State.DEGRADED;
            Matcher category = CATEGORY.matcher(item);
            while (category.find()) {
                String value = category.group(1).trim().toLowerCase(Locale.ROOT);
                if (value.equals("resolved") || value.equals("completed") || value.equals("postmortem")) {
                    resolved = true;
                } else if (value.equals("outage")) {
                    state = State.PARTIAL_OUTAGE;
                } else if (value.equals("maintenance")) {
                    state = State.MAINTENANCE;
                } else if (value.equals("info")) {
                    state = State.NO_ACTIVE_INCIDENTS;
                }
            }
            if (resolved || !state.isProblem()) {
                continue;
            }
            Matcher link = LINK.matcher(item);
            Matcher date = PUB_DATE.matcher(item);
            issues.add(new Issue(component, state, bracket.group(2).trim(),
                    link.find() ? link.group(1).trim() : "",
                    date.find() ? parseRfc1123(date.group(1).trim()) : 0L));
        }
        State worst = State.NO_ACTIVE_INCIDENTS;
        for (Issue issue : issues) {
            if (issue.state.severity > worst.severity) {
                worst = issue.state;
            }
        }
        sortWorstFirst(issues);
        return new ProviderStatus(providerId, worst, mergeByComponent(issues), pageUrl, now);
    }

    // ---- Persistence --------------------------------------------------------------------------

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("provider", providerId);
            json.put("state", state.name());
            json.put("page", pageUrl);
            json.put("checked_at", checkedAtMillis);
            JSONArray list = new JSONArray();
            for (Issue issue : issues) {
                JSONObject o = new JSONObject();
                o.put("component", issue.component);
                o.put("state", issue.state.name());
                o.put("title", issue.title);
                o.put("link", issue.link);
                o.put("started_at", issue.startedAtMillis);
                list.put(o);
            }
            json.put("issues", list);
        } catch (JSONException ignored) {
            // put() only throws for non-finite numbers.
        }
        return json;
    }

    public static ProviderStatus fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        List<Issue> issues = new ArrayList<>();
        JSONArray list = json.optJSONArray("issues");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null) {
                    issues.add(new Issue(o.optString("component", ""), State.fromId(o.optString("state")),
                            o.optString("title", ""), o.optString("link", ""), o.optLong("started_at", 0L)));
                }
            }
        }
        return new ProviderStatus(json.optString("provider", ""), State.fromId(json.optString("state")),
                issues, json.optString("page", ""), json.optLong("checked_at", 0L));
    }

    // ---- Helpers ------------------------------------------------------------------------------

    static boolean matches(String name, List<String> wanted) {
        if (name == null || wanted == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String w : wanted) {
            if (!w.isEmpty() && lower.contains(w.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean exactMatch(String name, List<String> wanted) {
        for (String w : wanted) {
            if (w.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void sortWorstFirst(List<Issue> issues) {
        Collections.sort(issues, (a, b) -> b.state.severity - a.state.severity);
    }

    /** Keeps the worst issue per component, so one incident on one component reads once. */
    private static List<Issue> mergeByComponent(List<Issue> sorted) {
        List<Issue> out = new ArrayList<>();
        for (Issue issue : sorted) {
            Issue existing = null;
            for (Issue o : out) {
                if (o.component.equalsIgnoreCase(issue.component)) {
                    existing = o;
                    break;
                }
            }
            if (existing == null) {
                out.add(issue);
            } else if (existing.title.isEmpty() && !issue.title.isEmpty()) {
                out.set(out.indexOf(existing), new Issue(existing.component, existing.state,
                        issue.title, issue.link, issue.startedAtMillis));
            }
        }
        return out;
    }

    private static String unescape(String s) {
        String t = s.trim();
        if (t.startsWith("<![CDATA[") && t.endsWith("]]>")) {
            t = t.substring(9, t.length() - 3);
        }
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }

    static long parseIso(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        String v = value.replaceAll("\\.\\d+", "");
        if (v.endsWith("Z")) {
            v = v.substring(0, v.length() - 1) + "+0000";
        } else {
            v = v.replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            return format.parse(v).getTime();
        } catch (ParseException e) {
            return 0L;
        }
    }

    static long parseRfc1123(String value) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            return format.parse(value).getTime();
        } catch (ParseException e) {
            return 0L;
        }
    }
}
