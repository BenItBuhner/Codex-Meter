package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import dev.bennett.codexmeter.wear.WearProvidersState;
import dev.bennett.codexmeter.wear.WearSettingsState;
import dev.bennett.codexmeter.wear.WearSyncStatus;
import dev.bennett.codexmeter.wear.WearUsageState;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * What the watch keeps from the phone's Data Layer items, through the same entry points the
 * listener service uses: newer items replace older ones and never the reverse, the provider the
 * watch follows survives list updates, and signing out on the phone clears what the watch shows.
 */
@RunWith(RobolectricTestRunner.class)
public final class WearSyncTest {
    private static final long NOW = 1_900_000_000_000L;
    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    private static UsageSnapshot usage(int fiveHourUsed, long fetchedAt) {
        UsageWindow five = new UsageWindow(fiveHourUsed, TimeUnit.HOURS.toSeconds(5), 0L,
                TimeUnit.MILLISECONDS.toSeconds(fetchedAt) + 7_200L);
        UsageWindow week = new UsageWindow(30, TimeUnit.DAYS.toSeconds(7), 0L,
                TimeUnit.MILLISECONDS.toSeconds(fetchedAt) + 300_000L);
        return new UsageSnapshot("plus", true, false, five, week, fetchedAt);
    }

    private boolean pushUsage(UsageSnapshot snapshot, long updatedAt) throws JSONException {
        return WearPhoneSync.applyRemoteUsage(app,
                new WearUsageState(snapshot, updatedAt, WearSettingsState.SOURCE_PHONE).toJson().toString());
    }

    private boolean pushProviders(String selected, long updatedAt, WearProvidersState.Entry... entries)
            throws JSONException {
        return WearPhoneSync.applyRemoteProviders(app,
                new WearProvidersState(Arrays.asList(entries), selected, updatedAt).toJson().toString());
    }

    private static WearProvidersState.Entry entry(String id, String name, UsageSnapshot snapshot) {
        return new WearProvidersState.Entry(id, name, snapshot != null, snapshot, "");
    }

    @Test
    public void newerUsageReplacesTheShownReading() throws JSONException {
        assertTrue(pushUsage(usage(40, NOW), NOW));
        assertTrue(pushUsage(usage(55, NOW + 60_000L), NOW + 60_000L));
        assertEquals(55, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);
        assertEquals(NOW + 60_000L, WearPreferences.lastUsageAt(app));
    }

    /**
     * Regression: the Data Layer can hand the watch an older usage item after a newer one (a
     * reconnect replays the stored item, or a slow phone push lands late). The watch then showed
     * the older numbers as current, with a fresher-looking "updated" time.
     */
    @Test
    public void anOlderUsageItemArrivingLateIsIgnored() throws JSONException {
        assertTrue(pushUsage(usage(70, NOW), NOW));
        assertFalse(pushUsage(usage(20, NOW - 600_000L), NOW - 600_000L));
        assertEquals(70, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);
        assertEquals(NOW, WearPreferences.lastUsageAt(app));
    }

    @Test
    public void leavingDemoDataForAnEarlierLiveReadingStillApplies() throws JSONException {
        assertTrue(pushUsage(usage(42, NOW), NOW));
        // The live reading was fetched before the demo started, but the phone pushes it later.
        assertTrue(pushUsage(usage(64, NOW - 3_600_000L), NOW + 1_000L));
        assertEquals(64, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);
    }

    @Test
    public void signingOutOnThePhoneClearsCodexAndALaterSignInShowsAgain() throws JSONException {
        assertTrue(pushUsage(usage(40, NOW), NOW));
        assertTrue(WearPhoneSync.applyRemoteStatus(app,
                new WearSyncStatus(false, false, 0L, "", "2.9.0", NOW + 1_000L).toJson().toString()));
        assertNull(WearPreferences.loadSnapshot(app));
        assertFalse(WearPreferences.isSelectedSignedIn(app));
        // A usage item pushed before the sign-out must not bring the old account's numbers back.
        assertFalse(pushUsage(usage(40, NOW), NOW + 500L));
        assertNull(WearPreferences.loadSnapshot(app));

        assertTrue(WearPhoneSync.applyRemoteStatus(app,
                new WearSyncStatus(true, false, NOW + 5_000L, "", "2.9.0", NOW + 5_000L).toJson().toString()));
        assertTrue(pushUsage(usage(5, NOW + 6_000L), NOW + 6_000L));
        assertEquals(5, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);
        assertTrue(WearPreferences.isSelectedSignedIn(app));
    }

