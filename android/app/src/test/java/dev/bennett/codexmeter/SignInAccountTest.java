package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Signing in again over a saved sign-in. A different account must never show the old one's
 * plan, email or history: the refresh after sign-in only fills the email when it is empty and
 * keeps the old plan when the new reply has none, so both used to carry over.
 */
@RunWith(RobolectricTestRunner.class)
public final class SignInAccountTest {
    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    private static AuthTokens tokens(String accountId, String email) {
        return new AuthTokens("access", "refresh", "", 0L, accountId, email);
    }

    @Test
    public void accountIdDecidesFirstThenEmail() {
        assertEquals(SignInAccount.Change.SAME, SignInAccount.compare(null, tokens("a", "")));
        assertEquals(SignInAccount.Change.SAME,
                SignInAccount.compare(tokens("a", "x@example.com"), tokens("a", "y@example.com")));
        assertEquals(SignInAccount.Change.DIFFERENT,
                SignInAccount.compare(tokens("a", "x@example.com"), tokens("b", "x@example.com")));
        assertEquals(SignInAccount.Change.SAME,
                SignInAccount.compare(tokens("", "X@Example.com "), tokens("", "x@example.com")));
        assertEquals(SignInAccount.Change.DIFFERENT,
                SignInAccount.compare(tokens("", "x@example.com"), tokens("", "y@example.com")));
        assertEquals("an API key says nothing about its account", SignInAccount.Change.UNKNOWN,
                SignInAccount.compare(tokens("", ""), tokens("", "")));
    }

    @Test
    public void anotherAccountForgetsTheOldNumbersEmailAndHistory() {
        seed(UsageProvider.CURSOR);
        ProviderPreferences.clearProvider(app, UsageProvider.CURSOR);
        assertNull(ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR));
        assertEquals("", ProviderPreferences.getEmail(app, UsageProvider.CURSOR));
        assertTrue(ProviderPreferences.loadUsageHistory(app, UsageProvider.CURSOR,
                UsageHistory.MONTHLY).samples.isEmpty());
    }

    @Test
    public void anUnknownAccountForgetsTheReadingButKeepsHistory() {
        seed(UsageProvider.CURSOR);
        ProviderPreferences.clearReading(app, UsageProvider.CURSOR);
        assertNull(ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR));
        assertEquals("", ProviderPreferences.getEmail(app, UsageProvider.CURSOR));
        assertFalse(ProviderPreferences.loadUsageHistory(app, UsageProvider.CURSOR,
                UsageHistory.MONTHLY).samples.isEmpty());
    }

    private void seed(UsageProvider provider) {
        DemoMode.seed(app, provider, System.currentTimeMillis());
        ProviderPreferences.setEmail(app, provider, "old@example.com");
        assertFalse(ProviderPreferences.loadUsageHistory(app, provider,
                UsageHistory.MONTHLY).samples.isEmpty());
    }
}
