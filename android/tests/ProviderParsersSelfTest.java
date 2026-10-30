package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Self-tests for the providers added through Add account: catalog coverage, the bottom-bar
 * rule, device-code parsing, and one usage response per provider. Response samples follow
 * each provider's CLI or open-source trackers; they are shapes, not real account data.
 */
public final class ProviderParsersSelfTest {
    private static final long NOW = 1_790_000_000_000L; // 2026-09-21T13:33:20Z
    private static final long NOW_S = NOW / 1000L;

    public static void main(String[] args) throws Exception {
        testNotificationRoutes();
        testCatalog();
        testProviderTabs();
        testDeviceAuth();
        testTimestamps();
        testCopilot();
        testKimi();
        testFactory();
        testGrok();
        testAntigravity();
        testCodeium();
        testAugment();
        testCline();
        testOpenCodeGo();
        testMiniMax();
        testZai();
        testKiro();
        testOpenRouter();
        testAmp();
        testBalances();
        testKeyQuota();
        testAppSessionRules();
        System.out.println("Provider catalog, tabs, device sign-in, and 21 usage parsers verified.");
    }

    private static void testCatalog() {
        Set<UsageProvider> seen = new HashSet<>();
        for (UsageProvider provider : ProviderCatalog.recommendedOrder()) {
            check(seen.add(provider), "catalog lists each provider once: " + provider.id);
            ProviderCatalog.Entry entry = ProviderCatalog.get(provider);
            check(entry.provider == provider, "catalog entry exists for " + provider.id);
            check(!entry.meters.isEmpty() && !entry.signInSummary.isEmpty(),
                    "catalog copy for " + provider.id);
            if (entry.signIn == ProviderCatalog.SignIn.API_KEY) {
                check(entry.acceptsApiKey, provider.id + " key sign-in accepts a key");
            }
        }
        check(seen.size() == UsageProvider.values().length, "catalog covers every provider");
        check(ProviderCatalog.recommendedOrder().subList(0, 7).equals(Arrays.asList(
                UsageProvider.CODEX, UsageProvider.CLAUDE_CODE, UsageProvider.CURSOR,
                UsageProvider.COPILOT, UsageProvider.KIMI, UsageProvider.FACTORY,
                UsageProvider.GROK)), "picker leads with the most-used providers");
        for (UsageProvider provider : new UsageProvider[] {UsageProvider.GROK,
                UsageProvider.DEVIN, UsageProvider.MINIMAX, UsageProvider.CLINE,
                UsageProvider.OPENCODE_GO, UsageProvider.KIRO}) {
            check(ProviderCatalog.get(provider).inferred,
                    provider.id + " is flagged experimental");
        }
        check(!ProviderCatalog.get(UsageProvider.COPILOT).inferred
                        && !ProviderCatalog.get(UsageProvider.CODEX).usesSignInScreen()
                        && ProviderCatalog.get(UsageProvider.COPILOT).usesSignInScreen(),
                "Copilot is confirmed and uses the sign-in screen; Codex keeps its own flow");
        List<UsageProvider> picker = ProviderCatalog.pickerProviders(
                Arrays.asList(UsageProvider.CODEX, UsageProvider.CURSOR), "");
        check(!picker.contains(UsageProvider.CODEX) && picker.get(0) == UsageProvider.CLAUDE_CODE
                        && picker.size() == UsageProvider.values().length - 2,
                "Add account hides connected providers");
        check(ProviderCatalog.pickerProviders(null, "github").equals(
                Collections.singletonList(UsageProvider.COPILOT)), "search matches the account");
        check(ProviderCatalog.pickerProviders(null, "  KIMI ").equals(
                Collections.singletonList(UsageProvider.KIMI)), "search trims and ignores case");
        check(ProviderCatalog.pickerProviders(null, "cognition").containsAll(Arrays.asList(
                UsageProvider.DEVIN, UsageProvider.WINDSURF)), "search matches the company");
        check(ProviderCatalog.pickerProviders(null, "zzzz").isEmpty(), "no match -> empty");
    }

