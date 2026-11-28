package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Debug-only probe for the upgrade check (.github/workflows/upgrade-check.yml). It is copied into
 * the debug source set of BOTH the old release (v2.8.0) and the current branch, and only calls APIs
 * that exist, with the same meaning, in both.
 *
 * <ul>
 *   <li>{@code --es action seed}: (old build) signs in and saves usage, history, reset credits and
 *       non-default settings through the old app's own storage code.</li>
 *   <li>{@code --es action dump --es out NAME}: writes what the running build reads back, through
 *       its own getters, to files/NAME.json, plus every key of every preferences file.</li>
 *   <li>{@code --es open history|settings}: opens that page, for screenshots.</li>
 * </ul>
 */
public class UpgradeProbeActivity extends Activity {
    private static final String TAG = "UpgradeProbe";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent().getStringExtra("action");
        String result = "ok";
        try {
            if ("seed".equals(action)) {
                seed(this);
                Log.i(TAG, "seed ok");
            } else if ("dump".equals(action)) {
                String out = getIntent().getStringExtra("out");
                write(this, (out == null ? "upgrade-dump" : out) + ".json", dump(this).toString(2));
                Log.i(TAG, "dump ok " + out);
            }
            String open = getIntent().getStringExtra("open");
            if ("history".equals(open)) {
                startActivity(new android.content.Intent(this, UsageHistoryActivity.class));
            } else if ("settings".equals(open)) {
                startActivity(new android.content.Intent(this, SettingsActivity.class));
            }
        } catch (Throwable e) {
            Log.e(TAG, action + " failed", e);
            result = "failed: " + e;
        }
        // The check waits for this file instead of guessing how long the work takes.
        if (action != null) {
            try {
                write(this, "probe-" + action + ".done", result);
            } catch (Throwable e) {
                Log.e(TAG, "could not write the done marker", e);
            }
        }
        finish();
    }

    private static void seed(Context c) throws Exception {
        long now = System.currentTimeMillis();
        // The six refreshes end just now, so the windows are current on the screenshots. The
        // check compares the old build's reading with the new one's, not fixed values.
        long BASE_SECONDS = now / 1000L - 5 * 1800L;
        SecureTokenStore.save(c, new AuthTokens("upgrade-access-token", "upgrade-refresh-token",
                "", now + 30L * 86_400_000L, "acct-upgrade-1234", "upgrader@example.com"));
        AppPreferences.completeOnboarding(c);

        // Usage, recorded through the same path a refresh takes, at six times 30 minutes apart.
        for (int i = 0; i < 6; i++) {
            long s = BASE_SECONDS + i * 1800L;
            String codex = "{\"plan_type\":\"pro\",\"rate_limit\":{\"allowed\":true,"
                    + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":" + (10 + i * 7)
                    + ",\"limit_window_seconds\":18000,\"reset_after_seconds\":"
                    + (16000 - i * 1800) + ",\"reset_at\":" + (BASE_SECONDS + 16000) + "},"
                    + "\"secondary_window\":{\"used_percent\":" + (40 + i)
                    + ",\"limit_window_seconds\":604800,\"reset_after_seconds\":"
                    + (300000 - i * 1800) + ",\"reset_at\":" + (BASE_SECONDS + 300000) + "}}}";
            UsageSnapshot snapshot = UsageParser.parse(codex, s * 1000L);
            AppPreferences.saveSnapshot(c, snapshot);
            UsageHistoryRecorder.record(c, snapshot);
        }
        String credits = "{\"credits\":["
                + "{\"id\":\"rc-upgrade-1\",\"reset_type\":\"rate_limit\",\"status\":\"available\","
                + "\"granted_at\":\"2026-08-01T10:00:00Z\",\"expires_at\":\"2026-12-31T10:00:00Z\","
                + "\"title\":\"Rate limit reset\",\"description\":\"Granted for the upgrade test\"},"
                + "{\"id\":\"rc-upgrade-2\",\"reset_type\":\"rate_limit\",\"status\":\"available\","
                + "\"granted_at\":\"2026-08-05T10:00:00Z\",\"expires_at\":\"2027-01-15T10:00:00Z\","
                + "\"title\":\"Rate limit reset\",\"description\":\"Second credit\"}]}";
        AppPreferences.saveResetCredits(c, ResetCreditsParser.parse(credits,
                (BASE_SECONDS + 9000L) * 1000L));

        // Settings, each set to something other than its default.
        AppPreferences.setAppTheme(c, "dark");
        AppPreferences.setMaterialYouEnabled(c, false);
        AppPreferences.setRefreshMinutes(c, 30);
        AppPreferences.setAutomaticRefresh(c, false);
        AppPreferences.setRefreshOnLaunch(c, false);
        AppPreferences.setDashboardOrder(c, Arrays.asList(DashboardSections.WEEKLY,
                DashboardSections.FIVE_HOUR, DashboardSections.RESET_CREDITS,
                DashboardSections.USAGE_HISTORY, DashboardSections.USAGE_CREDITS));
        AppPreferences.setDashboardSectionHidden(c, DashboardSections.USAGE_CREDITS, true);
        AppPreferences.setHistorySectionVisible(c, DashboardSections.WEEKLY, false);
        ResetAlertPreferences.save(c, "alarm", "weekly", 80);
        ResetAlertPreferences.setUnexpectedRefillsEnabled(c, false);
        ResetAlertPreferences.setResetCreditIncreasesEnabled(c, false);
        ResetAlertPreferences.setResetCreditExpiryEnabled(c, true);
        ResetAlertPreferences.setResetCreditExpiryLeadTimes(c,
                Arrays.asList(3_600_000L, 86_400_000L));
        UsagePacePreferences.setEnabled(c, true);
        UsagePacePreferences.setSensitivity(c, "sensitive");
        NowBarPreferences.save(c, true, "weekly", 70);
        NowBarPreferences.setAcceleratedStartEnabled(c, true);
        NowBarPreferences.setDisplayMode(c, "used");
        NowBarPreferences.setPercentMode(c, "used");
        UpdatePreferences.setChannel(c, "alpha");
        UpdatePreferences.setNotifyUpdatesEnabled(c, false);
        UpdatePreferences.setCheckIntervalHours(c, 12);
        UpdatePreferences.setAutomaticChecks(c, true);
        // apply() writes are asynchronous; give them a moment before the process is killed.
        Thread.sleep(1500);
    }

    private static JSONObject dump(Context c) throws Exception {
        JSONObject o = new JSONObject();
        AuthTokens tokens = SecureTokenStore.load(c);
        JSONObject auth = new JSONObject();
        auth.put("signed_in", tokens != null);
        if (tokens != null) {
            auth.put("email", tokens.email);
            auth.put("account_id", tokens.accountId);
            auth.put("refresh_token", tokens.refreshToken);
            auth.put("access_token", tokens.accessToken);
        }
        o.put("auth", auth);
        o.put("onboarding_complete", AppPreferences.isOnboardingComplete(c));

        UsageSnapshot snapshot = AppPreferences.loadSnapshot(c);
        JSONObject usage = new JSONObject();
        usage.put("present", snapshot != null);
        if (snapshot != null) {
            usage.put("plan", snapshot.planType);
            usage.put("fetched_at", snapshot.fetchedAtMillis);
            usage.put("five_hour", window(snapshot.fiveHour));
            usage.put("weekly", window(snapshot.weekly));
        }
        o.put("usage", usage);

        JSONObject history = new JSONObject();
        for (String kind : Arrays.asList(UsageHistory.FIVE_HOUR, UsageHistory.WEEKLY)) {
            UsageHistory h = AppPreferences.loadUsageHistory(c, kind);
            JSONArray samples = new JSONArray();
            for (UsageSample sample : h.samples) {
                samples.put(sample.observedAtMillis + ":" + sample.usedPercent + ":"
                        + sample.resetAtMillis + ":" + sample.windowSeconds);
            }
            history.put(kind, samples);
        }
        o.put("history", history);

        ResetCreditsSnapshot credits = AppPreferences.loadResetCredits(c);
        JSONObject reset = new JSONObject();
        reset.put("present", credits != null);
        if (credits != null) {
            reset.put("available", credits.availableCount);
            JSONArray list = new JSONArray();
            for (RateLimitResetCredit credit : credits.credits) {
                list.put(credit.id + ":" + credit.status + ":" + credit.expiresAtMillis);
            }
            reset.put("credits", list);
        }
        o.put("reset_credits", reset);

        JSONObject settings = new JSONObject();
        settings.put("app_theme", AppPreferences.getAppTheme(c));
        settings.put("material_you", AppPreferences.isMaterialYouEnabled(c));
        settings.put("refresh_minutes", AppPreferences.getRefreshMinutes(c));
        settings.put("automatic_refresh", AppPreferences.getAutomaticRefresh(c));
        settings.put("refresh_on_launch", AppPreferences.getRefreshOnLaunch(c));
        settings.put("dashboard_order", AppPreferences.getDashboardOrder(c));
        settings.put("usage_credits_hidden",
                AppPreferences.isDashboardSectionHidden(c, DashboardSections.USAGE_CREDITS));
        settings.put("history_weekly_visible",
                AppPreferences.isHistorySectionVisible(c, DashboardSections.WEEKLY));
        settings.put("alert_style", ResetAlertPreferences.getStyle(c));
        settings.put("alert_metric", ResetAlertPreferences.getMetric(c));
        settings.put("alert_threshold", ResetAlertPreferences.getThreshold(c));
        settings.put("unexpected_refills", ResetAlertPreferences.unexpectedRefillsEnabled(c));
        settings.put("credit_increases", ResetAlertPreferences.resetCreditIncreasesEnabled(c));
        settings.put("credit_expiry", ResetAlertPreferences.resetCreditExpiryEnabled(c));
        settings.put("credit_expiry_leads",
                String.valueOf(ResetAlertPreferences.getResetCreditExpiryLeadTimes(c)));
        settings.put("pace_enabled", UsagePacePreferences.isEnabled(c));
        settings.put("pace_sensitivity", UsagePacePreferences.getSensitivity(c));
        settings.put("now_bar_auto", NowBarPreferences.isAutoStartEnabled(c));
        settings.put("now_bar_accelerated", NowBarPreferences.isAcceleratedStartEnabled(c));
        settings.put("now_bar_metric", NowBarPreferences.getMetric(c));
        settings.put("now_bar_threshold", NowBarPreferences.getThreshold(c));
        settings.put("now_bar_display", NowBarPreferences.getDisplayMode(c));
        settings.put("now_bar_percent", NowBarPreferences.getPercentMode(c));
        settings.put("update_channel", UpdatePreferences.channel(c));
        settings.put("update_notify", UpdatePreferences.notifyUpdatesEnabled(c));
        settings.put("update_interval_hours", UpdatePreferences.checkIntervalHours(c));
        settings.put("update_automatic", UpdatePreferences.automaticChecks(c));
        o.put("settings", settings);

        // Every key in every preferences file (values only for non-secret files), so the check
        // can tell whether the new build dropped anything the old one wrote.
        JSONObject raw = new JSONObject();
        File dir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        String[] files = dir.list();
        if (files != null) {
            Arrays.sort(files);
            for (String file : files) {
                if (!file.endsWith(".xml")) continue;
                String name = file.substring(0, file.length() - 4);
                SharedPreferences prefs = c.getSharedPreferences(name, 0);
                JSONObject keys = new JSONObject();
                for (Map.Entry<String, ?> e : new TreeMap<>(prefs.getAll()).entrySet()) {
                    boolean secret = name.startsWith("secure_auth");
                    keys.put(e.getKey(), secret ? "<redacted>" : String.valueOf(e.getValue()));
                }
                raw.put(name, keys);
            }
        }
        o.put("raw", raw);
        return o;
    }

    private static Object window(UsageWindow w) throws Exception {
        if (w == null) return JSONObject.NULL;
        return w.usedPercent + "%/" + w.windowSeconds + "s/reset " + w.resetAtEpochSeconds;
    }

    private static void write(Context c, String name, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), name))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
