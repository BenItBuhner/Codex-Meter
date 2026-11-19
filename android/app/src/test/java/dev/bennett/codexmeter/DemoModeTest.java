package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Demo mode's sample usage, and that a provider showing it never reaches the network and loses
 * the sample data when its account is cleared. The Keystore-backed token save that enter() does
 * isn't available on the JVM, so these tests mark the providers directly.
 */
@RunWith(RobolectricTestRunner.class)
public final class DemoModeTest {
    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    private void markDemo(String ids) {
        app.getSharedPreferences("codex_meter_demo", Context.MODE_PRIVATE).edit()
                .putString("providers", ids).commit();
    }

    @Test
    public void sampleUsageCoversEachProviderWithFutureResets() {
        long now = System.currentTimeMillis();
        for (UsageProvider provider : DemoMode.PROVIDERS) DemoMode.seed(app, provider, now);

        UsageSnapshot codex = ProviderPreferences.loadSnapshot(app, UsageProvider.CODEX);
        assertEquals(37, codex.fiveHour.usedPercent);
        assertEquals(61, codex.weekly.usedPercent);
        assertTrue(codex.fiveHour.resetAtMillis() > now);
        assertEquals(2, AppPreferences.loadResetCredits(app).availableCount);
        assertFalse(AppPreferences.loadUsageHistory(app, UsageHistory.FIVE_HOUR).samples.isEmpty());

        UsageSnapshot claude = ProviderPreferences.loadSnapshot(app, UsageProvider.CLAUDE_CODE);
        assertEquals(71, claude.fiveHour.usedPercent);
        assertEquals(34, claude.weekly.usedPercent);
        assertFalse(ProviderPreferences.loadUsageHistory(app, UsageProvider.CLAUDE_CODE,
                UsageHistory.WEEKLY).samples.isEmpty());

        UsageSnapshot cursor = ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR);
        assertNull(cursor.fiveHour);
        assertEquals(27, cursor.monthly.usedPercent);
        assertTrue(cursor.monthly.resetAtMillis() > now);
    }

    @Test
    public void demoRefreshesNeverReachTheNetwork() throws Exception {
        markDemo("codex,claude_code,cursor");
        long before = System.currentTimeMillis();
        // With no tokens and no network on the JVM, any real request would throw here.
        assertTrue(UsageApi.refreshAndCache(app).fetchedAtMillis >= before);
        assertEquals(71, ClaudeUsageApi.refreshAndCache(app).fiveHour.usedPercent);
        assertEquals(27, CursorUsageApi.refreshAndCache(app).monthly.usedPercent);
        assertNotNull(ResetCreditApi.refreshAndCache(app));
        try {
            ResetCreditApi.consumeBestAvailable(app);
            fail("a demo reset credit must not be spent");
        } catch (DemoMode.DemoDataException expected) {
            assertEquals(DemoMode.NO_NETWORK_MESSAGE, expected.getMessage());
        }
    }

    @Test
    public void clearingTheAccountRemovesTheSampleUsage() {
        long now = System.currentTimeMillis();
        DemoMode.seed(app, UsageProvider.CURSOR, now);
        DemoMode.seed(app, UsageProvider.CODEX, now);
        markDemo("codex,cursor");
        assertTrue(DemoMode.isOn(app, UsageProvider.CURSOR));

        SecureTokenStore.clear(app, UsageProvider.CURSOR);
        assertFalse(DemoMode.isOn(app, UsageProvider.CURSOR));
        assertNull(ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR));
        assertTrue(ProviderPreferences.loadUsageHistory(app, UsageProvider.CURSOR,
                UsageHistory.MONTHLY).samples.isEmpty());
        assertTrue("the other demo provider stays", DemoMode.isOn(app, UsageProvider.CODEX));

        DemoMode.clear(app, UsageProvider.CODEX);
        assertFalse(DemoMode.isAnyOn(app));
        assertNull(ProviderPreferences.loadSnapshot(app, UsageProvider.CODEX));
        assertTrue(AppPreferences.loadUsageHistory(app, UsageHistory.FIVE_HOUR).samples.isEmpty());
    }

    @Test
    public void onlyTheDemoTokensAreMarked() {
        assertTrue(DemoMode.isDemoTokens(new AuthTokens(DemoMode.ACCESS_TOKEN,
                DemoMode.ACCESS_TOKEN, "", Long.MAX_VALUE, "demo", DemoMode.EMAIL)));
        assertFalse(DemoMode.isDemoTokens(new AuthTokens("real", "refresh", "", 0L, "a", "e")));
        assertFalse(DemoMode.isDemoTokens(null));
    }
}