    private static void testProviderTabs() {
        check(!ProviderTabs.showsBar(1) && ProviderTabs.ownTabs(1) == 0,
                "one provider hides the bar");
        check(ProviderTabs.ownTabs(2) == 2 && !ProviderTabs.showsMore(2), "two providers, two tabs");
        check(ProviderTabs.ownTabs(4) == 4 && !ProviderTabs.showsMore(4),
                "four providers still fit as tabs");
        check(ProviderTabs.ownTabs(5) == 3 && ProviderTabs.inMore(5) == 2,
                "five providers: three tabs plus More");
        check(ProviderTabs.ownTabs(24) == 3 && ProviderTabs.inMore(24) == 21,
                "every provider: three tabs, the rest in More");
        check(ProviderTabs.SLOT_COUNT == UsageProvider.values().length,
                "one menu slot per provider");
    }

    private static void testDeviceAuth() {
        DeviceAuth.DeviceCode github = DeviceAuth.parseDeviceCode("{\"device_code\":\"dc\","
                + "\"user_code\":\"ABCD-1234\",\"verification_uri\":\"https://github.com/login/"
                + "device\",\"expires_in\":899,\"interval\":5}");
        check(github.isValid() && "ABCD-1234".equals(github.userCode)
                        && github.openUrl().endsWith("/login/device")
                        && github.intervalSeconds == 5 && github.expiresInSeconds == 899,
                "RFC 8628 device code");
        DeviceAuth.DeviceCode aws = DeviceAuth.parseDeviceCode("{\"deviceCode\":\"d\","
                + "\"userCode\":\"WXYZ-9876\",\"verificationUri\":\"https://view.awsapps.com/"
                + "start/#/device\",\"verificationUriComplete\":\"https://view.awsapps.com/start/"
                + "#/device?user_code=WXYZ-9876\",\"expiresIn\":600,\"interval\":1}");
        check(aws.isValid() && aws.openUrl().contains("user_code=WXYZ-9876"),
                "camelCase device code prefers the pre-filled page");
        check(DeviceAuth.parsePoll(200, "{\"error\":\"authorization_pending\"}").state
                == DeviceAuth.PollState.PENDING, "GitHub answers pending with HTTP 200");
        check(DeviceAuth.parsePoll(400, "{\"error\":\"slow_down\"}").state
                == DeviceAuth.PollState.SLOW_DOWN, "slow_down");
        check(DeviceAuth.parsePoll(400, "{\"error\":\"access_denied\"}").state
                == DeviceAuth.PollState.DENIED, "denied");
        check(DeviceAuth.parsePoll(400, "{\"error\":\"expired_token\"}").state
                == DeviceAuth.PollState.EXPIRED, "expired");
        check(DeviceAuth.parsePoll(202, "").state == DeviceAuth.PollState.PENDING,
                "Kilo answers 202 while waiting");
        check(DeviceAuth.parsePoll(410, "").state == DeviceAuth.PollState.EXPIRED,
                "Kilo answers 410 once the code expires");
        DeviceAuth.Poll done = DeviceAuth.parsePoll(200, "{\"access_token\":\"at\","
                + "\"refresh_token\":\"rt\",\"expires_in\":3600,\"user\":{\"email\":"
                + "\"dev@example.com\"}}");
        check(done.state == DeviceAuth.PollState.DONE && "rt".equals(done.tokens.refreshToken)
                        && "dev@example.com".equals(done.tokens.email), "tokens and email");
        AuthTokens stored = done.tokens.toAuthTokens(NOW, "");
        check(stored.expiresAtMillis == NOW + 3_600_000L && stored.isUsable(),
                "expiry becomes epoch millis");
        AuthTokens forever = new DeviceAuth.Tokens("gho_x", "", "", 0, "").toAuthTokens(NOW, "");
        check(forever.isUsable() && forever.expiresAtMillis == 0L
                        && "gho_x".equals(forever.refreshToken),
                "non-expiring tokens keep the access token as the refresh token");
        check("abc".equals(DeviceAuth.parseTokens("{\"token\":\"abc\"}").accessToken),
                "Kilo's token key");
        check("cl".equals(DeviceAuth.parseTokens("{\"success\":true,\"data\":{\"accessToken\":"
                + "\"cl\",\"refreshToken\":\"r\",\"userInfo\":{\"email\":\"a@b.co\"}}}")
                .accessToken), "Cline wraps tokens in data");
        check("tok".equals(DeviceAuth.extractPasted("  tok\n", "access_token")),
                "bare pasted token");
        check("x y".equals(DeviceAuth.extractPasted(
                "https://example.com/cb?state=a&access_token=x%20y", "access_token")),
                "token from a pasted URL");
        check("c1".equals(DeviceAuth.extractPasted("{\"code\":\"c1\",\"state\":\"s\"}", "code")),
                "value from pasted JSON");
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        check("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(DeviceAuth.s256(verifier)),
                "S256 matches RFC 7636's example");
        check(DeviceAuth.randomToken(32).length() == 43, "random tokens are URL-safe base64");
    }

