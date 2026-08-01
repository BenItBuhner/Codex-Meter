package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Debug-only entry point that opens the dashboard with deterministic usage and reset credits. */
public final class UsagePaceDemoActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String settingsPage = getIntent().getStringExtra("open_settings_page");
        if (settingsPage != null && !settingsPage.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class)
                    .putExtra("settings_page", settingsPage)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        if (getIntent().getBooleanExtra("open_usage_history", false)) {
            startActivity(new Intent(this, UsageHistoryActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        String widgetMetric = getIntent().getStringExtra("open_widget_metric");
        if (widgetMetric != null && !widgetMetric.isEmpty()) {
            int widgetId = getIntent().getIntExtra("appWidgetId", 42);
            WidgetOptions defaults = AppPreferences.loadDefaultWidgetOptions(this);
            AppPreferences.saveWidgetOptions(this, widgetId, new WidgetOptions(
                    defaults.layout, defaults.density, defaults.surfaceStyle,
                    defaults.graphicScale, defaults.theme, defaults.accent, defaults.opacity,
                    defaults.resetMode, defaults.displayMode, widgetMetric,
                    defaults.showTitle, defaults.showPlan, defaults.showUpdated,
                    defaults.showRefresh, defaults.showResetCredits, defaults.showResetAction)
                    .withPercentSymbol(defaults.showPercentSymbol));
            startActivity(new Intent(this, WidgetConfigActivity.class)
                    .putExtra("appWidgetId", widgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        if (getIntent().getBooleanExtra("resume_live_settings", false)) {
            startActivity(new Intent(this, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        try {
            // Optional overrides for demoing the non-configurable zero-balance auto-hide.
            String creditsBalance = getIntent().getStringExtra("credits_balance");
            boolean creditsNone = getIntent().getBooleanExtra("credits_none", false);
            long now = System.currentTimeMillis();
            long fiveHourReset = now - TimeUnit.HOURS.toMillis(1)
                    + TimeUnit.HOURS.toMillis(5);
            long weeklyReset = now - TimeUnit.HOURS.toMillis(1)
                    + TimeUnit.DAYS.toMillis(7);
            UsageSnapshot snapshot = new UsageSnapshot("pro", true, false,
                    new UsageWindow(37, TimeUnit.HOURS.toSeconds(5), 0L,
                            fiveHourReset / 1000L),
                    new UsageWindow(61, TimeUnit.DAYS.toSeconds(7), 0L,
                            weeklyReset / 1000L),
                    Arrays.asList(new UsageLimit(
                            "codex-spark",
                            "GPT-5.3-Codex-Spark",
                            "codex_bengalfox",
                            true,
                            false,
                            new UsageWindow(24, TimeUnit.HOURS.toSeconds(5), 0L,
                                    (now + TimeUnit.HOURS.toMillis(3)) / 1000L),
                            new UsageWindow(42, TimeUnit.DAYS.toSeconds(7), 0L,
                                    (now + TimeUnit.DAYS.toMillis(5)) / 1000L))),
                    creditsNone
                            ? new UsageCredits(false, false, "")
                            : new UsageCredits(true, false,
                                    creditsBalance == null ? "2500" : creditsBalance),
                    3,
                    now);
            SecureTokenStore.save(this, new AuthTokens(
                    "debug-demo-access", "debug-demo-refresh", "", Long.MAX_VALUE,
                    "debug-demo-account", "demo@codexmeter.local"));
            AppPreferences.saveSnapshot(this, snapshot);
            seedHistory(now, fiveHourReset, weeklyReset);
            AppPreferences.saveResetCredits(this, new ResetCreditsSnapshot(3, Arrays.asList(
                    new RateLimitResetCredit("demo-soon", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(1), "Reset credit 1", ""),
                    new RateLimitResetCredit("demo-middle", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(3), "Reset credit 2", ""),
                    new RateLimitResetCredit("demo-later", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(7), "Reset credit 3", "")),
                    now));
            AppPreferences.setRefreshOnLaunch(this, false);
            AppPreferences.setDashboardVisibility(this, true, true, true, true, true, true);
            AppPreferences.completeOnboarding(this);
            UsagePacePreferences.setEnabled(this, true);
            UsagePacePreferences.setSensitivity(this, UsagePace.BALANCED);
            boolean livePreview = getIntent().getBooleanExtra("start_live_preview", false);
            if (livePreview && !NowBarManager.startPreview(this)) {
                throw new IllegalStateException("Could not start live notification preview");
            }
            startActivity(new Intent(this,
                    livePreview ? SettingsActivity.class : MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not seed usage pace demo", exception);
        } finally {
            finish();
        }
    }

    private void seedHistory(long now, long fiveHourReset, long weeklyReset) {
        // Varied historical shapes exercise the per-window breakdown, typical-pace
        // comparison, and scrubbable overlays: quiet, steady, heavy, and bursty windows.
        UsageHistory five = UsageHistory.empty(UsageHistory.FIVE_HOUR);
        int[][] fiveShapes = {
                {3, 7, 12, 18, 22},
                {6, 14, 25, 33, 41},
                {12, 30, 52, 74, 96},
                {10, 26, 38, 61, 83},
        };
        for (int window = fiveShapes.length; window >= 1; window--) {
            long reset = fiveHourReset - TimeUnit.HOURS.toMillis(5L * window);
            int[] shape = fiveShapes[fiveShapes.length - window];
            for (int point = 0; point < shape.length; point++) {
                long observed = reset - TimeUnit.HOURS.toMillis(5)
                        + TimeUnit.MINUTES.toMillis(45L * (point + 1));
                five = five.append(new UsageWindow(shape[point],
                        TimeUnit.HOURS.toSeconds(5), 0L, reset / 1000L), observed);
            }
        }
        // Current 5-hour window climbs to the snapshot's 37% over the elapsed hour.
        int[] fiveUsed = {8, 15, 22, 30, 37};
        for (int point = 0; point < fiveUsed.length; point++) {
            five = five.append(new UsageWindow(fiveUsed[point],
                            TimeUnit.HOURS.toSeconds(5), 0L, fiveHourReset / 1000L),
                    now - TimeUnit.MINUTES.toMillis(48L - 12L * point));
        }
        AppPreferences.saveUsageHistory(this, five);

        UsageHistory weekly = UsageHistory.empty(UsageHistory.WEEKLY);
        int[][] weeklyShapes = {
                {5, 9, 14, 22, 30, 38},
                {8, 19, 33, 47, 58, 71},
                {15, 34, 52, 78, 95, 100},
                {11, 24, 39, 52, 66, 84},
        };
        for (int window = weeklyShapes.length; window >= 1; window--) {
            long reset = weeklyReset - TimeUnit.DAYS.toMillis(7L * window);
            int[] shape = weeklyShapes[weeklyShapes.length - window];
            for (int point = 0; point < shape.length; point++) {
                long observed = reset - TimeUnit.DAYS.toMillis(7)
                        + TimeUnit.HOURS.toMillis(24L * (point + 1));
                weekly = weekly.append(new UsageWindow(shape[point],
                        TimeUnit.DAYS.toSeconds(7), 0L, reset / 1000L), observed);
            }
        }
        // Current weekly window climbs to the snapshot's 61% over the elapsed hour.
        int[] weeklyUsed = {12, 28, 41, 53, 61};
        for (int point = 0; point < weeklyUsed.length; point++) {
            weekly = weekly.append(new UsageWindow(weeklyUsed[point],
                            TimeUnit.DAYS.toSeconds(7), 0L, weeklyReset / 1000L),
                    now - TimeUnit.MINUTES.toMillis(48L - 12L * point));
        }
        AppPreferences.saveUsageHistory(this, weekly);
    }
}
