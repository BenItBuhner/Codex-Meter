package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Debug-only: seeds a Codex account with two banked resets for screenshots, then opens a screen.
 * Extras: open (main|refresh|credits|notify), lowuse (on|off), lead (millis).
 */
public class DebugSeedActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String open = getIntent().getStringExtra("open");
        try {
            long now = System.currentTimeMillis();
            long s = now / 1000L;
            AppPreferences.completeOnboarding(this);
            AppPreferences.setRefreshOnLaunch(this, false);
            ResetAlertPreferences.save(this, ResetAlertPreferences.STYLE_NOTIFICATION, "both", 10);
            SecureTokenStore.save(this, new AuthTokens("demo", "demo", "",
                    now + 30L * 86_400_000L, "", "demo@example.com"));
            String codex = "{\"plan_type\":\"plus\",\"rate_limit\":{\"allowed\":true,"
                    + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":63,"
                    + "\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,"
                    + "\"reset_at\":" + (s + 5400) + "},\"secondary_window\":"
                    + "{\"used_percent\":81,\"limit_window_seconds\":604800,"
                    + "\"reset_after_seconds\":200000,\"reset_at\":" + (s + 200000) + "}},"
                    + "\"rate_limit_reset_credits\":{\"available_count\":2}}";
            AppPreferences.saveSnapshot(this, UsageParser.parse(codex, now));
            String credits = "{\"available_count\":2,\"credits\":["
                    + credit("rc_1", s + 8L * 86_400L + 3_700L)
                    + "," + credit("rc_2", s + 16L * 86_400L + 16_000L) + "]}";
            AppPreferences.saveResetCredits(this, ResetCreditsParser.parse(credits, now));
            ResetAlertPreferences.setResetCreditExpiryAutoApplyEnabled(this, true);
            String lead = getIntent().getStringExtra("lead");
            ResetAlertPreferences.setResetCreditExpiryAutoApplyLeadMillis(this,
                    lead == null ? ResetCreditExpiryApply.DEFAULT_LEAD_MS : Long.parseLong(lead));
            boolean lowUse = "on".equals(getIntent().getStringExtra("lowuse"));
            ResetAlertPreferences.setResetCreditLowUsageAutoUseEnabled(this, lowUse);
            ResetAlertPreferences.setResetCreditLowUsageThreshold(this, 2);
            ResetAlertPreferences.setResetCreditLowUsageMetric(this, "both");
        } catch (Exception e) {
            Log.e("DebugSeed", "seed failed", e);
        }
        Intent next;
        if ("refresh".equals(open)) {
            next = new Intent(this, SettingsActivity.class).putExtra("settings_page",
                    "refresh_usage");
        } else if ("credits".equals(open)) {
            next = new Intent(this, ResetCreditActivity.class);
        } else if ("notify".equals(open)) {
            long expires = System.currentTimeMillis() + 30L * 60_000L;
            ResetNotificationManager.showResetCreditLowUsageNotification(this, "weekly", 2,
                    new ResetConsumeResult(ResetConsumeResult.RESET, 2, ""));
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            ResetNotificationManager.showResetCreditExpiryApplyNotification(this, expires,
                    new ResetConsumeResult(ResetConsumeResult.RESET, 2, ""));
            finish();
            return;
        } else {
            next = new Intent(this, MainActivity.class);
        }
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private static String credit(String id, long expiresEpochSeconds) {
        return "{\"id\":\"" + id + "\",\"reset_type\":\"both\",\"status\":\"available\","
                + "\"title\":\"Full reset\",\"granted_at\":\""
                + java.time.Instant.ofEpochSecond(expiresEpochSeconds - 20L * 86_400L)
                + "\",\"expires_at\":\"" + java.time.Instant.ofEpochSecond(expiresEpochSeconds)
                + "\"}";
    }
}
