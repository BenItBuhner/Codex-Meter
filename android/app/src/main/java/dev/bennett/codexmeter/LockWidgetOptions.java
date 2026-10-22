package dev.bennett.codexmeter;

/* JADX INFO: loaded from: classes.dex */
public final class LockWidgetOptions {
    public final String metricMode;
    public final boolean showCountdown;
    public final boolean showResetAction;
    public final boolean showResetCredits;
    /** Ordered CSV of {@link WidgetMeters} keys; empty means migrate from {@link #metricMode}. */
    public final String visibleMeters;
    /** Short and long limits share one dial, the long one in a lighter shade. */
    public final String overlapMode;

    public LockWidgetOptions(String str, boolean z, boolean z2, boolean z3) {
        this(str, z, z2, z3, "");
    }

    public LockWidgetOptions(String str, boolean z, boolean z2, boolean z3, String visibleMeters) {
        this(str, z, z2, z3, visibleMeters, MeterOverlap.AUTO);
    }

    public LockWidgetOptions(String str, boolean z, boolean z2, boolean z3, String visibleMeters,
            String overlapMode) {
        this.overlapMode = MeterOverlap.normalize(overlapMode);
        if (!"five_hour".equals(str) && !"weekly".equals(str)) {
            str = "both";
        }
        this.metricMode = str;
        this.showResetCredits = z;
        this.showResetAction = z2;
        this.showCountdown = z3;
        this.visibleMeters = visibleMeters == null ? "" : visibleMeters.trim();
    }

    public static LockWidgetOptions defaults() {
        return new LockWidgetOptions("both", false, false, true,
                WidgetMeters.serialize(WidgetMeters.defaultVisible()));
    }

    public LockWidgetOptions withVisibleMeters(String metersCsv) {
        return new LockWidgetOptions(this.metricMode, this.showResetCredits, this.showResetAction,
                this.showCountdown, metersCsv, this.overlapMode);
    }

    public LockWidgetOptions withOverlapMeters(boolean overlap) {
        return withOverlapMode(overlap ? MeterOverlap.ON : MeterOverlap.OFF);
    }

    public LockWidgetOptions withOverlapMode(String mode) {
        return new LockWidgetOptions(this.metricMode, this.showResetCredits, this.showResetAction,
                this.showCountdown, this.visibleMeters, mode);
    }

    /** Whether this widget overlaps meters while showing {@code providers} providers. */
    public boolean overlaps(int providers) {
        return MeterOverlap.enabled(this.overlapMode, providers);
    }

    public String effectiveVisibleMeters() {
        return WidgetMeters.effectiveVisibleCsv(this.visibleMeters, this.metricMode);
    }

    public boolean showsFiveHour() {
        return WidgetMeters.contains(
                WidgetMeters.parse(effectiveVisibleMeters()), WidgetMeters.FIVE_HOUR);
    }

    public boolean showsWeekly() {
        return WidgetMeters.contains(
                WidgetMeters.parse(effectiveVisibleMeters()), WidgetMeters.WEEKLY);
    }

    public boolean singleMetric() {
        return WidgetMeters.resolvedSingleUsageMetric(effectiveVisibleMeters(),
                WidgetMeters.availableKeys(null), metricMode);
    }
}
