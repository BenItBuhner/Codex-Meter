package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Temporary CI demo seeding: Claude Code and Cursor like the bug report. */
public final class DemoScenarioActivity extends Activity {
    private static final long H = TimeUnit.HOURS.toMillis(1);
    private static final long M = TimeUnit.MINUTES.toMillis(1);
    private static final long D = TimeUnit.DAYS.toMillis(1);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            long now = System.currentTimeMillis();
            long fiveReset = now + H + 22 * M;
            long weekReset = now + 4 * D + 13 * H;
            UsageSnapshot claude = new UsageSnapshot("max", true, false,
                    new UsageWindow(52, TimeUnit.HOURS.toSeconds(5), 0L, fiveReset / 1000L),
                    new UsageWindow(50, TimeUnit.DAYS.toSeconds(7), 0L, weekReset / 1000L),
                    null, Collections.emptyList(), null, -1, now);
            ProviderPreferences.saveSnapshot(this, UsageProvider.CLAUDE_CODE, claude);
            SecureTokenStore.save(this, UsageProvider.CLAUDE_CODE, new AuthTokens(
                    "demo", "demo", "", Long.MAX_VALUE, "demo", "demo@example.com"));

            UsageHistory five = UsageHistory.empty(UsageHistory.FIVE_HOUR);
            long fiveStart = fiveReset - 5 * H;
            int[] fiveUsed = {2, 4, 6, 8, 10, 12, 16, 22, 28, 34, 40, 46, 52};
            for (int i = 0; i < fiveUsed.length; i++) {
                long t = fiveStart + 10 * M + i * ((now - fiveStart - 10 * M) / (fiveUsed.length - 1));
                five = five.append(new UsageWindow(fiveUsed[i], TimeUnit.HOURS.toSeconds(5), 0L,
                        fiveReset / 1000L), t);
            }
            ProviderPreferences.saveUsageHistory(this, UsageProvider.CLAUDE_CODE, five);

            UsageHistory week = UsageHistory.empty(UsageHistory.WEEKLY);
            long weekStart = weekReset - 7 * D;
            int[] weekUsed = {1, 3, 6, 9, 12, 15, 19, 24, 30, 36, 42, 46, 50};
            for (int i = 0; i < weekUsed.length; i++) {
                long t = weekStart + H + i * ((now - weekStart - H) / (weekUsed.length - 1));
                week = week.append(new UsageWindow(weekUsed[i], TimeUnit.DAYS.toSeconds(7), 0L,
                        weekReset / 1000L), t);
            }
            ProviderPreferences.saveUsageHistory(this, UsageProvider.CLAUDE_CODE, week);

            long cursorReset = now + 11 * H + 31 * M;
            UsageSnapshot cursor = new UsageSnapshot("pro", true, true, null,
                    new UsageWindow(100, TimeUnit.DAYS.toSeconds(7), 0L, cursorReset / 1000L),
                    null, Collections.emptyList(), null, -1, now);
            ProviderPreferences.saveSnapshot(this, UsageProvider.CURSOR, cursor);
            SecureTokenStore.save(this, UsageProvider.CURSOR, new AuthTokens(
                    "demo", "demo", "", Long.MAX_VALUE, "demo", "demo@example.com"));
            UsageHistory cweek = UsageHistory.empty(UsageHistory.WEEKLY);
            long cStart = cursorReset - 7 * D;
            int[] cUsed = {2, 3, 4, 5, 6, 30, 45, 52, 60, 72, 85, 96, 100};
            for (int i = 0; i < cUsed.length; i++) {
                long t = cStart + D + i * ((now - cStart - D) / (cUsed.length - 1));
                cweek = cweek.append(new UsageWindow(cUsed[i], TimeUnit.DAYS.toSeconds(7), 0L,
                        cursorReset / 1000L), t);
            }
            ProviderPreferences.saveUsageHistory(this, UsageProvider.CURSOR, cweek);

            ProviderPreferences.setEnabledProviders(this,
                    Arrays.asList(UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR));
            AppPreferences.setRefreshOnLaunch(this, false);
            AppPreferences.completeOnboarding(this);
            UsagePacePreferences.setEnabled(this, true);
            String tab = getIntent().getStringExtra("tab");
            ProviderPreferences.setSelected(this,
                    "cursor".equals(tab) ? UsageProvider.CURSOR : UsageProvider.CLAUDE_CODE);
            if (getIntent().getBooleanExtra("notify", false)) {
                NowBarManager.stop(this, false);
                NowBarManager.startForApp(this, UsageProvider.CLAUDE_CODE, "com.anthropic.claude");
            }
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            finish();
        }
    }
}
