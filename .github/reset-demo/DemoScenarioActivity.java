package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Temporary CI demo seeding: a Claude Code saved reset partway through the week. */
public final class DemoScenarioActivity extends Activity {
    private static final long H = TimeUnit.HOURS.toMillis(1);
    private static final long M = TimeUnit.MINUTES.toMillis(1);
    private static final long D = TimeUnit.DAYS.toMillis(1);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            long now = System.currentTimeMillis();
            long weekSeconds = TimeUnit.DAYS.toSeconds(7);
            long fiveSeconds = TimeUnit.HOURS.toSeconds(5);
            long fiveReset = now + 3 * H + 30 * M;
            long weekReset = now + 4 * D + 3 * H;
            UsageSnapshot claude = new UsageSnapshot("max", true, false,
                    new UsageWindow(37, fiveSeconds, 0L, fiveReset / 1000L),
                    new UsageWindow(10, weekSeconds, 0L, weekReset / 1000L),
                    null, Collections.emptyList(), null, -1, now);
            ProviderPreferences.saveSnapshot(this, UsageProvider.CLAUDE_CODE, claude);
            SecureTokenStore.save(this, UsageProvider.CLAUDE_CODE, new AuthTokens(
                    "demo", "demo", "", Long.MAX_VALUE, "demo", "demo@example.com"));

            UsageHistory five = UsageHistory.empty(UsageHistory.FIVE_HOUR);
            long fiveStart = fiveReset - 5 * H;
            int[] fiveUsed = {3, 8, 14, 20, 26, 31, 37};
            for (int i = 0; i < fiveUsed.length; i++) {
                long t = fiveStart + 10 * M
                        + i * ((now - fiveStart - 10 * M) / (fiveUsed.length - 1));
                five = five.append(new UsageWindow(fiveUsed[i], fiveSeconds, 0L,
                        fiveReset / 1000L), t);
            }
            ProviderPreferences.saveUsageHistory(this, UsageProvider.CLAUDE_CODE, five);

            // Climb to 99% over the first ~2.8 days, then a saved reset two hours ago drops
            // usage to zero without moving the weekly deadline, and 10% is used since.
            UsageHistory week = UsageHistory.empty(UsageHistory.WEEKLY);
            long weekStart = weekReset - 7 * D;
            long resetAt = now - 2 * H;
            int[] beforeReset = {1, 3, 6, 9, 12, 15, 18, 20, 22, 26, 40, 60, 85, 99};
            for (int i = 0; i < beforeReset.length; i++) {
                long t = weekStart + H
                        + i * ((resetAt - 20 * M - weekStart - H) / (beforeReset.length - 1));
                week = week.append(new UsageWindow(beforeReset[i], weekSeconds, 0L,
                        weekReset / 1000L), t);
            }
            int[] afterReset = {0, 3, 6, 8, 10};
            for (int i = 0; i < afterReset.length; i++) {
                long t = resetAt + i * (2 * H / (afterReset.length - 1));
                week = week.append(new UsageWindow(afterReset[i], weekSeconds, 0L,
                        weekReset / 1000L), Math.min(now, t));
            }
            ProviderPreferences.saveUsageHistory(this, UsageProvider.CLAUDE_CODE, week);

            ProviderPreferences.setEnabledProviders(this,
                    Arrays.asList(UsageProvider.CLAUDE_CODE));
            AppPreferences.setRefreshOnLaunch(this, false);
            AppPreferences.completeOnboarding(this);
            UsagePacePreferences.setEnabled(this, true);
            ProviderPreferences.setSelected(this, UsageProvider.CLAUDE_CODE);
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            finish();
        }
    }
}
