package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Recent activity on a provider's limits, worked out by comparing one refresh with the one before:
 * a limit that refilled before its scheduled reset, newly saved resets or reset credits, and a
 * saved reset that was used (in this app, in Claude Code or on claude.ai). A refill that a used
 * reset explains is put down to that reset, not called a surprise. Kept free of Android so the
 * rules can be tested. {@link ProviderAlerts} turns each event into a
 * notification and keeps the recent ones for the Priority tab.
 */
final class ProviderEvents {
    private ProviderEvents() {}

    static final String SURPRISE_REFILL = "surprise_refill";
    static final String NEW_RESETS = "new_resets";
    static final String RESET_USED = "reset_used";
    /** How long after a saved reset is used a refill is still put down to it. */
    static final long USE_EXPLAINS_MS = 6L * 60L * 60L * 1000L;
    /** How long an event stays on the Priority tab. */
    static final long KEEP_MS = 24L * 60L * 60L * 1000L;
    static final int MAX_KEPT = 12;

    static final class Event {
        final String kind;
        /** The provider's id, see {@link UsageProvider#id}. */
        final String provider;
        /** The short form for a list row: "Weekly refilled early", "3 Fable resets added". */
        final String line;
        final String title;
        final String detail;
        final long at;
        /** The limits it is about ("Weekly", "Fable only"), for a surprise refill; else empty. */
        final List<String> labels;

        Event(String kind, String provider, String line, String title, String detail, long at) {
            this(kind, provider, line, title, detail, at, null);
        }

        Event(String kind, String provider, String line, String title, String detail, long at, List<String> labels) {
            this.kind = kind == null ? "" : kind;
            this.provider = provider == null ? "" : provider;
            this.line = line == null ? "" : line;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            this.at = at;
            this.labels = labels == null ? new ArrayList<String>() : new ArrayList<>(labels);
        }

        /** Names this event among the kept ones, so the person can dismiss it. */
        String key() {
            return kind + "|" + provider + "|" + at + "|" + line;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("kind", kind).put("provider", provider).put("line", line)
                    .put("title", title)
                    .put("detail", detail).put("at", at).put("labels", new JSONArray(labels));
        }

        static Event fromJson(JSONObject o) {
            if (o == null || o.optString("title", "").isEmpty()) return null;
            List<String> labels = new ArrayList<>();
            JSONArray array = o.optJSONArray("labels");
            for (int i = 0; array != null && i < array.length(); i++) {
                String label = array.optString(i, "");
                if (!label.isEmpty()) labels.add(label);
            }
            return new Event(o.optString("kind", ""), o.optString("provider", ""), o.optString("line", ""),
                    o.optString("title", ""),
                    o.optString("detail", ""), o.optLong("at", 0L), labels);
        }
    }

    /**
     * One saved reset that was used: what it was called ("Fable reset"), the one model it covers
     * ("Fable", or empty for every model), and which of the all-models limits it refills.
     */
    static final class Use {
        final String title;
        final String scope;
        final boolean fiveHour;
        final boolean weekly;
        final String clearsLabel;
        final long at;

        Use(String title, String scope, boolean fiveHour, boolean weekly, String clearsLabel, long at) {
            this.title = title == null ? "" : title;
            this.scope = scope == null ? "" : scope.trim();
            this.fiveHour = fiveHour;
            this.weekly = weekly;
            this.clearsLabel = clearsLabel == null ? "" : clearsLabel;
            this.at = at;
        }

        static Use of(ClaudeSavedResets.Grant grant, long at) {
            return new Use(grant.title(), grant.scope, grant.clearsFiveHour(), grant.clearsWeekly(),
                    grant.clearsLabel(), at);
        }

        /**
         * Whether this reset refills the limit a refill row names: "5-hour" (or the provider's own
         * short cadence), "Weekly", "Monthly", or a model limit such as "Fable only". A reset for
         * one model covers only the limit named for it; an all-models weekly or full reset covers
         * the weekly limits, the model ones included.
         */
        boolean covers(String label, boolean fiveHourLabel) {
            if (label == null || label.isEmpty()) return false;
            if (!scope.isEmpty()) {
                return label.toLowerCase(Locale.ROOT).contains(scope.toLowerCase(Locale.ROOT));
            }
            if (fiveHourLabel) return fiveHour;
            return weekly;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("title", title).put("scope", scope).put("five_hour", fiveHour)
                    .put("weekly", weekly).put("clears", clearsLabel).put("at", at);
        }

        static Use fromJson(JSONObject o) {
            if (o == null) return null;
            return new Use(o.optString("title", ""), o.optString("scope", ""), o.optBoolean("five_hour", false),
                    o.optBoolean("weekly", false), o.optString("clears", ""), o.optLong("at", 0L));
        }
    }