    private static void testTimestamps() {
        long iso = ProviderUsageParsers.epochSeconds("2026-10-01T00:00:00Z");
        check(iso == ProviderUsageParsers.epochSeconds(iso)
                        && iso == ProviderUsageParsers.epochSeconds(iso * 1000L)
                        && iso == ProviderUsageParsers.epochSeconds("2026-10-01")
                        && iso == ProviderUsageParsers.epochSeconds(String.valueOf(iso * 1000L))
                        && iso == ProviderUsageParsers.epochSeconds("2026-10-01T02:00:00+02:00"),
                "timestamps in seconds, millis, ISO and dates agree");
        check(ProviderUsageParsers.epochSeconds(null) == 0
                && ProviderUsageParsers.epochSeconds("soon") == 0, "unreadable time -> 0");
        check("Weekly".equals(ProviderUsageParsers.durationName(ProviderUsageParsers.WEEK))
                && "5-hour".equals(ProviderUsageParsers.durationName(5 * 3600))
                && "Daily".equals(ProviderUsageParsers.durationName(86_400))
                && "Monthly".equals(ProviderUsageParsers.durationName(ProviderUsageParsers.MONTH)),
                "window names");
    }

    private static void testCopilot() {
        String body = "{\"copilot_plan\":\"individual\",\"quota_reset_date\":\"2026-10-01\","
                + "\"quota_snapshots\":{"
                + "\"premium_interactions\":{\"entitlement\":300,\"remaining\":75,"
                + "\"percent_remaining\":25.0,\"unlimited\":false},"
                + "\"chat\":{\"entitlement\":0,\"remaining\":0,\"percent_remaining\":100,"
                + "\"unlimited\":true},"
                + "\"completions\":{\"entitlement\":0,\"remaining\":0,\"unlimited\":false}}}";
        UsageSnapshot snapshot = ProviderUsageParsers.copilot(body, NOW);
        check("Pro".equals(snapshot.planType), "Copilot plan label");
        check(snapshot.monthly != null && snapshot.monthly.usedPercent == 75
                        && snapshot.monthly.resetAtEpochSeconds
                        == ProviderUsageParsers.epochSeconds("2026-10-01"),
                "Copilot premium requests are the monthly window");
        check(snapshot.additionalLimits.isEmpty(),
                "unlimited chat and placeholder completions are skipped");
        UsageSnapshot free = ProviderUsageParsers.copilot("{\"access_type_sku\":\"free_limited"
                + "_copilot\",\"limited_user_reset_date\":\"2026-10-05\",\"monthly_quotas\":{"
                + "\"chat\":50,\"completions\":2000},\"limited_user_quotas\":{\"chat\":40,"
                + "\"completions\":500}}", NOW);
        check("Free".equals(free.planType) && free.monthly.usedPercent == 20
                        && free.additionalLimits.size() == 1
                        && free.additionalLimits.get(0).primary.usedPercent == 75,
                "Copilot Free counts chat and completions");
        check("Pro+".equals(ProviderUsageParsers.copilotPlan("individual_pro"))
                && "Business".equals(ProviderUsageParsers.copilotPlan("business")),
                "Copilot plan names");
    }

