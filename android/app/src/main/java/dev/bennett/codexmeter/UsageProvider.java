package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Coding-agent services whose subscription limits Codex Meter can show.
 *
 * <p>Codex stays the default and keeps every legacy storage key, so existing installs see no
 * change until they open another provider tab. To add a provider: add an entry here, its mark
 * as {@code ic_provider_<id>} (mapped in {@code ProvidersActivity.iconRes}), one more tab slot in
 * {@code res/menu/provider_tabs.xml} if needed, add
 * its sign-in branch to {@link OAuthService}, and give it a usage client like
 * {@link ClaudeUsageApi} that returns a {@link UsageSnapshot}. Everything else (dashboard
 * cards, pace labels, Settings account row, token storage) is shared.
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
     * Providers whose tabs are on, in enum order. Unknown ids are skipped; when nothing valid
     * is saved the result is {@code fallback} (see {@link #migrationDefault}).
     */
    public static List<UsageProvider> enabledFromIds(Collection<String> ids,
            List<UsageProvider> fallback) {
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
        return enabled.isEmpty() ? new ArrayList<>(fallback) : enabled;
    }

    /**
     * Tabs for an install that never chose its providers (an upgrade from before provider
     * setup): Codex, plus any other provider it is already signed in to. A Codex-only install
     * therefore shows no tab bar.
     */
    public static List<UsageProvider> migrationDefault(Collection<UsageProvider> signedIn) {
        List<UsageProvider> result = new ArrayList<>();
        for (UsageProvider provider : values()) {
            if (provider == DEFAULT || (signedIn != null && signedIn.contains(provider))) {
                result.add(provider);
            }
        }
        return result;
    }

    /**
     * Every provider in the user's tab order: saved ids first (duplicates and unknown ids
     * skipped), then any provider the saved order doesn't mention, in enum order.
     */
    public static List<UsageProvider> orderFromIds(Collection<String> ids) {
        List<UsageProvider> order = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null) continue;
                String normalized = id.trim().toLowerCase(Locale.ROOT);
                for (UsageProvider provider : values()) {
                    if (provider.id.equals(normalized) && !order.contains(provider)) {
                        order.add(provider);
                    }
                }
            }
        }
        for (UsageProvider provider : values()) {
            if (!order.contains(provider)) order.add(provider);
        }
        return order;
    }

    /** The providers in {@code enabled}, arranged in {@code order}. */
    public static List<UsageProvider> inOrder(List<UsageProvider> enabled,
            List<UsageProvider> order) {
        List<UsageProvider> result = new ArrayList<>();
        for (UsageProvider provider : order) {
            if (enabled.contains(provider)) result.add(provider);
        }
        return result;
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