    @Test
    public void anOlderStatusItemIsIgnored() throws JSONException {
        assertTrue(WearPhoneSync.applyRemoteStatus(app,
                new WearSyncStatus(true, false, NOW, "", "2.9.0", NOW).toJson().toString()));
        assertFalse(WearPhoneSync.applyRemoteStatus(app,
                new WearSyncStatus(false, false, 0L, "", "2.9.0", NOW - 1L).toJson().toString()));
        assertTrue(WearPreferences.syncStatus(app).signedIn);
    }

    @Test
    public void theWatchFollowsItsOwnChoiceThenThePhonesTab() throws JSONException {
        assertTrue(pushUsage(usage(40, NOW), NOW));
        assertTrue(pushProviders("cursor", NOW,
                entry("codex", "Codex", usage(40, NOW)),
                entry("cursor", "Cursor", usage(80, NOW)),
                entry("copilot", "Copilot", usage(10, NOW))));
        // Before the watch picks one, it shows the phone's selected tab.
        assertEquals("cursor", WearPreferences.selectedProviderId(app));
        assertEquals(80, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);

        WearPreferences.setWatchProvider(app, "copilot");
        assertEquals("Copilot", WearPreferences.selectedProviderName(app));
        assertEquals(10, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);

        // A new list keeps the watch's choice while the phone still sends it...
        assertTrue(pushProviders("codex", NOW + 1_000L,
                entry("codex", "Codex", usage(41, NOW + 1_000L)),
                entry("copilot", "Copilot", usage(12, NOW + 1_000L))));
        assertEquals(12, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);
        // ...and falls back to the phone's tab once it doesn't.
        assertTrue(pushProviders("codex", NOW + 2_000L, entry("codex", "Codex", usage(42, NOW + 2_000L))));
        assertEquals("codex", WearPreferences.selectedProviderId(app));
        // Codex reads its own, more often pushed snapshot.
        assertEquals(40, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);
    }

    @Test
    public void anOlderProviderListIsIgnored() throws JSONException {
        assertTrue(pushProviders("cursor", NOW, entry("cursor", "Cursor", usage(80, NOW))));
        assertFalse(pushProviders("cursor", NOW - 1L, entry("cursor", "Cursor", usage(1, NOW - 1L))));
        assertEquals(80, WearPreferences.loadSnapshot(app).fiveHour.usedPercent);
    }

    @Test
    public void aSignedOutProviderShowsNoUsageAndItsError() throws JSONException {
        assertTrue(pushProviders("cursor", NOW,
                new WearProvidersState.Entry("cursor", "Cursor", false, usage(80, NOW), "Sign in again")));
        assertNull(WearPreferences.loadSnapshot(app));
        assertFalse(WearPreferences.isSelectedSignedIn(app));
        assertEquals("Sign in again", WearPreferences.selectedError(app));
        assertEquals(0L, WearPreferences.lastUsageAt(app));
    }

    @Test
    public void settingsChangedOnTheWatchAreNotOverwrittenByAnOlderPhoneCopy() throws JSONException {
        WearSettingsState fromPhone = new WearSettingsState(NowBarDisplayMode.AUTO, NowBarPercentMode.AUTO,
                false, NowBarAutoStart.METRIC_BOTH, 25, false, 15, NOW, WearSettingsState.SOURCE_PHONE);
        assertTrue(WearPhoneSync.applyRemoteSettings(app, fromPhone.toJson().toString()));
        assertEquals(15, WearPreferences.settingsState(app, 0L, WearSettingsState.SOURCE_WEAR).refreshMinutes);

        // The watch's own echo never applies as if it came from the phone.
        WearSettingsState echo = new WearSettingsState(NowBarDisplayMode.AUTO, NowBarPercentMode.AUTO,
                false, NowBarAutoStart.METRIC_BOTH, 25, false, 60, NOW + 5_000L, WearSettingsState.SOURCE_WEAR);
        assertFalse(WearPhoneSync.applyRemoteSettings(app, echo.toJson().toString()));
        WearSettingsState stale = new WearSettingsState(NowBarDisplayMode.AUTO, NowBarPercentMode.AUTO,
                false, NowBarAutoStart.METRIC_BOTH, 25, false, 120, NOW - 1L, WearSettingsState.SOURCE_PHONE);
        assertFalse(WearPhoneSync.applyRemoteSettings(app, stale.toJson().toString()));
        assertEquals(15, WearPreferences.settingsState(app, 0L, WearSettingsState.SOURCE_WEAR).refreshMinutes);
        assertNotNull(WearPreferences.settingsState(app, 0L, WearSettingsState.SOURCE_WEAR));
    }
}
