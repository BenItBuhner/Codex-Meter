package dev.bennett.codexmeter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Official status feeds: component matching, unknown-not-outage, RSS, polling and backoff. */
public final class ProviderStatusSelfTest {
    private static final long NOW = 1_794_000_000_000L;

    public static void main(String[] args) throws Exception {
        realFeedsReadOperational();
        claudeApiOutageIsNotClaudeCode();
        claudeCodeIncidentNamesTheComponent();
        resolvedAndUnlinkedIncidentsAreIgnored();
        maintenanceInProgress();
        garbageIsUnknownNotOutage();
        unlistedProviderIsUnknown();
        cursorPageNameIsChecked();
        rssActiveGrokIncident();
        rssCursorItemsDontAffectGrok();
        noFeedForAntigravity();
        roundTrip();
        pollPolicy();
        retryAfter();
        System.out.println("Provider status: official feeds, components, unknown, RSS and polling verified.");
    }

    private static String fixture(String name) throws Exception {
        Path dir = Paths.get(System.getProperty("codex.fixtures", "tests/fixtures"), "status");
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void realFeedsReadOperational() throws Exception {
        ProviderStatus codex = ProviderStatusSources.parse(UsageProvider.CODEX,
                fixture("openai-summary.json"), NOW);
        check(codex.state == ProviderStatus.State.OPERATIONAL, "OpenAI feed: Codex operational, was " + codex.state);
        check(!codex.shouldShowCard(), "operational shows no card");
        ProviderStatus claude = ProviderStatusSources.parse(UsageProvider.CLAUDE_CODE,
                fixture("claude-summary.json"), NOW);
        check(claude.state == ProviderStatus.State.OPERATIONAL, "Claude Code operational");
        ProviderStatus cursor = ProviderStatusSources.parse(UsageProvider.CURSOR,
                fixture("cursor-compat-summary.json"), NOW);
        check(cursor.state == ProviderStatus.State.OPERATIONAL, "Cursor operational via status.x.ai compat");
        ProviderStatus grok = ProviderStatusSources.parse(UsageProvider.GROK, fixture("xai-feed.xml"), NOW);
        check(grok.state == ProviderStatus.State.NO_ACTIVE_INCIDENTS,
                "RSS with only resolved items says no active incidents, was " + grok.state);
        check(grok.headline("Grok").equals("Grok: no active incidents reported"), grok.headline("Grok"));
    }

    private static String claudeWith(String apiStatus, String codeStatus, String incidents) {
        return "{\"page\":{\"name\":\"Claude\"},\"components\":["
                + "{\"name\":\"claude.ai\",\"status\":\"operational\"},"
                + "{\"name\":\"Claude API (api.anthropic.com)\",\"status\":\"" + apiStatus + "\"},"
                + "{\"name\":\"Claude Code\",\"status\":\"" + codeStatus + "\"}],"
                + "\"incidents\":" + incidents + ",\"scheduled_maintenances\":[]}";
    }

    private static void claudeApiOutageIsNotClaudeCode() {
        ProviderStatus s = ProviderStatusSources.parse(UsageProvider.CLAUDE_CODE,
                claudeWith("major_outage", "operational",
                        "[{\"name\":\"API errors\",\"status\":\"investigating\",\"impact\":\"critical\","
                                + "\"components\":[{\"name\":\"Claude API (api.anthropic.com)\",\"status\":\"major_outage\"}]}]"),
                NOW);
        check(s.state == ProviderStatus.State.OPERATIONAL, "API outage must not mark Claude Code down");
        check(s.issues.isEmpty(), "no Claude Code issues");
    }

    private static void claudeCodeIncidentNamesTheComponent() {
        ProviderStatus s = ProviderStatusSources.parse(UsageProvider.CLAUDE_CODE,
                claudeWith("operational", "degraded_performance",
                        "[{\"name\":\"Elevated errors in Claude Code\",\"status\":\"identified\",\"impact\":\"minor\","
                                + "\"created_at\":\"2026-11-07T01:00:00.000Z\","
                                + "\"components\":[{\"name\":\"Claude Code\",\"status\":\"degraded_performance\"}]}]"),
                NOW);
        check(s.state == ProviderStatus.State.DEGRADED, "degraded, was " + s.state);
        check(s.shouldShowCard(), "degraded shows a card");
        check(s.headline("Claude Code").equals("Claude Code: degraded performance"), s.headline("Claude Code"));
        check(s.detail().equals("Elevated errors in Claude Code"), s.detail());
        check(s.issues.size() == 1, "one issue per component, was " + s.issues.size());
        check(s.issues.get(0).startedAtMillis > 0L, "incident start parsed");
    }

    private static void resolvedAndUnlinkedIncidentsAreIgnored() {
        ProviderStatus s = ProviderStatusSources.parse(UsageProvider.CLAUDE_CODE,
                claudeWith("operational", "operational",
                        "[{\"name\":\"Old\",\"status\":\"resolved\",\"impact\":\"major\","
                                + "\"components\":[{\"name\":\"Claude Code\",\"status\":\"operational\"}]},"
                                + "{\"name\":\"Billing\",\"status\":\"investigating\",\"impact\":\"major\",\"components\":[]},"
                                + "{\"name\":\"Watching\",\"status\":\"monitoring\",\"impact\":\"none\","
                                + "\"components\":[{\"name\":\"Claude Code\",\"status\":\"operational\"}]}]"),
                NOW);
        check(s.state == ProviderStatus.State.OPERATIONAL, "resolved/unlinked/monitoring-none ignored, was " + s.state);
    }

    private static void maintenanceInProgress() {
        String body = "{\"page\":{\"name\":\"OpenAI\"},\"components\":[{\"name\":\"Codex in ChatGPT Desktop\","
                + "\"status\":\"operational\"}],\"incidents\":[],\"scheduled_maintenances\":["
                + "{\"name\":\"Planned\",\"status\":\"scheduled\",\"impact\":\"maintenance\",\"components\":[{\"name\":\"Codex in ChatGPT Desktop\"}]},"
                + "{\"name\":\"Upgrade\",\"status\":\"in_progress\",\"impact\":\"maintenance\",\"components\":[{\"name\":\"Codex in ChatGPT Desktop\"}]}]}";
        ProviderStatus s = ProviderStatusSources.parse(UsageProvider.CODEX, body, NOW);
        check(s.state == ProviderStatus.State.MAINTENANCE, "in-progress maintenance, was " + s.state);
        check(s.detail().equals("Upgrade"), "scheduled-but-not-started maintenance ignored: " + s.detail());
    }

    private static void garbageIsUnknownNotOutage() {
        for (String body : Arrays.asList(null, "", "<html>blocked</html>", "{}", "{\"page\":{\"name\":\"Claude\"}}")) {
            ProviderStatus s = ProviderStatusSources.parse(UsageProvider.CLAUDE_CODE, body, NOW);
            check(s.state == ProviderStatus.State.UNKNOWN, "unreadable feed is unknown: " + body);
            check(!s.shouldShowCard(), "unknown shows no outage card");
            check(s.headline("Claude Code").equals("Claude Code: status unknown"), s.headline("Claude Code"));
        }
        check(ProviderStatusSources.parse(UsageProvider.GROK, "not rss", NOW).state == ProviderStatus.State.UNKNOWN,
                "unreadable RSS is unknown");
    }

    private static void unlistedProviderIsUnknown() {
        String body = "{\"page\":{\"name\":\"Claude\"},\"components\":[{\"name\":\"claude.ai\",\"status\":\"major_outage\"}],\"incidents\":[]}";
        ProviderStatus s = ProviderStatusSources.parse(UsageProvider.CLAUDE_CODE, body, NOW);
        check(s.state == ProviderStatus.State.UNKNOWN, "feed without Claude Code is unknown, not an outage");
    }

    private static void cursorPageNameIsChecked() throws Exception {
        String wrongPage = fixture("cursor-compat-summary.json").replace("\"name\":\"Cursor\"", "\"name\":\"SpaceXAI\"");
        check(ProviderStatusSources.parse(UsageProvider.CURSOR, wrongPage, NOW).state == ProviderStatus.State.UNKNOWN,
                "a status.x.ai page that isn't Cursor's is not used for Cursor");
    }

    private static String rss(String... items) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>SpaceXAI System Status</title>");
        for (String item : items) {
            b.append(item);
        }
        return b.append("</channel></rss>").toString();
    }

