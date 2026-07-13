package dev.bennett.codexmeter;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Arrays;

/**
 * Debug-only helper used to seed a rich signed-in demo state for emulator recordings.
 * Not part of the production user flow.
 */
public final class DemoSeedActivity extends AppCompatActivity {
    public static final String EXTRA_STYLE = "demo_style";
    public static final String EXTRA_SEND_NOTIFICATION = "demo_send_notification";
    public static final String EXTRA_OPEN_MAIN = "demo_open_main";

    @Override
    protected void onCreate(Bundle bundle) {
        Ui.applySelectedTheme(this);
        super.onCreate(bundle);
        try {
            seed();
            Toast.makeText(this, "Demo data ready", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Demo seed failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        if (getIntent().getBooleanExtra(EXTRA_OPEN_MAIN, true)) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        finish();
    }

    private void seed() throws Exception {
        String style = getIntent().getStringExtra(EXTRA_STYLE);
        if (style != null && !style.isEmpty()) {
            AppPreferences.setAppStyle(this, style);
        }

        SecureTokenStore.save(this, new AuthTokens(
                "demo-access-token",
                "demo-refresh-token",
                "demo-id-token",
                System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L,
                "acct_demo_codex",
                "demo@codexmeter.app"));

        long nowSec = System.currentTimeMillis() / 1000L;
        UsageWindow fiveHour = new UsageWindow(
                27,
                5L * 60L * 60L,
                2L * 60L * 60L + 14L * 60L,
                nowSec + 2L * 60L * 60L + 14L * 60L);
        UsageWindow weekly = new UsageWindow(
                56,
                7L * 24L * 60L * 60L,
                2L * 24L * 60L * 60L,
                nowSec + 2L * 24L * 60L * 60L);
        UsageSnapshot snapshot = new UsageSnapshot(
                "plus",
                true,
                false,
                fiveHour,
                weekly,
                2,
                System.currentTimeMillis());
        AppPreferences.saveSnapshot(this, snapshot);

        long now = System.currentTimeMillis();
        RateLimitResetCredit credit = new RateLimitResetCredit(
                "demo-credit-1",
                "rate_limit_reset",
                RateLimitResetCredit.STATUS_AVAILABLE,
                now - 86400000L,
                now + 3L * 24L * 60L * 60L * 1000L,
                "Reset credit",
                "Demo credit for recordings");
        AppPreferences.saveResetCredits(this,
                new ResetCreditsSnapshot(2, Arrays.asList(credit), now));

        AppPreferences.completeOnboarding(this);
        AppPreferences.setOnboardingStep(this, OnboardingFlow.STEP_COMPLETE);
        AppPreferences.setRefreshOnLaunch(this, false);

        ResetAlertPreferences.save(
                this,
                ResetAlertPreferences.STYLE_NOTIFICATION,
                ResetAlertPreferences.METRIC_BOTH,
                100);
        ResetAlertPreferences.setUnexpectedRefillsEnabled(this, true);
        ResetAlertPreferences.setResetCreditIncreasesEnabled(this, true);
        ResetAlertPreferences.setResetCreditExpiryEnabled(this, true);
        getSharedPreferences("codex_meter_settings_v1", MODE_PRIVATE).edit()
                .putBoolean("notifications_allowed_ui", true)
                .apply();

        ResetNotificationManager.ensureChannel(this);
        if (getIntent().getBooleanExtra(EXTRA_SEND_NOTIFICATION, true)) {
            ResetNotificationManager.sendTestNotification(this);
        }

        WidgetRenderer.updateAll(this);
    }
}