    /**
     * The limits that went back to nothing used before the reset time the previous refresh gave:
     * "5-hour", "Weekly", "Monthly" and any extra limit by its name ("Fable only"). A limit with
     * no known reset time is left out, because its ordinary reset would look the same.
     */
    static List<String> refilledEarly(UsageSnapshot previous, UsageSnapshot current, String shortLabel) {
        List<String> out = new ArrayList<>();
        if (previous == null || current == null || current.fetchedAtMillis <= 0L) return out;
        int mask = CelebrationDetector.detectUnexpectedRefills(previous, current);
        if ((mask & CelebrationDetector.FIVE_HOUR) != 0) {
            out.add(shortLabel == null || shortLabel.isEmpty() ? "5-hour" : shortLabel);
        }
        if ((mask & CelebrationDetector.WEEKLY) != 0) out.add("Weekly");
        if ((mask & CelebrationDetector.MONTHLY) != 0) out.add("Monthly");
        for (UsageLimit now : current.additionalLimits) {
            if (now == null || now.id.isEmpty() || now.primary == null) continue;
            for (UsageLimit before : previous.additionalLimits) {
                if (before == null || !now.id.equals(before.id) || before.primary == null) continue;
                if (before.primary.usedPercent > 0 && now.primary.usedPercent == 0
                        && CelebrationDetector.expectedResetMillis(previous, before.primary)
                                > current.fetchedAtMillis) {
                    out.add(now.displayName());
                }
            }
        }
        return out;
    }

    /**
     * True for the 5-hour row of {@link #refilledEarly}: the provider's short cadence label, which
     * is always listed first.
     */
    static boolean isFiveHourLabel(List<String> labels, int index, String shortLabel) {
        String five = shortLabel == null || shortLabel.isEmpty() ? "5-hour" : shortLabel;
        return index == 0 && five.equals(labels.get(0));
    }

    /**
     * Splits {@code labels} (from {@link #refilledEarly}) into the ones a saved reset used in the
     * last {@link #USE_EXPLAINS_MS} explains and the rest. Returns the explained ones; {@code rest}
     * receives the others. {@code explainedBy} receives the first reset that explains each.
     */
    static List<String> explainedByUses(List<String> labels, String shortLabel, List<Use> uses, long now,
            List<String> rest, List<Use> explainedBy) {
        List<String> explained = new ArrayList<>();
        for (int i = 0; labels != null && i < labels.size(); i++) {
            String label = labels.get(i);
            boolean five = isFiveHourLabel(labels, i, shortLabel);
            Use by = null;
            for (Use u : uses == null ? new ArrayList<Use>() : uses) {
                if (u != null && u.at <= now + 60_000L && now - u.at <= USE_EXPLAINS_MS && u.covers(label, five)) {
                    by = u;
                    break;
                }
            }
            if (by != null) {
                explained.add(label);
                if (explainedBy != null && !explainedBy.contains(by)) explainedBy.add(by);
            } else if (rest != null) {
                rest.add(label);
            }
        }
        return explained;
    }

    /**
     * Saved resets used since the last list: a grant that holds fewer resets than before, or one
     * that is gone before it expired while the person can still be offered resets. A grant that
     * expired, or a list that came back without the program, says nothing.
     */
    static List<Use> usedSavedResets(ClaudeSavedResets previous, ClaudeSavedResets current, long now) {
        List<Use> out = new ArrayList<>();
        if (previous == null || current == null) return out;
        for (ClaudeSavedResets.Grant old : previous.grants) {
            if (!old.hasResets(now)) continue;
            ClaudeSavedResets.Grant still = null;
            for (ClaudeSavedResets.Grant g : current.grants) {
                if (g.id.equals(old.id)) still = g;
            }
            int fewer;
            if (still != null) {
                fewer = old.resetsLeft - still.resetsLeft;
            } else {
                fewer = current.eligible && (old.endsAtMillis <= 0L || old.endsAtMillis > now + 60_000L)
                        ? old.resetsLeft : 0;
            }
            for (int i = 0; i < fewer; i++) out.add(Use.of(old, now));
        }
        return out;
    }

