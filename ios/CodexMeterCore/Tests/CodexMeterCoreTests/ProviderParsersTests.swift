import Foundation
import XCTest
@testable import CodexMeterCore

/// Mirrors Android's `ProviderParsersSelfTest`: catalog coverage, device-code parsing, and one
/// usage response per added provider. Samples are shapes, not real account data.
final class ProviderParsersTests: XCTestCase {
    private let nowSeconds: Int64 = 1_790_000_000 // 2026-09-21T13:33:20Z
    private var now: Date { Date(timeIntervalSince1970: TimeInterval(nowSeconds)) }

    private func resetSeconds(_ window: UsageWindow?) -> Int64 {
        Int64(window?.resetAt?.timeIntervalSince1970 ?? 0)
    }

    // MARK: Catalog

    func testCatalogCoversEveryProviderOnce() {
        let order = ProviderCatalog.recommendedOrder
        XCTAssertEqual(Set(order).count, order.count)
        XCTAssertEqual(Set(order), Set(UsageProvider.allCases))
        for provider in order {
            let entry = ProviderCatalog.entry(for: provider)
            XCTAssertEqual(entry.provider, provider)
            XCTAssertFalse(entry.meters.isEmpty)
            XCTAssertFalse(entry.signInSummary.isEmpty)
            if entry.signIn == .apiKey { XCTAssertTrue(entry.acceptsAPIKey, provider.rawValue) }
        }
        XCTAssertEqual(Array(order.prefix(7)), [.codex, .claudeCode, .cursor, .copilot, .kimi, .factory, .grok])
        // Every route is confirmed, so no provider carries the experimental note.
        for provider in order {
            XCTAssertFalse(ProviderCatalog.entry(for: provider).inferred, provider.rawValue)
        }
        XCTAssertEqual(ProviderCatalog.entry(for: .devin).signInSummary, "Devin session")
        XCTAssertEqual(ProviderCatalog.entry(for: .windsurf).signInSummary, "Windsurf token")
        XCTAssertEqual(ProviderCatalog.meterTitle(.cursor, "Weekly"), "Included usage · Weekly")
        XCTAssertEqual(ProviderCatalog.meterTitle(.copilot, "Weekly"), "Weekly")
        XCTAssertEqual(ProviderCatalog.entry(for: .cursor).meters, "Included usage and extra spend")
        XCTAssertFalse(ProviderCatalog.entry(for: .codex).usesSignInScreen)
        XCTAssertTrue(ProviderCatalog.entry(for: .copilot).usesSignInScreen)
    }

    func testRawValuesMatchAndroid() {
        XCTAssertEqual(UsageProvider.allCases.map(\.rawValue), [
            "codex", "claude_code", "cursor", "copilot", "kimi", "factory", "grok", "antigravity",
            "devin", "windsurf", "augment", "cline", "opencode_go", "minimax", "zai", "kiro", "amp",
            "openrouter", "kilo", "codebuff", "synthetic", "chutes", "v0", "warp"
        ])
        XCTAssertTrue(UsageProvider.copilot.isBuiltIn == false && UsageProvider.cursor.isBuiltIn)
    }

    func testPickerHidesConnectedAndSearches() {
        let picker = ProviderCatalog.pickerProviders(connected: [UsageProvider.codex, .cursor], query: "")
        XCTAssertFalse(picker.contains(.codex))
        XCTAssertEqual(picker.first, .claudeCode)
        // Antigravity needs a local callback that iOS can't host, so iOS leaves it out.
        XCTAssertFalse(picker.contains(.antigravity))
        XCTAssertEqual(picker.count, UsageProvider.allCases.count - 3)
        XCTAssertEqual(ProviderCatalog.pickerProviders(connected: [UsageProvider](), query: "github"), [.copilot])
        XCTAssertEqual(ProviderCatalog.pickerProviders(connected: [UsageProvider](), query: "  KIMI "), [.kimi])
        let cognition = ProviderCatalog.pickerProviders(connected: [UsageProvider](), query: "cognition")
        XCTAssertTrue(cognition.contains(.devin) && cognition.contains(.windsurf))
        XCTAssertTrue(ProviderCatalog.pickerProviders(connected: [UsageProvider](), query: "zzzz").isEmpty)
    }

    func testIOSSignInFallsBackToKeys() {
        let openRouter = ProviderCatalog.iOSEntry(for: .openrouter)
        XCTAssertEqual(openRouter.signIn, .apiKey)
        XCTAssertTrue(openRouter.acceptsAPIKey)
        XCTAssertFalse(ProviderCatalog.isAvailableOnIOS(.antigravity))
        XCTAssertTrue(ProviderCatalog.isAvailableOnIOS(.openrouter))
        XCTAssertEqual(ProviderCatalog.iOSEntry(for: .copilot), ProviderCatalog.entry(for: .copilot))
    }

