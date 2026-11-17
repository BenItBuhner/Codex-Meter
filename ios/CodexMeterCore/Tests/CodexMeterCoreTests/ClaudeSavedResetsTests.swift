import Foundation
import Testing
@testable import CodexMeterCore

@Suite("Claude saved resets")
struct ClaudeSavedResetsTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func body(_ grants: String, next: String = "g1", eligible: Bool = true, cooldown: String = "null") -> Data {
        Data("""
        {"five_hour":{"utilization":100,"resets_at":"2027-01-15T10:00:00Z"},
         "cedar_ember":{"eligible":\(eligible),"at_limit":true,"next_grant_id":"\(next)",
          "cooldown_until":\(cooldown),"grants":[\(grants)]}}
        """.utf8)
    }

    private let fullGrant = """
    {"id":"g1","label":"Full reset","resets_total":3,"resets_left":2,"ends_at":"2027-02-01T00:00:00Z",
     "clears":["five_hour","seven_day"],"paused":false,"usable_now":true,"use_requires_limit":true,"type":"full"}
    """

    private let modelGrant = """
    {"id":"g2","label":"Fable reset","resets_total":1,"resets_left":1,"ends_at":"2027-01-20T00:00:00Z",
     "clears":["seven_day_fable"],"usable_now":false,"type":"weekly_scoped_fable","unavailable_reason":"not_limited"}
    """

    @Test func parsesGrantsAndInventory() throws {
        let resets = try #require(ClaudeSavedResets.fromUsageBody(body(fullGrant + "," + modelGrant), fetchedAt: now))
        #expect(resets.eligible)
        #expect(resets.grants.count == 2)
        let full = resets.grants[0]
        #expect(full.title == "Full reset")
        #expect(full.clearsFiveHour && full.clearsWeekly)
        #expect(full.clearsLabel == "5-hour and weekly limits")
        let fable = resets.grants[1]
        #expect(fable.scope == "Fable")
        #expect(fable.title == "Fable reset")
        #expect(!fable.clearsWeekly)
        #expect(fable.heldText == "Works once you hit your Fable limit")
        #expect(resets.availableCount(at: now) == 3)
        #expect(resets.inventoryLabel(at: now) == "2 Full resets and 1 Fable reset")
        #expect(resets.resetCreditsSnapshot(at: now).availableCount == 3)
        #expect(resets.usableGrant(at: now)?.id == "g1")
        #expect(resets.usableGrant(for: "5-hour", at: now)?.id == "g1")
    }

    @Test func missingOrMalformedBlockMeansNone() {
        #expect(ClaudeSavedResets.fromUsageBody(Data("{\"five_hour\":{}}".utf8), fetchedAt: now) == nil)
        #expect(ClaudeSavedResets.fromUsageBody(Data("not json".utf8), fetchedAt: now) == nil)
        let bad = ClaudeSavedResets.fromUsageBody(body("{\"id\":\"BAD ID!\",\"resets_left\":1}"), fetchedAt: now)
        #expect(bad?.grants.isEmpty == true)
    }

    @Test func choicesGroupAndExplainHeldResets() throws {
        let resets = try #require(ClaudeSavedResets.fromUsageBody(body(fullGrant + "," + modelGrant), fetchedAt: now))
        let choices = resets.choices(at: now)
        #expect(choices.map(\.title) == ["Full reset", "Fable reset"])
        #expect(choices[0].usableNow && choices[0].count == 2 && choices[0].countedTitle == "2 Full resets")
        #expect(!choices[1].usableNow && choices[1].held == "Works once you hit your Fable limit")
        let after = resets.afterUse("g1")
        #expect(after.availableCount(at: now) == 2)
        #expect(after.usableGrant(at: now) == nil)
    }

    @Test func ineligibleAndCooldownHoldEverything() throws {
        let off = try #require(ClaudeSavedResets.fromUsageBody(body(fullGrant, eligible: false), fetchedAt: now))
        #expect(off.usableGrant(at: now) == nil)
        #expect(off.choices(at: now).first?.held == "Anthropic isn't offering resets on this account right now")
        let cooling = try #require(ClaudeSavedResets.fromUsageBody(
            body(fullGrant, cooldown: "\"2027-12-01T00:00:00Z\""), fetchedAt: now))
        #expect(cooling.unavailableMessage(at: now) == "Available again after a short cooldown")
    }

    @Test func claimBodyAndURLValidateInput() throws {
        let data = try #require(ClaudeSavedResets.claimBody(grantID: "g1", requestID: "abc-123"))
        let json = try #require(try JSONSerialization.jsonObject(with: data) as? [String: String])
        #expect(json == ["program": "cedar_ember", "grant_id": "g1", "request_id": "abc-123"])
        #expect(ClaudeSavedResets.claimBody(grantID: "../x", requestID: "a") == nil)
        #expect(ClaudeSavedResets.resetURL(organization: "8f2e-11aa")?.absoluteString
            == "https://api.anthropic.com/api/organizations/8f2e-11aa/reset_rate_limits")
        #expect(ClaudeSavedResets.resetURL(organization: "a/b") == nil)
        #expect(ClaudeSavedResets.needsClaudeCodeSignIn(grantedScope: "user:profile user:inference"))
        #expect(!ClaudeSavedResets.needsClaudeCodeSignIn(grantedScope: ""))
        #expect(!ClaudeSavedResets.needsClaudeCodeSignIn(grantedScope: "user:inference user:sessions:claude_code"))
    }

    @Test func claimResultParsing() {
        let ok = ClaudeResetClaimResult.parse(Data("{\"result\":\"reset\",\"cleared\":[\"five_hour\",\"seven_day\"],\"resets_left\":1}".utf8))
        #expect(ok.applied && ok.resetsLeft == 1)
        #expect(ok.userMessage == "Reset applied to 2 usage windows.")
        #expect(ClaudeResetClaimResult.parse(Data("{\"result\":\"not_limited\"}".utf8)).result == .notLimited)
        #expect(ClaudeResetClaimResult.parse(Data("{\"result\":\"weird\"}".utf8)).result == .unavailable)
        #expect(ClaudeResetClaimResult.parse(Data("x".utf8)).retryable)
    }

    @Test func autoUseSpendsExpiringResetOnce() throws {
        let soon = """
        {"id":"g3","resets_total":1,"resets_left":1,"ends_at":"\(ISO8601DateFormatter().string(from: now.addingTimeInterval(1_800)))",
         "clears":["five_hour"],"usable_now":true,"type":"session"}
        """
        let resets = try #require(ClaudeSavedResets.fromUsageBody(body(soon, next: "g3"), fetchedAt: now))
        let first = ClaudeResetAutoUse.evaluate(status: resets, usage: nil, expiryEnabled: true, leadMinutes: 60,
                                                lowUsageEnabled: false, threshold: 1, lastExpiryKey: nil,
                                                lastLowUsageToken: nil, now: now)
        #expect(first.outcome == .fireExpiring && first.grant?.id == "g3")
        let again = ClaudeResetAutoUse.evaluate(status: resets, usage: nil, expiryEnabled: true, leadMinutes: 60,
                                                lowUsageEnabled: false, threshold: 1, lastExpiryKey: first.attemptKey,
                                                lastLowUsageToken: nil, now: now)
        #expect(again.outcome == .skipAlreadyTried)
        let stale = ClaudeResetAutoUse.evaluate(status: resets, usage: nil, expiryEnabled: true, leadMinutes: 60,
                                                lowUsageEnabled: false, threshold: 1, lastExpiryKey: nil,
                                                lastLowUsageToken: nil, now: now.addingTimeInterval(600))
        #expect(stale.outcome == .wait)
    }
}

