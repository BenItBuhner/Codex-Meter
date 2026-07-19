package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.TimeUnit;

/** Debug-only entry point that opens the dashboard with a deterministic pace warning. */
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
            AppPreferences.saveResetCredits(this, new ResetCreditsSnapshot(2,
                    java.util.Arrays.asList(
                            new RateLimitResetCredit("credit-soon", "both",
                                    RateLimitResetCredit.STATUS_AVAILABLE,
                                    now - TimeUnit.DAYS.toMillis(1),
                                    now + TimeUnit.DAYS.toMillis(2),
                                    "Soonest reset credit",
                                    "This credit expires first."),
                            new RateLimitResetCredit("credit-later", "both",
                                    RateLimitResetCredit.STATUS_AVAILABLE,
                                    now - TimeUnit.DAYS.toMillis(1),
                                    now + TimeUnit.DAYS.toMillis(9),
                                    "Later reset credit",
                                    "This credit expires after the soonest one.")),
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
}
