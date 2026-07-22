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
            long now = System.currentTimeMillis();
            long fiveHourReset = now - TimeUnit.HOURS.toMillis(1)
                    + TimeUnit.HOURS.toMillis(5);
            long weeklyReset = now - TimeUnit.HOURS.toMillis(1)
                    + TimeUnit.DAYS.toMillis(7);
            UsageSnapshot snapshot = new UsageSnapshot("pro", true, false,
                    new UsageWindow(15, TimeUnit.HOURS.toSeconds(5), 0L,
                            fiveHourReset / 1000L),
                    new UsageWindow(15, TimeUnit.DAYS.toSeconds(7), 0L,
                            weeklyReset / 1000L),
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
        UsageHistory five = UsageHistory.empty(UsageHistory.FIVE_HOUR);
        for (int window = 2; window >= 1; window--) {
            long reset = fiveHourReset - TimeUnit.HOURS.toMillis(5L * window);
            for (int point = 1; point <= 5; point++) {
                long observed = reset - TimeUnit.HOURS.toMillis(5)
                        + TimeUnit.MINUTES.toMillis(45L * point);
                int used = Math.min(96, point * (window == 1 ? 18 : 15));
                five = five.append(new UsageWindow(used, TimeUnit.HOURS.toSeconds(5), 0L,
                        reset / 1000L), observed);
            }
        }
        int[] fiveUsed = {4, 9, 15};
        for (int point = 0; point < fiveUsed.length; point++) {
            five = five.append(new UsageWindow(fiveUsed[point],
                            TimeUnit.HOURS.toSeconds(5), 0L, fiveHourReset / 1000L),
                    now - TimeUnit.MINUTES.toMillis(40L - 20L * point));
        }
        AppPreferences.saveUsageHistory(this, five);

        UsageHistory weekly = UsageHistory.empty(UsageHistory.WEEKLY);
        long previousWeeklyReset = weeklyReset - TimeUnit.DAYS.toMillis(7);
        for (int point = 1; point <= 6; point++) {
            long observed = previousWeeklyReset - TimeUnit.DAYS.toMillis(7)
                    + TimeUnit.HOURS.toMillis(24L * point);
            weekly = weekly.append(new UsageWindow(point * 9,
                            TimeUnit.DAYS.toSeconds(7), 0L, previousWeeklyReset / 1000L),
                    observed);
        }
        int[] weeklyUsed = {4, 9, 15};
        for (int point = 0; point < weeklyUsed.length; point++) {
            weekly = weekly.append(new UsageWindow(weeklyUsed[point],
                            TimeUnit.DAYS.toSeconds(7), 0L, weeklyReset / 1000L),
                    now - TimeUnit.MINUTES.toMillis(40L - 20L * point));
        }
        AppPreferences.saveUsageHistory(this, weekly);
    }
}
