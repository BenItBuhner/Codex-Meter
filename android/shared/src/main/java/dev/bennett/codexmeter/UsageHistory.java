package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded, local-only usage samples used by charts and trend-aware estimates. */
public final class UsageHistory {
    public static final String FIVE_HOUR = "five_hour";
    public static final String WEEKLY = "weekly";
    private static final int MAX_FIVE_HOUR_SAMPLES = 288;
    private static final int MAX_WEEKLY_SAMPLES = 336;

    public final String kind;
    public final List<UsageSample> samples;

    public UsageHistory(String kind, List<UsageSample> samples) {
        this.kind = normalizeKind(kind);
        this.samples = Collections.unmodifiableList(new ArrayList<>(
                samples == null ? Collections.emptyList() : samples));
    }

    public static UsageHistory empty(String kind) {
        return new UsageHistory(kind, Collections.emptyList());
    }

    public UsageHistory append(UsageWindow window, long observedAtMillis) {
        if (window == null || observedAtMillis <= 0L || window.windowSeconds <= 0L) return this;
        long resetAt = window.effectiveResetAtMillis(observedAtMillis);
        if (resetAt <= observedAtMillis) return this;
        UsageSample next = new UsageSample(observedAtMillis, window.usedPercent, resetAt,
                window.windowSeconds);
        ArrayList<UsageSample> updated = new ArrayList<>(samples);
        if (!updated.isEmpty()) {
            UsageSample last = updated.get(updated.size() - 1);
            if (observedAtMillis <= last.observedAtMillis) return this;
            long minimumSpacing = FIVE_HOUR.equals(kind)
                    ? TimeUnit.MINUTES.toMillis(5) : TimeUnit.MINUTES.toMillis(30);
            if (sameWindow(last, next) && last.usedPercent == next.usedPercent
                    && observedAtMillis - last.observedAtMillis < minimumSpacing) {
                updated.set(updated.size() - 1, next);
                return new UsageHistory(kind, updated);
            }
        }
        updated.add(next);
        int max = FIVE_HOUR.equals(kind) ? MAX_FIVE_HOUR_SAMPLES : MAX_WEEKLY_SAMPLES;
        while (updated.size() > max) updated.remove(0);
        return new UsageHistory(kind, updated);
    }

    /** Samples belonging to the latest reset window, ordered oldest to newest. */
    public List<UsageSample> currentWindowSamples() {
        if (samples.isEmpty()) return Collections.emptyList();
        UsageSample latest = samples.get(samples.size() - 1);
        ArrayList<UsageSample> result = new ArrayList<>();
        for (UsageSample sample : samples) {
            if (sameWindow(sample, latest)) result.add(sample);
        }
        return Collections.unmodifiableList(result);
    }

    /** Most recent reset windows, oldest to newest, for normalized historical overlays. */
    public List<List<UsageSample>> recentWindows(int maximum) {
        if (samples.isEmpty() || maximum <= 0) return Collections.emptyList();
        ArrayList<List<UsageSample>> windows = new ArrayList<>();
        ArrayList<UsageSample> current = new ArrayList<>();
        UsageSample previous = null;
        for (UsageSample sample : samples) {
            if (previous != null && !sameWindow(previous, sample)) {
                windows.add(Collections.unmodifiableList(current));
                current = new ArrayList<>();
            }
            current.add(sample);
            previous = sample;
        }
        if (!current.isEmpty()) windows.add(Collections.unmodifiableList(current));
        int from = Math.max(0, windows.size() - maximum);
        return Collections.unmodifiableList(new ArrayList<>(windows.subList(from, windows.size())));
    }

    public int completedWindowCount() {
        if (samples.isEmpty()) return 0;
        int boundaries = 0;
        UsageSample previous = samples.get(0);
        for (int i = 1; i < samples.size(); i++) {
            UsageSample next = samples.get(i);
            if (!sameWindow(previous, next)) boundaries++;
            previous = next;
        }
        return boundaries;
    }

    /**
     * Returns a recent observed burn rate in percent per millisecond. Samples must span enough
     * time and percentage points to avoid magnifying refresh jitter.
     */
    public double observedBurnRate() {
        List<UsageSample> current = currentWindowSamples();
        if (current.size() < 2) return 0d;
        UsageSample latest = current.get(current.size() - 1);
        long minimumSpan = FIVE_HOUR.equals(kind)
                ? TimeUnit.MINUTES.toMillis(10) : TimeUnit.HOURS.toMillis(2);
        UsageSample first = null;
        for (UsageSample candidate : current) {
            if (latest.observedAtMillis - candidate.observedAtMillis >= minimumSpan
                    && latest.usedPercent - candidate.usedPercent >= 2) {
                first = candidate;
                break;
            }
        }
        if (first == null) return 0d;
        return (latest.usedPercent - first.usedPercent)
                / (double) (latest.observedAtMillis - first.observedAtMillis);
    }

    public JSONObject toJson() throws JSONException {
        JSONArray array = new JSONArray();
        for (UsageSample sample : samples) array.put(sample.toJson());
        return new JSONObject().put("version", 1).put("kind", kind).put("samples", array);
    }

    public static UsageHistory fromJson(JSONObject json, String fallbackKind) {
        if (json == null) return empty(fallbackKind);
        String kind = normalizeKind(json.optString("kind", fallbackKind));
        JSONArray array = json.optJSONArray("samples");
        ArrayList<UsageSample> samples = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                UsageSample sample = UsageSample.fromJson(array.optJSONObject(i));
                if (sample != null) samples.add(sample);
            }
        }
        int max = FIVE_HOUR.equals(kind) ? MAX_FIVE_HOUR_SAMPLES : MAX_WEEKLY_SAMPLES;
        if (samples.size() > max) {
            samples = new ArrayList<>(samples.subList(samples.size() - max, samples.size()));
        }
        return new UsageHistory(kind, samples);
    }

    static boolean sameWindow(UsageSample left, UsageSample right) {
        if (left == null || right == null || left.windowSeconds != right.windowSeconds) return false;
        long tolerance = Math.min(TimeUnit.MINUTES.toMillis(15),
                Math.max(TimeUnit.MINUTES.toMillis(1), left.windowSeconds * 1000L / 20L));
        return Math.abs(left.resetAtMillis - right.resetAtMillis) <= tolerance;
    }

    private static String normalizeKind(String kind) {
        return WEEKLY.equals(kind) ? WEEKLY : FIVE_HOUR;
    }
}