    private static String item(String title, String... categories) {
        StringBuilder b = new StringBuilder("<item><title>").append(title)
                .append("</title><link>https://status.x.ai/x/1</link><pubDate>Sat, 07 Nov 2026 01:00:00 GMT</pubDate>");
        for (String c : categories) {
            b.append("<category>").append(c).append("</category>");
        }
        return b.append("</item>").toString();
    }

    private static void rssActiveGrokIncident() {
        ProviderStatus s = ProviderStatusSources.parse(UsageProvider.GROK, rss(
                item("[Grok Bot] Grok Bot is currently degraded", "disruption", "investigating"),
                item("[grok.com] Login errors", "outage", "identified"),
                item("[Grok Bot] Old issue", "outage", "resolved")), NOW);
        check(s.state == ProviderStatus.State.PARTIAL_OUTAGE, "outage category is a partial outage, was " + s.state);
        check(s.issues.size() == 2, "two active components, was " + s.issues.size());
        check(s.headline("Grok").equals("grok.com and 1 more: partial outage"), s.headline("Grok"));
        check(s.detail().equals("Login errors"), s.detail());
        check(s.issues.get(0).startedAtMillis > 0L, "pubDate parsed");
    }

    private static void rssCursorItemsDontAffectGrok() {
        ProviderStatus s = ProviderStatusSources.parse(UsageProvider.GROK, rss(
                item("[Cloud Agents] Agents failing", "outage", "investigating"),
                item("[Grok (iOS)] App crash", "disruption", "investigating")), NOW);
        check(s.state == ProviderStatus.State.NO_ACTIVE_INCIDENTS, "other components don't count, was " + s.state);
    }