    private static void testKimi() {
        String body = "{\"user\":{\"membership\":{\"level\":\"LEVEL_INTERMEDIATE\"}},"
                + "\"usage\":{\"limit\":\"2048\",\"used\":\"512\",\"resetTime\":"
                + "\"2026-09-25T00:00:00Z\"},"
                + "\"limits\":[{\"window\":{\"duration\":300,\"timeUnit\":\"TIME_UNIT_MINUTE\"},"
                + "\"detail\":{\"limit\":\"200\",\"remaining\":\"150\",\"resetTime\":"
                + "\"2026-09-21T15:00:00Z\"}}]}";
        UsageSnapshot snapshot = ProviderUsageParsers.kimi(body, NOW);
        check("Allegretto".equals(snapshot.planType), "Kimi membership name");
        check(snapshot.fiveHour != null && snapshot.fiveHour.usedPercent == 25
                && snapshot.fiveHour.windowSeconds == 18_000L, "Kimi 5-hour limit");
        check(snapshot.weekly != null && snapshot.weekly.usedPercent == 25,
                "Kimi top-level usage is weekly");
        UsageSnapshot ratios = ProviderUsageParsers.kimi("{\"usages\":{\"limit_5h\":{"
                + "\"used_ratio\":0.4},\"limit_7d\":{\"used_ratio\":0.1,\"reset_time\":"
                + "1790500000}}}", NOW);
        check(ratios.fiveHour.usedPercent == 40 && ratios.weekly.usedPercent == 10
                && ratios.weekly.resetAtEpochSeconds == 1_790_500_000L, "Kimi ratio shape");
    }

    private static void testFactory() {
        String body = "{\"usesTokenRateLimitsBilling\":true,\"limits\":{\"standard\":{"
                + "\"fiveHour\":{\"usedPercent\":12.5,\"windowEnd\":" + (NOW_S + 3600) + "},"
                + "\"weekly\":{\"usedPercent\":40,\"secondsRemaining\":86400},"
                + "\"monthly\":{\"usedPercent\":90,\"windowEnd\":" + (NOW_S - 60) + "}},"
                + "\"core\":{\"weekly\":{\"usedPercent\":5,\"windowEnd\":\"2026-09-28T00:00:00Z\"}}},"
                + "\"extraUsageBalanceCents\":1250}";
        UsageSnapshot snapshot = ProviderUsageParsers.factory(body, "Pro", NOW);
        check(snapshot.fiveHour.usedPercent == 13 && snapshot.weekly.usedPercent == 40
                && snapshot.weekly.resetAtEpochSeconds == NOW_S + 86_400L, "Factory windows");
        check(snapshot.monthly.usedPercent == 0, "an ended Factory window has reset");
        check(snapshot.additionalLimits.size() == 1
                && "Core weekly limit".equals(snapshot.additionalLimits.get(0).name),
                "Factory core limit");
        check("$12.50".equals(snapshot.usageCredits.balance), "Factory extra-usage balance");
        UsageSnapshot legacy = ProviderUsageParsers.factory("{\"usage\":{\"startDate\":"
                + "\"2026-09-01T00:00:00Z\",\"endDate\":\"2026-10-01T00:00:00Z\",\"standard\":{"
                + "\"usedRatio\":0.3}}}", "", NOW);
        check(legacy.monthly != null && legacy.monthly.usedPercent == 30,
                "Factory subscription-usage fallback");
        check("Max".equals(ProviderUsageParsers.factoryPlan("{\"organization\":{"
                + "\"subscription\":{\"orbSubscription\":{\"plan\":{\"name\":\"Factory Max\"}}}}}")),
                "Factory plan name");
    }

    private static void testGrok() {
        String body = "{\"config\":{\"creditUsagePercent\":37.5,\"currentPeriod\":{"
                + "\"start\":\"2026-09-01T00:00:00Z\",\"end\":\"2026-10-01T00:00:00Z\"},"
                + "\"subscriptionTier\":\"SUBSCRIPTION_TIER_SUPER_GROK_PRO\","
                + "\"productUsage\":[{\"product\":\"grok_code\",\"usagePercent\":20}],"
                + "\"prepaidBalance\":{\"val\":\"500\"}}}";
        UsageSnapshot snapshot = ProviderUsageParsers.grok(body, NOW);
        check("SuperGrok".equals(snapshot.planType), "Grok plan");
        check(snapshot.monthly != null && snapshot.monthly.usedPercent == 38,
                "Grok credits fill the billing period");
        check(snapshot.additionalLimits.size() == 1
                && "Grok Code".equals(snapshot.additionalLimits.get(0).name), "Grok products");
        check("$5.00".equals(snapshot.usageCredits.balance), "Grok prepaid balance");
    }

