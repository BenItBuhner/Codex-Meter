package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Debug-only: seeds sample accounts and usage for screenshots, then opens a screen.
 * Extras: enabled (comma ids, tab order), selected (id), open (main|settings|add|sign_in),
 * provider (for sign_in).
 */
public class DebugSeedActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String enabledIds = getIntent().getStringExtra("enabled");
        String selected = getIntent().getStringExtra("selected");
        String open = getIntent().getStringExtra("open");
        List<UsageProvider> enabled = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            long s = now / 1000L;
            AppPreferences.completeOnboarding(this);
            AppPreferences.setRefreshOnLaunch(this, false);
            for (UsageProvider provider : UsageProvider.values()) {
                SecureTokenStore.clear(this, provider);
                ProviderPreferences.clearProvider(this, provider);
            }
            if (enabledIds != null && !enabledIds.isEmpty()) {
                for (String id : enabledIds.split(",")) {
                    if (!id.isEmpty() && !"none".equals(id)) enabled.add(UsageProvider.fromId(id));
                }
            }
            for (UsageProvider provider : enabled) seed(provider, now, s);
            if (!enabled.isEmpty()) {
                List<UsageProvider> order = new ArrayList<>(enabled);
                for (UsageProvider provider : UsageProvider.values()) {
                    if (!order.contains(provider)) order.add(provider);
                }
                ProviderPreferences.setOrder(this, order);
                ProviderPreferences.setEnabledProviders(this, enabled);
            }
            if (selected != null) {
                ProviderPreferences.setSelected(this, UsageProvider.fromId(selected));
            }
        } catch (Exception e) {
            Log.e("DebugSeed", "seed failed", e);
        }
        Intent next;
        if ("settings".equals(open)) {
            next = new Intent(this, SettingsActivity.class);
        } else if ("appearance".equals(open)) {
            next = new Intent(this, SettingsActivity.class).putExtra("settings_page", "appearance");
        } else if ("widget".equals(open)) {
            next = new Intent(this, WidgetConfigActivity.class)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 4242);
        } else if ("add".equals(open)) {
            next = new Intent(this, AddAccountActivity.class);
        } else if ("sign_in".equals(open)) {
            next = SignInActivity.intent(this,
                    UsageProvider.fromId(getIntent().getStringExtra("provider")));
        } else {
            next = new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_PROVIDER, selected);
        }
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void seed(UsageProvider provider, long now, long s) throws Exception {
        String email = "demo@example.com";
        SecureTokenStore.save(this, provider, new AuthTokens("demo", "demo", "",
                now + 30L * 86_400_000L, "", email));
        if (provider == UsageProvider.CODEX) {
            String codex = "{\"plan_type\":\"plus\",\"rate_limit\":{\"allowed\":true,"
                    + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":37,"
                    + "\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,"
                    + "\"reset_at\":" + (s + 5400) + "},\"secondary_window\":"
                    + "{\"used_percent\":61,\"limit_window_seconds\":604800,"
                    + "\"reset_after_seconds\":200000,\"reset_at\":" + (s + 200000) + "}}}";
            UsageSnapshot snapshot = UsageParser.parse(codex, now);
            AppPreferences.saveSnapshot(this, snapshot);
            UsageHistoryRecorder.record(this, UsageProvider.CODEX, snapshot);
            return;
        }
        UsageSnapshot snapshot = sample(provider, now, s);
        if (snapshot != null) {
            ProviderPreferences.saveSnapshot(this, provider, snapshot);
            UsageHistoryRecorder.record(this, provider, snapshot);
        }
        ProviderPreferences.setEmail(this, provider, email);
    }

    private static String iso(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds).toString();
    }

    private static UsageSnapshot sample(UsageProvider provider, long now, long s)
            throws Exception {
        switch (provider) {
            case CLAUDE_CODE:
                return ClaudeUsageParser.parse("{\"five_hour\":{\"utilization\":42.0,"
                        + "\"resets_at\":\"" + iso(s + 7920) + "\"},\"seven_day\":{"
                        + "\"utilization\":68.0,\"resets_at\":\"" + iso(s + 266000) + "\"}}",
                        "Max 5x", now);
            case CURSOR:
                return new UsageSnapshot("Pro", true, false, null, null,
                        new UsageWindow(46, 2_592_000L, 900_000L, s + 900_000L),
                        Collections.emptyList(), null, -1, now);
            case COPILOT:
                return ProviderUsageParsers.copilot("{\"copilot_plan\":\"individual\","
                        + "\"quota_reset_date\":\"" + iso(s + 864_000L) + "\",\"quota_snapshots\":{"
                        + "\"premium_interactions\":{\"entitlement\":300,\"remaining\":171,"
                        + "\"percent_remaining\":57.0}}}", now);
            case KIMI:
                return ProviderUsageParsers.kimi("{\"user\":{\"membership\":{\"level\":"
                        + "\"LEVEL_INTERMEDIATE\"}},\"usages\":{\"limit_5h\":{\"used_ratio\":0.18,"
                        + "\"reset_time\":" + (s + 9000) + "},\"limit_7d\":{\"used_ratio\":0.44,"
                        + "\"reset_time\":" + (s + 300000) + "}}}", now);
            case FACTORY:
                return ProviderUsageParsers.factory("{\"limits\":{\"standard\":{\"fiveHour\":{"
                        + "\"usedPercent\":22,\"windowEnd\":" + (s + 6000) + "},\"weekly\":{"
                        + "\"usedPercent\":51,\"windowEnd\":" + (s + 350000) + "}}},"
                        + "\"extraUsageBalanceCents\":1250}", "Pro", now);
            case GROK:
                return ProviderUsageParsers.grok("{\"config\":{\"creditUsagePercent\":31,"
                        + "\"currentPeriod\":{\"start\":\"" + iso(s - 1_000_000L) + "\",\"end\":\""
                        + iso(s + 1_592_000L) + "\"},\"subscriptionTier\":\"SUPER_GROK\"}}", now);
            case ANTIGRAVITY:
                return ProviderUsageParsers.antigravity("{\"groups\":[{\"buckets\":["
                        + "{\"bucketId\":\"gemini-5h\",\"remainingFraction\":0.66,\"resetTime\":\""
                        + iso(s + 4000) + "\"},{\"bucketId\":\"gemini-weekly\",\"remainingFraction\""
                        + ":0.81,\"resetTime\":\"" + iso(s + 400000) + "\"},{\"bucketId\":\"3p-5h\","
                        + "\"remainingFraction\":0.4,\"resetTime\":\"" + iso(s + 4000) + "\"}]}]}",
                        "Pro", now);
            case DEVIN:
            case WINDSURF:
                return ProviderUsageParsers.codeium("{\"userStatus\":{\"planStatus\":{"
                        + "\"planInfo\":{\"planName\":\"Pro\"},\"dailyQuotaRemainingPercent\":73,"
                        + "\"dailyQuotaResetAtUnix\":\"" + (s + 30000) + "\","
                        + "\"weeklyQuotaRemainingPercent\":58,\"weeklyQuotaResetAtUnix\":\""
                        + (s + 400000) + "\"}}}", now);
            case AUGMENT:
                return ProviderUsageParsers.augment("{\"plan_name\":\"Developer\","
                        + "\"amount_remaining\":29000,\"amount_included_per_cycle\":40000,"
                        + "\"billing_cycle_end_date_iso\":\"" + iso(s + 1_200_000L) + "\"}", now);
            case KILO:
                return ProviderUsageParsers.kilo("{\"balance\":18.42}", now);
            case OPENROUTER:
                return ProviderUsageParsers.openrouter("{\"data\":{\"limit\":25,"
                        + "\"usage_monthly\":9,\"limit_reset\":\"monthly\"}}",
                        "{\"data\":{\"total_credits\":40,\"total_usage\":11.6}}", now);
            default:
                return ProviderUsageParsers.cline("{\"data\":{\"limits\":[{\"type\":"
                        + "\"five_hour\",\"percentUsed\":27,\"resetsAt\":\"" + iso(s + 8000)
                        + "\"},{\"type\":\"weekly\",\"percentUsed\":39,\"resetsAt\":\""
                        + iso(s + 330000) + "\"}]}}", now);
        }
    }
}
