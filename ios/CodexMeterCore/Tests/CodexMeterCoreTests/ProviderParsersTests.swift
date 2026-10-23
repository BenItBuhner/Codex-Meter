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
        for provider in [UsageProvider.grok, .devin, .minimax, .cline, .opencodeGo, .kiro] {
            XCTAssertTrue(ProviderCatalog.entry(for: provider).inferred, provider.rawValue)
        }
        XCTAssertFalse(ProviderCatalog.entry(for: .copilot).inferred)
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
        let body = #"{"groups":[{"name":"Gemini","buckets":[{"bucketId":"gemini-5h","remainingFraction":0.75,"resetTime":"2026-09-21T16:00:00Z"},{"bucketId":"gemini-weekly","remainingFraction":0.5}]},{"name":"Third party","buckets":[{"bucketId":"3p-5h","resetTime":"2026-09-21T15:00:00Z"},{"bucketId":"3p-weekly","remainingFraction":0.9}]}]}"#
        let snapshot = ProviderUsageParsers.antigravity(body, plan: "Pro", now: now)
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 25)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 50)
        let third = try XCTUnwrap(snapshot.additionalLimits.first)
        XCTAssertEqual(third.name, "Claude and other models")
        XCTAssertEqual(third.primary?.usedPercent, 100)
        XCTAssertEqual(third.secondary?.usedPercent, 10)
        XCTAssertTrue(third.limitReached)
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
    }

    func testBalancesAndKeyQuota() {
        XCTAssertEqual(ProviderUsageParsers.kilo(#"{"balance":4.2}"#, now: now).usageCredits?.balance, "$4.20")
        XCTAssertEqual(ProviderUsageParsers.codebuff(#"{"usage":250,"quota":1000,"next_quota_reset":"2026-10-01T00:00:00Z"}"#, now: now).monthly?.usedPercent, 25)
        let synthetic = ProviderUsageParsers.keyQuota(#"{"subscription":{"limit":135,"requests":20},"rollingFiveHourLimit":{"limit":200,"used":50,"resetAt":"2026-09-21T15:00:00Z"},"weeklyTokenLimit":{"limit":1000,"remaining":900}}"#, defaultSeconds: 5 * 3600, now: now)
        XCTAssertEqual(synthetic.fiveHour?.usedPercent, 25)
        XCTAssertEqual(synthetic.weekly?.usedPercent, 10)
        let warp = ProviderUsageParsers.keyQuota(#"{"data":{"user":{"user":{"requestLimitInfo":{"isUnlimited":false,"nextRefreshTime":"2026-10-01T00:00:00Z","requestLimit":150,"requestsUsedSinceLastRefresh":30}}}}}"#, defaultSeconds: ProviderUsageParsers.month, now: now)
        XCTAssertEqual(warp.monthly?.usedPercent, 20)
        XCTAssertFalse(ProviderUsageParsers.keyQuota(#"{"ok":true}"#, defaultSeconds: 3600, now: now).hasDisplayableData)
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
