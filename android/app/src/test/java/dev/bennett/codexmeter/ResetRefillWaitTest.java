package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Issue #214: how long "Use when running low" waits for a limit to refill on its own is a
 * setting, per provider and separately for the 5-hour and weekly limits, and a held-back use is
 * remembered until that limit refills.
 */
@RunWith(RobolectricTestRunner.class)
public final class ResetRefillWaitTest {
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    @Test
    public void waitsDefaultToFifteenMinutes() {
        for (UsageProvider provider : new UsageProvider[] {UsageProvider.CODEX,
                UsageProvider.CLAUDE_CODE}) {
            assertEquals(15, ResetAlertPreferences.lowUsageWaitMinutes(app, provider, false));
            assertEquals(15, ResetAlertPreferences.lowUsageWaitMinutes(app, provider, true));
        }
    }

    @Test
    public void waitsAreSeparatePerProviderAndLimit() {
        ResetAlertPreferences.setLowUsageWaitMinutes(app, UsageProvider.CODEX, true, 1440);
        ResetAlertPreferences.setLowUsageWaitMinutes(app, UsageProvider.CLAUDE_CODE, false, 120);
        assertEquals(1440,
                ResetAlertPreferences.lowUsageWaitMinutes(app, UsageProvider.CODEX, true));
        assertEquals(15,
                ResetAlertPreferences.lowUsageWaitMinutes(app, UsageProvider.CODEX, false));
        assertEquals(120,
                ResetAlertPreferences.lowUsageWaitMinutes(app, UsageProvider.CLAUDE_CODE, false));
        assertEquals(15,
                ResetAlertPreferences.lowUsageWaitMinutes(app, UsageProvider.CLAUDE_CODE, true));
    }

    @Test
    public void aWaitOffTheMenuIsStoredAsTheDefault() {
        // A day is offered for the weekly limit only; the 5-hour limit refills sooner than that.
        ResetAlertPreferences.setLowUsageWaitMinutes(app, UsageProvider.CODEX, false, 1440);
        assertEquals(15,
                ResetAlertPreferences.lowUsageWaitMinutes(app, UsageProvider.CODEX, false));
        ResetAlertPreferences.setLowUsageWaitMinutes(app, UsageProvider.CODEX, false, 0);
        assertEquals(0,
                ResetAlertPreferences.lowUsageWaitMinutes(app, UsageProvider.CODEX, false));
    }

    @Test
    public void aHeldBackUseIsShownUntilItsLimitRefills() {
        long now = 4_000_000_000_000L;
        assertNull(ResetAlertPreferences.refillWait(app, UsageProvider.CODEX, now));
        ResetAlertPreferences.recordRefillWait(app, UsageProvider.CODEX, "weekly",
                now + 20 * HOUR, now);
        ResetAlertPreferences.RefillWait wait =
                ResetAlertPreferences.refillWait(app, UsageProvider.CODEX, now + HOUR);
        assertNotNull(wait);
        assertEquals("weekly", wait.windowLabel);
        assertEquals(now + 20 * HOUR, wait.resetAtMillis);
        assertNull("only the provider that held back shows it",
                ResetAlertPreferences.refillWait(app, UsageProvider.CLAUDE_CODE, now + HOUR));
        assertNull("gone once the limit has refilled",
                ResetAlertPreferences.refillWait(app, UsageProvider.CODEX, now + 20 * HOUR));
        String summary = SettingsActivity.SettingsFragment.refillWaitSummary(app, wait, now);
        assertTrue(summary, summary.startsWith("Your weekly limit refills on its own "));
        assertTrue(summary, summary.endsWith(", so no reset was used."));
    }

    @Test
    public void signingOutOfCodexForgetsItsHeldBackUse() {
        long now = System.currentTimeMillis();
        ResetAlertPreferences.recordRefillWait(app, UsageProvider.CODEX, "5-hour",
                now + HOUR, now);
        ResetCreditExpiryApplyReceiver.clearState(app);
        assertNull(ResetAlertPreferences.refillWait(app, UsageProvider.CODEX, now));
    }

    @Test
    public void waitsTravelWithExportedSettings() throws Exception {
        ResetAlertPreferences.setLowUsageWaitMinutes(app, UsageProvider.CODEX, true, 4320);
        ResetAlertPreferences.setLowUsageWaitMinutes(app, UsageProvider.CLAUDE_CODE, false, 60);
        SettingsTransfer.Document document =
                SettingsTransferStore.collect(app, false, true, false, false);
        ResetAlertPreferences.setLowUsageWaitMinutes(app, UsageProvider.CODEX, true, 15);
        ResetAlertPreferences.setLowUsageWaitMinutes(app, UsageProvider.CLAUDE_CODE, false, 15);
        SettingsTransferStore.apply(app, document, false, true, false, false);
        assertEquals(4320,
                ResetAlertPreferences.lowUsageWaitMinutes(app, UsageProvider.CODEX, true));
        assertEquals(60,
                ResetAlertPreferences.lowUsageWaitMinutes(app, UsageProvider.CLAUDE_CODE, false));
    }
}
