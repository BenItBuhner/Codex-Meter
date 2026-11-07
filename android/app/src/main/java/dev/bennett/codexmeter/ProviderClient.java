package dev.bennett.codexmeter;

import android.content.Context;
import java.util.Map;
import org.json.JSONObject;

/**
 * Sign-in and usage for one provider added through the Add account picker. Each subclass in
 * {@link ProviderClients} implements the sign-in styles its catalog entry lists
 * ({@link ProviderCatalog.SignIn}) plus {@link #fetch}. Network calls run off the main thread.
 */
public abstract class ProviderClient {
    /** Marks a stored credential that is a pasted API key: it never refreshes. */
    public static final String API_KEY_ACCOUNT = "api_key";

    public final UsageProvider provider;

    protected ProviderClient(UsageProvider provider) {
        this.provider = provider;
    }

    /** The provider turned the stored credential down; the user has to sign in again. */
    public static final class Unauthorized extends Exception {
        public Unauthorized(String message) {
            super(message);
        }
    }

    /** One sign-in attempt's secrets: PKCE values, the device code, and provider extras. */
    public static final class Session {
        public final String verifier = DeviceAuth.randomToken(48);
        public final String challenge = DeviceAuth.s256(verifier);
        public final String state = DeviceAuth.randomToken(24);
        public DeviceAuth.DeviceCode device;
        public final JSONObject extra = new JSONObject();
    }

    // ------------------------------------------------------------ device code

    public DeviceAuth.DeviceCode startDevice(Context context, Session session) throws Exception {
        throw new UnsupportedOperationException("No device sign-in for " + provider.displayName);
    }

    public DeviceAuth.Poll pollDevice(Context context, Session session) throws Exception {
        throw new UnsupportedOperationException("No device sign-in for " + provider.displayName);
    }

    /** Turns finished device tokens into stored tokens (some providers need one more call). */
    public AuthTokens finishDevice(Context context, Session session, DeviceAuth.Tokens tokens)
            throws Exception {
        return tokens.toAuthTokens(System.currentTimeMillis(), "");
    }

    // ------------------------------------------------------------ browser with local callback

    /** Callback path the local server waits on, e.g. "/callback". */
    public String loopbackPath() {
        return "/callback";
    }

    /** Fixed port when the provider only accepts one; 0 lets the system choose. */
    public int loopbackPort() {
        return 0;
    }

    public String loopbackAuthUrl(String redirectUri, Session session) throws Exception {
        throw new UnsupportedOperationException("No browser sign-in for " + provider.displayName);
    }

    public AuthTokens finishLoopback(Context context, Session session,
            Map<String, String> parameters, String redirectUri) throws Exception {
        throw new UnsupportedOperationException("No browser sign-in for " + provider.displayName);
    }

    // ------------------------------------------------------------ copy and paste

    /** Page that shows the user something to paste back. */
    public String pasteUrl(Session session) {
        throw new UnsupportedOperationException("No paste sign-in for " + provider.displayName);
    }

    /** One line under the paste field, e.g. "Paste the token the page shows." */
    public String pasteHint() {
        return "Paste what the page shows after you sign in.";
    }

    public AuthTokens finishPaste(Context context, Session session, String pasted)
            throws Exception {
        throw new UnsupportedOperationException("No paste sign-in for " + provider.displayName);
    }

    // ------------------------------------------------------------ API key

    /** Stores a pasted key after checking it against the usage route. */
    public AuthTokens fromApiKey(Context context, String key) throws Exception {
        String clean = key == null ? "" : key.trim();
        if (clean.isEmpty()) throw new Exception("Paste a key first.");
        AuthTokens tokens = new AuthTokens(clean, clean, "", 0L, API_KEY_ACCOUNT, "");
        fetch(context, tokens);
        return tokens;
    }

    // ------------------------------------------------------------ refresh and usage

    /** Whether {@link #refresh} can renew this credential. */
    public boolean canRefresh(AuthTokens tokens) {
        return false;
    }

    public AuthTokens refresh(Context context, AuthTokens tokens) throws Exception {
        return tokens;
    }

    /**
     * Fetches usage. Throws {@link Unauthorized} when the credential is rejected so the caller
     * can refresh once and retry.
     */
    public abstract UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception;

    // ------------------------------------------------------------ helpers

    protected static boolean isApiKey(AuthTokens tokens) {
        return API_KEY_ACCOUNT.equals(tokens.accountId);
    }

    /** Throws {@link Unauthorized} on 401/403, a readable error on other failures. */
    protected ProviderHttp.Response check(ProviderHttp.Response response, String what)
            throws Exception {
        if (response.status == 401) {
            throw new Unauthorized(provider.displayName + " rejected the saved sign-in.");
        }
        if (response.ok()) return response;
        switch (response.status) {
            case 403:
                throw new Unauthorized(provider.displayName + " denied " + what
                        + " for this account.");
            case 404:
                throw new Exception("The " + provider.displayName + " " + what
                        + " endpoint is unavailable or has changed.");
            case 429:
                throw new Exception(provider.displayName
                        + " is rate-limiting checks. Try again in a few minutes.");
            default:
                throw new Exception(OAuthClient.readError(response.body, provider.displayName
                        + " " + what + " failed (HTTP " + response.status + ")."));
        }
    }

    protected static long now() {
        return System.currentTimeMillis();
    }

    /** Fails a refresh when the server found nothing to show. */
    protected UsageSnapshot nonEmpty(UsageSnapshot snapshot) throws Exception {
        if (!snapshot.hasDisplayableData()) {
            throw new Exception(provider.displayName + " returned no recognizable usage data.");
        }
        return snapshot;
    }
}
