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
 * as {@code ic_provider_<id>} (mapped in {@code ProvidersActivity.iconRes}), its sign-in style
 * and description in {@link ProviderCatalog}, and a {@link ProviderClient} registered in
 * {@link ProviderClients} that signs in and returns a {@link UsageSnapshot}. Everything else
 * (Add account picker, sign-in screen, dashboard cards, tabs, refresh, token storage) is shared.
 * Codex, Claude Code and Cursor predate that and keep their own clients.
 */
public enum UsageProvider {
    CODEX("codex", "Codex", "ChatGPT", "OpenAI"),
    CLAUDE_CODE("claude_code", "Claude Code", "Claude", "Anthropic"),
    CURSOR("cursor", "Cursor", "Cursor", "Cursor"),
    COPILOT("copilot", "Copilot", "GitHub", "GitHub"),
    KIMI("kimi", "Kimi Code", "Kimi", "Moonshot AI"),
    FACTORY("factory", "Factory", "Factory", "Factory"),
    GROK("grok", "Grok", "xAI", "xAI"),
    ANTIGRAVITY("antigravity", "Antigravity", "Google", "Google"),
    DEVIN("devin", "Devin", "Devin", "Cognition"),
    WINDSURF("windsurf", "Windsurf", "Windsurf", "Cognition"),
    AUGMENT("augment", "Augment", "Augment", "Augment Code"),
    CLINE("cline", "Cline", "Cline", "Cline"),
    OPENCODE_GO("opencode_go", "OpenCode Go", "OpenCode", "OpenCode"),
    MINIMAX("minimax", "MiniMax", "MiniMax", "MiniMax"),
    ZAI("zai", "Z.ai", "Z.ai", "Z.ai"),
    KIRO("kiro", "Kiro", "AWS Builder ID", "AWS"),
    AMP("amp", "Amp", "Amp", "Amp"),
    OPENROUTER("openrouter", "OpenRouter", "OpenRouter", "OpenRouter"),
    KILO("kilo", "Kilo Code", "Kilo", "Kilo"),
    CODEBUFF("codebuff", "Codebuff", "Codebuff", "Codebuff"),
    SYNTHETIC("synthetic", "Synthetic", "Synthetic", "Synthetic"),
    CHUTES("chutes", "Chutes", "Chutes", "Chutes"),
    V0("v0", "v0", "v0", "Vercel"),
    WARP("warp", "Warp", "Warp", "Warp");

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

    /** Usage cards this provider usually reports, listed in Edit dashboard before any data. */
    public List<String> usualDashboardSections() {
        List<String> sections = new ArrayList<>();
        if (this == CURSOR) {
            sections.add(DashboardSections.WEEKLY);
        } else if (this == CODEX || this == CLAUDE_CODE) {
            sections.add(DashboardSections.FIVE_HOUR);
            sections.add(DashboardSections.WEEKLY);
        }
        // Other providers list their cards once they report data.
        return sections;
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
