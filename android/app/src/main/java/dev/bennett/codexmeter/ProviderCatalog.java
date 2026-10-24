package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * What the Add account picker and the sign-in screen need to know about each provider: how it
 * signs in, what it meters, and whether its usage route is confirmed. Pure Java so the
 * self-tests can check it.
 */
public final class ProviderCatalog {
    /** How a provider's account gets connected on a phone. */
    public enum SignIn {
        /** Codex, Claude Code and Cursor: their own browser flows in {@link OAuthService}. */
        BROWSER,
        /** Browser sign-in that returns to a local port, run by {@link OAuthService}. */
        LOOPBACK,
        /** The app shows a code, the user approves it on the provider's site, the app polls. */
        DEVICE_CODE,
        /** The provider's site shows a token or code that the user pastes back. */
        CODE_PASTE,
        /** The user creates a key on the provider's site and pastes it. */
        API_KEY
    }

    /** One provider's catalog entry. */
    public static final class Entry {
        public final UsageProvider provider;
        public final SignIn signIn;
        /** What the dashboard shows, e.g. "5-hour and weekly limits". */
        public final String meters;
        /** How sign-in works, e.g. "GitHub device sign-in". */
        public final String signInSummary;
        /** Where the user creates a key; also offered as an alternative for device flows. */
        public final String keyUrl;
        /** Whether the provider also accepts a pasted API key on the sign-in screen. */
        public final boolean acceptsApiKey;
        /**
         * True when the sign-in flow is known but it is not yet confirmed that its token is
         * accepted by the usage route. The sign-in screen says so.
         */
        public final boolean inferred;

        Entry(UsageProvider provider, SignIn signIn, String meters, String signInSummary,
                String keyUrl, boolean acceptsApiKey, boolean inferred) {
            this.provider = provider;
            this.signIn = signIn;
            this.meters = meters;
            this.signInSummary = signInSummary;
            this.keyUrl = keyUrl == null ? "" : keyUrl;
            this.acceptsApiKey = acceptsApiKey;
            this.inferred = inferred;
        }

        /** One line for picker rows: "5-hour and weekly limits · GitHub sign-in". */
        public String pickerSummary() {
            return meters + " · " + signInSummary;
        }

        /** Whether this provider connects through {@code SignInActivity}'s own flows. */
        public boolean usesSignInScreen() {
            return signIn != SignIn.BROWSER;
        }
    }

