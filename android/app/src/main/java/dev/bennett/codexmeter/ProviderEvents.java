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
 * Good news about a provider's limits, worked out by comparing one refresh with the one before:
 * a limit that refilled before its scheduled reset, and newly saved resets or reset credits. Kept
 * free of Android so the rules can be tested. {@link ProviderAlerts} turns each event into a
 * notification and keeps the recent ones for the Priority tab.
 */
final class ProviderEvents {
    private ProviderEvents() {}

    static final String SURPRISE_REFILL = "surprise_refill";
    static final String NEW_RESETS = "new_resets";
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

        Event(String kind, String provider, String line, String title, String detail, long at) {
            this.kind = kind == null ? "" : kind;
            this.provider = provider == null ? "" : provider;
            this.line = line == null ? "" : line;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            this.at = at;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("kind", kind).put("provider", provider).put("line", line)
                    .put("title", title)
                    .put("detail", detail).put("at", at);
        }

        static Event fromJson(JSONObject o) {
            if (o == null || o.optString("title", "").isEmpty()) return null;
            return new Event(o.optString("kind", ""), o.optString("provider", ""), o.optString("line", ""),
                    o.optString("title", ""),
                    o.optString("detail", ""), o.optLong("at", 0L));
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
                "Surprise " + provider.displayName + " refill", detail, at);
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
