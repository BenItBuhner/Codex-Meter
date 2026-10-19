package dev.bennett.codexmeter;

import java.util.Locale;

/**
 * Coding-agent services whose subscription limits Codex Meter can show.
 *
 * <p>Codex stays the default and keeps every legacy storage key, so existing installs see no
 * change until they open another provider tab. To add a provider: add an entry here and a tab
 * in {@code res/menu/provider_tabs.xml} (wired in {@code MainActivity.tabId}), add
 * its sign-in branch to {@link OAuthService}, and give it a usage client like
 * {@link ClaudeUsageApi} that returns a {@link UsageSnapshot}. Everything else (dashboard
 * cards, pace labels, account card, token storage) is shared.
 */
public enum UsageProvider {
    CODEX("codex", "Codex", "ChatGPT", "OpenAI"),
    CLAUDE_CODE("claude_code", "Claude Code", "Claude", "Anthropic");

    public static final UsageProvider DEFAULT = CODEX;

    /** Stable id used in preferences and intents. Never rename an existing id. */
    public final String id;
    /** Tab label and dashboard name. */
    public final String displayName;
    /** The account the user signs in with, as in "Sign in with ChatGPT". */
    public final String accountName;
    /** The company that reports the usage data, used in empty-state copy. */
    public final String vendorName;

    UsageProvider(String id, String displayName, String accountName, String vendorName) {
        this.id = id;
        this.displayName = displayName;
        this.accountName = accountName;
        this.vendorName = vendorName;
    }

    /** Resolves a saved id, falling back to {@link #DEFAULT} for unknown or missing values. */
    public static UsageProvider fromId(String id) {
        if (id != null) {
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            for (UsageProvider provider : values()) {
                if (provider.id.equals(normalized)) {
                    return provider;
                }
            }
        }
        return DEFAULT;
    }

    public String signInLabel() {
        return "Sign in with " + accountName;
    }
}