    /**
     * "Fable reset used" for the Priority tab. {@code refilled} are the limits seen refilling
     * after it, or empty when none were seen yet. Null when nothing was used.
     */
    static Event resetUsed(UsageProvider provider, List<Use> uses, List<String> refilled, long at) {
        if (provider == null || uses == null || uses.isEmpty()) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Use u : uses) {
            Integer had = counts.get(u.title);
            counts.put(u.title, (had == null ? 0 : had) + 1);
        }
        String what = ClaudeSavedResets.countsLabel(counts);
        if (uses.size() == 1) what = uses.get(0).title;
        String line = what + " used" + (refilled == null || refilled.isEmpty() ? ""
                : " · " + join(refilled) + " refilled");
        Use first = uses.get(0);
        String detail = uses.size() == 1 && !first.clearsLabel.isEmpty()
                ? "It refills your " + first.clearsLabel + (first.scope.isEmpty() ? "." : " only.")
                : "";
        return new Event(RESET_USED, provider.id, line, provider.displayName + ": " + what + " used", detail, at,
                refilled);
    }

    /** Null when nothing refilled early. */
    static Event surpriseRefill(UsageProvider provider, List<String> labels, long at) {
        if (provider == null || labels == null || labels.isEmpty()) return null;
        String detail;
        if (labels.size() == 1) {
            detail = "Your " + limitName(labels.get(0)) + " limit jumped back to 100% before its scheduled reset.";
        } else {
            List<String> names = new ArrayList<>();
            for (String label : labels) names.add(limitName(label));
            detail = "Your " + join(names) + " limits jumped back to 100% before their scheduled resets.";
        }
        return new Event(SURPRISE_REFILL, provider.id, join(labels) + " refilled early",
                "Surprise " + provider.displayName + " refill", detail, at, labels);
    }

    /** "Fable only" stays as it is; "Weekly" reads "weekly". */
    private static String limitName(String label) {
        boolean plain = "Weekly".equals(label) || "Monthly".equals(label) || "Daily".equals(label)
                || "Session".equals(label);
        return plain ? label.toLowerCase(Locale.ROOT) : label;
    }

    /**
     * Claude Code resets saved since the last refresh, by kind: {"Fable reset": 3}. A grant that
     * is new, or holds more resets than before, counts. Empty when {@code current} has none new.
     */
    static Map<String, Integer> addedSavedResets(ClaudeSavedResets previous, ClaudeSavedResets current, long now) {
        Map<String, Integer> added = new LinkedHashMap<>();
        if (current == null) return added;
        for (ClaudeSavedResets.Grant grant : current.grants) {
            if (!grant.hasResets(now)) continue;
            int before = 0;
            if (previous != null) {
                for (ClaudeSavedResets.Grant old : previous.grants) {
                    if (old.id.equals(grant.id)) before = old.resetsLeft;
                }
            }
            int more = grant.resetsLeft - before;
            if (more > 0) {
                Integer had = added.get(grant.title());
                added.put(grant.title(), (had == null ? 0 : had) + more);
            }
        }
        return added;
    }

    /**
     * "3 Fable resets added" for Claude Code. {@code expiryText} is when the soonest of them
     * expires, already formatted, or empty. Null when nothing was added.
     */
    static Event newSavedResets(UsageProvider provider, Map<String, Integer> added, int availableNow,
            String expiryText, long at) {
        if (provider == null || added == null || added.isEmpty()) return null;
        int count = 0;
        for (int n : added.values()) count += n;
        if (count <= 0) return null;
        String what = ClaudeSavedResets.countsLabel(added);
        String detail = "You now have " + availableNow + " saved reset" + (availableNow == 1 ? "" : "s") + "."
                + (expiryText == null || expiryText.isEmpty() ? ""
                        : (count == 1 ? " It expires " : " The soonest expires ") + expiryText + ".");
        return new Event(NEW_RESETS, provider.id, what + " added", provider.displayName + ": " + what + " added",
                detail, at);
    }

    /** A provider whose reset credits are only a count (Codex). Null when the count did not grow. */
    static Event newResetCredits(UsageProvider provider, int previous, int current, long at) {
        int added = CelebrationDetector.resetCreditsAdded(previous, current);
        if (provider == null || added <= 0) return null;
        String what = added + " reset credit" + (added == 1 ? "" : "s");
        return new Event(NEW_RESETS, provider.id, what + " added", provider.displayName + ": " + what + " added",
                "You now have " + current + ".", at);
    }

    /** {@code events} plus {@code event}, newest first, without the ones too old to show. */
    static List<Event> remember(List<Event> events, Event event, long now) {
        List<Event> out = new ArrayList<>();
        if (event != null) out.add(event);
        if (events != null) out.addAll(events);
        return recent(out, now);
    }

    /** {@code events} without the one {@link Event#key} names. */
    static List<Event> without(List<Event> events, String key) {
        List<Event> out = new ArrayList<>();
        if (events == null) return out;
        for (Event e : events) {
            if (e != null && !e.key().equals(key)) out.add(e);
        }
        return out;
    }

    /**
     * After a saved reset turns out to have been used, the provider's recent surprise refills it
     * explains are folded into it: explained limits leave the surprise (which goes when none are
     * left) and are listed on the returned use event. {@code useEvent} is the new "reset used"
     * event; the result holds it, updated, plus everything else.
     */
    static List<Event> explainEarlierRefills(List<Event> events, Event useEvent, List<Use> uses, long now) {
        List<Event> out = new ArrayList<>();
        if (useEvent == null) {
            if (events != null) out.addAll(events);
            return out;
        }
        List<String> refilled = new ArrayList<>(useEvent.labels);
        for (Event e : events == null ? new ArrayList<Event>() : events) {
            if (e == null) continue;
            boolean candidate = SURPRISE_REFILL.equals(e.kind) && e.provider.equals(useEvent.provider)
                    && !e.labels.isEmpty() && now - e.at <= USE_EXPLAINS_MS && e.at <= now + 60_000L;
            if (!candidate) {
                out.add(e);
                continue;
            }
            List<String> rest = new ArrayList<>();
            // Earlier refills: the reset was used before the refresh that found them, so any time within
            // the window counts.
            List<Use> anyTime = new ArrayList<>();
            for (Use u : uses) anyTime.add(new Use(u.title, u.scope, u.fiveHour, u.weekly, u.clearsLabel, e.at));
            List<String> explained = explainedByUses(e.labels, "", anyTime, e.at, rest, null);
            if (explained.isEmpty()) {
                out.add(e);
                continue;
            }
            for (String label : explained) {
                if (!refilled.contains(label)) refilled.add(label);
            }
            if (!rest.isEmpty()) {
                Event left = surpriseRefill(UsageProvider.fromId(e.provider), rest, e.at);
                if (left != null) out.add(left);
            }
        }
        UsageProvider p = UsageProvider.fromId(useEvent.provider);
        Event merged = p == null ? useEvent : resetUsed(p, uses, refilled, useEvent.at);
        out.add(0, merged == null ? useEvent : merged);
        return out;
    }

    /** The events from the last {@link #KEEP_MS}, newest first. */
    static List<Event> recent(List<Event> events, long now) {
        List<Event> out = new ArrayList<>();
        if (events == null) return out;
        for (Event e : events) {
            if (e != null && e.at <= now && now - e.at < KEEP_MS) out.add(e);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
        return out.size() > MAX_KEPT ? new ArrayList<>(out.subList(0, MAX_KEPT)) : out;
    }

    static String toJson(List<Event> events) {
        JSONArray array = new JSONArray();
        for (Event e : events) {
            try {
                array.put(e.toJson());
            } catch (JSONException ignored) {
                // Only non-finite numbers throw; there are none.
            }
        }
        return array.toString();
    }

    static List<Event> fromJson(String json) {
        List<Event> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            for (int i = 0; i < array.length(); i++) {
                Event e = Event.fromJson(array.optJSONObject(i));
                if (e != null) out.add(e);
            }
        } catch (JSONException ignored) {
            // An unreadable list is an empty one.
        }
        return out;
    }

    /** How long ago, for the Priority tab: "Just now", "12m ago", "3h ago". */
    static String ago(long at, long now) {
        long minutes = Math.max(0L, now - at) / 60000L;
        if (minutes < 2) return "Just now";
        if (minutes < 60) return minutes + "m ago";
        return (minutes / 60) + "h ago";
    }

    /** A monthly limit this close to its reset is "renewing soon" on the Priority tab. */
    static final long RENEWAL_SOON_MS = 3L * 24L * 60L * 60L * 1000L;
    /** Saved resets this close to expiring are listed on the Priority tab. */
    static final long EXPIRY_SOON_MS = 3L * 24L * 60L * 60L * 1000L;

    /** True when a monthly limit resets within {@link #RENEWAL_SOON_MS}. */
    static boolean renewsSoon(UsageWindow monthly, long observedAt, long now) {
        if (monthly == null || monthly.windowSeconds <= 0L) return false;
        long resetAt = monthly.effectiveResetAtMillis(observedAt);
        return resetAt > now && resetAt - now <= RENEWAL_SOON_MS;
    }

    /** "2d 4h", "5h 12m", "9m". */
    static String duration(long millis) {
        long minutes = Math.max(1L, millis / 60000L);
        long days = minutes / (60 * 24);
        if (days > 0) return days + "d " + ((minutes / 60) % 24) + "h";
        if (minutes >= 60) return (minutes / 60) + "h " + (minutes % 60) + "m";
        return minutes + "m";
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(i == parts.size() - 1 ? " and " : ", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }
}
