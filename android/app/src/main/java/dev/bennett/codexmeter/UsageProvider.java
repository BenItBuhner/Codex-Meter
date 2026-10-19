package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Coding-agent services whose subscription limits Codex Meter can show.
 *
 * <p>Codex stays the default and keeps every legacy storage key, so existing installs see no
 * change until they open another provider tab. To add a provider: add an entry here, a tab
 * in {@code res/menu/provider_tabs.xml} (wired in {@code MainActivity.tabId}) with its mark as
 * {@code ic_provider_<id>}, a switch in {@code preferences_settings_providers.xml}, add
 * its sign-in branch to {@link OAuthService}, and give it a usage client like
 * {@link ClaudeUsageApi} that returns a {@link UsageSnapshot}. Everything else (dashboard
 * cards, pace labels, account card, token storage) is shared.
 */
public enum UsageProvider {
    CODEX("codex", "Codex", "ChatGPT", "OpenAI"),
    CLAUDE_CODE("claude_code", "Claude Code", "Claude", "Anthropic"),
    CURSOR("cursor", "Cursor", "Cursor", "Cursor");

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

    /**
     * Providers whose tabs are on, in tab order. Unknown ids are skipped; a missing or empty
     * list turns every provider on, so new installs and upgrades see all tabs.
     */
    public static List<UsageProvider> enabledFromIds(Collection<String> ids) {
        List<UsageProvider> enabled = new ArrayList<>();
        if (ids != null) {
            for (UsageProvider provider : values()) {
                for (String id : ids) {
                    if (id != null && provider.id.equals(id.trim().toLowerCase(Locale.ROOT))) {
                        enabled.add(provider);
                        break;
                    }
                }
            }
        }
        return enabled.isEmpty() ? new ArrayList<>(Arrays.asList(values())) : enabled;
    }

    /** {@code enabled} with one provider switched on or off; the last one can't be turned off. */
    public static List<UsageProvider> withEnabled(List<UsageProvider> enabled,
            UsageProvider provider, boolean on) {
        List<UsageProvider> result = new ArrayList<>();
        for (UsageProvider candidate : values()) {
            boolean wasOn = enabled != null && enabled.contains(candidate);
            if (candidate == provider ? on : wasOn) {
                result.add(candidate);
            }
        }
        return result.isEmpty() ? new ArrayList<>(enabled) : result;
    }

    /** The selected tab if it is still on, otherwise the first provider that is. */
    public static UsageProvider visibleSelection(UsageProvider selected,
            List<UsageProvider> enabled) {
        if (enabled == null || enabled.isEmpty() || enabled.contains(selected)) {
            return selected;
        }
        return enabled.get(0);
    }

    public String signInLabel() {
        return "Sign in with " + accountName;
    }
}
