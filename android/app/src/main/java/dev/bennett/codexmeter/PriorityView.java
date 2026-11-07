package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Priority view: what needs attention across the signed-in providers, built only from facts
 * already on the device, in a fixed order: official outages, then performance warnings, then
 * limits that are used up or running out before they reset, then the other meters, then a tip.
 * Every number and incident state shown here is worked out locally.
 *
 * <p>An optional model-written summary (off unless the person turns it on) may only replace the
 * summary sentence and the tip; see {@link #factsForModel} and {@link #validateProse}.
 */
public final class PriorityView {
    private PriorityView() {}

    /** The closed card types. Nothing else is ever drawn. */
    public enum Kind {
        DOWNTIME("downtime"),
        PERFORMANCE_WARNING("performance_warning"),
        USAGE_METER("usage_meter"),
        TIP("tip");

        public final String id;

        Kind(String id) {
            this.id = id;
        }
    }

    /** One provider's local facts. Any field may be null. */
    public static final class ProviderFacts {
        public final String providerName;
        public final ProviderStatus status;
        public final ModelSpeed speed;
        public final UsageSnapshot snapshot;

        public ProviderFacts(String providerName, ProviderStatus status, ModelSpeed speed,
                UsageSnapshot snapshot) {
            this.providerName = providerName == null ? "" : providerName;
            this.status = status;
            this.speed = speed;
            this.snapshot = snapshot;
        }
    }

    /** One row of the view. */
    public static final class Item {
        public final Kind kind;
        public final String provider;
        public final String title;
        public final String detail;
        /** Lower sorts first within a kind. */
        final double rank;
        /** USAGE_METER: left in the window, 0 to 100; -1 otherwise. */
        public final int percentLeft;
        public final boolean exhausted;
        public final boolean runsOutEarly;
        /** True for items that need attention (everything but plain meters and tips). */
        public final boolean urgent;

        Item(Kind kind, String provider, String title, String detail, double rank, int percentLeft,
                boolean exhausted, boolean runsOutEarly, boolean urgent) {
            this.kind = kind;
            this.provider = provider;
            this.title = title;
            this.detail = detail == null ? "" : detail;
            this.rank = rank;
            this.percentLeft = percentLeft;
            this.exhausted = exhausted;
            this.runsOutEarly = runsOutEarly;
            this.urgent = urgent;
        }
    }

    /** OpenRouter uptime (percent, last 30 minutes) under which the model gets a warning. */
    static final double UPTIME_WARNING = 95.0;
    /** Plain meters shown after the urgent ones. */
    static final int MAX_PLAIN_METERS = 3;

    /** The items, in the fixed priority order. */
    public static List<Item> build(List<ProviderFacts> providers, long now, String paceSensitivity) {
        List<Item> downtime = new ArrayList<>();
        List<Item> performance = new ArrayList<>();
        List<Item> urgentMeters = new ArrayList<>();
        List<Item> meters = new ArrayList<>();
        for (ProviderFacts p : providers) {
            ProviderStatus s = p.status;
            if (s != null && s.state.isProblem()) {
                if (s.state == ProviderStatus.State.DEGRADED) {
                    performance.add(new Item(Kind.PERFORMANCE_WARNING, p.providerName,
                            s.headline(p.providerName), s.detail(), -s.state.severity, -1, false, false, true));
                } else {
                    downtime.add(new Item(Kind.DOWNTIME, p.providerName, s.headline(p.providerName),
                            s.detail(), -s.state.severity, -1, false, false, true));
                }
            }
            ModelSpeed.Endpoint e = p.speed == null ? null : p.speed.featured();
            if (e != null && !Double.isNaN(e.uptime30m) && e.uptime30m < UPTIME_WARNING) {
                performance.add(new Item(Kind.PERFORMANCE_WARNING, p.providerName,
                        p.speed.modelName + " is less reliable right now",
                        "OpenRouter measured " + ModelSpeed.uptimeText(e.uptime30m)
                                + " uptime over the last 30 minutes, " + p.speed.sourceLine()
                                + ". Public figures, not your subscription.",
                        e.uptime30m, -1, false, false, true));
            }
            UsageSnapshot snap = p.snapshot;
            if (snap == null) {
                continue;
            }
            addMeter(p.providerName, "5-hour", snap.fiveHour, snap.fetchedAtMillis, now, paceSensitivity,
                    urgentMeters, meters);
            addMeter(p.providerName, "Weekly", snap.weekly, snap.fetchedAtMillis, now, paceSensitivity,
                    urgentMeters, meters);
            addMeter(p.providerName, "Monthly", snap.monthly, snap.fetchedAtMillis, now, paceSensitivity,
                    urgentMeters, meters);
        }
        List<Item> out = new ArrayList<>();
        sortByRank(downtime);
        sortByRank(performance);
        sortByRank(urgentMeters);
        sortByRank(meters);
        out.addAll(downtime);
        out.addAll(performance);
        out.addAll(urgentMeters);
        out.addAll(meters.subList(0, Math.min(MAX_PLAIN_METERS, meters.size())));
        out.add(localTip(downtime, performance, urgentMeters, providers));
        return out;
    }

    private static void addMeter(String provider, String label, UsageWindow w, long observedAt, long now,
            String paceSensitivity, List<Item> urgent, List<Item> plain) {
        if (w == null || w.windowSeconds <= 0L) {
            return;
        }
        long resetAt = w.effectiveResetAtMillis(observedAt);
        if (resetAt > 0L && resetAt <= now) {
            // The window has reset since the last refresh; its old number would mislead.
            return;
        }
        int left = Math.max(0, Math.min(100, w.remainingPercent()));
        String resets = resetAt > now ? "resets in " + duration(resetAt - now) : "";
        String title = provider + " " + label.toLowerCase(Locale.ROOT) + ": " + left + "% left";
        if (left <= 0) {
            urgent.add(new Item(Kind.USAGE_METER, provider, provider + " " + label.toLowerCase(Locale.ROOT)
                    + " limit used up", resets.isEmpty() ? "" : "It " + resets + ".", -1000 + left, 0,
                    true, false, true));
            return;
        }
        UsagePace.Assessment pace = UsagePace.assess(w, observedAt, now, paceSensitivity);
        if (pace.available && pace.accelerated) {
            // Running ahead of its reset, which is not the same as "faster than you usually use it".
            urgent.add(new Item(Kind.USAGE_METER, provider, title,
                    "At this pace it runs out in about " + duration(pace.estimatedRemainingMillis)
                            + ", before it " + (resets.isEmpty() ? "resets" : resets) + ".",
                    left, left, false, true, true));
            return;
        }
        plain.add(new Item(Kind.USAGE_METER, provider, title,
                resets.isEmpty() ? "" : "It " + resets + ".", left, left, false, false, false));
    }

    private static Item localTip(List<Item> downtime, List<Item> performance, List<Item> urgentMeters,
            List<ProviderFacts> providers) {
        String tip;
        if (!downtime.isEmpty()) {
            tip = "Tap a status card to open the official status page for updates.";
        } else if (hasExhausted(urgentMeters) && resetCredits(providers) > 0) {
            tip = "You have reset credits banked. Open Reset credits to use one.";
        } else if (!urgentMeters.isEmpty()) {
            tip = "Usage estimates use your own pace since the window started.";
        } else if (!performance.isEmpty()) {
            tip = "Performance figures come from public sources, not your own requests.";
        } else {
            tip = "Nothing needs attention right now.";
        }
        return new Item(Kind.TIP, "", tip, "", 0, -1, false, false, false);
    }

    private static boolean hasExhausted(List<Item> items) {
        for (Item i : items) {
            if (i.exhausted) {
                return true;
            }
        }
        return false;
    }

    private static int resetCredits(List<ProviderFacts> providers) {
        int n = 0;
        for (ProviderFacts p : providers) {
            if (p.snapshot != null) {
                n += Math.max(0, p.snapshot.resetCreditsAvailable);
            }
        }
        return n;
    }

    private static void sortByRank(List<Item> items) {
        Collections.sort(items, (a, b) -> Double.compare(a.rank, b.rank));
    }

    /** True when anything other than plain meters and the tip is in the view. */
    public static boolean needsAttention(List<Item> items) {
        for (Item i : items) {
            if (i.urgent) {
                return true;
            }
        }
        return false;
    }

    /** The summary sentence, worked out locally. {@code name} is added here and never sent. */
    public static String localSummary(List<Item> items, String name) {
        int outages = 0;
        int warnings = 0;
        int limits = 0;
        for (Item i : items) {
            if (i.kind == Kind.DOWNTIME) {
                outages++;
            } else if (i.kind == Kind.PERFORMANCE_WARNING) {
                warnings++;
            } else if (i.kind == Kind.USAGE_METER && i.urgent) {
                limits++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (outages > 0) {
            parts.add(outages == 1 ? "an official incident" : outages + " official incidents");
        }
        if (warnings > 0) {
            parts.add(warnings == 1 ? "a performance warning" : warnings + " performance warnings");
        }
        if (limits > 0) {
            parts.add(limits == 1 ? "a limit to watch" : limits + " limits to watch");
        }
        String body = parts.isEmpty() ? "Everything looks fine." : "Needs attention: " + join(parts) + ".";
        return greeting(name) + body;
    }

    /** "Hi Sam. " with a name, "" without one. */
    public static String greeting(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            return "";
        }
        if (clean.length() > 30) {
            clean = clean.substring(0, 30).trim();
        }
        return "Hi " + clean + ". ";
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                b.append(i == parts.size() - 1 ? " and " : ", ");
            }
            b.append(parts.get(i));
        }
        return b.toString();
    }

    // ---- the optional model summary -------------------------------------------------------

    /**
     * The only data a model ever sees: card kinds, provider names and plain-word states. No
     * numbers, names, prompts, code, tokens or account data.
     */
    public static JSONObject factsForModel(List<Item> items) {
        JSONArray cards = new JSONArray();
        for (Item i : items) {
            if (i.kind == Kind.TIP) {
                continue;
            }
            String state;
            if (i.kind == Kind.DOWNTIME) {
                state = "official incident";
            } else if (i.kind == Kind.PERFORMANCE_WARNING) {
                state = "performance warning";
            } else if (i.exhausted) {
                state = "limit used up until it resets";
            } else if (i.runsOutEarly) {
                state = "limit may run out before it resets";
            } else {
                state = i.percentLeft >= 50 ? "plenty left" : i.percentLeft >= 20 ? "some left" : "low";
            }
            try {
                cards.put(new JSONObject().put("type", i.kind.id).put("provider", i.provider).put("state", state));
            } catch (Exception ignored) {
                // put() only throws for non-finite numbers; there are none.
            }
        }
        try {
            return new JSONObject().put("cards", cards);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Fixed instructions; the facts are the only part that changes. */
    public static final String INSTRUCTIONS = "You write one short status line for a usage dashboard. "
            + "Use only the facts given. Do not add numbers, percentages, times, dates, links or advice "
            + "about other products. Plain sentences, no markdown. Reply with only a JSON object: "
            + "{\"summary\": \"at most 140 characters\", \"tip\": \"at most 110 characters, or empty\"}.";

    /** What the model may contribute: the summary sentence and the tip. */
    public static final class Prose {
        public final String summary;
        public final String tip;

        Prose(String summary, String tip) {
            this.summary = summary;
            this.tip = tip;
        }
    }

    static final int MAX_SUMMARY = 140;
    static final int MAX_TIP = 110;

    /**
     * Checks a model reply against the closed schema. Returns null for anything else: other keys,
     * non-strings, too long, digits (the model must not do quota arithmetic), markup or links.
     * A rejected reply is not retried; the local text stays.
     */
    public static Prose validateProse(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            text = text.substring(start, end + 1);
        }
        try {
            JSONObject o = new JSONObject(text);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals("summary") && !k.equals("tip")) {
                    return null;
                }
            }
            Object summary = o.opt("summary");
            Object tip = o.opt("tip");
            if (!(summary instanceof String) || (tip != null && !(tip instanceof String))) {
                return null;
            }
            String s = clean((String) summary);
            String t = tip == null ? "" : clean((String) tip);
            if (s.isEmpty() || s.length() > MAX_SUMMARY || t.length() > MAX_TIP || !safe(s) || !safe(t)) {
                return null;
            }
            return new Prose(s, t);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean safe(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '<' || c == '>' || c == '`' || c == '*' || c == '#'
                    || c == '[' || c == ']' || c == '{' || c == '}' || c == '%' || Character.isISOControl(c)) {
                return false;
            }
        }
        return !lower.contains("http") && !lower.contains("www.");
    }

    /** A stable key for the facts, so the summary is only asked for again when they change. */
    public static String factsKey(List<Item> items) {
        return Integer.toHexString(factsForModel(items).toString().hashCode());
    }

    private static String duration(long millis) {
        long minutes = Math.max(1L, millis / 60000L);
        long days = minutes / (60 * 24);
        long hours = (minutes / 60) % 24;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (minutes >= 60) {
            return (minutes / 60) + "h " + (minutes % 60) + "m";
        }
        return minutes + "m";
    }
}