    private static void noFeedForAntigravity() {
        check(ProviderStatusSources.forProvider(UsageProvider.ANTIGRAVITY) == null,
                "Antigravity has no official status feed; Google Cloud/Gemini status is not used");
        check(ProviderStatusSources.parse(UsageProvider.ANTIGRAVITY, "{}", NOW) == null, "no status at all");
        check(ProviderStatusSources.forProvider(UsageProvider.COPILOT) == null, "only verified feeds are listed");
    }

    private static void roundTrip() {
        ProviderStatus s = ProviderStatusSources.parse(UsageProvider.GROK, rss(
                item("[Grok Bot] Slow replies", "disruption", "investigating")), NOW);
        ProviderStatus back = ProviderStatus.fromJson(s.toJson());
        check(back.state == s.state && back.issues.size() == 1, "round trip keeps state and issues");
        check(back.headline("Grok").equals(s.headline("Grok")) && back.detail().equals(s.detail()), "round trip copy");
        check(back.checkedAtMillis == NOW && back.pageUrl.equals("https://status.x.ai"), "round trip metadata");
    }

    private static void pollPolicy() {
        long fg = StatusPollPolicy.FOREGROUND_INTERVAL_MS;
        check(StatusPollPolicy.shouldFetch(NOW, 0L, 0, 0L, true, 0.5), "first fetch is immediate");
        check(!StatusPollPolicy.shouldFetch(NOW + fg - 1, NOW, 0, 0L, true, 0.0), "not before 5 minutes");
        check(StatusPollPolicy.shouldFetch(NOW + fg, NOW, 0, 0L, true, 0.0), "at 5 minutes");
        check(!StatusPollPolicy.shouldFetch(NOW + fg + fg / 10, NOW, 0, 0L, true, 0.9), "jitter adds up to 20%");
        check(!StatusPollPolicy.shouldFetch(NOW + TimeUnit.MINUTES.toMillis(29), NOW, 0, 0L, false, 0.0),
                "background waits 30 minutes");
        long afterTwo = StatusPollPolicy.nextAllowedAt(NOW, 2, 0L, true, 0.0) - NOW;
        check(afterTwo == 4 * fg, "two failures back off to 20 minutes, was " + afterTwo);
        long capped = StatusPollPolicy.nextAllowedAt(NOW, 30, 0L, true, 0.0) - NOW;
        check(capped == StatusPollPolicy.MAX_BACKOFF_MS, "backoff caps at 2 hours");
        long retry = NOW + TimeUnit.MINUTES.toMillis(40);
        check(StatusPollPolicy.nextAllowedAt(NOW, 0, retry, true, 0.0) == retry, "Retry-After wins when later");
        check(StatusPollPolicy.feedKey("https://status.x.ai/feed.xml").equals("https_status_x_ai_feed_xml"), "feed key");
    }

    private static void retryAfter() {
        check(StatusPollPolicy.retryAfterUntil("120", NOW) == NOW + 120_000L, "seconds");
        check(StatusPollPolicy.retryAfterUntil("999999", NOW) == NOW + StatusPollPolicy.MAX_BACKOFF_MS, "capped");
        check(StatusPollPolicy.retryAfterUntil("soon", NOW) == 0L, "unreadable ignored");
        check(StatusPollPolicy.retryAfterUntil(null, NOW) == 0L, "missing ignored");
        long date = StatusPollPolicy.retryAfterUntil("Sat, 07 Nov 2026 01:00:00 GMT", ProviderStatus.parseRfc1123(
                "Sat, 07 Nov 2026 00:30:00 GMT"));
        check(date == ProviderStatus.parseRfc1123("Sat, 07 Nov 2026 01:00:00 GMT"), "HTTP date");
    }
}