@Suite("Loopback sign-in")
struct LoopbackSignInTests {
    @Test func parsesOnlyTheCallbackRequest() {
        let head = "GET /callback?code=abc%2B1&state=xyz HTTP/1.1\r\nHost: 127.0.0.1:5000\r\n\r\n"
        #expect(LoopbackCallback.parameters(requestHead: head) == ["code": "abc+1", "state": "xyz"])
        #expect(LoopbackCallback.parameters(requestHead: "GET /favicon.ico HTTP/1.1\r\n") == nil)
        #expect(LoopbackCallback.parameters(requestHead: "POST /callback?code=1 HTTP/1.1\r\n") == nil)
        #expect(LoopbackCallback.parameters(requestHead: "") == nil)
        #expect(LoopbackCallback.redirectURI(port: 5123) == "http://127.0.0.1:5123/callback")
        let response = String(decoding: LoopbackCallback.response(success: true), as: UTF8.self)
        #expect(response.hasPrefix("HTTP/1.1 200 OK\r\n") && response.contains("Signed in"))
    }

    @Test func authorizeURLsCarryPKCEAndState() throws {
        let openRouter = try #require(OpenRouterOAuth.authorizeURL(
            redirectURI: "http://127.0.0.1:5123/callback", challenge: "ch", state: "st"))
        let items = URLComponents(url: openRouter, resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(items.first { $0.name == "callback_url" }?.value == "http://127.0.0.1:5123/callback?state=st")
        #expect(items.first { $0.name == "code_challenge_method" }?.value == "S256")
        let google = try #require(AntigravityOAuth.authorizeURL(
            clientID: "id", redirectURI: "http://127.0.0.1:1/callback", challenge: "ch", state: "st"))
        #expect(google.host() == "accounts.google.com")
        #expect(google.absoluteString.contains("access_type=offline"))
        #expect(!AntigravityOAuth.isConfigured)
    }
}

