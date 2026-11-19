package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Rebuilding a page reuses the last parse of each saved value, and a new save is always read back:
 * the cache must never show an older snapshot than the one stored.
 */
@RunWith(RobolectricTestRunner.class)
public final class ParsedCacheTest {
    private final Context app = RuntimeEnvironment.getApplication();

    @Test
    public void unchangedSnapshotIsParsedOnce() {
        ProviderPreferences.saveSnapshot(app, UsageProvider.CURSOR,
                new UsageSnapshot("Pro", true, false, null, null, 1_000L));
        UsageSnapshot first = ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR);
        assertSame(first, ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR));
    }

    @Test
    public void savedSnapshotReplacesTheCachedOne() {
        ProviderPreferences.saveSnapshot(app, UsageProvider.CURSOR,
                new UsageSnapshot("Pro", true, false, null, null, 1_000L));
        UsageSnapshot before = ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR);
        ProviderPreferences.saveSnapshot(app, UsageProvider.CURSOR,
                new UsageSnapshot("Ultra", true, false, null, null, 2_000L));
        UsageSnapshot after = ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR);
        assertNotSame(before, after);
        assertEquals("Ultra", after.planType);
        assertEquals(2_000L, after.fetchedAtMillis);
    }

    @Test
    public void codexSnapshotFollowsSaves() {
        AppPreferences.saveSnapshot(app, new UsageSnapshot("plus", true, false, null, null, 5_000L));
        assertEquals(5_000L, AppPreferences.loadSnapshot(app).fetchedAtMillis);
        AppPreferences.saveSnapshot(app, new UsageSnapshot("pro", true, false, null, null, 6_000L));
        assertEquals("pro", AppPreferences.loadSnapshot(app).planType);
    }

    @Test
    public void failedParseIsNotCached() throws Exception {
        String[] calls = {""};
        ParsedCache.Parser<String> parser = raw -> {
            calls[0] += "x";
            if (raw.equals("bad")) throw new IllegalArgumentException(raw);
            return raw.toUpperCase(java.util.Locale.ROOT);
        };
        for (int i = 0; i < 2; i++) {
            try {
                ParsedCache.get("test/bad", "bad", parser);
            } catch (IllegalArgumentException expected) {
                // the caller's fallback runs
            }
        }
        assertEquals("xx", calls[0]);
        assertEquals("OK", ParsedCache.get("test/bad", "ok", parser));
        assertEquals("OK", ParsedCache.get("test/bad", new String("ok".toCharArray()), parser));
        assertEquals("xxx", calls[0]);
        assertNull(ParsedCache.get("test/null", "n", raw -> null));
    }
}