    private static void testAntigravity() {
        String body = "{\"groups\":[{\"name\":\"Gemini\",\"buckets\":["
                + "{\"bucketId\":\"gemini-5h\",\"remainingFraction\":0.75,\"resetTime\":"
                + "\"2026-09-21T16:00:00Z\"},"
                + "{\"bucketId\":\"gemini-weekly\",\"remainingFraction\":0.5}]},"
                + "{\"name\":\"Third party\",\"buckets\":["
                + "{\"bucketId\":\"3p-5h\",\"resetTime\":\"2026-09-21T15:00:00Z\"},"
                + "{\"bucketId\":\"3p-weekly\",\"remainingFraction\":0.9}]}]}";
        UsageSnapshot snapshot = ProviderUsageParsers.antigravity(body, "Pro", NOW);
        check(snapshot.fiveHour.usedPercent == 25 && snapshot.weekly.usedPercent == 50,
                "Antigravity Gemini windows");
        UsageLimit third = snapshot.additionalLimits.get(0);
        check("Claude and other models".equals(third.name) && third.primary.usedPercent == 100
                        && third.secondary.usedPercent == 10 && third.limitReached,
                "Antigravity third-party bucket, with a missing fraction meaning used up");
        check("Ultra".equals(ProviderUsageParsers.antigravityPlan("{\"paidTier\":{\"id\":"
                + "\"g1-ultra-tier\"}}"))
                && "Ultra Lite".equals(ProviderUsageParsers.antigravityPlan("{\"paidTier\":{"
                + "\"id\":\"g1-ultra-lite-tier\"}}"))
                && "Free".equals(ProviderUsageParsers.antigravityPlan("{\"currentTier\":{"
                + "\"name\":\"Free\"}}")), "Antigravity plans");
    }

    private static void testCodeium() {
        String body = "{\"userStatus\":{\"planStatus\":{\"planInfo\":{\"planName\":\"Pro\"},"
                + "\"dailyQuotaRemainingPercent\":80,\"dailyQuotaResetAtUnix\":\""
                + (NOW_S + 3600) + "\",\"weeklyQuotaResetAtUnix\":\"" + (NOW_S + 86400)
                + "\",\"overageBalanceMicros\":\"2500000\"}}}";
        UsageSnapshot snapshot = ProviderUsageParsers.codeium(body, NOW);
        check("Pro".equals(snapshot.planType), "Windsurf plan");
        check(snapshot.fiveHour.usedPercent == 20 && snapshot.fiveHour.windowSeconds == 86_400L,
                "daily quota takes the short window");
        check(snapshot.weekly.usedPercent == 100,
                "a weekly reset with no percent means the quota is used up");
        check("$2.50".equals(snapshot.usageCredits.balance), "Windsurf overage balance");
    }

    private static void testAugment() {
        UsageSnapshot snapshot = ProviderUsageParsers.augment("{\"plan_name\":\"Developer\","
                + "\"usage_unit\":\"credits\",\"amount_remaining\":30000,"
                + "\"amount_included_per_cycle\":40000,\"billing_cycle_end_date_iso\":"
                + "\"2026-10-01T00:00:00Z\"}", NOW);
        check("Developer".equals(snapshot.planType) && snapshot.monthly.usedPercent == 25,
                "Augment monthly credits");
    }

    private static void testCline() {
        UsageSnapshot snapshot = ProviderUsageParsers.cline("{\"success\":true,\"data\":{"
                + "\"limits\":[{\"type\":\"five_hour\",\"percentUsed\":61,\"resetsAt\":"
                + "\"2026-09-21T17:00:00Z\"},{\"type\":\"weekly\",\"percentUsed\":20},"
                + "{\"type\":\"monthly\",\"percentUsed\":7}]}}", NOW);
        check(snapshot.fiveHour.usedPercent == 61 && snapshot.weekly.usedPercent == 20
                && snapshot.monthly.usedPercent == 7, "Cline windows");
    }

