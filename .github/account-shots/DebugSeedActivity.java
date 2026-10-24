package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Debug-only (temporary screenshot job): seeds sample accounts, including two Claude Code
 * accounts and two Codex accounts, then opens the dashboard on a provider.
 * Extras: design (oneui9|classic), tab (provider id), action (seed|finish_add|open).
 */
public class DebugSeedActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent().getStringExtra("action");
        String design = getIntent().getStringExtra("design");
        String tab = getIntent().getStringExtra("tab");
        String open = getIntent().getStringExtra("open");
        boolean singleClaude = "yes".equals(getIntent().getStringExtra("single_claude"));
        long now = System.currentTimeMillis();
        long s = now / 1000L;
        try {
            if ("finish_add".equals(action)) {
                // Stands in for a finished browser sign-in to a third Claude account.
                AppPreferences.setOAuthPending(this, false, "");
                seed(UsageProvider.CLAUDE_CODE, "sam@side-project.dev", "Pro", 12, 30, now, s);
                AccountVault.finishAdding(this, UsageProvider.CLAUDE_CODE);
            } else if (!"open".equals(action)) {
                AppPreferences.completeOnboarding(this);
                getSharedPreferences("codex_meter_settings_v1", 0).edit()
                        .putBoolean("one_ui_9_design", !"classic".equals(design)).commit();
                AppPreferences.setRefreshOnLaunch(this, false);
                getSharedPreferences("codex_meter_account_vault_v1", 0).edit().clear().commit();
                for (UsageProvider provider : UsageProvider.values()) {
                    SecureTokenStore.clear(this, provider);
                    ProviderPreferences.clearProvider(this, provider);
                }
                AppPreferences.clearSnapshot(this);
                List<UsageProvider> enabled = new ArrayList<>();
                enabled.add(UsageProvider.CODEX);
                enabled.add(UsageProvider.CLAUDE_CODE);
                enabled.add(UsageProvider.CURSOR);
                // Codex: a personal account saved, a work account active.
                seed(UsageProvider.CODEX, "sam.lee@gmail.com", "plus", 37, 61, now, s);
                AccountVault.prepareToAdd(this, UsageProvider.CODEX);
                seed(UsageProvider.CODEX, "sam@acme.dev", "pro", 8, 22, now, s);
                AccountVault.finishAdding(this, UsageProvider.CODEX);
                // Claude Code: a personal Pro account saved, a work Max account active.
                if (!singleClaude) {
                    seed(UsageProvider.CLAUDE_CODE, "sam.lee@gmail.com", "Pro", 81, 74, now, s);
                    AccountVault.prepareToAdd(this, UsageProvider.CLAUDE_CODE);
                }
                seed(UsageProvider.CLAUDE_CODE, "sam@acme.dev", "Max 5x", 42, 68, now, s);
                AccountVault.finishAdding(this, UsageProvider.CLAUDE_CODE);
                // Cursor: one account only.
                seed(UsageProvider.CURSOR, "sam@acme.dev", "Pro", 54, 0, now, s);
                List<UsageProvider> order = new ArrayList<>(enabled);
                for (UsageProvider provider : UsageProvider.values()) {
                    if (!order.contains(provider)) order.add(provider);
                }
                ProviderPreferences.setOrder(this, order);
                ProviderPreferences.setEnabledProviders(this, enabled);
            }
            if (tab != null) ProviderPreferences.setSelected(this, UsageProvider.fromId(tab));
        } catch (Exception e) {
            Log.e("DebugSeed", "seed failed", e);
        }
        Intent next;
        if ("settings".equals(open)) {
            next = new Intent(this, SettingsActivity.class);
        } else if ("providers".equals(open)) {
            next = new Intent(this, ProvidersActivity.class);
        } else if ("onboarding".equals(open)) {
            List<UsageProvider> picks = new ArrayList<>();
            picks.add(UsageProvider.CODEX);
            picks.add(UsageProvider.CLAUDE_CODE);
            AppPreferences.setOnboardingProviders(this, picks);
            getSharedPreferences("codex_meter_settings_v1", 0).edit()
                    .putBoolean("onboarding_complete", false).commit();
            AppPreferences.setOnboardingStep(this, OnboardingFlow.FIRST_SIGN_IN_STEP + 1);
            next = new Intent(this, OnboardingActivity.class);
        } else {
            next = new Intent(this, MainActivity.class);
        }
        startActivity(next.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private static String iso(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds).toString();
    }

    private void seed(UsageProvider provider, String email, String plan, int five, int week,
            long now, long s) throws Exception {
        SecureTokenStore.save(this, provider, new AuthTokens("demo", "demo", "",
                now + 30L * 86_400_000L, "", email));
        if (provider == UsageProvider.CODEX) {
            String codex = "{\"plan_type\":\"" + plan + "\",\"rate_limit\":{\"allowed\":true,"
                    + "\"limit_reached\":false,\"primary_window\":{\"used_percent\":" + five + ","
                    + "\"limit_window_seconds\":18000,\"reset_after_seconds\":5400,"
                    + "\"reset_at\":" + (s + 5400) + "},\"secondary_window\":"
                    + "{\"used_percent\":" + week + ",\"limit_window_seconds\":604800,"
                    + "\"reset_after_seconds\":200000,\"reset_at\":" + (s + 200000) + "}}}";
            AppPreferences.saveSnapshot(this, UsageParser.parse(codex, now));
            return;
        }
        UsageSnapshot snapshot = provider == UsageProvider.CLAUDE_CODE
                ? ClaudeUsageParser.parse("{\"five_hour\":{\"utilization\":" + five + ".0,"
                        + "\"resets_at\":\"" + iso(s + 7920) + "\"},\"seven_day\":{"
                        + "\"utilization\":" + week + ".0,\"resets_at\":\"" + iso(s + 266000)
                        + "\"}}", plan, now)
                : new UsageSnapshot(plan, true, false, null, null,
                        new UsageWindow(five, 2_592_000L, 900_000L, s + 900_000L),
                        Collections.emptyList(), null, -1, now);
        ProviderPreferences.saveSnapshot(this, provider, snapshot);
        ProviderPreferences.setEmail(this, provider, email);
    }
}
