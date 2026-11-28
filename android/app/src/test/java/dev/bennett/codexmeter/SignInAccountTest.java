package dev.bennett.codexmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    /**
     * Codex keeps its reading and history apart from the other providers, and clearing used to
     * skip it, so another ChatGPT account kept the old plan, email and history.
     */
    @Test
    public void anotherCodexAccountForgetsTheOldPlanEmailAndHistory() {
        seed(UsageProvider.CODEX);
        assertEquals("plus", ProviderPreferences.loadSnapshot(app, UsageProvider.CODEX).planType);
        assertNotNull(AppPreferences.loadResetCredits(app));

        SignInAccount.Change change = SecureTokenStore.forgetPreviousAccount(app,
                UsageProvider.CODEX, tokens("old-account", "old@example.com"),
                tokens("new-account", "new@example.com"));

        assertEquals(SignInAccount.Change.DIFFERENT, change);
        assertNull(ProviderPreferences.loadSnapshot(app, UsageProvider.CODEX));
        assertNull(AppPreferences.loadResetCredits(app));
        assertEquals("", ProviderPreferences.getEmail(app, UsageProvider.CODEX));
        assertTrue(ProviderPreferences.loadUsageHistory(app, UsageProvider.CODEX,
                UsageHistory.FIVE_HOUR).samples.isEmpty());
        assertTrue(ProviderPreferences.loadUsageHistory(app, UsageProvider.CODEX,
                UsageHistory.WEEKLY).samples.isEmpty());
    }

    @Test
    public void theSameCodexAccountKeepsEverything() {
        seed(UsageProvider.CODEX);
        SignInAccount.Change change = SecureTokenStore.forgetPreviousAccount(app,
                UsageProvider.CODEX, tokens("same", "old@example.com"),
                tokens("same", "old@example.com"));
        assertEquals(SignInAccount.Change.SAME, change);
        assertEquals("plus", ProviderPreferences.loadSnapshot(app, UsageProvider.CODEX).planType);
        assertEquals("old@example.com", ProviderPreferences.getEmail(app, UsageProvider.CODEX));
        assertFalse(ProviderPreferences.loadUsageHistory(app, UsageProvider.CODEX,
                UsageHistory.FIVE_HOUR).samples.isEmpty());
    }

    @Test
    public void anUnknownCodexAccountForgetsTheReadingButKeepsHistory() {
        seed(UsageProvider.CODEX);
        SignInAccount.Change change = SecureTokenStore.forgetPreviousAccount(app,
                UsageProvider.CODEX, tokens("", ""), tokens("", ""));
        assertEquals(SignInAccount.Change.UNKNOWN, change);
        assertNull(ProviderPreferences.loadSnapshot(app, UsageProvider.CODEX));
        assertNull(AppPreferences.loadResetCredits(app));
        assertEquals("", ProviderPreferences.getEmail(app, UsageProvider.CODEX));
        assertFalse(ProviderPreferences.loadUsageHistory(app, UsageProvider.CODEX,
                UsageHistory.FIVE_HOUR).samples.isEmpty());
    }

    /** The same path for another provider, end to end rather than one clear at a time. */
    @Test
    public void anotherCursorAccountThroughTheSignInPathForgetsHistory() {
        seed(UsageProvider.CURSOR);
        SecureTokenStore.forgetPreviousAccount(app, UsageProvider.CURSOR,
                tokens("", "old@example.com"), tokens("", "new@example.com"));
        assertNull(ProviderPreferences.loadSnapshot(app, UsageProvider.CURSOR));
        assertTrue(ProviderPreferences.loadUsageHistory(app, UsageProvider.CURSOR,
                UsageHistory.MONTHLY).samples.isEmpty());
    }

    private void seed(UsageProvider provider) {
        DemoMode.seed(app, provider, System.currentTimeMillis());
        ProviderPreferences.setEmail(app, provider, "old@example.com");
        // The demo seeds Cursor's monthly meter and Codex's 5-hour and weekly ones.
        String kind = provider == UsageProvider.CURSOR ? UsageHistory.MONTHLY
                : UsageHistory.FIVE_HOUR;
        assertFalse(ProviderPreferences.loadUsageHistory(app, provider, kind).samples.isEmpty());
    }
}