    private static void testOpenCodeGo() {
        UsageSnapshot snapshot = ProviderUsageParsers.opencodeGo("{\"usage\":{\"rolling\":{"
                + "\"usagePercent\":33,\"resetInSec\":600},\"weekly\":{\"usedPercent\":12,"
                + "\"resetAt\":\"2026-09-27T00:00:00Z\"},\"monthly\":{\"percent\":4}}}", NOW);
        check(snapshot.fiveHour.usedPercent == 33
                && snapshot.fiveHour.resetAtEpochSeconds == NOW_S + 600
                && snapshot.weekly.usedPercent == 12 && snapshot.monthly.usedPercent == 4,
                "OpenCode Go windows");
    }

    private static void testMiniMax() {
        long start = NOW - 3_600_000L;
        long end = start + 18_000_000L;
        UsageSnapshot snapshot = ProviderUsageParsers.minimax("{\"base_resp\":{\"status_code\""
                + ":0},\"model_remains\":[{\"model_name\":\"MiniMax-M2\",\"start_time\":" + start
                + ",\"end_time\":" + end + ",\"current_interval_total_count\":300,"
                + "\"current_interval_usage_count\":240,\"current_weekly_total_count\":3000,"
                + "\"current_weekly_usage_count\":2700}]}", NOW);
        check(snapshot.fiveHour != null && snapshot.fiveHour.usedPercent == 20
                && snapshot.fiveHour.windowSeconds == 18_000L,
                "MiniMax usage_count is what remains");
        check(snapshot.weekly.usedPercent == 10, "MiniMax weekly prompts");
        check(!ProviderUsageParsers.minimax("{\"base_resp\":{\"status_code\":1004}}", NOW)
                .hasDisplayableData(), "MiniMax errors carry no usage");
    }

    private static void testZai() {
        UsageSnapshot snapshot = ProviderUsageParsers.zai("{\"code\":200,\"success\":true,"
                + "\"data\":{\"limits\":[{\"type\":\"TOKENS_LIMIT\",\"unit\":3,\"number\":5,"
                + "\"percentage\":44,\"nextResetTime\":" + (NOW + 3_600_000L) + "},"
                + "{\"type\":\"TOKENS_LIMIT\",\"unit\":6,\"number\":1,\"percentage\":9},"
                + "{\"type\":\"TIME_LIMIT\",\"unit\":5,\"number\":1,\"usage\":100,"
                + "\"currentValue\":30}],\"planName\":\"pro\"}}", NOW);
        check("Pro".equals(snapshot.planType) && snapshot.fiveHour.usedPercent == 44
                && snapshot.fiveHour.resetAtEpochSeconds == NOW_S + 3600
                && snapshot.weekly.usedPercent == 9, "Z.ai token windows");
        check(snapshot.additionalLimits.size() == 1
                && snapshot.additionalLimits.get(0).primary.usedPercent == 30,
                "Z.ai web tool quota");
    }

    private static void testKiro() {
        UsageSnapshot snapshot = ProviderUsageParsers.kiro("{\"subscriptionInfo\":{"
                + "\"subscriptionTitle\":\"KIRO PRO\"},\"nextDateReset\":1791000000,"
                + "\"usageBreakdownList\":[{\"resourceType\":\"CREDIT\",\"currentUsage\":250,"
                + "\"usageLimit\":1000}]}", NOW);
        check("PRO".equals(snapshot.planType) && snapshot.monthly.usedPercent == 25
                && snapshot.monthly.resetAtEpochSeconds == 1_791_000_000L, "Kiro credits");
    }

    private static void testOpenRouter() {
        UsageSnapshot snapshot = ProviderUsageParsers.openrouter("{\"data\":{\"limit\":20,"
                + "\"usage\":30,\"usage_monthly\":5,\"limit_reset\":\"monthly\","
                + "\"is_free_tier\":false}}", "{\"data\":{\"total_credits\":50,"
                + "\"total_usage\":12.345}}", NOW);
        check(snapshot.monthly != null && snapshot.monthly.usedPercent == 25,
                "OpenRouter monthly key limit");
        check(snapshot.monthly.resetAtEpochSeconds
                == ProviderUsageParsers.epochSeconds("2026-10-01"), "resets on the 1st (UTC)");
        check("$37.66".equals(snapshot.usageCredits.balance), "OpenRouter credit balance");
        UsageSnapshot keyOnly = ProviderUsageParsers.openrouter("{\"data\":{\"limit\":null,"
                + "\"usage\":3}}", "", NOW);
        check(!keyOnly.hasDisplayableData(), "an unlimited key with no credits shows nothing");
    }