    /** Recommended order: the picker lists providers this way. */
    private static final Entry[] ENTRIES = {
            new Entry(UsageProvider.CODEX, SignIn.BROWSER,
                    "5-hour and weekly limits, reset credits", "ChatGPT sign-in", "", false, false),
            new Entry(UsageProvider.CLAUDE_CODE, SignIn.BROWSER,
                    "5-hour and weekly limits", "Claude sign-in", "", false, false),
            new Entry(UsageProvider.CURSOR, SignIn.BROWSER,
                    "Cursor usage, or the shared X Subscription pool", "Cursor sign-in", "",
                    false, false),
            new Entry(UsageProvider.COPILOT, SignIn.DEVICE_CODE,
                    "Monthly premium requests and chat", "GitHub sign-in", "", false, false),
            new Entry(UsageProvider.KIMI, SignIn.DEVICE_CODE,
                    "5-hour and weekly limits", "Kimi sign-in",
                    "https://www.kimi.com/code/console", true, false),
            new Entry(UsageProvider.FACTORY, SignIn.DEVICE_CODE,
                    "5-hour, weekly and monthly limits", "Factory sign-in", "", false, false),
            new Entry(UsageProvider.GROK, SignIn.DEVICE_CODE,
                    "Grok credits, or the shared X Subscription pool", "xAI sign-in", "", false,
                    true),
            new Entry(UsageProvider.ANTIGRAVITY, SignIn.LOOPBACK,
                    "Gemini and Claude model limits, 5-hour and weekly", "Google sign-in", "",
                    false, false),
            new Entry(UsageProvider.DEVIN, SignIn.CODE_PASTE,
                    "Daily and weekly quota", "Windsurf token", "", false, true),
            new Entry(UsageProvider.WINDSURF, SignIn.CODE_PASTE,
                    "Daily and weekly quota", "Windsurf token", "", false, true),
            new Entry(UsageProvider.AUGMENT, SignIn.CODE_PASTE,
                    "Monthly credits", "Augment sign-in", "", false, false),
            new Entry(UsageProvider.CLINE, SignIn.DEVICE_CODE,
                    "5-hour, weekly and monthly limits", "Cline sign-in", "", false, true),
            new Entry(UsageProvider.OPENCODE_GO, SignIn.DEVICE_CODE,
                    "5-hour, weekly and monthly limits", "OpenCode sign-in",
                    "https://opencode.ai/auth", true, true),
            new Entry(UsageProvider.MINIMAX, SignIn.DEVICE_CODE,
                    "Coding plan prompts per window", "MiniMax sign-in",
                    "https://platform.minimax.io/user-center/basic-information/interface-key",
                    true, true),
            new Entry(UsageProvider.ZAI, SignIn.API_KEY,
                    "5-hour and weekly limits", "API key",
                    "https://z.ai/manage-apikey/apikey-list", true, false),
            new Entry(UsageProvider.KIRO, SignIn.DEVICE_CODE,
                    "Monthly credits", "AWS Builder ID", "", false, true),
            new Entry(UsageProvider.AMP, SignIn.API_KEY,
                    "Free daily usage and credits", "Access token",
                    "https://ampcode.com/settings", true, true),
            new Entry(UsageProvider.OPENROUTER, SignIn.LOOPBACK,
                    "Key limit and credit balance", "OpenRouter sign-in",
                    "https://openrouter.ai/settings/keys", true, false),
            new Entry(UsageProvider.KILO, SignIn.DEVICE_CODE,
                    "Credit balance", "Kilo sign-in", "https://app.kilo.ai/profile", true,
                    false),
            new Entry(UsageProvider.CODEBUFF, SignIn.DEVICE_CODE,
                    "Subscription credits", "Codebuff sign-in", "", false, true),
            new Entry(UsageProvider.SYNTHETIC, SignIn.API_KEY,
                    "5-hour and weekly limits", "API key", "https://synthetic.new/billing",
                    true, false),
            new Entry(UsageProvider.CHUTES, SignIn.API_KEY,
                    "Subscription quota", "API key", "https://chutes.ai/app/api", true, true),
            new Entry(UsageProvider.V0, SignIn.API_KEY,
                    "Rate limits", "API key", "https://v0.app/chat/settings/keys", true, true),
            new Entry(UsageProvider.WARP, SignIn.API_KEY,
                    "AI request limit", "API key", "https://app.warp.dev/settings/api-keys",
                    true, true),
    };

    private ProviderCatalog() {
    }

    public static Entry get(UsageProvider provider) {
        for (Entry entry : ENTRIES) {
            if (entry.provider == provider) return entry;
        }
        // Every provider has an entry; the self-test checks it.
        return new Entry(provider, SignIn.BROWSER, "Usage limits",
                provider.accountName + " sign-in", "", false, false);
    }

    /** Every provider in recommended order. */
    public static List<UsageProvider> recommendedOrder() {
        List<UsageProvider> order = new ArrayList<>();
        for (Entry entry : ENTRIES) order.add(entry.provider);
        for (UsageProvider provider : UsageProvider.values()) {
            if (!order.contains(provider)) order.add(provider);
        }
        return order;
    }

    /**
     * Providers the Add account picker offers: everything not already connected, in
     * recommended order, narrowed by {@code query} (matched against name, company and sign-in).
     */
    public static List<UsageProvider> pickerProviders(Collection<UsageProvider> connected,
            String query) {
        List<UsageProvider> result = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (UsageProvider provider : recommendedOrder()) {
            if (connected != null && connected.contains(provider)) continue;
            if (!needle.isEmpty() && !matches(provider, needle)) continue;
            result.add(provider);
        }
        return result;
    }

    static boolean matches(UsageProvider provider, String needle) {
        Entry entry = get(provider);
        String extra = "";
        if (provider == UsageProvider.CURSOR || provider == UsageProvider.GROK) {
            extra = " x subscription the x subscription shared pool";
        }
        if (provider == UsageProvider.GROK) {
            extra += " supergrok";
        }
        String haystack = (provider.displayName + " " + provider.accountName + " "
                + provider.vendorName + " " + entry.meters + " " + entry.signInSummary + extra)
                .toLowerCase(Locale.ROOT);
        return haystack.contains(needle);
    }
}
