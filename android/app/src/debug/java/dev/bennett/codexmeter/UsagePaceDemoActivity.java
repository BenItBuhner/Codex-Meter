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
            boolean zeroCredits = getIntent().getBooleanExtra("zero_usage_credits", false);
            boolean openReorder = getIntent().getBooleanExtra("open_reorder", false);
            UsageCredits credits = zeroCredits
                    ? new UsageCredits(true, false, "0")
                    : new UsageCredits(true, false, "2500");
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
                    credits,
                    3,
                    now);
            SecureTokenStore.save(this, new AuthTokens(
                    "debug-demo-access", "debug-demo-refresh", "", Long.MAX_VALUE,
                    "debug-demo-account", "demo@codexmeter.local"));
            AppPreferences.saveSnapshot(this, snapshot);
            AppPreferences.saveResetCredits(this, new ResetCreditsSnapshot(3, Arrays.asList(
                    new RateLimitResetCredit("demo-soon", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(1), "Reset credit 1", ""),
                    new RateLimitResetCredit("demo-middle", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(3), "Reset credit 2", ""),
                    new RateLimitResetCredit("demo-later", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(7), "Reset credit 3", "")),
                    now));
            AppPreferences.setRefreshOnLaunch(this, false);
            AppPreferences.setDashboardVisibility(this, true, true, true, true, true);
            if (getIntent().hasExtra("dashboard_order")) {
                AppPreferences.setDashboardItemOrder(this,
                        DashboardOrder.parse(getIntent().getStringExtra("dashboard_order")));
            }
            AppPreferences.completeOnboarding(this);
            UsagePacePreferences.setEnabled(this, true);
            UsagePacePreferences.setSensitivity(this, UsagePace.BALANCED);
            boolean livePreview = getIntent().getBooleanExtra("start_live_preview", false);
            if (livePreview && !NowBarManager.startPreview(this)) {
                throw new IllegalStateException("Could not start live notification preview");
            }
            Class<?> target = livePreview
                    ? SettingsActivity.class
                    : (openReorder ? DashboardReorderActivity.class : MainActivity.class);
            startActivity(new Intent(this, target)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not seed usage pace demo", exception);
        } finally {
            finish();
        }
    }
}