    // MARK: JSON

    func testJSONKeepsKeyOrder() throws {
        let object = ProviderJSON.object(#"{"b":1,"a":{"z":true,"y":null},"c":[1,"x"],"d":"é\n"}"#)
        XCTAssertEqual(object.keys, ["b", "a", "c", "d"])
        XCTAssertEqual(object.object("a")?.keys, ["z", "y"])
        XCTAssertEqual(object.string("b"), "1")
        XCTAssertEqual(object.string("d"), "é\n")
        XCTAssertTrue(ProviderJSON.object("not json").isEmpty)
        XCTAssertTrue(ProviderJSON.object("").isEmpty)
        XCTAssertEqual(ProviderJSON.object(#"{"n":12.50}"#).int("n", default: 0), 12)
    }

    // MARK: Device sign-in

    func testDeviceAuth() throws {
        let github = DeviceAuth.parseDeviceCode(#"{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":899,"interval":5}"#)
        XCTAssertTrue(github.isValid)
        XCTAssertEqual(github.userCode, "ABCD-1234")
        XCTAssertTrue(github.openURL.hasSuffix("/login/device"))
        XCTAssertEqual(github.intervalSeconds, 5)
        XCTAssertEqual(github.expiresInSeconds, 899)
        let aws = DeviceAuth.parseDeviceCode(#"{"deviceCode":"d","userCode":"WXYZ-9876","verificationUri":"https://view.awsapps.com/start/#/device","verificationUriComplete":"https://view.awsapps.com/start/#/device?user_code=WXYZ-9876","expiresIn":600,"interval":1}"#)
        XCTAssertTrue(aws.isValid && aws.openURL.contains("user_code=WXYZ-9876"))
        XCTAssertEqual(DeviceAuth.parsePoll(status: 200, body: #"{"error":"authorization_pending"}"#).state, .pending)
        XCTAssertEqual(DeviceAuth.parsePoll(status: 400, body: #"{"error":"slow_down"}"#).state, .slowDown)
        XCTAssertEqual(DeviceAuth.parsePoll(status: 400, body: #"{"error":"access_denied"}"#).state, .denied)
        XCTAssertEqual(DeviceAuth.parsePoll(status: 400, body: #"{"error":"expired_token"}"#).state, .expired)
        XCTAssertEqual(DeviceAuth.parsePoll(status: 202, body: "").state, .pending)
        XCTAssertEqual(DeviceAuth.parsePoll(status: 410, body: "").state, .expired)
        let done = DeviceAuth.parsePoll(status: 200, body: #"{"access_token":"at","refresh_token":"rt","expires_in":3600,"user":{"email":"dev@example.com"}}"#)
        XCTAssertEqual(done.state, .done)
        XCTAssertEqual(done.tokens?.refreshToken, "rt")
        XCTAssertEqual(done.tokens?.email, "dev@example.com")
        XCTAssertEqual(done.tokens?.expiresInSeconds, 3600)
        XCTAssertEqual(DeviceAuth.parseTokens(#"{"token":"abc"}"#).accessToken, "abc")
        XCTAssertEqual(DeviceAuth.parseTokens(#"{"success":true,"data":{"accessToken":"cl","refreshToken":"r","userInfo":{"email":"a@b.co"}}}"#).accessToken, "cl")
        XCTAssertEqual(DeviceAuth.extractPasted("  tok\n", keys: ["access_token"]), "tok")
        XCTAssertEqual(DeviceAuth.extractPasted("https://example.com/cb?state=a&access_token=x%20y", keys: ["access_token"]), "x y")
        XCTAssertEqual(DeviceAuth.extractPasted(#"{"code":"c1","state":"s"}"#, keys: ["code"]), "c1")
        XCTAssertEqual(DeviceAuth.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        XCTAssertEqual(DeviceAuth.randomToken(bytes: 32).count, 43)
        XCTAssertEqual(DeviceAuth.absolute("https://opencode.ai", "/console/device?user_code=AB"),
                       "https://opencode.ai/console/device?user_code=AB")
        XCTAssertEqual(DeviceAuth.absolute("https://o", "https://x.ai/a"), "https://x.ai/a")
        XCTAssertEqual(DeviceAuth.absolute("https://o", ""), "")
    }

    // MARK: Timestamps

    func testTimestamps() {
        let iso = ProviderUsageParsers.epochSeconds("2026-10-01T00:00:00Z")
        XCTAssertGreaterThan(iso, 0)
        XCTAssertEqual(ProviderUsageParsers.epochSeconds(.number(Double(iso), "\(iso)")), iso)
        XCTAssertEqual(ProviderUsageParsers.epochSeconds(.number(Double(iso * 1000), "\(iso * 1000)")), iso)
        XCTAssertEqual(ProviderUsageParsers.epochSeconds("2026-10-01"), iso)
        XCTAssertEqual(ProviderUsageParsers.epochSeconds(String(iso * 1000)), iso)
        XCTAssertEqual(ProviderUsageParsers.epochSeconds("2026-10-01T02:00:00+02:00"), iso)
        XCTAssertEqual(ProviderUsageParsers.epochSeconds("2026-10-01T00:00:00.123456Z"), iso)
        XCTAssertEqual(ProviderUsageParsers.epochSeconds(nil), 0)
        XCTAssertEqual(ProviderUsageParsers.epochSeconds("soon"), 0)
        XCTAssertEqual(ProviderUsageParsers.durationName(ProviderUsageParsers.week), "Weekly")
        XCTAssertEqual(ProviderUsageParsers.durationName(5 * 3600), "5-hour")
        XCTAssertEqual(ProviderUsageParsers.durationName(86_400), "Daily")
        XCTAssertEqual(ProviderUsageParsers.durationName(ProviderUsageParsers.month), "Monthly")
    }

    // MARK: Providers

    func testCopilot() throws {
        let body = #"{"copilot_plan":"individual","quota_reset_date":"2026-10-01","quota_snapshots":{"premium_interactions":{"entitlement":300,"remaining":75,"percent_remaining":25.0,"unlimited":false},"chat":{"entitlement":0,"remaining":0,"percent_remaining":100,"unlimited":true},"completions":{"entitlement":0,"remaining":0,"unlimited":false}}}"#
        let snapshot = ProviderUsageParsers.copilot(body, now: now)
        XCTAssertEqual(snapshot.planType, "Pro")
        XCTAssertEqual(snapshot.monthly?.usedPercent, 75)
        XCTAssertEqual(resetSeconds(snapshot.monthly), ProviderUsageParsers.epochSeconds("2026-10-01"))
        XCTAssertTrue(snapshot.additionalLimits.isEmpty)
        let free = ProviderUsageParsers.copilot(#"{"access_type_sku":"free_limited_copilot","limited_user_reset_date":"2026-10-05","monthly_quotas":{"chat":50,"completions":2000},"limited_user_quotas":{"chat":40,"completions":500}}"#, now: now)
        XCTAssertEqual(free.planType, "Free")
        XCTAssertEqual(free.monthly?.usedPercent, 20)
        XCTAssertEqual(free.additionalLimits.count, 1)
        XCTAssertEqual(free.additionalLimits.first?.primary?.usedPercent, 75)
        XCTAssertEqual(ProviderUsageParsers.copilotPlan("individual_pro"), "Pro+")
        XCTAssertEqual(ProviderUsageParsers.copilotPlan("business"), "Business")
    }

    func testKimi() {
        let body = #"{"user":{"membership":{"level":"LEVEL_INTERMEDIATE"}},"usage":{"limit":"2048","used":"512","resetTime":"2026-09-25T00:00:00Z"},"limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},"detail":{"limit":"200","remaining":"150","resetTime":"2026-09-21T15:00:00Z"}}]}"#
        let snapshot = ProviderUsageParsers.kimi(body, now: now)
        XCTAssertEqual(snapshot.planType, "Allegretto")
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 25)
        XCTAssertEqual(snapshot.fiveHour?.windowSeconds, 18_000)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 25)
        let ratios = ProviderUsageParsers.kimi(#"{"usages":{"limit_5h":{"used_ratio":0.4},"limit_7d":{"used_ratio":0.1,"reset_time":1790500000}}}"#, now: now)
        XCTAssertEqual(ratios.fiveHour?.usedPercent, 40)
        XCTAssertEqual(ratios.weekly?.usedPercent, 10)
        XCTAssertEqual(resetSeconds(ratios.weekly), 1_790_500_000)
    }

    func testFactory() {
        let body = """
        {"usesTokenRateLimitsBilling":true,"limits":{"standard":{"fiveHour":{"usedPercent":12.5,"windowEnd":\(nowSeconds + 3600)},"weekly":{"usedPercent":40,"secondsRemaining":86400},"monthly":{"usedPercent":90,"windowEnd":\(nowSeconds - 60)}},"core":{"weekly":{"usedPercent":5,"windowEnd":"2026-09-28T00:00:00Z"}}},"extraUsageBalanceCents":1250}
        """
        let snapshot = ProviderUsageParsers.factory(body, plan: "Pro", now: now)
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 13)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 40)
        XCTAssertEqual(resetSeconds(snapshot.weekly), nowSeconds + 86_400)
        XCTAssertEqual(snapshot.monthly?.usedPercent, 0)
        XCTAssertEqual(snapshot.additionalLimits.map(\.name), ["Core weekly limit"])
        XCTAssertEqual(snapshot.usageCredits?.balance, "$12.50")
        let legacy = ProviderUsageParsers.factory(#"{"usage":{"startDate":"2026-09-01T00:00:00Z","endDate":"2026-10-01T00:00:00Z","standard":{"usedRatio":0.3}}}"#, plan: "", now: now)
        XCTAssertEqual(legacy.monthly?.usedPercent, 30)
        XCTAssertEqual(ProviderUsageParsers.factoryPlan(#"{"organization":{"subscription":{"orbSubscription":{"plan":{"name":"Factory Max"}}}}}"#), "Max")
    }

    func testGrok() {
        let body = #"{"config":{"creditUsagePercent":37.5,"currentPeriod":{"start":"2026-09-01T00:00:00Z","end":"2026-10-01T00:00:00Z"},"subscriptionTier":"SUBSCRIPTION_TIER_SUPER_GROK_PRO","productUsage":[{"product":"grok_code","usagePercent":20}],"prepaidBalance":{"val":"500"}}}"#
        let snapshot = ProviderUsageParsers.grok(body, now: now)
        XCTAssertEqual(snapshot.planType, "SuperGrok")
        XCTAssertEqual(snapshot.monthly?.usedPercent, 38)
        XCTAssertEqual(snapshot.additionalLimits.map(\.name), ["Grok Code"])
        XCTAssertEqual(snapshot.usageCredits?.balance, "$5.00")
    }

    func testAntigravity() throws {
        // retrieveUserQuotaSummary, as CodexBar's fixtures record it (same cases as Android).
        let body = #"{"response":{"groups":[{"displayName":"Gemini Models","buckets":[{"bucketId":"gemini-weekly","displayName":"Weekly Limit","remaining":{"remainingFraction":0.5},"resetTime":"2026-09-25T16:00:00Z"},{"bucketId":"gemini-5h","displayName":"Five Hour Limit","remaining":{"remainingFraction":0.75},"resetTime":"2026-09-21T16:00:00Z"}]},{"displayName":"Claude and GPT models","buckets":[{"bucketId":"3p-weekly","remaining":{"case":"remainingFraction","value":0.9}},{"bucketId":"3p-5h","remaining":{"remainingFraction":0},"resetTime":"2026-09-21T15:00:00Z"}]}]}}"#
        let snapshot = ProviderUsageParsers.antigravity(body, plan: "Pro", now: now)
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 25)
        XCTAssertEqual(snapshot.fiveHour?.windowSeconds, 5 * 3600)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 50)
        let third = try XCTUnwrap(snapshot.additionalLimits.first)
        XCTAssertEqual(third.name, "Claude and GPT models")
        XCTAssertEqual(third.primary?.usedPercent, 100)
        XCTAssertEqual(third.secondary?.usedPercent, 10)
        XCTAssertTrue(third.limitReached)
        // A bucket with no fraction, or a disabled one, is unknown rather than used up.
        XCTAssertFalse(ProviderUsageParsers.antigravity(#"{"groups":[{"displayName":"Gemini Models","buckets":[{"bucketId":"gemini-weekly","resetTime":"2026-09-25T16:00:00Z"},{"bucketId":"gemini-5h","disabled":true,"remaining":{"remainingFraction":1}}]}]}"#, plan: "", now: now).hasDisplayableData)
        let models = ProviderUsageParsers.antigravity(#"{"models":{"gemini-3-pro-high":{"displayName":"Gemini 3 Pro","quotaInfo":{"remainingFraction":0.4,"resetTime":"2026-09-21T16:00:00Z"}},"gemini-3-flash":{"quotaInfo":{"remainingFraction":0.8}},"claude-sonnet-4-6":{"quotaInfo":{"remainingFraction":1}}}}"#, plan: "", now: now)
        XCTAssertEqual(models.fiveHour?.usedPercent, 60)
        XCTAssertEqual(models.additionalLimits.first?.primary?.usedPercent, 0)
        XCTAssertEqual(ProviderUsageParsers.antigravity(#"{"buckets":[{"modelId":"gemini-2.5-pro","remainingFraction":0.3}]}"#, plan: "", now: now).fiveHour?.usedPercent, 70)
        XCTAssertTrue(ProviderUsageParsers.antigravityClientRejected(#"{"ineligibleTiers":[{"reasonCode":"GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT"}]}"#))
        XCTAssertFalse(ProviderUsageParsers.antigravityClientRejected(#"{"currentTier":{"id":"free-tier"},"cloudaicompanionProject":"p-1"}"#))
        XCTAssertEqual(ProviderUsageParsers.antigravityProject(#"{"response":{"cloudaicompanionProject":{"id":"p-1"}}}"#), "p-1")
        XCTAssertEqual(ProviderUsageParsers.antigravityOnboardTier(#"{"allowedTiers":[{"id":"free-tier"},{"id":"standard-tier","isDefault":true}]}"#), "standard-tier")
        XCTAssertEqual(ProviderUsageParsers.antigravityPlan(#"{"paidTier":{"id":"g1-ultra-tier"}}"#), "Ultra")
        XCTAssertEqual(ProviderUsageParsers.antigravityPlan(#"{"paidTier":{"id":"g1-ultra-lite-tier"}}"#), "Ultra Lite")
        XCTAssertEqual(ProviderUsageParsers.antigravityPlan(#"{"currentTier":{"name":"Free"}}"#), "Free")
    }

    func testCodeium() {
        let body = """
        {"userStatus":{"planStatus":{"planInfo":{"planName":"Pro"},"dailyQuotaRemainingPercent":80,"dailyQuotaResetAtUnix":"\(nowSeconds + 3600)","weeklyQuotaResetAtUnix":"\(nowSeconds + 86400)","overageBalanceMicros":"2500000"}}}
        """
        let snapshot = ProviderUsageParsers.codeium(body, now: now)
        XCTAssertEqual(snapshot.planType, "Pro")
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 20)
        XCTAssertEqual(snapshot.fiveHour?.windowSeconds, 86_400)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 100)
        XCTAssertEqual(snapshot.usageCredits?.balance, "$2.50")
    }

    func testAugmentClineOpenCodeGo() {
        let augment = ProviderUsageParsers.augment(#"{"plan_name":"Developer","usage_unit":"credits","amount_remaining":30000,"amount_included_per_cycle":40000,"billing_cycle_end_date_iso":"2026-10-01T00:00:00Z"}"#, now: now)
        XCTAssertEqual(augment.planType, "Developer")
        XCTAssertEqual(augment.monthly?.usedPercent, 25)
        let cline = ProviderUsageParsers.cline(#"{"success":true,"data":{"limits":[{"type":"five_hour","percentUsed":61,"resetsAt":"2026-09-21T17:00:00Z"},{"type":"weekly","percentUsed":20},{"type":"monthly","percentUsed":7}]}}"#, now: now)
        XCTAssertEqual(cline.fiveHour?.usedPercent, 61)
        XCTAssertEqual(cline.weekly?.usedPercent, 20)
        XCTAssertEqual(cline.monthly?.usedPercent, 7)
        let go = ProviderUsageParsers.opencodeGo(#"{"usage":{"rolling":{"usagePercent":33,"resetInSec":600},"weekly":{"usedPercent":12,"resetAt":"2026-09-27T00:00:00Z"},"monthly":{"percent":4}}}"#, now: now)
        XCTAssertEqual(go.fiveHour?.usedPercent, 33)
        XCTAssertEqual(resetSeconds(go.fiveHour), nowSeconds + 600)
        XCTAssertEqual(go.weekly?.usedPercent, 12)
        XCTAssertEqual(go.monthly?.usedPercent, 4)
    }

    func testMiniMax() {
        let start = nowSeconds * 1000 - 3_600_000
        let end = start + 18_000_000
        let snapshot = ProviderUsageParsers.minimax("""
        {"base_resp":{"status_code":0},"model_remains":[{"model_name":"MiniMax-M2","start_time":\(start),"end_time":\(end),"current_interval_total_count":300,"current_interval_usage_count":240,"current_weekly_total_count":3000,"current_weekly_usage_count":2700}]}
        """, now: now)
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 20)
        XCTAssertEqual(snapshot.fiveHour?.windowSeconds, 18_000)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 10)
        XCTAssertFalse(ProviderUsageParsers.minimax(#"{"base_resp":{"status_code":1004}}"#, now: now).hasDisplayableData)
    }

    func testZai() {
        let snapshot = ProviderUsageParsers.zai("""
        {"code":200,"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":44,"nextResetTime":\(nowSeconds * 1000 + 3_600_000)},{"type":"TOKENS_LIMIT","unit":6,"number":1,"percentage":9},{"type":"TIME_LIMIT","unit":5,"number":1,"usage":100,"currentValue":30}],"planName":"pro"}}
        """, now: now)
        XCTAssertEqual(snapshot.planType, "Pro")
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 44)
        XCTAssertEqual(resetSeconds(snapshot.fiveHour), nowSeconds + 3600)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 9)
        XCTAssertEqual(snapshot.additionalLimits.count, 1)
        XCTAssertEqual(snapshot.additionalLimits.first?.primary?.usedPercent, 30)
    }

    func testKiroOpenRouterAmp() {
        let kiro = ProviderUsageParsers.kiro(#"{"subscriptionInfo":{"subscriptionTitle":"KIRO PRO"},"nextDateReset":1791000000,"usageBreakdownList":[{"resourceType":"CREDIT","currentUsage":250,"usageLimit":1000}]}"#, now: now)
        XCTAssertEqual(kiro.planType, "PRO")
        XCTAssertEqual(kiro.monthly?.usedPercent, 25)
        XCTAssertEqual(resetSeconds(kiro.monthly), 1_791_000_000)
        // Credits lead even when listed second.
        let mixed = ProviderUsageParsers.kiro(#"{"nextDateReset":1791000000,"usageBreakdownList":[{"resourceType":"AGENTIC_REQUEST","currentUsageWithPrecision":5,"usageLimitWithPrecision":50},{"resourceType":"CREDIT","currentUsageWithPrecision":100.5,"usageLimitWithPrecision":201}]}"#, now: now)
        XCTAssertEqual(mixed.monthly?.usedPercent, 50)
        XCTAssertEqual(mixed.additionalLimits.count, 1)

        let router = ProviderUsageParsers.openrouter(
            keyBody: #"{"data":{"limit":20,"usage":30,"usage_monthly":5,"limit_reset":"monthly","is_free_tier":false}}"#,
            creditsBody: #"{"data":{"total_credits":50,"total_usage":12.345}}"#,
            now: now
        )
        XCTAssertEqual(router.monthly?.usedPercent, 25)
        XCTAssertEqual(resetSeconds(router.monthly), ProviderUsageParsers.epochSeconds("2026-10-01"))
        XCTAssertEqual(router.usageCredits?.balance, "$37.66")
        XCTAssertFalse(ProviderUsageParsers.openrouter(keyBody: #"{"data":{"limit":null,"usage":3}}"#, creditsBody: "", now: now).hasDisplayableData)

        let amp = ProviderUsageParsers.amp(#"{"result":{"displayText":"Amp Free: $7.50/$10 remaining (replenishes +$0.42/hour) - Individual credits: $5.00 credits"}}"#, now: now)
        XCTAssertEqual(amp.planType, "Free")
        XCTAssertEqual(amp.fiveHour?.usedPercent, 25)
        XCTAssertEqual(amp.usageCredits?.balance, "$5.00")
        // 2026-09-21T13:33:20Z is 09:33 in New York, so Amp Free refills at 20:00 EDT that day.
        XCTAssertEqual(resetSeconds(amp.fiveHour), ProviderUsageParsers.epochSeconds("2026-09-22T00:00:00Z"))
        let percent = ProviderUsageParsers.amp(#"{"ok":true,"result":{"displayText":"Signed in as user@example.com\nAmp Free: 61% remaining today (resets daily)\nIndividual credits: $12.50 remaining"}}"#, now: now)
        XCTAssertEqual(percent.fiveHour?.usedPercent, 39)
        XCTAssertEqual(percent.usageCredits?.balance, "$12.50")
        let tier = ProviderUsageParsers.amp(#"{"ok":true,"result":{"displayText":"Amp Megawatt Tier: agent usage $18.57 of $20 remaining (93%), orb usage 732.8h of 750h a1.small orb hours remaining (98%) - period 2026-09-13 to 2026-10-13, resets upon renewal in 27 days"}}"#, now: now)
        XCTAssertEqual(tier.planType, "Megawatt")
        XCTAssertEqual(tier.monthly?.usedPercent, 7)
        XCTAssertEqual(resetSeconds(tier.monthly), ProviderUsageParsers.epochSeconds("2026-10-13"))
        XCTAssertEqual(tier.additionalLimits.count, 1)
        XCTAssertEqual(tier.additionalLimits.first?.name, "Orb hours")
        XCTAssertEqual(tier.additionalLimits.first?.primary?.usedPercent, 2)
    }

    func testBalances() {
        XCTAssertEqual(ProviderUsageParsers.kilo(#"{"balance":4.2}"#, now: now).usageCredits?.balance, "$4.20")
        XCTAssertEqual(ProviderUsageParsers.codebuff(#"{"usage":250,"quota":1000,"next_quota_reset":"2026-10-01T00:00:00Z"}"#, now: now).monthly?.usedPercent, 25)
    }

    func testSynthetic() {
        let current = ProviderUsageParsers.synthetic(#"{"subscription":{"limit":135,"requests":20},"planWeeklyLimit":{"nextRegenAt":"2026-09-21T15:00:00Z","percentRemaining":0.6,"nextRegenPercent":2},"search":{"hourly":{"limit":250,"requests":25}}}"#, now: now)
        // planWeeklyLimit is in percentage points, and the retired counter is ignored.
        XCTAssertEqual(current.weekly?.usedPercent, 99)
        XCTAssertNil(current.fiveHour)
        XCTAssertEqual(resetSeconds(current.weekly), ProviderUsageParsers.epochSeconds("2026-09-21T15:00:00Z"))
        XCTAssertEqual(current.additionalLimits.count, 1)
        XCTAssertEqual(current.additionalLimits.first?.primary?.usedPercent, 10)
        let legacy = ProviderUsageParsers.synthetic(#"{"data":{"rollingFiveHourLimit":{"percentRemaining":0.75},"weeklyTokenLimit":{"percentRemaining":0.98}}}"#, now: now)
        XCTAssertEqual(legacy.fiveHour?.usedPercent, 25)
        XCTAssertEqual(legacy.fiveHour?.windowSeconds, 5 * 3600)
        XCTAssertEqual(legacy.weekly?.usedPercent, 2)
        let counter = ProviderUsageParsers.synthetic(#"{"subscription":{"limit":135,"requests":27,"renewsAt":"2026-09-21T15:00:00Z"}}"#, now: now)
        XCTAssertEqual(counter.fiveHour?.usedPercent, 20)
        XCTAssertFalse(ProviderUsageParsers.synthetic("{}", now: now).hasDisplayableData)
    }

    func testChutes() {
        let pro = ProviderUsageParsers.chutes(#"{"subscription":true,"custom":false,"monthly_price":20,"anchor_date":"2026-09-01T00:00:00","four_hour":{"usage":2.5,"cap":8.333,"remaining":5.833,"reset_at":"2026-09-21T16:00:00+00:00"},"monthly":{"usage":25,"cap":100,"remaining":75,"reset_at":"2026-10-01T00:00:00+00:00"}}"#, now: now)
        XCTAssertEqual(pro.planType, "$20/month")
        XCTAssertEqual(pro.fiveHour?.usedPercent, 30)
        XCTAssertEqual(pro.fiveHour?.windowSeconds, 4 * 3600)
        XCTAssertEqual(resetSeconds(pro.fiveHour), ProviderUsageParsers.epochSeconds("2026-09-21T16:00:00Z"))
        XCTAssertEqual(pro.monthly?.usedPercent, 25)
        let custom = ProviderUsageParsers.chutes(#"{"subscription":true,"custom":true,"monthly_price":50,"four_hour":{"usage":1,"cap":4,"remaining":3,"reset_at":"2026-09-21T16:00:00+00:00"},"monthly":{"uncapped":true}}"#, now: now)
        XCTAssertEqual(custom.planType, "Custom")
        XCTAssertNil(custom.monthly)
        XCTAssertEqual(custom.fiveHour?.usedPercent, 25)
        XCTAssertFalse(ProviderUsageParsers.chutes(#"{"subscription":false}"#, now: now).hasDisplayableData)
    }

    func testV0() {
        let cycleEnd: Int64 = 1_790_726_400_000 // 2026-09-30T00:00:00Z, in milliseconds
        let cycleStart = cycleEnd - 30 * 86_400_000
        let soon = nowSeconds * 1000 + 3_600_000
        let token = ProviderUsageParsers.v0(
            billing: #"{"billingType":"token","data":{"balance":{"total":20,"remaining":15},"billingCycle":{"start":\#(cycleStart),"end":\#(cycleEnd)},"onDemand":{"balance":4.5}}}"#,
            plan: #"{"object":"plan","plan":"premium"}"#,
            rateLimits: #"{"limit":1000,"remaining":900,"reset":\#(soon)}"#,
            now: now
        )
        XCTAssertEqual(token.planType, "Premium")
        XCTAssertEqual(token.monthly?.usedPercent, 25)
        XCTAssertEqual(resetSeconds(token.monthly), cycleEnd / 1000)
        XCTAssertEqual(token.usageCredits?.balance, "$4.50")
        XCTAssertNil(token.fiveHour)
        XCTAssertEqual(token.additionalLimits.count, 1)
        XCTAssertEqual(token.additionalLimits.first?.primary?.usedPercent, 10)
        let free = ProviderUsageParsers.v0(
            billing: #"{"billingType":"legacy","data":{"limit":5,"remaining":1,"reset":\#(cycleEnd)}}"#,
            plan: "",
            rateLimits: #"{"limit":100,"remaining":100,"dailyLimit":{"limit":10,"remaining":4,"reset":\#(soon),"isWithinGracePeriod":false}}"#,
            now: now
        )
        XCTAssertEqual(free.monthly?.usedPercent, 80)
        XCTAssertEqual(free.fiveHour?.usedPercent, 60)
        XCTAssertEqual(free.fiveHour?.windowSeconds, 86_400)
        let planOnly = ProviderUsageParsers.v0(
            billing: "{}",
            plan: #"{"plan":"free","balance":{"remaining":3,"total":5},"billingCycle":{"start":\#(cycleStart),"end":\#(cycleEnd)}}"#,
            rateLimits: "",
            now: now
        )
        XCTAssertEqual(planOnly.monthly?.usedPercent, 40)
    }

    func testWarp() {
        let warp = ProviderUsageParsers.warp(#"{"data":{"user":{"__typename":"UserOutput","user":{"requestLimitInfo":{"isUnlimited":false,"nextRefreshTime":"2026-10-01T00:00:00Z","requestLimit":1500,"requestsUsedSinceLastRefresh":300},"bonusGrants":[{"requestCreditsGranted":100,"requestCreditsRemaining":40}],"workspaces":[{"bonusGrantsInfo":{"grants":[{"requestCreditsGranted":50,"requestCreditsRemaining":10}]}}]}}}}"#, now: now)
        XCTAssertEqual(warp.monthly?.usedPercent, 20)
        XCTAssertEqual(resetSeconds(warp.monthly), ProviderUsageParsers.epochSeconds("2026-10-01"))
        XCTAssertEqual(warp.usageCredits?.balance, "50 add-on credits")
        let unlimited = ProviderUsageParsers.warp(#"{"data":{"user":{"user":{"requestLimitInfo":{"isUnlimited":true}}}}}"#, now: now)
        XCTAssertEqual(unlimited.planType, "Unlimited")
        XCTAssertNil(unlimited.monthly)
        XCTAssertEqual(unlimited.usageCredits?.unlimited, true)
        XCTAssertEqual(ProviderUsageParsers.warpError(#"{"errors":[{"message":"Not authorized"}],"data":null}"#), "Not authorized")
        XCTAssertFalse(ProviderUsageParsers.warp(#"{"data":null}"#, now: now).hasDisplayableData)
    }

    func testDevin() {
        let devin = ProviderUsageParsers.devin(#"{"plan_name":"core","daily_percentage":30,"daily_reset_at":"2026-09-22T00:00:00Z","weekly_percentage":45,"weekly_reset_at":\#(nowSeconds + 3 * 86_400),"overage_balance_cents":1250}"#, now: now)
        XCTAssertEqual(devin.planType, "Core")
        XCTAssertEqual(devin.fiveHour?.usedPercent, 30)
        XCTAssertEqual(devin.fiveHour?.windowSeconds, 86_400)
        XCTAssertEqual(devin.weekly?.usedPercent, 45)
        XCTAssertEqual(devin.usageCredits?.balance, "$12.50")
        XCTAssertNil(ProviderUsageParsers.devin(#"{"hide_daily_quota":true,"daily_percentage":10,"weekly_percentage":20}"#, now: now).fiveHour)
        let curl = """
        curl 'https://app.devin.ai/api/org-a1b2c3d4e5/billing/quota/usage' \\
          -H 'accept: application/json' \\
          -H 'authorization: Bearer auth1_abcdefghijklmnopqrstuvwxyz012345' \\
          -H 'x-cog-org-id: org-a1b2c3d4e5'
        """
        let parts = DeviceAuth.parseDevinRequest(curl)
        XCTAssertEqual(parts.token, "auth1_abcdefghijklmnopqrstuvwxyz012345")
        XCTAssertEqual(parts.org, "org-a1b2c3d4e5")
        let bare = DeviceAuth.parseDevinRequest("auth1_tokenvalue1234 org_ZYXWVU98")
        XCTAssertEqual(bare.token, "auth1_tokenvalue1234")
        XCTAssertEqual(bare.org, "org_ZYXWVU98")
        XCTAssertEqual(DeviceAuth.parseDevinRequest("hello").token, "")
    }

    func testBuiltInsAndWidgetFiles() {
        XCTAssertEqual(UsageProvider.builtIns, [.codex, .claudeCode, .cursor])
        XCTAssertTrue(UsageProvider.builtIns.allSatisfy(\.isBuiltIn))
        XCTAssertFalse(UsageProvider.copilot.isBuiltIn)
        // Codex keeps the original widget file so existing widgets keep working.
        XCTAssertEqual(SharedWidgetSnapshot.fileName(for: .codex), "widget-snapshot.json")
        XCTAssertEqual(SharedWidgetSnapshot.fileName(for: .opencodeGo), "widget-snapshot-opencode_go.json")
        XCTAssertEqual(Set(UsageProvider.allCases.map(SharedWidgetSnapshot.fileName(for:))).count, UsageProvider.allCases.count)
    }
}
