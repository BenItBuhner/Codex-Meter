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
        // Optional: pick the design ("classic", "one_ui_9", "liquid_glass", "material_3_expressive";
        // the former "liquid_glass_ios_26" and "liquid_glass_ios_27" read as "liquid_glass") and theme
        // ("light", "dark", "system") before any page is built.
        String design = getIntent().getStringExtra("design");
        if (design != null && !design.isEmpty()) {
            AppPreferences.setDesign(this, design);
        }
        String theme = getIntent().getStringExtra("theme");
        if (theme != null && !theme.isEmpty()) {
            AppPreferences.setAppTheme(this, theme);
        }
        String settingsPage = getIntent().getStringExtra("open_settings_page");
        if (settingsPage != null && !settingsPage.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class)
                    .putExtra("settings_page", settingsPage)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        String screen = getIntent().getStringExtra("open_activity");
        if (screen != null && !screen.isEmpty()) {
            try {
                startActivity(new Intent(this, Class.forName("dev.bennett.codexmeter." + screen))
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ClassNotFoundException missing) {
                android.util.Log.w("UsagePaceDemo", "No screen " + screen);
            }
            finish();
            return;
        }
        if (getIntent().getBooleanExtra("open_usage_history", false)) {
            startActivity(new Intent(this, UsageHistoryActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        String widgetMetric = getIntent().getStringExtra("open_widget_metric");
        if (widgetMetric != null && !widgetMetric.isEmpty()) {
            int widgetId = getIntent().getIntExtra("appWidgetId", 42);
            WidgetOptions defaults = AppPreferences.loadDefaultWidgetOptions(this);
            AppPreferences.saveWidgetOptions(this, widgetId, new WidgetOptions(
                    defaults.layout, defaults.density, defaults.surfaceStyle,
                    defaults.graphicScale, defaults.theme, defaults.accent, defaults.opacity,
                    defaults.resetMode, defaults.displayMode, widgetMetric,
                    defaults.showTitle, defaults.showPlan, defaults.showUpdated,
                    defaults.showRefresh, defaults.showResetCredits, defaults.showResetAction)
                    .withPercentSymbol(defaults.showPercentSymbol));
            startActivity(new Intent(this, WidgetConfigActivity.class)
                    .putExtra("appWidgetId", widgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        if (getIntent().getBooleanExtra("resume_live_settings", false)) {
            startActivity(new Intent(this, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        String claudeScenario = getIntent().getStringExtra("claude_scenario");
        if (claudeScenario != null && !claudeScenario.isEmpty()) {
            try {
                seedClaude(claudeScenario);
                startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_PROVIDER, UsageProvider.CLAUDE_CODE.id)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                                | Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not seed Claude demo", exception);
            } finally {
                finish();
            }
            return;
        }
        try {
            // Optional overrides for demoing the non-configurable zero-balance auto-hide.
            String creditsBalance = getIntent().getStringExtra("credits_balance");
            boolean creditsNone = getIntent().getBooleanExtra("credits_none", false);
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
                    creditsNone
                            ? new UsageCredits(false, false, "")
                            : new UsageCredits(true, false,
                                    creditsBalance == null ? "2500" : creditsBalance),
                    3,
                    now);
            SecureTokenStore.save(this, new AuthTokens(
                    "debug-demo-access", "debug-demo-refresh", "", Long.MAX_VALUE,
                    "debug-demo-account", "demo@codexmeter.local"));
            AppPreferences.saveSnapshot(this, snapshot);
            seedHistory(now, fiveHourReset, weeklyReset);
            AppPreferences.saveResetCredits(this, new ResetCreditsSnapshot(3, Arrays.asList(
                    new RateLimitResetCredit("demo-soon", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(1), "Reset credit 1", ""),
                    new RateLimitResetCredit("demo-middle", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(3), "Reset credit 2", ""),
                    new RateLimitResetCredit("demo-later", "both", "available", now,
                            now + TimeUnit.DAYS.toMillis(7), "Reset credit 3", "")),
                    now));
            AppPreferences.setRefreshOnLaunch(this, false);
            AppPreferences.setDashboardVisibility(this, true, true, true, true, true, true, true);
            AppPreferences.completeOnboarding(this);
            if (getIntent().getBooleanExtra("demo_providers", false)) {
                seedProviders(now);
            }
            UsagePacePreferences.setEnabled(this, true);
            UsagePacePreferences.setSensitivity(this, UsagePace.BALANCED);
            // Opens on the Priority tab only when asked, so other scenes start on Codex as before.
            PriorityPrefs.setViewEnabled(this, true);
            PriorityPrefs.setTabSelected(this, getIntent().getBooleanExtra("open_priority", false));
            String newModels = getIntent().getStringExtra("demo_new_models");
            if (newModels != null && !newModels.isEmpty()) {
                seedNewModels(newModels, now);
            }
            if (getIntent().getBooleanExtra("demo_notifications_on", false)) {
                ResetAlertPreferences.save(this, ResetAlertPreferences.STYLE_NOTIFICATION,
                        ResetAlertPreferences.getMetric(this), ResetAlertPreferences.getThreshold(this));
            }
            boolean livePreview = getIntent().getBooleanExtra("start_live_preview", false);
            if (livePreview && !NowBarManager.startPreview(this)) {
                throw new IllegalStateException("Could not start live notification preview");
            }
            Intent next = new Intent(this, livePreview ? SettingsActivity.class : MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            String openProvider = getIntent().getStringExtra("open_provider");
            if (openProvider != null && !livePreview) {
                ProviderPreferences.setSelected(this, UsageProvider.fromId(openProvider));
                PriorityPrefs.setTabSelected(this, false);
                next.putExtra(MainActivity.EXTRA_PROVIDER, openProvider);
            }
            startActivity(next);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not seed usage pace demo", exception);
        } finally {
            finish();
        }
    }

    /**
     * Claude Code states for screenshots: "ultra" (no 5-hour limit, profile says Ultra),
     * "legacy_badge" (a badge saved as "Max 20x" before the rename), "sign_in_again" (Anthropic
     * rejected the refresh token) and "expired" (the saved weekly window reset since the last
     * refresh). Data is marked fresh so opening the tab does not hit the network.
     */
    private void seedClaude(String scenario) throws Exception {
        long now = System.currentTimeMillis();
        long day = TimeUnit.DAYS.toMillis(1);
        long weekSeconds = TimeUnit.DAYS.toSeconds(7);
        boolean expired = "expired".equals(scenario);
        long weeklyReset = expired ? now - TimeUnit.HOURS.toMillis(9) : now + 6 * day;
        int weeklyUsed = expired ? 100 : 4;
        String plan = "legacy_badge".equals(scenario) ? "Max 20x" : "Ultra";
        UsageSnapshot snapshot = new UsageSnapshot(plan, true, false, null,
                new UsageWindow(weeklyUsed, weekSeconds, 0L, weeklyReset / 1000L),
                Arrays.asList(new UsageLimit(ClaudeUsageParser.LIMIT_ID_PREFIX
                        + "weekly_scoped_fable", "Fable only", "", true, false,
                        new UsageWindow(2, weekSeconds, 0L, weeklyReset / 1000L), null)),
                null, -1, now);
        SecureTokenStore.save(this, UsageProvider.CLAUDE_CODE, new AuthTokens(
                "debug-demo-access", "debug-demo-refresh", "", Long.MAX_VALUE,
                "debug-demo-org", "demo@codexmeter.local"));
        ProviderPreferences.saveSnapshot(this, UsageProvider.CLAUDE_CODE, snapshot);
        // Fresh data plus a recent back-off keep the tab from fetching with the fake token.
        ProviderPreferences.setRateLimitedAt(this, UsageProvider.CLAUDE_CODE, now);
        if ("sign_in_again".equals(scenario)) {
            ProviderPreferences.setLastError(this, UsageProvider.CLAUDE_CODE,
                    ClaudeUsageApi.SIGN_IN_AGAIN_MESSAGE);
        }
        AppPreferences.setRefreshOnLaunch(this, false);
        AppPreferences.completeOnboarding(this);
    }

    /**
     * A catalog record for the "New models" card. "relevant" adds an OpenAI model (Codex is
     * signed in) next to a Google one; "irrelevant" adds only models no connected provider maps
     * to, so the card stays hidden. Both seed the baseline first, like a real first fetch.
     */
    private void seedNewModels(String mode, long now) {
        ModelDiscovery.State state = new ModelDiscovery.State();
        java.util.List<ModelDiscovery.Model> base = Arrays.asList(
                new ModelDiscovery.Model("openai/gpt-5.5", "GPT-5.5", "", 0L, ""),
                new ModelDiscovery.Model("google/gemini-3-pro", "Gemini 3 Pro", "", 0L, ""));
        ModelDiscovery.observe(state, ModelDiscovery.Source.OPENROUTER, ModelDiscoveryStore.ACCOUNT,
                base, true, now - TimeUnit.HOURS.toMillis(3));
        java.util.List<ModelDiscovery.Model> later = new java.util.ArrayList<>(base);
        if ("relevant".equals(mode)) {
            later.add(new ModelDiscovery.Model("openai/gpt-5.6-codex", "GPT-5.6-Codex", "", 0L, ""));
        }
        later.add(new ModelDiscovery.Model("google/gemini-3.5-flash", "Gemini 3.5 Flash", "", 0L, ""));
        later.add(new ModelDiscovery.Model("qwen/qwen3.8-coder", "Qwen3.8 Coder", "", 0L, ""));
        ModelDiscovery.observe(state, ModelDiscovery.Source.OPENROUTER, ModelDiscoveryStore.ACCOUNT,
                later, true, now - TimeUnit.HOURS.toMillis(2));
        ModelDiscovery.observe(state, ModelDiscovery.Source.OPENROUTER, ModelDiscoveryStore.ACCOUNT,
                later, true, now - TimeUnit.HOURS.toMillis(1));
        ModelDiscoveryStore.seedForDemo(this, state);
        PriorityPrefs.setViewEnabled(this, true);
    }

    /** Signs Claude Code and Cursor in with demo usage, so the dashboard shows provider tabs. */
    private void seedProviders(long now) throws Exception {
        long nowSeconds = now / 1000L;
        String claude = "{\"plan_type\":\"max\",\"allowed\":true,\"limit_reached\":false,"
                + "\"five_hour\":" + window(71, 18000, nowSeconds + 52 * 60) + ","
                + "\"weekly\":" + window(34, 604800, nowSeconds + 5 * 86400) + ","
                + "\"fetched_at\":" + (now - 600000) + "}";
        String cursor = "{\"plan_type\":\"pro\",\"allowed\":true,\"limit_reached\":false,"
                + "\"monthly\":" + window(getIntent().getBooleanExtra("demo_cursor_used_up", false)
                        ? 100 : 27, 2592000, nowSeconds + 11 * 86400) + ","
                + "\"fetched_at\":" + (now - 900000) + "}";
        seedProvider(UsageProvider.CLAUDE_CODE, claude);
        seedProvider(UsageProvider.CURSOR, cursor);
        ProviderPreferences.setEnabledProviders(this, Arrays.asList(UsageProvider.CODEX,
                UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR));
        ProviderPreferences.setSelected(this, UsageProvider.CODEX);
    }

    private void seedProvider(UsageProvider provider, String json) throws Exception {
        SecureTokenStore.save(this, provider, new AuthTokens(
                "debug-demo-access", "debug-demo-refresh", "", Long.MAX_VALUE,
                "debug-demo-account", "demo@codexmeter.local"));
        ProviderPreferences.saveSnapshot(this, provider,
                UsageSnapshot.fromJson(new org.json.JSONObject(json)));
    }

    private static String window(int used, long seconds, long resetAt) {
        return "{\"used_percent\":" + used + ",\"limit_window_seconds\":" + seconds
                + ",\"reset_after_seconds\":0,\"reset_at\":" + resetAt + "}";
    }

    private void seedHistory(long now, long fiveHourReset, long weeklyReset) {
        // Varied historical shapes exercise the per-window breakdown, typical-pace
        // comparison, and scrubbable overlays: quiet, steady, heavy, and bursty windows.
        UsageHistory five = UsageHistory.empty(UsageHistory.FIVE_HOUR);
        int[][] fiveShapes = {
                {3, 7, 12, 18, 22},
                {6, 14, 25, 33, 41},
                {12, 30, 52, 74, 96},
                {10, 26, 38, 61, 83},
        };
        for (int window = fiveShapes.length; window >= 1; window--) {
            long reset = fiveHourReset - TimeUnit.HOURS.toMillis(5L * window);
            int[] shape = fiveShapes[fiveShapes.length - window];
            for (int point = 0; point < shape.length; point++) {
                long observed = reset - TimeUnit.HOURS.toMillis(5)
                        + TimeUnit.MINUTES.toMillis(45L * (point + 1));
                five = five.append(new UsageWindow(shape[point],
                        TimeUnit.HOURS.toSeconds(5), 0L, reset / 1000L), observed);
            }
        }
        // Current 5-hour window climbs to the snapshot's 37% over the elapsed hour.
        int[] fiveUsed = {8, 15, 22, 30, 37};
        for (int point = 0; point < fiveUsed.length; point++) {
            five = five.append(new UsageWindow(fiveUsed[point],
                            TimeUnit.HOURS.toSeconds(5), 0L, fiveHourReset / 1000L),
                    now - TimeUnit.MINUTES.toMillis(48L - 12L * point));
        }
        AppPreferences.saveUsageHistory(this, five);

        UsageHistory weekly = UsageHistory.empty(UsageHistory.WEEKLY);
        int[][] weeklyShapes = {
                {5, 9, 14, 22, 30, 38},
                {8, 19, 33, 47, 58, 71},
                {15, 34, 52, 78, 95, 100},
                {11, 24, 39, 52, 66, 84},
        };
        for (int window = weeklyShapes.length; window >= 1; window--) {
            long reset = weeklyReset - TimeUnit.DAYS.toMillis(7L * window);
            int[] shape = weeklyShapes[weeklyShapes.length - window];
            for (int point = 0; point < shape.length; point++) {
                long observed = reset - TimeUnit.DAYS.toMillis(7)
                        + TimeUnit.HOURS.toMillis(24L * (point + 1));
                weekly = weekly.append(new UsageWindow(shape[point],
                        TimeUnit.DAYS.toSeconds(7), 0L, reset / 1000L), observed);
            }
        }
        // Current weekly window climbs to the snapshot's 61% over the elapsed hour.
        int[] weeklyUsed = {12, 28, 41, 53, 61};
        for (int point = 0; point < weeklyUsed.length; point++) {
            weekly = weekly.append(new UsageWindow(weeklyUsed[point],
                            TimeUnit.DAYS.toSeconds(7), 0L, weeklyReset / 1000L),
                    now - TimeUnit.MINUTES.toMillis(48L - 12L * point));
        }
        AppPreferences.saveUsageHistory(this, weekly);
    }
}
