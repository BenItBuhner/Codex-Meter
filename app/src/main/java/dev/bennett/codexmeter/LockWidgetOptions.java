package dev.bennett.codexmeter;

/* JADX INFO: loaded from: classes.dex */
public final class LockWidgetOptions {
    public final String metricMode;
    public final boolean showCountdown;
    public final boolean showResetAction;
    public final boolean showResetCredits;

    public LockWidgetOptions(String str, boolean z, boolean z2, boolean z3) {
        if (!"five_hour".equals(str) && !"weekly".equals(str)) {
            str = "both";
        }
        this.metricMode = str;
        this.showResetCredits = z;
        this.showResetAction = z2;
        this.showCountdown = z3;
    }

    public static LockWidgetOptions defaults() {
        return new LockWidgetOptions("both", false, false, true);
    }

    public boolean showsFiveHour() {
        return !"weekly".equals(this.metricMode);
    }

    public boolean showsWeekly() {
        return !"five_hour".equals(this.metricMode);
    }

    public boolean singleMetric() {
        return !"both".equals(this.metricMode);
    }
}
