package dev.bennett.codexmeter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which official status feed each provider uses, and which of that feed's components count for
 * it. Only first-party feeds are listed; there is no scraping of status pages or third-party
 * outage sites. Providers without an official feed return {@code null} and show no status.
 *
 * <ul>
 *   <li>Codex: OpenAI's page has no plain "Codex" component, only "Codex in ChatGPT Desktop"; any
 *       component naming Codex counts.</li>
 *   <li>Claude Code: Anthropic lists Claude Code separately from the Claude API and claude.ai; only
 *       Claude Code counts.</li>
 *   <li>Cursor: status.cursor.com/api/v2/summary.json now answers with a 301 to
 *       status.x.ai/api/compat/summary.json, whose page is named "Cursor" and lists Cursor's own
 *       components (IDE, CLI, Cloud Agents and so on). Checked on 2026-11-07. The client requests
 *       the compat URL directly and still checks the page name before trusting it.</li>
 *   <li>Grok: SpaceXSI publishes an RSS incident feed (status.x.ai/feed.xml) and no public JSON
 *       for Grok, so Grok reads that feed's Grok components.</li>
 *   <li>Antigravity: Google has no dedicated Antigravity status endpoint. Google Cloud or Gemini
 *       status is not Antigravity status, so Antigravity has none.</li>
 * </ul>
 */
public final class ProviderStatusSources {

    public enum Format { STATUSPAGE_JSON, RSS }

    public static final class Source {
        public final String feedUrl;
        public final String pageUrl;
        public final Format format;
        /** Component names to match (substring for JSON, exact bracket name for RSS). */
        public final List<String> components;
        /** For JSON, the page name the feed must report, or "" to skip the check. */
        public final String expectedPageName;

        Source(String feedUrl, String pageUrl, Format format, String expectedPageName,
                String... components) {
            this.feedUrl = feedUrl;
            this.pageUrl = pageUrl;
            this.format = format;
            this.expectedPageName = expectedPageName;
            this.components = Collections.unmodifiableList(Arrays.asList(components));
        }
    }

    private static final Source CODEX = new Source(
            "https://status.openai.com/api/v2/summary.json", "https://status.openai.com",
            Format.STATUSPAGE_JSON, "OpenAI", "Codex");
    private static final Source CLAUDE_CODE = new Source(
            "https://status.claude.com/api/v2/summary.json", "https://status.claude.com",
            Format.STATUSPAGE_JSON, "Claude", "Claude Code");
    private static final Source CURSOR = new Source(
            "https://status.x.ai/api/compat/summary.json", "https://status.cursor.com",
            Format.STATUSPAGE_JSON, "Cursor", "IDE", "CLI", "Cloud Agents", "Third-party models");
    private static final Source GROK = new Source(
            "https://status.x.ai/feed.xml", "https://status.x.ai", Format.RSS, "",
            "Grok Bot", "grok.com", "Grok (Build)", "Global (api.x.ai)", "US (us.api.x.ai)");

    private ProviderStatusSources() {}

    public static Source forProvider(UsageProvider provider) {
        if (provider == null) {
            return null;
        }
        switch (provider) {
            case CODEX:
                return CODEX;
            case CLAUDE_CODE:
                return CLAUDE_CODE;
            case CURSOR:
                return CURSOR;
            case GROK:
                return GROK;
            default:
                return null;
        }
    }

    /** Parses a fetched body for {@code provider}; anything unexpected is "status unknown". */
    public static ProviderStatus parse(UsageProvider provider, String body, long now) {
        Source source = forProvider(provider);
        if (source == null) {
            return null;
        }
        if (source.format == Format.RSS) {
            return ProviderStatus.parseRss(provider.id, body, source.components, source.pageUrl, now);
        }
        if (!source.expectedPageName.isEmpty() && !pageNameMatches(body, source.expectedPageName)) {
            return ProviderStatus.unknown(provider.id, source.pageUrl, now);
        }
        return ProviderStatus.parseSummary(provider.id, body, source.components, source.pageUrl, now);
    }

    static boolean pageNameMatches(String body, String expected) {
        try {
            org.json.JSONObject page = new org.json.JSONObject(body).optJSONObject("page");
            return page != null && expected.equalsIgnoreCase(page.optString("name", "").trim());
        } catch (org.json.JSONException | NullPointerException e) {
            return false;
        }
    }
}
