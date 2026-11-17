package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The consolidated settings: one low-allowance level drives both alerts and the live
 * notification, reset auto-use keeps one rule set per provider, and haptics have one setting.
 */
@RunWith(RobolectricTestRunner.class)
public final class SettingsConsolidationTest {
    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    @Test
    public void liveNotificationUsesTheNotificationsLowAllowanceLevel() {
        ResetAlertPreferences.save(app, ResetAlertPreferences.STYLE_NOTIFICATION,
                ResetAlertPreferences.METRIC_WEEKLY, 10);
        assertEquals(ResetAlertPreferences.METRIC_WEEKLY, NowBarPreferences.getMetric(app));
        assertEquals(10, NowBarPreferences.getThreshold(app));
    }

    @Test
    public void aWatchChangingTheLevelChangesTheOneSettingAndKeepsTheStyle() {
        ResetAlertPreferences.save(app, ResetAlertPreferences.STYLE_ALARM,
                ResetAlertPreferences.METRIC_BOTH, 25);
        NowBarPreferences.save(app, true, ResetAlertPreferences.METRIC_FIVE_HOUR, 50);
        assertTrue(NowBarPreferences.isAutoStartEnabled(app));
        assertEquals(ResetAlertPreferences.METRIC_FIVE_HOUR, ResetAlertPreferences.getMetric(app));
        assertEquals(50, ResetAlertPreferences.getThreshold(app));
        assertEquals(ResetAlertPreferences.STYLE_ALARM, ResetAlertPreferences.getStyle(app));
    }

    @Test
    public void resetAutoUseRulesStayPerProvider() {
        ResetAlertPreferences.setLowUsageAutoUseEnabled(app, UsageProvider.CLAUDE_CODE, true);
        ResetAlertPreferences.setLowUsageAutoUseThreshold(app, UsageProvider.CLAUDE_CODE, 5);
        ResetAlertPreferences.setExpiryAutoUseEnabled(app, UsageProvider.CODEX, false);
        assertTrue(ResetAlertPreferences.claudeSavedResetLowUsageEnabled(app));
        assertEquals(5, ResetAlertPreferences.claudeSavedResetThreshold(app));
        assertFalse(ResetAlertPreferences.resetCreditLowUsageAutoUseEnabled(app));
        assertFalse(ResetAlertPreferences.expiryAutoUseEnabled(app, UsageProvider.CODEX));
        assertTrue(ResetAlertPreferences.expiryAutoUseEnabled(app, UsageProvider.CLAUDE_CODE));
    }

    @Test
    public void onlyCodexAndClaudeCodeBankResets() {
        assertTrue(ResetAlertPreferences.hasResets(UsageProvider.CODEX));
        assertTrue(ResetAlertPreferences.hasResets(UsageProvider.CLAUDE_CODE));
        assertFalse(ResetAlertPreferences.hasResets(UsageProvider.CURSOR));
        assertNull(ProviderPreferences.loadResetCredits(app, UsageProvider.CURSOR,
                System.currentTimeMillis()));
    }

    @Test
    public void hapticsDefaultToLightAndAcceptOnlyKnownLevels() {
        assertEquals(AppHaptics.LIGHT, AppPreferences.getHaptics(app));
        AppPreferences.setHaptics(app, AppHaptics.OFF);
        assertEquals(AppHaptics.OFF, AppPreferences.getHaptics(app));
        AppPreferences.setHaptics(app, "loud");
        assertEquals(AppHaptics.LIGHT, AppPreferences.getHaptics(app));
    }

    @Test
    public void oldPageLinksOpenPrivacyAndData() {
        assertEquals("privacy", SettingsActivity.normalizePage("transfer"));
        assertEquals("privacy", SettingsActivity.normalizePage("diagnostics"));
        assertEquals("Privacy & data", SettingsActivity.pageTitle("privacy"));
    }

    @Test
    public void expressiveChoiceRowsStateTheValueOnce() {
        assertEquals("Automatic · Live notification", ExpressiveSettings.choiceSummary(
                "Automatic (recommended)", "Automatic · Live notification"));
        assertEquals("Balanced", ExpressiveSettings.choiceSummary("Balanced", null));
        assertEquals("Wide screens\nBack moves right", ExpressiveSettings.choiceSummary(
                "Wide screens", "Back moves right"));
    }
}
