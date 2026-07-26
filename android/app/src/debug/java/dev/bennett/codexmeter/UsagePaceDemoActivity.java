package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;
import java.util.concurrent.Executors;
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
        final boolean livePreview = getIntent().getBooleanExtra("start_live_preview", false);
        final boolean openResetCredits = getIntent().getBooleanExtra("open_reset_credits", false);
        Executors.newSingleThreadExecutor().execute(() -> {
            Exception failure = null;
            try {
                seed();
                if (livePreview && !NowBarManager.startPreview(this)) {
                    throw new IllegalStateException("Could not start live notification preview");
                }
            } catch (Exception exception) {
                failure = exception;
            }
            final Exception seedFailure = failure;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (seedFailure != null) {
                    throw new IllegalStateException("Could not seed usage pace demo", seedFailure);
                }
                Class<? extends Activity> target = livePreview
                        ? SettingsActivity.class
                        : (openResetCredits ? ResetCreditActivity.class : MainActivity.class);
                startActivity(new Intent(this, target)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            });
        });
    }

    private void seed() throws Exception {
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
                new UsageCredits(true, false, "2500"),
                1,
                now);
        SecureTokenStore.save(this, new AuthTokens(
                "debug-demo-access", "debug-demo-refresh", "", Long.MAX_VALUE,
                "debug-demo-account", "demo@codexmeter.local"));
        AppPreferences.saveSnapshot(this, snapshot);
        AppPreferences.saveResetCredits(this, new ResetCreditsSnapshot(1, Arrays.asList(
                new RateLimitResetCredit("demo-soon", "both", "available", now,
                        now + TimeUnit.DAYS.toMillis(17), "Reset credit", "")),
                now));
        AppPreferences.setRefreshOnLaunch(this, false);
        AppPreferences.setDashboardVisibility(this, true, true, true, true, true);
        AppPreferences.completeOnboarding(this);
        AppPreferences.setAppTheme(this, WidgetOptions.THEME_DARK);
        UsagePacePreferences.setEnabled(this, true);
        UsagePacePreferences.setSensitivity(this, UsagePace.BALANCED);
    }
}
