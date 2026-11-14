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
    public static final String MONTHLY = "monthly";
    private static final int MAX_FIVE_HOUR_SAMPLES = 288;
    private static final int MAX_WEEKLY_SAMPLES = 336;
    private static final int MAX_MONTHLY_SAMPLES = 372;

    public final String kind;
    public final List<UsageSample> samples;
    /**
     * Set by {@link #withLiveWindow}: the window the provider reports right now. Without it the
     * newest recorded window counts as the current one, which is wrong once that window has
     * reset (or gone idle) and nothing has been recorded for the next one yet.
     */
    private final boolean liveKnown;
    private final long liveResetAtMillis;
    private final long liveWindowSeconds;

    public UsageHistory(String kind, List<UsageSample> samples) {
        this(kind, samples, false, 0L, 0L);
    }

    private UsageHistory(String kind, List<UsageSample> samples, boolean liveKnown,
            long liveResetAtMillis, long liveWindowSeconds) {
        this.kind = normalizeKind(kind);
        this.samples = Collections.unmodifiableList(new ArrayList<>(
                samples == null ? Collections.emptyList() : samples));
        this.liveKnown = liveKnown;
        this.liveResetAtMillis = liveResetAtMillis;
        this.liveWindowSeconds = liveWindowSeconds;
    }

    /**
     * The same samples, read against the window the provider reports now. Only samples from
     * that window are "current"; every other recorded window is a completed one. A window with
     * no future reset time (Claude's idle 5-hour session, or one whose reset has passed) has no
     * current samples at all, so charts can't draw a finished window on the live axis.
     */
    public UsageHistory withLiveWindow(UsageWindow window, long observedAtMillis) {
        long resetAt = window == null ? 0L : window.effectiveResetAtMillis(observedAtMillis);
        if (resetAt <= observedAtMillis) resetAt = 0L;
        return new UsageHistory(kind, samples, true, resetAt,
                window == null ? 0L : window.windowSeconds);
    }

    /** Whether the live window has a usable time axis (a reset still ahead). */
    public boolean hasLiveAxis() {
        return !liveKnown || liveResetAtMillis > 0L;
    }

    private boolean isCurrent(UsageSample sample, UsageSample latest) {
        if (!liveKnown) return sameWindow(sample, latest);
        return liveResetAtMillis > 0L && UsageWindow.sameResetWindow(sample.resetAtMillis,
                sample.windowSeconds, liveResetAtMillis, liveWindowSeconds);
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
                    ? TimeUnit.MINUTES.toMillis(5)
                    : MONTHLY.equals(kind)
                            ? TimeUnit.HOURS.toMillis(2) : TimeUnit.MINUTES.toMillis(30);
            if (sameWindow(last, next) && last.usedPercent == next.usedPercent
                    && observedAtMillis - last.observedAtMillis < minimumSpacing) {
                updated.set(updated.size() - 1, next);
                return new UsageHistory(kind, updated);
            }
        }
        updated.add(next);
        int max = maximumSamples(kind);
        while (updated.size() > max) updated.remove(0);
        return new UsageHistory(kind, updated);
    }

    /** Samples belonging to the latest reset window, ordered oldest to newest. */
    public List<UsageSample> currentWindowSamples() {
        if (samples.isEmpty()) return Collections.emptyList();
        UsageSample latest = samples.get(samples.size() - 1);
        ArrayList<UsageSample> result = new ArrayList<>();
        for (UsageSample sample : samples) {
            if (isCurrent(sample, latest)) result.add(sample);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Most recent reset windows, oldest to newest, for normalized historical overlays. The last
     * entry is the current window; after {@link #withLiveWindow} it may be empty, when nothing
     * has been recorded for the live window yet.
     */
    public List<List<UsageSample>> recentWindows(int maximum) {
        if (samples.isEmpty() || maximum <= 0) return Collections.emptyList();
        if (liveKnown) return recentWindowsAroundLive(maximum);
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

    private List<List<UsageSample>> recentWindowsAroundLive(int maximum) {
        UsageSample latest = samples.get(samples.size() - 1);
        ArrayList<List<UsageSample>> completed = new ArrayList<>();
        ArrayList<UsageSample> live = new ArrayList<>();
        ArrayList<UsageSample> group = new ArrayList<>();
        UsageSample previous = null;
        for (UsageSample sample : samples) {
            if (isCurrent(sample, latest)) {
                live.add(sample);
                continue;
            }
            if (previous != null && !sameWindow(previous, sample) && !group.isEmpty()) {
                completed.add(Collections.unmodifiableList(group));
                group = new ArrayList<>();
            }
            group.add(sample);
            previous = sample;
        }
        if (!group.isEmpty()) completed.add(Collections.unmodifiableList(group));
        int from = Math.max(0, completed.size() - (maximum - 1));
        ArrayList<List<UsageSample>> windows = new ArrayList<>(completed.subList(from,
                completed.size()));
        windows.add(Collections.unmodifiableList(live));
        return Collections.unmodifiableList(windows);
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
                ? TimeUnit.MINUTES.toMillis(10)
                : MONTHLY.equals(kind) ? TimeUnit.HOURS.toMillis(6) : TimeUnit.HOURS.toMillis(2);
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
        int max = maximumSamples(kind);
        if (samples.size() > max) {
            samples = new ArrayList<>(samples.subList(samples.size() - max, samples.size()));
        }
        return new UsageHistory(kind, samples);
    }

    private static int maximumSamples(String kind) {
        if (FIVE_HOUR.equals(kind)) return MAX_FIVE_HOUR_SAMPLES;
        if (MONTHLY.equals(kind)) return MAX_MONTHLY_SAMPLES;
        return MAX_WEEKLY_SAMPLES;
    }

    static boolean sameWindow(UsageSample left, UsageSample right) {
        if (left == null || right == null) return false;
        return UsageWindow.sameResetWindow(left.resetAtMillis, left.windowSeconds,
                right.resetAtMillis, right.windowSeconds);
    }

    private static String normalizeKind(String kind) {
        if (WEEKLY.equals(kind)) return WEEKLY;
        if (MONTHLY.equals(kind)) return MONTHLY;
        return FIVE_HOUR;
    }
}
