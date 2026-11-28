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
        testCatalog();
        testClaudeOnlyLiveMonitor();
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
        testOpenCodeFree();
        testMiniMax();
        testZai();
        testKiro();
        testOpenRouter();
        testAmp();
        testBalances();
        testSynthetic();
        testChutes();
        testV0();
        testWarp();
        testDevin();
        testRecordedReplies();
        testAppSessionRules();
        testAppSessionTransitions();
        System.out.println("Provider catalog, tabs, device sign-in, and 25 usage parsers verified, plus OpenCode Free's reset.");
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
        for (UsageProvider provider : UsageProvider.values()) {
            check(!ProviderCatalog.get(provider).inferred,
                    provider.id + "'s usage route is confirmed, so it is not experimental");
        }
        check("Devin session".equals(ProviderCatalog.get(UsageProvider.DEVIN).signInSummary)
                        && "Windsurf token".equals(
                                ProviderCatalog.get(UsageProvider.WINDSURF).signInSummary),
                "Devin signs in with its own session, not a Windsurf token");
        check("Included usage · Weekly".equals(ProviderCatalog.meterTitle(UsageProvider.CURSOR,
                        "Weekly")) && "Weekly".equals(ProviderCatalog.meterTitle(
                                UsageProvider.CODEX, "Weekly"))
                        && "Included usage and extra spend".equals(
                                ProviderCatalog.get(UsageProvider.CURSOR).meters),
                "Cursor meter copy");
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
        check(ProviderTabs.ownTabs(25) == 3 && ProviderTabs.inMore(25) == 22,
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
        check("https://opencode.ai/console/device?user_code=AB".equals(DeviceAuth.absolute(
                        "https://opencode.ai", "/console/device?user_code=AB"))
                        && "https://x.ai/a".equals(DeviceAuth.absolute("https://o", "https://x.ai/a"))
                        && DeviceAuth.absolute("https://o", "").isEmpty(),
                "site-relative sign-in pages are opened on the provider's site");
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
        UsageSnapshot over = ProviderUsageParsers.kimi("{\"usages\":{\"limit_5h\":{"
                + "\"used_ratio\":1.2},\"limit_7d\":{\"used_ratio\":0.01}}}", NOW);
        check(over.fiveHour.usedPercent == 100 && over.weekly.usedPercent == 1,
                "a ratio over 1 is over the limit, not 1%");
        check(ProviderUsageParsers.epochSeconds("2026-12-01T15:30:00")
                        == ProviderUsageParsers.epochSeconds("2026-12-01T15:30:00Z")
                        && ProviderUsageParsers.epochSeconds("2026-12-01T15:30:00.123456")
                        == ProviderUsageParsers.epochSeconds("2026-12-01T15:30:00Z")
                        && ProviderUsageParsers.epochSeconds("2026-12-01")
                        == ProviderUsageParsers.epochSeconds("2026-12-01T00:00:00Z"),
                "a time with no zone keeps its time of day as UTC");
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
        // Grok's percentages are points: 1 is 1%, not a fraction meaning 100%.
        UsageSnapshot light = ProviderUsageParsers.grok("{\"config\":{\"usagePercent\":1,"
                + "\"productUsage\":[{\"product\":\"grok_code\",\"usagePercent\":0.4}]}}", NOW);
        check(light.monthly.usedPercent == 1 && !light.limitReached
                && light.additionalLimits.get(0).primary.usedPercent == 0,
                "a small Grok percentage stays small");
    }

    private static void testAntigravity() {
        // retrieveUserQuotaSummary, as CodexBar's fixtures record it.
        String body = "{\"response\":{\"groups\":[{\"displayName\":\"Gemini Models\","
                + "\"buckets\":[{\"bucketId\":\"gemini-weekly\",\"displayName\":"
                + "\"Weekly Limit\",\"remaining\":{\"remainingFraction\":0.5},"
                + "\"resetTime\":\"2026-09-25T16:00:00Z\"},{\"bucketId\":\"gemini-5h\","
                + "\"displayName\":\"Five Hour Limit\",\"remaining\":{\"remainingFraction\":"
                + "0.75},\"resetTime\":\"2026-09-21T16:00:00Z\"}]},{\"displayName\":"
                + "\"Claude and GPT models\",\"buckets\":[{\"bucketId\":\"3p-weekly\","
                + "\"remaining\":{\"case\":\"remainingFraction\",\"value\":0.9}},"
                + "{\"bucketId\":\"3p-5h\",\"remaining\":{\"remainingFraction\":0},"
                + "\"resetTime\":\"2026-09-21T15:00:00Z\"}]}]}}";
        UsageSnapshot snapshot = ProviderUsageParsers.antigravity(body, "Pro", NOW);
        check(snapshot.fiveHour.usedPercent == 25 && snapshot.weekly.usedPercent == 50
                        && snapshot.fiveHour.windowSeconds == 5 * 3600L,
                "Antigravity Gemini windows from the nested remaining fraction");
        UsageLimit third = snapshot.additionalLimits.get(0);
        check("Claude and GPT models".equals(third.name) && third.primary.usedPercent == 100
                        && third.secondary.usedPercent == 10 && third.limitReached,
                "Antigravity Claude and GPT limit, including the oneof value shape");
        UsageSnapshot unknown = ProviderUsageParsers.antigravity("{\"groups\":[{"
                + "\"displayName\":\"Gemini Models\",\"buckets\":[{\"bucketId\":"
                + "\"gemini-weekly\",\"resetTime\":\"2026-09-25T16:00:00Z\"},{\"bucketId\":"
                + "\"gemini-5h\",\"disabled\":true,\"remaining\":{\"remainingFraction\":1}}]}]}",
                "", NOW);
        check(!unknown.hasDisplayableData(),
                "a bucket with no fraction or a disabled one is unknown, not used up");
        UsageSnapshot models = ProviderUsageParsers.antigravity("{\"models\":{"
                + "\"gemini-3-pro-high\":{\"displayName\":\"Gemini 3 Pro\",\"quotaInfo\":{"
                + "\"remainingFraction\":0.4,\"resetTime\":\"2026-09-21T16:00:00Z\"}},"
                + "\"gemini-3-flash\":{\"quotaInfo\":{\"remainingFraction\":0.8}},"
                + "\"claude-sonnet-4-6\":{\"quotaInfo\":{\"remainingFraction\":1}}}}", "", NOW);
        check(models.fiveHour.usedPercent == 60
                        && models.additionalLimits.get(0).primary.usedPercent == 0,
                "Antigravity per-model quotas: each family shows its most used model");
        UsageSnapshot buckets = ProviderUsageParsers.antigravity("{\"buckets\":[{\"modelId\":"
                + "\"gemini-2.5-pro\",\"remainingFraction\":0.3}]}", "", NOW);
        check(buckets.fiveHour.usedPercent == 70, "Antigravity retrieveUserQuota buckets");
        check(ProviderUsageParsers.antigravityClientRejected("{\"ineligibleTiers\":[{"
                        + "\"reasonCode\":\"GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT\"}]}")
                        && !ProviderUsageParsers.antigravityClientRejected("{\"currentTier\":{"
                        + "\"id\":\"free-tier\"},\"cloudaicompanionProject\":\"p-1\"}"),
                "Antigravity spots a sign-in from the wrong OAuth client");
        check("p-1".equals(ProviderUsageParsers.antigravityProject("{\"response\":{"
                        + "\"cloudaicompanionProject\":{\"id\":\"p-1\"}}}"))
                        && "standard-tier".equals(ProviderUsageParsers.antigravityOnboardTier(
                        "{\"allowedTiers\":[{\"id\":\"free-tier\"},{\"id\":\"standard-tier\","
                        + "\"isDefault\":true}]}")),
                "Antigravity project from onboardUser and the default onboarding tier");
        check("Ultra".equals(ProviderUsageParsers.antigravityPlan("{\"paidTier\":{\"id\":"
                + "\"g1-ultra-tier\"}}"))
                && "Ultra Lite".equals(ProviderUsageParsers.antigravityPlan("{\"paidTier\":{"
                + "\"id\":\"g1-ultra-lite-tier\"}}"))
                && "Free".equals(ProviderUsageParsers.antigravityPlan("{\"currentTier\":{"
                + "\"name\":\"Free\"}}")), "Antigravity plans");
    }

    /** Replies as CodexBar's tests record them, which these parsers used to miss. */
    private static void testRecordedReplies() {
        UsageSnapshot minimax = ProviderUsageParsers.minimax("{\"base_resp\":{\"status_code\":"
                + "\"0\"},\"data\":{\"current_subscribe_title\":\"Max\",\"model_remains\":[{"
                + "\"current_interval_total_count\":\"15000\",\"current_interval_usage_count\":"
                + "\"14989\",\"start_time\":0,\"end_time\":0,\"remains_time\":8941292}]}}", NOW);
        check("Max".equals(minimax.planType) && minimax.fiveHour != null
                        && minimax.fiveHour.usedPercent == 0,
                "MiniMax token plan reply wrapped in data, usage count meaning remaining");
        UsageSnapshot tokenPlan = ProviderUsageParsers.minimax("{\"model_remains\":[{"
                + "\"model_name\":\"general\",\"current_interval_total_count\":0,"
                + "\"current_interval_usage_count\":0,\"current_interval_remaining_percent\":100,"
                + "\"current_weekly_total_count\":0,\"current_weekly_usage_count\":0,"
                + "\"current_weekly_remaining_percent\":70}],\"base_resp\":{\"status_code\":0}}",
                NOW);
        check(tokenPlan.fiveHour.usedPercent == 0 && tokenPlan.weekly.usedPercent == 30,
                "MiniMax token plan weekly limit from its remaining percent");
        UsageSnapshot copilot = ProviderUsageParsers.copilot("{\"copilot_plan\":\"business\","
                + "\"token_based_billing\":true,\"quota_snapshots\":{\"premium_interactions\":{"
                + "\"entitlement\":0,\"remaining\":0,\"percent_remaining\":100,\"credits_used\":31}}}",
                NOW);
        check(copilot.hasDisplayableData() && copilot.usageCredits.unlimited
                        && "Business".equals(copilot.planType),
                "Copilot Business token-based billing shows as unlimited, not an error");
        UsageSnapshot kilo = ProviderUsageParsers.kiloTrpc("[{\"result\":{\"data\":{"
                + "\"creditBlocks\":[{\"balance_mUsd\":19000000,\"amount_mUsd\":19000000}],"
                + "\"totalBalance_mUsd\":19000000}}},{\"result\":{\"data\":{\"subscription\":{"
                + "\"tier\":\"tier_19\",\"currentPeriodUsageUsd\":7.125,"
                + "\"currentPeriodBaseCreditsUsd\":19.0,\"currentPeriodBonusCreditsUsd\":9.5,"
                + "\"nextBillingAt\":\"2026-10-28T04:00:00.000Z\"}}}}]", NOW);
        check("$19.00".equals(kilo.usageCredits.balance) && "Starter".equals(kilo.planType)
                        && kilo.monthly.usedPercent == 25,
                "Kilo tRPC batch: credit blocks and the Kilo Pass month");
        check("$0.00".equals(ProviderUsageParsers.kiloTrpc("[{\"result\":{\"data\":{"
                        + "\"creditBlocks\":[],\"totalBalance_mUsd\":0}}},{\"result\":{\"data\":{"
                        + "\"subscription\":null}}}]", NOW).usageCredits.balance)
                        && ProviderUsageParsers.kiloUnauthorized("[{\"error\":{\"json\":{"
                        + "\"data\":{\"code\":\"UNAUTHORIZED\"}}}}]"),
                "Kilo zero balance shows $0.00, and a tRPC auth error is spotted");
        UsageSnapshot codebuff = ProviderUsageParsers.codebuffSubscription(
                ProviderUsageParsers.codebuff("{\"usage\":1250,\"quota\":5000}", NOW),
                "{\"subscription\":{\"status\":\"active\",\"tier\":\"pro\"},\"rateLimit\":{"
                        + "\"weeklyUsed\":2100,\"weeklyLimit\":7000,\"weeklyResetsAt\":"
                        + "\"2026-09-25T00:00:00Z\"}}", NOW);
        check("Pro".equals(codebuff.planType) && codebuff.weekly.usedPercent == 30
                        && codebuff.monthly.usedPercent == 25,
                "Codebuff weekly limit and tier from the subscription call");
        UsageSnapshot grok = ProviderUsageParsers.grok("{\"config\":{\"onDemandCap\":{\"val\":"
                + "1000.0},\"onDemandUsed\":{\"val\":250.5}}}", NOW);
        check(grok.hasDisplayableData(), "Grok pay-as-you-go spend against the on-demand cap");
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
        UsageSnapshot light = ProviderUsageParsers.opencodeGo("{\"usage\":{\"rolling\":{"
                + "\"utilization\":1,\"resetInSec\":600},\"weekly\":{\"usagePercent\":0.5}}}", NOW);
        check(light.fiveHour.usedPercent == 1 && light.weekly.usedPercent == 1
                && !light.limitReached, "OpenCode Go reads 1 as 1%, not a fraction");
    }

    private static void testOpenCodeFree() {
        // OpenCode's gateway resets free usage at 00:00 UTC (retry-after = seconds to midnight).
        long day = 86_400_000L;
        long midnight = 20_000L * day;
        check(OpenCodeFree.nextResetMillis(midnight - 1L) == midnight,
                "OpenCode Free resets at the coming midnight UTC");
        check(OpenCodeFree.nextResetMillis(midnight) == midnight + day,
                "at midnight itself the next reset is a day away");
        check(OpenCodeFree.nextResetMillis(midnight + 4856_000L) == midnight + day,
                "mid-day resets at the next midnight");
        UsageSnapshot snapshot = OpenCodeFree.snapshot(NOW);
        check(snapshot.fiveHour == null && snapshot.weekly == null && snapshot.monthly == null
                && snapshot.additionalLimits.isEmpty() && "Free".equals(snapshot.planType),
                "OpenCode Free saves no made-up meters");
        check(!UsageProvider.OPENCODE_FREE.hasMeters() && UsageProvider.OPENCODE_GO.hasMeters(),
                "only OpenCode Free lacks meters");
        java.util.List<UsageProvider> order = ProviderCatalog.recommendedOrder();
        check(order.indexOf(UsageProvider.OPENCODE_FREE)
                        == order.indexOf(UsageProvider.OPENCODE_GO) + 1,
                "OpenCode Free sits right under OpenCode Go");
        check(ProviderCatalog.get(UsageProvider.OPENCODE_FREE).signIn
                == ProviderCatalog.SignIn.NONE, "OpenCode Free needs no sign-in");
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
        UsageSnapshot mixed = ProviderUsageParsers.kiro("{\"nextDateReset\":1791000000,"
                + "\"usageBreakdownList\":[{\"resourceType\":\"AGENTIC_REQUEST\","
                + "\"currentUsageWithPrecision\":5,\"usageLimitWithPrecision\":50},{"
                + "\"resourceType\":\"CREDIT\",\"currentUsageWithPrecision\":100.5,"
                + "\"usageLimitWithPrecision\":201}]}", NOW);
        check(mixed.monthly.usedPercent == 50 && mixed.additionalLimits.size() == 1,
                "Kiro credits lead even when listed second");
        UsageSnapshot twoCredits = ProviderUsageParsers.kiro("{\"nextDateReset\":1791000000,"
                + "\"usageBreakdownList\":[{\"resourceType\":\"CREDIT\",\"currentUsage\":10,"
                + "\"usageLimit\":100},{\"resourceType\":\"CREDIT\",\"currentUsage\":90,"
                + "\"usageLimit\":100}]}", NOW);
        check(twoCredits.monthly.usedPercent == 10,
                "with two credit entries the first is the monthly meter, as on iOS and Windows");
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
        // 2026-09-21T13:33:20Z is 09:33 in New York, so Amp Free refills at 20:00 EDT that day.
        check(snapshot.fiveHour.resetAtEpochSeconds
                        == ProviderUsageParsers.epochSeconds("2026-09-22T00:00:00Z"),
                "Amp Free resets at 8 PM New York time");
        UsageSnapshot percent = ProviderUsageParsers.amp("{\"ok\":true,\"result\":{"
                + "\"displayText\":\"Signed in as user@example.com\\nAmp Free: 61% remaining "
                + "today (resets daily)\\nIndividual credits: $12.50 remaining\"}}", NOW);
        check(percent.fiveHour.usedPercent == 39, "Amp Free as a percentage");
        check("$12.50".equals(percent.usageCredits.balance), "Amp individual credits");
        UsageSnapshot tier = ProviderUsageParsers.amp("{\"ok\":true,\"result\":{"
                + "\"displayText\":\"Amp Megawatt Tier: agent usage $18.57 of $20 remaining "
                + "(93%), orb usage 732.8h of 750h a1.small orb hours remaining (98%) - period "
                + "2026-09-13 to 2026-10-13, resets upon renewal in 27 days\"}}", NOW);
        check("Megawatt".equals(tier.planType) && tier.monthly.usedPercent == 7
                        && tier.monthly.resetAtEpochSeconds
                        == ProviderUsageParsers.epochSeconds("2026-10-13"),
                "Amp tier agent dollars for the billing period");
        check(tier.additionalLimits.size() == 1
                        && "Orb hours".equals(tier.additionalLimits.get(0).name)
                        && tier.additionalLimits.get(0).primary.usedPercent == 2,
                "Amp orb hours");
    }

    private static void testBalances() {
        check("$4.20".equals(ProviderUsageParsers.kilo("{\"balance\":4.2}", NOW)
                .usageCredits.balance), "Kilo balance");
        UsageSnapshot codebuff = ProviderUsageParsers.codebuff("{\"usage\":250,\"quota\":1000,"
                + "\"next_quota_reset\":\"2026-10-01T00:00:00Z\"}", NOW);
        check(codebuff.monthly.usedPercent == 25, "Codebuff credits");
    }

    private static void testSynthetic() {
        UsageSnapshot current = ProviderUsageParsers.synthetic("{\"subscription\":{\"limit\":"
                + "135,\"requests\":20},\"planWeeklyLimit\":{\"nextRegenAt\":"
                + "\"2026-09-21T15:00:00Z\",\"percentRemaining\":0.6,\"nextRegenPercent\":2},"
                + "\"search\":{\"hourly\":{\"limit\":250,\"requests\":25}}}", NOW);
        check(current.weekly.usedPercent == 99 && current.fiveHour == null,
                "planWeeklyLimit is in percentage points, and the retired counter is ignored");
        check(current.weekly.resetAtEpochSeconds
                == ProviderUsageParsers.epochSeconds("2026-09-21T15:00:00Z"), "next regen time");
        check(current.additionalLimits.size() == 1
                        && current.additionalLimits.get(0).primary.usedPercent == 10,
                "hourly search allowance");
        UsageSnapshot legacy = ProviderUsageParsers.synthetic("{\"data\":{"
                + "\"rollingFiveHourLimit\":{\"percentRemaining\":0.75},\"weeklyTokenLimit\":{"
                + "\"percentRemaining\":0.98}}}", NOW);
        check(legacy.fiveHour.usedPercent == 25 && legacy.fiveHour.windowSeconds == 5 * 3600L
                && legacy.weekly.usedPercent == 2, "older plans report fractions");
        UsageSnapshot counter = ProviderUsageParsers.synthetic("{\"subscription\":{\"limit\":"
                + "135,\"requests\":27,\"renewsAt\":\"2026-09-21T15:00:00Z\"}}", NOW);
        check(counter.fiveHour.usedPercent == 20, "the old request counter as a fallback");
        UsageSnapshot over = ProviderUsageParsers.synthetic("{\"rollingFiveHourLimit\":{"
                + "\"percentUsed\":1.2},\"weeklyTokenLimit\":{\"percentRemaining\":1}}", NOW);
        check(over.fiveHour.usedPercent == 100 && over.limitReached
                && over.weekly.usedPercent == 0, "older fractions over 1 are over the limit");
        UsageSnapshot lightSearch = ProviderUsageParsers.synthetic("{\"planWeeklyLimit\":{"
                + "\"percentRemaining\":99},\"search\":{\"hourly\":{\"percentUsed\":1}}}", NOW);
        check(lightSearch.weekly.usedPercent == 1
                && lightSearch.additionalLimits.get(0).primary.usedPercent == 1,
                "current Synthetic percentages are points, so 1 is 1%");
        check(!ProviderUsageParsers.synthetic("{}", NOW).hasDisplayableData(), "empty reply");
    }

    private static void testChutes() {
        UsageSnapshot pro = ProviderUsageParsers.chutes("{\"subscription\":true,\"custom\":false,"
                + "\"monthly_price\":20,\"anchor_date\":\"2026-09-01T00:00:00\",\"four_hour\":{"
                + "\"usage\":2.5,\"cap\":8.333,\"remaining\":5.833,\"reset_at\":"
                + "\"2026-09-21T16:00:00+00:00\"},\"monthly\":{\"usage\":25,\"cap\":100,"
                + "\"remaining\":75,\"reset_at\":\"2026-10-01T00:00:00+00:00\"}}", NOW);
        check("$20/month".equals(pro.planType), "Chutes plan by price");
        check(pro.fiveHour.usedPercent == 30 && pro.fiveHour.windowSeconds == 4 * 3600L
                        && pro.fiveHour.resetAtEpochSeconds
                        == ProviderUsageParsers.epochSeconds("2026-09-21T16:00:00Z"),
                "rolling 4-hour cap");
        check(pro.monthly.usedPercent == 25, "monthly cap");
        UsageSnapshot custom = ProviderUsageParsers.chutes("{\"subscription\":true,\"custom\":true,"
                + "\"monthly_price\":50,\"four_hour\":{\"usage\":1,\"cap\":4,\"remaining\":3,"
                + "\"reset_at\":\"2026-09-21T16:00:00+00:00\"},\"monthly\":{\"uncapped\":true}}",
                NOW);
        check("Custom".equals(custom.planType) && custom.monthly == null
                && custom.fiveHour.usedPercent == 25, "custom plans only cap 4 hours");
        check(!ProviderUsageParsers.chutes("{\"subscription\":false}", NOW)
                .hasDisplayableData(), "no subscription, nothing to show");
    }

    private static void testV0() {
        long cycleEnd = 1_790_726_400_000L; // 2026-09-30T00:00:00Z, in milliseconds
        UsageSnapshot token = ProviderUsageParsers.v0("{\"billingType\":\"token\",\"data\":{"
                + "\"balance\":{\"total\":20,\"remaining\":15},\"billingCycle\":{\"start\":"
                + (cycleEnd - 30L * 86_400_000L) + ",\"end\":" + cycleEnd + "},\"onDemand\":{"
                + "\"balance\":4.5}}}", "{\"object\":\"plan\",\"plan\":\"premium\"}",
                "{\"limit\":1000,\"remaining\":900,\"reset\":" + (NOW + 3_600_000L) + "}", NOW);
        check("Premium".equals(token.planType) && token.monthly.usedPercent == 25
                && token.monthly.resetAtEpochSeconds == cycleEnd / 1000L, "v0 credits");
        check("$4.50".equals(token.usageCredits.balance), "v0 on-demand balance");
        check(token.fiveHour == null && token.additionalLimits.size() == 1
                && token.additionalLimits.get(0).primary.usedPercent == 10, "v0 API requests");
        UsageSnapshot free = ProviderUsageParsers.v0("{\"billingType\":\"legacy\",\"data\":{"
                + "\"limit\":5,\"remaining\":1,\"reset\":" + cycleEnd + "}}", "",
                "{\"limit\":100,\"remaining\":100,\"dailyLimit\":{\"limit\":10,\"remaining\":4,"
                + "\"reset\":" + (NOW + 3_600_000L) + ",\"isWithinGracePeriod\":false}}", NOW);
        check(free.monthly.usedPercent == 80 && free.fiveHour.usedPercent == 60
                && free.fiveHour.windowSeconds == 86_400L, "legacy allowance and daily messages");
        UsageSnapshot planOnly = ProviderUsageParsers.v0("{}", "{\"plan\":\"free\",\"balance\":{"
                + "\"remaining\":3,\"total\":5},\"billingCycle\":{\"start\":"
                + (cycleEnd - 30L * 86_400_000L) + ",\"end\":" + cycleEnd + "}}", "", NOW);
        check(planOnly.monthly.usedPercent == 40, "the plan's balance when billing is empty");
    }

    private static void testWarp() {
        UsageSnapshot warp = ProviderUsageParsers.warp("{\"data\":{\"user\":{\"__typename\":"
                + "\"UserOutput\",\"user\":{\"requestLimitInfo\":{\"isUnlimited\":false,"
                + "\"nextRefreshTime\":\"2026-10-01T00:00:00Z\",\"requestLimit\":1500,"
                + "\"requestsUsedSinceLastRefresh\":300},\"bonusGrants\":[{"
                + "\"requestCreditsGranted\":100,\"requestCreditsRemaining\":40}],\"workspaces\":"
                + "[{\"bonusGrantsInfo\":{\"grants\":[{\"requestCreditsGranted\":50,"
                + "\"requestCreditsRemaining\":10}]}}]}}}}", NOW);
        check(warp.monthly.usedPercent == 20 && warp.monthly.resetAtEpochSeconds
                == ProviderUsageParsers.epochSeconds("2026-10-01"), "Warp monthly credits");
        check("50 add-on credits".equals(warp.usageCredits.balance), "Warp add-on credits");
        UsageSnapshot unlimited = ProviderUsageParsers.warp("{\"data\":{\"user\":{\"user\":{"
                + "\"requestLimitInfo\":{\"isUnlimited\":true}}}}}", NOW);
        check("Unlimited".equals(unlimited.planType) && unlimited.monthly == null
                && unlimited.usageCredits.unlimited, "unlimited Warp plan");
        check("Not authorized".equals(ProviderUsageParsers.warpError("{\"errors\":[{\"message\":"
                + "\"Not authorized\"}],\"data\":null}")) && !ProviderUsageParsers.warp(
                        "{\"data\":null}", NOW).hasDisplayableData(), "GraphQL errors");
    }

    private static void testDevin() {
        UsageSnapshot devin = ProviderUsageParsers.devin("{\"plan_name\":\"core\","
                + "\"daily_percentage\":30,\"daily_reset_at\":\"2026-09-22T00:00:00Z\","
                + "\"weekly_percentage\":45,\"weekly_reset_at\":" + (NOW_S + 3 * 86_400L)
                + ",\"overage_balance_cents\":1250}", NOW);
        check("Core".equals(devin.planType) && devin.fiveHour.usedPercent == 30
                        && devin.fiveHour.windowSeconds == 86_400L && devin.weekly.usedPercent == 45,
                "Devin daily and weekly quota");
        check("$12.50".equals(devin.usageCredits.balance), "Devin extra usage balance");
        check(ProviderUsageParsers.devin("{\"hide_daily_quota\":true,\"daily_percentage\":10,"
                + "\"weekly_percentage\":20}", NOW).fiveHour == null, "hidden daily quota");
        String curl = "curl 'https://app.devin.ai/api/org-a1b2c3d4e5/billing/quota/usage' \\\n"
                + "  -H 'accept: application/json' \\\n"
                + "  -H 'authorization: Bearer auth1_abcdefghijklmnopqrstuvwxyz012345' \\\n"
                + "  -H 'x-cog-org-id: org-a1b2c3d4e5'";
        String[] parts = DeviceAuth.parseDevinRequest(curl);
        check("auth1_abcdefghijklmnopqrstuvwxyz012345".equals(parts[0])
                && "org-a1b2c3d4e5".equals(parts[1]), "Devin request copied as cURL");
        String[] bare = DeviceAuth.parseDevinRequest("auth1_tokenvalue1234 org_ZYXWVU98");
        check("auth1_tokenvalue1234".equals(bare[0]) && "org_ZYXWVU98".equals(bare[1]),
                "Devin token and organization pasted on their own");
        check(DeviceAuth.parseDevinRequest("hello")[0].isEmpty(), "nothing to read");
    }

    /**
     * Tester report, 12 Nov: with only Claude Code signed in (no Codex), turning on the Now Bar
     * said "Refresh your signed-in usage before starting the monitor", because the monitor only
     * read Codex usage. Fixed in 2.9.0-alpha.24; this keeps it fixed, including Claude's idle
     * 5-hour session, which has no reset time of its own.
     */
    private static void testClaudeOnlyLiveMonitor() throws Exception {
        long now = 2_000_000_000_000L;
        String weeklyReset = java.time.Instant.ofEpochMilli(now + 3L * 24 * 3600 * 1000).toString();
        UsageSnapshot idle = ClaudeUsageParser.parse("{\"five_hour\":{\"utilization\":null,"
                + "\"resets_at\":null},\"seven_day\":{\"utilization\":12,\"resets_at\":\""
                + weeklyReset + "\"}}", "Pro", now);
        java.util.Map<UsageProvider, UsageSnapshot> saved = new java.util.EnumMap<>(UsageProvider.class);
        saved.put(UsageProvider.CLAUDE_CODE, idle);
        saved.put(UsageProvider.CURSOR, null);
        java.util.function.Predicate<UsageProvider> canMonitor = provider ->
                saved.containsKey(provider) && MonitorProviderPicker.hasWindows(saved.get(provider));
        java.util.List<UsageProvider> order = java.util.Arrays.asList(UsageProvider.CURSOR,
                UsageProvider.CLAUDE_CODE);
        check(!canMonitor.test(UsageProvider.CODEX), "Codex is not signed in");
        check(MonitorProviderPicker.pick(UsageProvider.CURSOR, order, canMonitor)
                == UsageProvider.CLAUDE_CODE, "Claude-only account monitors Claude Code");
        check(MonitorProviderPicker.pick(UsageProvider.CLAUDE_CODE, order, canMonitor)
                == UsageProvider.CLAUDE_CODE, "the selected Claude Code tab is monitored");
        long until = MonitorProviderPicker.until(idle, now);
        check(until == java.time.Instant.parse(weeklyReset).toEpochMilli(),
                "an idle 5-hour session runs the monitor until the weekly reset");
        saved.put(UsageProvider.CODEX, idle);
        check(MonitorProviderPicker.pick(UsageProvider.CLAUDE_CODE, order, canMonitor)
                == UsageProvider.CLAUDE_CODE, "the selected tab leads even when Codex has limits");
        check(MonitorProviderPicker.pick(UsageProvider.CURSOR, java.util.Arrays.asList(
                        UsageProvider.CODEX, UsageProvider.CLAUDE_CODE), canMonitor)
                == UsageProvider.CODEX, "otherwise the first provider in tab order with limits");
        check(MonitorProviderPicker.pick(null, null, provider -> false) == UsageProvider.CODEX,
                "nothing to monitor and no tab: Codex (and the start is refused)");
        check(MonitorProviderPicker.until(null, now) == 0L, "no snapshot, no monitor");
        System.out.println("Live monitor: a Claude-only account starts on Claude Code, idle 5-hour included.");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError("Failed: " + label);
    }

    @SuppressWarnings("unused")
    private static List<UsageProvider> list(UsageProvider... providers) {
        return new ArrayList<>(Arrays.asList(providers));
    }

    private static void testAppSessionTransitions() {
        AppSessionRules.Session session = new AppSessionRules.Session();
        long grace = AppSessionRules.Session.LEAVE_GRACE_MILLIS;
        check(session.startLeaving(0) == -1 && session.graceElapsed(10_000) == null,
                "an idle session has nothing to leave");
        session.enter("com.anthropic.claude");
        check(session.phase() == AppSessionRules.Session.Phase.SHOWING, "entering shows the app");
        long due = session.startLeaving(1_000);
        check(due == 1_000 + grace && session.phase() == AppSessionRules.Session.Phase.LEAVING,
                "leaving counts down the grace period");
        check("com.anthropic.claude".equals(session.app()), "the app is still tracked while leaving");
        check(session.startLeaving(1_300) == -1 && session.leaveAt() == due,
                "a second leave keeps the first deadline");
        check(session.graceElapsed(due - 1) == null, "an early check does nothing");
        session.stay();
        check(session.phase() == AppSessionRules.Session.Phase.SHOWING, "coming back calls the leave off");
        check(session.graceElapsed(due) == null, "a stale check after a return does nothing");
        due = session.startLeaving(5_000);
        check("com.anthropic.claude".equals(session.graceElapsed(due))
                        && session.phase() == AppSessionRules.Session.Phase.IDLE && session.app() == null,
                "the grace period ending hides the app");
        session.enter("com.openai.chatgpt");
        session.startLeaving(6_000);
        session.enter("com.anthropic.claude");
        check(session.phase() == AppSessionRules.Session.Phase.SHOWING
                        && "com.anthropic.claude".equals(session.app()),
                "switching straight to another matched app cancels the leave");
        check("com.anthropic.claude".equals(session.end()) && session.end() == null,
                "ending is immediate and idempotent");
        System.out.println("Live monitor sessions: enter, grace-period leave, return and switch transitions.");
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
}