    private static void testAmp() {
        UsageSnapshot snapshot = ProviderUsageParsers.amp("{\"result\":{\"displayText\":"
                + "\"Amp Free: $7.50/$10 remaining (replenishes +$0.42/hour) - "
                + "Individual credits: $5.00 credits\"}}", NOW);
        check("Free".equals(snapshot.planType) && snapshot.fiveHour.usedPercent == 25,
                "Amp free tier");
        check("$5.00".equals(snapshot.usageCredits.balance), "Amp credits");
    }

    private static void testBalances() {
        check("$4.20".equals(ProviderUsageParsers.kilo("{\"balance\":4.2}", NOW)
                .usageCredits.balance), "Kilo balance");
        UsageSnapshot codebuff = ProviderUsageParsers.codebuff("{\"usage\":250,\"quota\":1000,"
                + "\"next_quota_reset\":\"2026-10-01T00:00:00Z\"}", NOW);
        check(codebuff.monthly.usedPercent == 25, "Codebuff credits");
    }

    private static void testKeyQuota() {
        UsageSnapshot synthetic = ProviderUsageParsers.keyQuota("{\"subscription\":{\"limit\":"
                + "135,\"requests\":20},\"rollingFiveHourLimit\":{\"limit\":200,\"used\":50,"
                + "\"resetAt\":\"2026-09-21T15:00:00Z\"},\"weeklyTokenLimit\":{\"limit\":1000,"
                + "\"remaining\":900}}", 5 * 3600, NOW);
        check(synthetic.fiveHour != null && synthetic.fiveHour.usedPercent == 25,
                "rolling 5-hour quota");
        check(synthetic.weekly != null && synthetic.weekly.usedPercent == 10, "weekly quota");
        UsageSnapshot warp = ProviderUsageParsers.keyQuota("{\"data\":{\"user\":{\"user\":{"
                + "\"requestLimitInfo\":{\"isUnlimited\":false,\"nextRefreshTime\":"
                + "\"2026-10-01T00:00:00Z\",\"requestLimit\":150,"
                + "\"requestsUsedSinceLastRefresh\":30}}}}}", ProviderUsageParsers.MONTH, NOW);
        check(warp.monthly != null && warp.monthly.usedPercent == 20, "Warp request limit");
        UsageSnapshot nothing = ProviderUsageParsers.keyQuota("{\"ok\":true}", 3600, NOW);
        check(!nothing.hasDisplayableData(), "no quota-like data -> empty");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError("Failed: " + label);
    }

    @SuppressWarnings("unused")
    private static List<UsageProvider> list(UsageProvider... providers) {
        return new ArrayList<>(Arrays.asList(providers));
    }

