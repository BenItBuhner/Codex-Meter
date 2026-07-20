package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.NowBarAutoStart;
import dev.bennett.codexmeter.NowBarDisplayMode;
import dev.bennett.codexmeter.NowBarPercentMode;
import dev.bennett.codexmeter.UsagePace;
import org.json.JSONException;
import org.json.JSONObject;

public final class WearSettingsState {
    public static final String SOURCE_PHONE = "phone";
    public static final String SOURCE_WEAR = "wear";

    public final boolean acceleratedStartEnabled;
    public final boolean autoStartEnabled;
    public final String displayMode;
    public final String metric;
    public final boolean monitorActive;
    public final String packageName;
    public final String percentMode;
    public final int refreshMinutes;
    public final String sourceNode;
    public final int threshold;
    public final long updatedAtMillis;
    public final boolean usagePaceEnabled;
    public final String usagePaceSensitivity;

    public WearSettingsState(String displayMode, String percentMode, boolean autoStartEnabled,
            String metric, int threshold, boolean monitorActive, int refreshMinutes,
            long updatedAtMillis, String sourceNode) {
        this(displayMode, percentMode, autoStartEnabled, metric, threshold, monitorActive,
                refreshMinutes, updatedAtMillis, sourceNode, null, true, UsagePace.BALANCED,
                false);
    }

    public WearSettingsState(String displayMode, String percentMode, boolean autoStartEnabled,
            String metric, int threshold, boolean monitorActive, int refreshMinutes,
            long updatedAtMillis, String sourceNode, String packageName) {
        this(displayMode, percentMode, autoStartEnabled, metric, threshold, monitorActive,
                refreshMinutes, updatedAtMillis, sourceNode, packageName, true,
                UsagePace.BALANCED, false);
    }

    public WearSettingsState(String displayMode, String percentMode, boolean autoStartEnabled,
            String metric, int threshold, boolean monitorActive, int refreshMinutes,
            long updatedAtMillis, String sourceNode, String packageName,
            boolean usagePaceEnabled, String usagePaceSensitivity,
            boolean acceleratedStartEnabled) {
        this.displayMode = NowBarDisplayMode.normalize(displayMode);
        this.percentMode = NowBarPercentMode.normalize(percentMode);
        this.autoStartEnabled = autoStartEnabled;
        this.metric = NowBarAutoStart.normalizeMetric(metric);
        this.threshold = NowBarAutoStart.normalizeThreshold(threshold);
        this.monitorActive = monitorActive;
        this.refreshMinutes = normalizeRefreshMinutes(refreshMinutes);
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
        this.sourceNode = normalizeSource(sourceNode);
        this.packageName = packageName == null ? "" : packageName.trim();
        this.usagePaceEnabled = usagePaceEnabled;
        this.usagePaceSensitivity = UsagePace.normalizeSensitivity(usagePaceSensitivity);
        this.acceleratedStartEnabled = acceleratedStartEnabled;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("display_mode", displayMode);
        json.put("percent_mode", percentMode);
        json.put("auto_start_enabled", autoStartEnabled);
        json.put("metric", metric);
        json.put("threshold", threshold);
        json.put("monitor_active", monitorActive);
        json.put("refresh_minutes", refreshMinutes);
        json.put("usage_pace_enabled", usagePaceEnabled);
        json.put("usage_pace_sensitivity", usagePaceSensitivity);
        json.put("accelerated_start_enabled", acceleratedStartEnabled);
        json.put("updated_at_millis", updatedAtMillis);
        json.put("source_node", sourceNode);
        if (!packageName.isEmpty()) {
            json.put("package_name", packageName);
        }
        return json;
    }

    public static WearSettingsState fromJson(JSONObject json) {
        if (json == null) return null;
        return new WearSettingsState(
                json.optString("display_mode", NowBarDisplayMode.AUTO),
                json.optString("percent_mode", NowBarPercentMode.AUTO),
                json.optBoolean("auto_start_enabled", false),
                json.optString("metric", NowBarAutoStart.METRIC_BOTH),
                json.optInt("threshold", 25),
                json.optBoolean("monitor_active", false),
                json.optInt("refresh_minutes", 30),
                json.optLong("updated_at_millis", 0L),
                json.optString("source_node", SOURCE_PHONE),
                json.optString("package_name", ""),
                json.optBoolean("usage_pace_enabled", true),
                json.optString("usage_pace_sensitivity", UsagePace.BALANCED),
                json.optBoolean("accelerated_start_enabled", false));
    }

    /**
     * Content equality intentionally ignores updatedAtMillis so devices can compare whether
     * settings actually changed after conflict resolution timestamps are assigned.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WearSettingsState)) return false;
        WearSettingsState that = (WearSettingsState) other;
        return autoStartEnabled == that.autoStartEnabled
                && acceleratedStartEnabled == that.acceleratedStartEnabled
                && monitorActive == that.monitorActive
                && usagePaceEnabled == that.usagePaceEnabled
                && threshold == that.threshold
                && refreshMinutes == that.refreshMinutes
                && displayMode.equals(that.displayMode)
                && percentMode.equals(that.percentMode)
                && metric.equals(that.metric)
                && usagePaceSensitivity.equals(that.usagePaceSensitivity)
                && sourceNode.equals(that.sourceNode);
    }

    @Override
    public int hashCode() {
        int result = displayMode.hashCode();
        result = 31 * result + percentMode.hashCode();
        result = 31 * result + (autoStartEnabled ? 1 : 0);
        result = 31 * result + (acceleratedStartEnabled ? 1 : 0);
        result = 31 * result + metric.hashCode();
        result = 31 * result + threshold;
        result = 31 * result + (monitorActive ? 1 : 0);
        result = 31 * result + refreshMinutes;
        result = 31 * result + (usagePaceEnabled ? 1 : 0);
        result = 31 * result + usagePaceSensitivity.hashCode();
        result = 31 * result + sourceNode.hashCode();
        return result;
    }

    private static int normalizeRefreshMinutes(int minutes) {
        if (minutes == 5 || minutes == 10 || minutes == 15
                || minutes == 30 || minutes == 60 || minutes == 120) {
            return minutes;
        }
        return 30;
    }

    private static String normalizeSource(String source) {
        return SOURCE_WEAR.equals(source) ? SOURCE_WEAR : SOURCE_PHONE;
    }
}
