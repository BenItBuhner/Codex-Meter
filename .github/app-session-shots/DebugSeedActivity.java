package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Debug-only (temporary screenshot job): seeds Codex, Claude Code and Cursor sample usage,
 * turns on "Show in AI apps", then opens a screen. Extras: open (main|now_bar|app_sessions|none),
 * design (oneui9|classic), sessions (on|off).
 */
public class DebugSeedActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String open = getIntent().getStringExtra("open");
        String design = getIntent().getStringExtra("design");
        boolean reseed = !"no".equals(getIntent().getStringExtra("reseed"));
        try {
            if (!reseed) throw new SkipSeed();
            long now = System.currentTimeMillis();
            long s = now / 1000L;
            AppPreferences.completeOnboarding(this);
            getSharedPreferences("codex_meter_settings_v1", 0).edit()
                    .putBoolean("one_ui_9_design", !"classic".equals(design)).commit();
            AppPreferences.setRefreshOnLaunch(this, false);
            List<UsageProvider> enabled = new ArrayList<>();
            enabled.add(UsageProvider.CODEX);
            enabled.add(UsageProvider.CLAUDE_CODE);
            enabled.add(UsageProvider.CURSOR);
            for (UsageProvider provider : UsageProvider.values()) {
                SecureTokenStore.clear(this, provider);
                ProviderPreferences.clearProvider(this, provider);
            }
            for (UsageProvider provider : enabled) seed(provider, now, s);
            List<UsageProvider> order = new ArrayList<>(enabled);
            for (UsageProvider provider : UsageProvider.values()) {
                if (!order.contains(provider)) order.add(provider);
            }
            ProviderPreferences.setOrder(this, order);
            ProviderPreferences.setEnabledProviders(this, enabled);
            ProviderPreferences.setSelected(this, UsageProvider.CODEX);
            AppSessionPreferences.setEnabled(this, !"off".equals(
                    getIntent().getStringExtra("sessions")));
        } catch (SkipSeed skip) {
            // Only open the screen.
        } catch (Exception e) {
            Log.e("DebugSeed", "seed failed", e);
        }
        if ("none".equals(open)) {
            finish();
            return;
        }
        Intent next = "now_bar".equals(open) || "app_sessions".equals(open)
                ? new Intent(this, SettingsActivity.class).putExtra("settings_page", open)
                : new Intent(this, MainActivity.class);
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private static String iso(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds).toString();
    }

    private void seed(UsageProvider provider, long now, long s) throws Exception {
        SecureTokenStore.save(this, provider, new AuthTokens("demo", "demo", "",
                now + 30L * 86_400_000L, "", "demo@example.com"));
        if (provider == UsageProvider.CODEX) {
            String codex = "{\"plan_type\":\"plus\",\"rate_limit\":{\"allowed\":true,"
                    + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":37,"
                    + "\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,"
                    + "\"reset_at\":" + (s + 5400) + "},\"secondary_window\":"
                    + "{\"used_percent\":61,\"limit_window_seconds\":604800,"
                    + "\"reset_after_seconds\":200000,\"reset_at\":" + (s + 200000) + "}}}";
            AppPreferences.saveSnapshot(this, UsageParser.parse(codex, now));
            return;
        }
        UsageSnapshot snapshot = provider == UsageProvider.CLAUDE_CODE
                ? ClaudeUsageParser.parse("{\"five_hour\":{\"utilization\":42.0,"
                        + "\"resets_at\":\"" + iso(s + 7920) + "\"},\"seven_day\":{"
                        + "\"utilization\":68.0,\"resets_at\":\"" + iso(s + 266000) + "\"}}",
                        "Max 5x", now)
                : new UsageSnapshot("Pro", true, false, null, null,
                        new UsageWindow(46, 2_592_000L, 900_000L, s + 900_000L),
                        Collections.emptyList(), null, -1, now);
        ProviderPreferences.saveSnapshot(this, provider, snapshot);
        ProviderPreferences.setEmail(this, provider, "demo@example.com");
    }

    private static final class SkipSeed extends Exception {
    }
}