@Suite("Live usage")
struct LiveUsageTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func usage(fiveHourUsed: Int, weeklyUsed: Int) -> UsageSnapshot {
        UsageSnapshot(
            planType: "Plus", allowed: true, limitReached: false,
            fiveHour: UsageWindow(usedPercent: fiveHourUsed, windowSeconds: 18_000, resetAt: now.addingTimeInterval(3_600)),
            weekly: UsageWindow(usedPercent: weeklyUsed, windowSeconds: 604_800, resetAt: now.addingTimeInterval(200_000)),
            fetchedAt: now
        )
    }

    @Test func picksTheTightestWindowAcrossProviders() throws {
        let state = try #require(LiveUsage.pick([
            PriorityInput(provider: .codex, usage: usage(fiveHourUsed: 40, weeklyUsed: 70)),
            PriorityInput(provider: .claudeCode, usage: usage(fiveHourUsed: 88, weeklyUsed: 20))
        ], now: now))
        #expect(state.provider == "claude_code")
        #expect(state.windowLabel == "5-hour")
        #expect(state.remainingPercent == 12)
        #expect(state.title == "Claude Code 5-hour")
        #expect(LiveUsage.pick([PriorityInput(provider: .codex, usage: nil)], now: now) == nil)
    }

    @Test func autoStartFollowsTheLowAllowanceLevelOncePerWindow() throws {
        let state = try #require(LiveUsage.pick([PriorityInput(provider: .codex, usage: usage(fiveHourUsed: 80, weeklyUsed: 10))], now: now))
        #expect(LiveUsage.shouldAutoStart(state, threshold: 25, whenLow: true, whenFast: false, dismissedToken: nil))
        #expect(!LiveUsage.shouldAutoStart(state, threshold: 10, whenLow: true, whenFast: false, dismissedToken: nil))
        #expect(!LiveUsage.shouldAutoStart(state, threshold: 25, whenLow: false, whenFast: false, dismissedToken: nil))
        #expect(!LiveUsage.shouldAutoStart(state, threshold: 25, whenLow: true, whenFast: false, dismissedToken: state.windowToken))
        #expect(LiveUsage.staleDate(for: state, now: now) == now.addingTimeInterval(3_600))
    }
}
