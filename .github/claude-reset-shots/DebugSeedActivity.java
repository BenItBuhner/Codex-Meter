package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Debug-only (temporary): seeds Codex + Claude Code with sample usage and saved resets.
 * Extras: grant (full|session_held|none), selected (id), open (main|refresh).
 */
public class DebugSeedActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String grant = getIntent().getStringExtra("grant");
        String selected = getIntent().getStringExtra("selected");
        String open = getIntent().getStringExtra("open");
        try {
            long now = System.currentTimeMillis();
            long s = now / 1000L;
            AppPreferences.completeOnboarding(this);
            AppPreferences.setRefreshOnLaunch(this, false);
            for (UsageProvider provider : UsageProvider.values()) {
                SecureTokenStore.clear(this, provider);
                ProviderPreferences.clearProvider(this, provider);
            }
            List<UsageProvider> enabled = new ArrayList<>();
            enabled.add(UsageProvider.CODEX);
            enabled.add(UsageProvider.CLAUDE_CODE);
            for (UsageProvider provider : enabled) {
                SecureTokenStore.save(this, provider, new AuthTokens("demo", "demo", "",
                        now + 30L * 86_400_000L, "", "demo@example.com"));
            }
            String codex = "{\"plan_type\":\"plus\",\"rate_limit\":{\"allowed\":true,"
                    + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":37,"
                    + "\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,"
                    + "\"reset_at\":" + (s + 5400) + "},\"secondary_window\":"
                    + "{\"used_percent\":61,\"limit_window_seconds\":604800,"
                    + "\"reset_after_seconds\":200000,\"reset_at\":" + (s + 200000) + "}}}";
            AppPreferences.saveSnapshot(this, UsageParser.parse(codex, now));
            long expiry = now + 39L * 86_400_000L;
            List<RateLimitResetCredit> credits = new ArrayList<>();
            credits.add(new RateLimitResetCredit("codex_demo", "full",
                    RateLimitResetCredit.STATUS_AVAILABLE, now - 86_400_000L, expiry,
                    "Reset credit", ""));
            AppPreferences.saveResetCredits(this, new ResetCreditsSnapshot(1, credits, now));
            String claude = "{\"five_hour\":{\"utilization\":19.0,\"resets_at\":\""
                    + iso(s + 7920) + "\"},\"seven_day\":{\"utilization\":49.0,"
                    + "\"resets_at\":\"" + iso(s + 266000) + "\"}" + cedar(grant, s) + "}";
            ProviderPreferences.saveSnapshot(this, UsageProvider.CLAUDE_CODE,
                    ClaudeUsageParser.parse(claude, "Max 20x", now));
            ProviderPreferences.saveSavedResets(this, UsageProvider.CLAUDE_CODE,
                    ClaudeSavedResets.fromUsageBody(claude, now));
            ProviderPreferences.setEmail(this, UsageProvider.CLAUDE_CODE, "demo@example.com");
            ProviderPreferences.setEnabledProviders(this, enabled);
            List<UsageProvider> order = new ArrayList<>(enabled);
            for (UsageProvider provider : UsageProvider.values()) {
                if (!order.contains(provider)) order.add(provider);
            }
            ProviderPreferences.setOrder(this, order);
            ProviderPreferences.setSelected(this, UsageProvider.fromId(selected));
        } catch (Exception e) {
            Log.e("DebugSeed", "seed failed", e);
        }
        Intent next = "reset".equals(open)
                ? ResetCreditActivity.intent(this, UsageProvider.fromId(selected))
                : "refresh".equals(open)
                ? new Intent(this, SettingsActivity.class).putExtra("settings_page", "refresh_usage")
                : new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PROVIDER, selected);
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private static String cedar(String grant, long s) {
        if ("none".equals(grant)) return "";
        boolean full = !"session_held".equals(grant);
        return ",\"cedar_ember\":{\"eligible\":true,\"at_limit\":false,\"grants\":[{"
                + "\"id\":\"demo_grant\",\"label\":\"Limit reset\",\"resets_total\":1,"
                + "\"resets_left\":1,\"ends_at\":\"" + iso(s + 39L * 86_400L) + "\","
                + "\"clears\":" + (full ? "[\"five_hour\",\"seven_day\"]" : "[\"five_hour\"]") + ","
                + "\"paused\":false,\"usable_now\":" + full + ",\"use_requires_limit\":" + !full + ","
                + "\"type\":\"" + (full ? "full" : "session") + "\","
                + "\"unavailable_reason\":" + (full ? "null" : "\"needs_limit\"") + "}],"
                + "\"next_grant_id\":\"demo_grant\"}";
    }

    private static String iso(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds).toString();
    }
}