    private static void testAppSessionRules() {
        java.util.Map<String, UsageProvider> defaults =
                AppSessionRules.resolve(null, null);
        check(defaults.get("com.openai.chatgpt") == UsageProvider.CODEX, "ChatGPT shows Codex");
        check(defaults.get("com.anthropic.claude") == UsageProvider.CLAUDE_CODE,
                "Claude shows Claude Code");
        check(defaults.get("com.android.chrome") == null, "unmatched apps stay unmatched");

        java.util.Map<String, String> added = new java.util.HashMap<>();
        added.put("com.termux", "claude_code");
        added.put("com.openai.chatgpt", "cursor");
        java.util.Map<String, UsageProvider> custom = AppSessionRules.resolve(added,
                java.util.Collections.singleton("com.anthropic.claude"));
        check(custom.get("com.termux") == UsageProvider.CLAUDE_CODE, "added app maps");
        check(custom.get("com.openai.chatgpt") == UsageProvider.CURSOR, "added app re-points");
        check(custom.get("com.anthropic.claude") == null, "switched-off app is ignored");

        AppSessionRules.Decision enter = AppSessionRules.decide("com.anthropic.claude", true,
                defaults, null);
        check(enter.action == AppSessionRules.Action.ENTER
                && enter.provider == UsageProvider.CLAUDE_CODE, "opening Claude shows Claude");
        check(AppSessionRules.decide("com.anthropic.claude", true, defaults,
                "com.anthropic.claude").action == AppSessionRules.Action.STAY,
                "moving within the app keeps it");
        AppSessionRules.Decision swap = AppSessionRules.decide("com.openai.chatgpt", true,
                defaults, "com.anthropic.claude");
        check(swap.action == AppSessionRules.Action.ENTER
                && swap.provider == UsageProvider.CODEX, "switching apps switches provider");
        check(AppSessionRules.decide("com.sec.android.app.launcher", true, defaults,
                "com.anthropic.claude").action == AppSessionRules.Action.LEAVE,
                "going home leaves");
        check(AppSessionRules.decide("com.android.systemui", false, defaults,
                "com.anthropic.claude").action == AppSessionRules.Action.NONE,
                "pulling down the shade is not leaving");
        check(AppSessionRules.decide("com.android.chrome", true, defaults, null).action
                == AppSessionRules.Action.NONE, "unmatched app without a session does nothing");
        AppSessionRules.Decision warm = AppSessionRules.decide("com.anthropic.claude", false,
                defaults, null);
        check(warm.action == AppSessionRules.Action.ENTER
                && warm.provider == UsageProvider.CLAUDE_CODE,
                "reopening Claude from Recents shows Claude even without an activity name");
        check(AppSessionRules.decide("com.samsung.android.honeyboard", false, defaults,
                "com.openai.chatgpt").action == AppSessionRules.Action.NONE,
                "the keyboard opening is not leaving");
        check(AppSessionRules.knownApps(UsageProvider.CODEX).size() == 1, "Codex official apps");
    }

    private static void testNotificationRoutes() {
        UsageProvider[] all = UsageProvider.values();
        java.util.Set<Integer> codes = new java.util.HashSet<>();
        java.util.Set<String> uris = new java.util.HashSet<>();
        for (UsageProvider provider : all) {
            codes.add(NotificationRoutes.requestCode(8614, provider));
            String uri = NotificationRoutes.uri(provider, "now_bar");
            uris.add(uri);
            java.net.URI parsed = java.net.URI.create(uri);
            check(NotificationRoutes.requestedProvider(null, parsed.getScheme(),
                            parsed.getHost(), parsed.getPath()) == provider,
                    "live notification URI routes back to " + provider.id);
            check(NotificationRoutes.requestedProvider(provider.id, null, null, null) == provider,
                    "provider extra routes to " + provider.id);
        }
        check(codes.size() == all.length, "each provider gets its own PendingIntent request code");
        check(uris.size() == all.length, "each provider gets its own notification data URI");
        check(all.length < NotificationRoutes.STRIDE, "request-code stride fits every provider");
        check(NotificationRoutes.requestCode(8614, UsageProvider.CURSOR)
                        != NotificationRoutes.requestCode(8615, UsageProvider.CODEX),
                "surfaces don't collide");
        check(NotificationRoutes.requestedProvider("cursor", "codexmeter", "usage", "/claude_code")
                        == UsageProvider.CURSOR, "the explicit extra wins over the URI");
        check(NotificationRoutes.requestedProvider("bogus", "codexmeter", "usage", "/claude_code")
                        == UsageProvider.CLAUDE_CODE, "an unknown extra falls back to the URI");
        check(NotificationRoutes.requestedProvider(null, null, null, null) == null,
                "a launcher start names no provider and keeps the last tab");
        check(NotificationRoutes.requestedProvider(null, "codexmeter", "auth", "/cursor") == null,
                "OAuth callbacks are not provider routes");
        check(NotificationRoutes.requestedProvider(null, "codexmeter", "usage", "/nope") == null,
                "unknown provider URIs are ignored");
        check("codexmeter://usage/claude_code?from=alert_42".equals(
                        NotificationRoutes.uri(UsageProvider.CLAUDE_CODE, "alert_42")),
                "URI shape");
        System.out.println("Notification routes: per-provider URIs and request codes verified.");
    }

}
