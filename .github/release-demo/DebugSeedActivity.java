package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Debug-only (temporary release demo recording): seeds sample usage for six providers and
 * opens a screen. Extras: open (main|add_account|none|any Settings page key),
 * design (oneui9|classic), sessions (on|off), select (provider id), reseed (no).
 */
public class DebugSeedActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String open = getIntent().getStringExtra("open");
        String design = getIntent().getStringExtra("design");
        String select = getIntent().getStringExtra("select");
        boolean reseed = !"no".equals(getIntent().getStringExtra("reseed"));
        try {
            if (reseed) {
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
                enabled.add(UsageProvider.COPILOT);
                enabled.add(UsageProvider.GROK);
                enabled.add(UsageProvider.KIMI);
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
                AppSessionPreferences.setEnabled(this, !"off".equals(
                        getIntent().getStringExtra("sessions")));
            }
            UsageProvider selected = UsageProvider.CODEX;
            if (select != null) {
                for (UsageProvider provider : UsageProvider.values()) {
                    if (provider.id.equals(select)) selected = provider;
                }
            }
            if (reseed || select != null) ProviderPreferences.setSelected(this, selected);
        } catch (Exception e) {
            Log.e("DebugSeed", "seed failed", e);
        }
        if (open == null || "none".equals(open)) {
            finish();
            return;
        }
        Intent next;
        if ("main".equals(open)) {
            next = new Intent(this, MainActivity.class);
        } else if ("add_account".equals(open)) {
            next = new Intent(this, AddAccountActivity.class);
        } else {
            next = new Intent(this, SettingsActivity.class).putExtra("settings_page", open);
        }
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private static String iso(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds).toString();
    }

    private static UsageSnapshot monthly(String plan, int used, long s, long resetIn, long now) {
        return new UsageSnapshot(plan, true, false, null, null,
                new UsageWindow(used, 2_592_000L, resetIn, s + resetIn),
                Collections.emptyList(), null, -1, now);
    }

    private void seed(UsageProvider provider, long now, long s) throws Exception {
        SecureTokenStore.save(this, provider, new AuthTokens("demo", "demo", "",
                now + 30L * 86_400_000L, "", "demo@example.com"));
        if (provider == UsageProvider.CODEX) {
            String codex = "{\"plan_type\":\"pro\",\"rate_limit\":{\"allowed\":true,"
                    + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":37,"
                    + "\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,"
                    + "\"reset_at\":" + (s + 5400) + "},\"secondary_window\":"
                    + "{\"used_percent\":61,\"limit_window_seconds\":604800,"
                    + "\"reset_after_seconds\":200000,\"reset_at\":" + (s + 200000) + "}},"
                    + "\"rate_limit_reset_credits\":{\"available_count\":2},"
                    + "\"spend_control\":{\"reached\":false,\"individual_limit\":{"
                    + "\"limit\":\"1000\",\"used\":\"426.1\",\"remaining_percent\":57,"
                    + "\"reset_at\":" + (s + 1_100_000) + "}}}";
            AppPreferences.saveSnapshot(this, UsageParser.parse(codex, now));
            return;
        }
        UsageSnapshot snapshot;
        switch (provider) {
            case CLAUDE_CODE:
                snapshot = ClaudeUsageParser.parse("{\"five_hour\":{\"utilization\":42.0,"
                        + "\"resets_at\":\"" + iso(s + 7920) + "\"},\"seven_day\":{"
                        + "\"utilization\":68.0,\"resets_at\":\"" + iso(s + 266000) + "\"}}",
                        "Max 5x", now);
                break;
            case CURSOR:
                snapshot = monthly("Pro", 46, s, 900_000L, now);
                break;
            case COPILOT:
                snapshot = monthly("Pro", 64, s, 780_000L, now);
                break;
            case GROK:
                snapshot = new UsageSnapshot("SuperGrok", true, false,
                        new UsageWindow(23, 7_200L, 4_000L, s + 4_000L), null, -1, now);
                break;
            default:
                snapshot = new UsageSnapshot("Moderato", true, false,
                        new UsageWindow(18, 18_000L, 11_000L, s + 11_000L),
                        new UsageWindow(39, 604_800L, 330_000L, s + 330_000L), -1, now);
                break;
        }
        ProviderPreferences.saveSnapshot(this, provider, snapshot);
        ProviderPreferences.setEmail(this, provider, "demo@example.com");
    }
}
