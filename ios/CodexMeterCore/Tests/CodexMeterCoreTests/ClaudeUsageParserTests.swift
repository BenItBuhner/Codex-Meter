import Foundation
import XCTest
@testable import CodexMeterCore

/// Mirrors the Android `testClaudeUsageParsing` / `testClaudeOAuthRequests` self-tests.
final class ClaudeUsageParserTests: XCTestCase {
    private let fetchedAt = Date(timeIntervalSince1970: 1_000)

    /// Current shape: a server-built `limits` list plus the legacy windows.
    private let fullBody = """
    {
      "five_hour": {"utilization": 6.0, "resets_at": "2026-10-19T18:59:59.943648+00:00"},
      "seven_day": {"utilization": 35.4, "resets_at": "2026-10-23T03:00:00Z"},
      "seven_day_opus": {"utilization": 0.0, "resets_at": null},
      "seven_day_sonnet": {"utilization": 100, "resets_at": null},
      "iguana_necktie": null,
      "limits": [
        {"kind": "session", "group": "session", "percent": 41, "severity": "normal",
         "resets_at": "2026-10-19T18:59:59.943648+00:00", "scope": null, "is_active": true},
        {"kind": "weekly_all", "group": "weekly", "percent": 20, "severity": "normal",
         "resets_at": "2026-10-23T03:00:00Z", "scope": null, "is_active": false},
        {"kind": "weekly_scoped", "group": "weekly", "percent": 100, "severity": "critical",
         "resets_at": "2026-10-23T03:00:00Z",
         "scope": {"model": {"id": null, "display_name": "Fable"}, "surface": null}},
        {"kind": "weekly_scoped", "group": "weekly", "percent": 20,
         "scope": {"model": {"id": null, "display_name": "All models"}, "surface": null}},
        {"kind": "weekly_scoped", "group": "weekly", "percent": null,
         "scope": {"model": {"id": null, "display_name": "Other"}, "surface": null}}
      ],
      "extra_usage": {"is_enabled": true, "monthly_limit": 5000, "used_credits": 1200, "utilization": 24.0}
    }
    """

    func testLimitsListDrivesTheCards() throws {
        let snapshot = try ClaudeUsageParser.parse(fullBody, planLabel: "Max 20x", fetchedAt: fetchedAt)

        let fiveHour = try XCTUnwrap(snapshot.fiveHour)
        XCTAssertEqual(fiveHour.usedPercent, 41)
        XCTAssertEqual(fiveHour.windowSeconds, 18_000)
        XCTAssertEqual(fiveHour.resetAt, date("2026-10-19T18:59:59Z"))

        let weekly = try XCTUnwrap(snapshot.weekly)
        XCTAssertEqual(weekly.usedPercent, 20)
        XCTAssertEqual(weekly.windowSeconds, 604_800)
        XCTAssertEqual(weekly.resetAt, date("2026-10-23T03:00:00Z"))

        XCTAssertNil(snapshot.monthly)
        XCTAssertNil(snapshot.usageCredits)
        XCTAssertEqual(snapshot.planType, "Ultra", "cached Max 20x label is renamed")
        XCTAssertEqual(snapshot.fetchedAt, fetchedAt)

        // Only Fable and extra usage: no Opus/Sonnet, "All models" and null rows skipped.
        XCTAssertEqual(snapshot.additionalLimits.map(\.displayName), ["Fable only", "Extra usage"])
        let fable = snapshot.additionalLimits[0]
        XCTAssertEqual(fable.id, "claude_weekly_scoped_fable")
        XCTAssertEqual(fable.primary?.usedPercent, 100)
        XCTAssertEqual(fable.primary?.windowSeconds, 604_800)
        XCTAssertTrue(fable.limitReached)
        XCTAssertNil(fable.secondary)
        XCTAssertEqual(DashboardSections.limitKey(fable), "limit:claude_weekly_scoped_fable")

        let extra = snapshot.additionalLimits[1]
        XCTAssertEqual(extra.id, "claude_extra_usage")
        XCTAssertEqual(extra.primary?.usedPercent, 24)
        XCTAssertEqual(extra.primary?.windowSeconds, 2_592_000)

        XCTAssertTrue(snapshot.limitReached)
        XCTAssertFalse(snapshot.allowed)
    }

    func testUnifiedPlanShowsOnlyFiveHourAndWeekly() throws {
        let snapshot = try ClaudeUsageParser.parse(
            """
            {"limits": [
              {"kind": "session", "group": "session", "percent": 12, "resets_at": null, "scope": null},
              {"kind": "weekly_all", "group": "weekly", "percent": 3, "resets_at": null, "scope": null}
            ], "extra_usage": {"is_enabled": false, "utilization": null}}
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 12)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 3)
        XCTAssertTrue(snapshot.additionalLimits.isEmpty)
        XCTAssertFalse(snapshot.limitReached)
    }

    func testLegacyBodyIgnoresPerModelKeys() throws {
        let snapshot = try ClaudeUsageParser.parse(
            """
            {"five_hour": {"utilization": 6.0, "resets_at": null},
             "seven_day": {"utilization": 35.4, "resets_at": null},
             "seven_day_opus": {"utilization": 0.0, "resets_at": null},
             "seven_day_sonnet": {"utilization": 100, "resets_at": null},
             "seven_day_fable": {"utilization": 51, "resets_at": null}}
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.fiveHour?.usedPercent, 6)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 35)
        XCTAssertEqual(snapshot.additionalLimits.map(\.displayName), ["Fable only"])
        XCTAssertEqual(snapshot.additionalLimits.first?.id, "claude_weekly_scoped_fable")
        XCTAssertFalse(snapshot.limitReached)
    }

    func testSnapshotSurvivesCodableRoundTrip() throws {
        let snapshot = try ClaudeUsageParser.parse(fullBody, planLabel: "Max 20x", fetchedAt: fetchedAt)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(UsageSnapshot.self, from: encoder.encode(snapshot))
        XCTAssertEqual(decoded.weekly?.usedPercent, 20)
        XCTAssertEqual(decoded.additionalLimits.count, 2)
        XCTAssertEqual(decoded.planType, "Ultra")
    }

    func testNullWindowsAndDisabledExtraUsageAreSkipped() throws {
        let snapshot = try ClaudeUsageParser.parse(
            """
            {"five_hour": null, "seven_day": {"utilization": 12.6, "resets_at": null},
             "extra_usage": {"is_enabled": false, "utilization": null}}
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertEqual(snapshot.weekly?.usedPercent, 13)
        XCTAssertNil(snapshot.weekly?.resetAt)
        XCTAssertTrue(snapshot.additionalLimits.isEmpty)
        XCTAssertFalse(snapshot.limitReached)
        XCTAssertTrue(snapshot.allowed)
        XCTAssertEqual(snapshot.planType, "")
    }

    func testUnknownScopedMetersGetGenericCards() throws {
        let snapshot = try ClaudeUsageParser.parse(
            """
            {"limits": [
              {"kind": "weekly_scoped", "group": "weekly", "percent": 7,
               "scope": {"model": null, "surface": {"id": "claude_design", "display_name": "Claude Design"}}},
              {"kind": "monthly_new_thing", "group": "monthly", "percent": 99.6, "scope": null}
            ], "extra_usage": {"is_enabled": true, "utilization": null}}
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.additionalLimits.map(\.id),
                       ["claude_weekly_scoped_claude_design", "claude_monthly_new_thing"])
        XCTAssertEqual(snapshot.additionalLimits.map(\.displayName),
                       ["Claude Design only", "Monthly new thing"])
        XCTAssertEqual(snapshot.additionalLimits[1].primary?.windowSeconds, 2_592_000)
        XCTAssertEqual(snapshot.additionalLimits[1].primary?.usedPercent, 100)
        XCTAssertTrue(snapshot.limitReached)
    }

    func testResetTimestamps() {
        XCTAssertNil(ClaudeUsageParser.parseResetDate("not a date"))
        XCTAssertNil(ClaudeUsageParser.parseResetDate(NSNull()))
        XCTAssertNil(ClaudeUsageParser.parseResetDate(nil))
        XCTAssertEqual(
            ClaudeUsageParser.parseResetDate("2026-10-19T18:59:59.9+00:00"),
            date("2026-10-19T18:59:59Z")
        )
        XCTAssertEqual(
            ClaudeUsageParser.parseResetDate("2026-10-19T20:59:59+02:00"),
            date("2026-10-19T18:59:59Z")
        )
    }

    func testMalformedRootThrows() {
        XCTAssertThrowsError(try ClaudeUsageParser.parse("[]", fetchedAt: fetchedAt))
        XCTAssertThrowsError(try ClaudeUsageParser.parse("not json", fetchedAt: fetchedAt))
    }

    func testPlanLabelsFromProfile() {
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(
                profile: #"{"organization":{"organization_type":"claude_max","rate_limit_tier":"default_claude_max_20x"}}"#
            ),
            "Ultra"
        )
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(profile: #"{"organization":{"rate_limit_tier":"default_claude_max_5x"}}"#),
            "Max"
        )
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(profile: #"{"account":{"has_claude_pro":true},"organization":{}}"#),
            "Pro"
        )
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(profile: #"{"account":{"has_claude_max":true}}"#),
            "Max"
        )
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(profile: #"{"organization":{"organization_type":"claude_team"}}"#),
            "Team"
        )
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(profile: #"{"organization":{"organization_type":"claude_enterprise"}}"#),
            "Enterprise"
        )
        XCTAssertEqual(ClaudeUsageParser.planLabel(profile: "not json"), "")
        XCTAssertEqual(
            ClaudeUsageParser.email(profile: #"{"account":{"email_address":"me@example.com"}}"#),
            "me@example.com"
        )
        XCTAssertEqual(ClaudeUsageParser.email(profile: "nope"), "")
    }

    func testProviderIdentifiers() {
        XCTAssertEqual(UsageProvider.defaultProvider, .codex)
        XCTAssertEqual(UsageProvider.codex.rawValue, "codex")
        XCTAssertEqual(UsageProvider.claudeCode.rawValue, "claude_code")
        XCTAssertEqual(UsageProvider.resolve(" CLAUDE_CODE "), .claudeCode)
        XCTAssertEqual(UsageProvider.resolve("unknown"), .codex)
        XCTAssertEqual(UsageProvider.resolve(nil), .codex)
        XCTAssertEqual(UsageProvider.codex.accountName, "ChatGPT")
        XCTAssertEqual(UsageProvider.claudeCode.accountName, "Claude")
        XCTAssertEqual(UsageProvider.claudeCode.displayName, "Claude Code")
        XCTAssertEqual(UsageProvider.claudeCode.signInLabel, "Sign in with Claude")
    }

    private func date(_ string: String) -> Date {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: string)!
    }
}

final class ClaudeCodeOAuthTests: XCTestCase {
    func testAuthorizeURL() {
        let url = ClaudeCodeAPI.authorizeURL(
            redirectURI: ClaudeCodeAPI.manualRedirectURI,
            challenge: "challenge-value",
            state: "state-value"
        ).absoluteString
        XCTAssertTrue(url.hasPrefix(
            "https://claude.ai/oauth/authorize?code=true&client_id=\(ClaudeCodeAPI.clientID)&response_type=code&"
        ))
        XCTAssertTrue(url.contains(
            "redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"
        ))
        XCTAssertTrue(url.contains("scope=user%3Aprofile+user%3Ainference"))
        XCTAssertTrue(url.contains("code_challenge=challenge-value&code_challenge_method=S256"))
        XCTAssertTrue(url.hasSuffix("&state=state-value"))

        let loopback = ClaudeCodeAPI.authorizeURL(
            redirectURI: "http://localhost:54545/callback",
            challenge: "c",
            state: "s"
        ).absoluteString
        XCTAssertTrue(loopback.contains("redirect_uri=http%3A%2F%2Flocalhost%3A54545%2Fcallback"))
    }

    func testCodeExchangeAndRefreshBodies() {
        let exchange = ClaudeCodeAPI.codeExchangeBody(
            code: "auth-code#state-value",
            redirectURI: ClaudeCodeAPI.manualRedirectURI,
            verifier: "verifier",
            state: "state-value"
        )
        XCTAssertEqual(exchange["code"], "auth-code")
        XCTAssertEqual(exchange["grant_type"], "authorization_code")
        XCTAssertEqual(exchange["redirect_uri"], ClaudeCodeAPI.manualRedirectURI)
        XCTAssertEqual(exchange["client_id"], ClaudeCodeAPI.clientID)
        XCTAssertEqual(exchange["code_verifier"], "verifier")
        XCTAssertEqual(exchange["state"], "state-value")

        let refresh = ClaudeCodeAPI.refreshBody(refreshToken: "refresh-token")
        XCTAssertEqual(refresh["grant_type"], "refresh_token")
        XCTAssertEqual(refresh["refresh_token"], "refresh-token")
        XCTAssertEqual(refresh["client_id"], ClaudeCodeAPI.clientID)
        XCTAssertEqual(refresh.count, 3)
    }

    func testPKCEChallengeAndRandomRequests() {
        XCTAssertEqual(
            ClaudeAuthorizationRequest.codeChallenge(for: "dBjftJeZ4CVP-mJ92IZ8sYzYp2EV2KtmZ8w2C6LrYzA"),
            "t29kr_UtntJhJTCEVtPI58A_dNHJcZUruispMak34Gs"
        )
        let first = ClaudeAuthorizationRequest.make()
        let second = ClaudeAuthorizationRequest.make()
        XCTAssertNotEqual(first.verifier, second.verifier)
        XCTAssertNotEqual(first.state, second.state)
        XCTAssertEqual(first.verifier.count, 43)
        XCTAssertFalse(first.verifier.contains("="))
        XCTAssertEqual(first.redirectURI, ClaudeCodeAPI.manualRedirectURI)
        XCTAssertTrue(first.authorizeURL.absoluteString.contains("state=\(first.state)"))
    }

    func testPastedCodeVerifiesState() throws {
        let request = ClaudeAuthorizationRequest(verifier: "v", state: "expected")
        XCTAssertEqual(try request.pastedCode("  the-code#expected \n"), "the-code")
        XCTAssertEqual(try request.pastedCode("bare-code"), "bare-code")
        XCTAssertEqual(
            try request.pastedCode("https://platform.claude.com/oauth/code/callback?code=url-code&state=expected"),
            "url-code"
        )
        XCTAssertThrowsError(try request.pastedCode("the-code#other")) { error in
            XCTAssertEqual(error as? ClaudeAuthorizationError, .stateMismatch)
        }
        XCTAssertThrowsError(try request.pastedCode("   ")) { error in
            XCTAssertEqual(error as? ClaudeAuthorizationError, .missingCode)
        }
    }

    func testJSONRetryRule() {
        XCTAssertTrue(ClaudeCodeAPI.shouldRetryAsJSON(statusCode: 415, body: Data()))
        XCTAssertTrue(ClaudeCodeAPI.shouldRetryAsJSON(
            statusCode: 400,
            body: Data(#"{"error":"invalid_request"}"#.utf8)
        ))
        XCTAssertFalse(ClaudeCodeAPI.shouldRetryAsJSON(
            statusCode: 400,
            body: Data(#"{"error":"invalid_grant"}"#.utf8)
        ))
        XCTAssertFalse(ClaudeCodeAPI.shouldRetryAsJSON(statusCode: 401, body: Data()))
        XCTAssertFalse(ClaudeCodeAPI.shouldRetryAsJSON(statusCode: 200, body: Data()))
    }

    func testTokenResponseParsing() throws {
        let response = try ClaudeTokenResponse.parse(Data("""
        {"access_token":"access","refresh_token":"refresh","expires_in":28800,
         "account":{"email_address":"me@example.com"},"organization":{"uuid":"org-1"}}
        """.utf8))
        XCTAssertEqual(response.accessToken, "access")
        XCTAssertEqual(response.refreshToken, "refresh")
        XCTAssertEqual(response.expiresIn, 28_800)
        XCTAssertEqual(response.email, "me@example.com")
        XCTAssertEqual(response.organizationID, "org-1")

        let minimal = try ClaudeTokenResponse.parse(Data(#"{"access_token":"a","expires_in":5}"#.utf8))
        XCTAssertEqual(minimal.refreshToken, "")
        XCTAssertEqual(minimal.expiresIn, 60)

        XCTAssertThrowsError(try ClaudeTokenResponse.parse(Data(#"{"refresh_token":"r"}"#.utf8)))
    }

    func testUltraAndMaxRename() throws {
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(profile: #"{"organization":{"rate_limit_tier":"default_claude_ultra"}}"#),
            "Ultra"
        )
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(profile: #"{"organization":{"organization_type":"claude_ultra"}}"#),
            "Ultra"
        )
        let aliases: [(String?, String)] = [
            ("Max 20x", "Ultra"), ("max_20x", "Ultra"), ("default_claude_max_20x", "Ultra"),
            (" ultra ", "Ultra"), ("Max 5x", "Max"), ("max-5x", "Max"), ("max", "Max"),
            ("Pro", "Pro"), ("Team", "Team"), ("", ""), (nil, ""),
        ]
        for (alias, expected) in aliases {
            XCTAssertEqual(ClaudeUsageParser.normalizePlanLabel(alias), expected, "\(alias ?? "nil")")
        }
    }

    func testVerifiedUltraHasNoFiveHourWindow() throws {
        let ultra = try ClaudeUsageParser.parse(
            #"{"limits":[{"kind":"weekly_all","group":"weekly","percent":30,"resets_at":"2026-11-02T03:00:00Z"}],"five_hour":null,"seven_day":null}"#,
            planLabel: "Ultra",
            fetchedAt: fetchedAt
        )
        XCTAssertNil(ultra.fiveHour)
        XCTAssertEqual(ultra.weekly?.usedPercent, 30)
        XCTAssertFalse(ultra.limitReached)
        XCTAssertTrue(ClaudeUsageParser.noFiveHourLimit(ultra))

        let idle = #"{"five_hour":{"utilization":0,"resets_at":null},"seven_day":{"utilization":12,"resets_at":null}}"#
        let ultraIdle = try ClaudeUsageParser.parse(idle, planLabel: "Max 20x", fetchedAt: fetchedAt)
        XCTAssertNil(ultraIdle.fiveHour, "idle placeholder 5-hour window on Ultra is dropped")
        XCTAssertEqual(ultraIdle.planType, "Ultra")
        let maxIdle = try ClaudeUsageParser.parse(idle, planLabel: "Max 5x", fetchedAt: fetchedAt)
        XCTAssertNotNil(maxIdle.fiveHour, "Max keeps its 5-hour window")
        XCTAssertEqual(maxIdle.planType, "Max")
        XCTAssertFalse(ClaudeUsageParser.noFiveHourLimit(maxIdle))

        let ultraLive = try ClaudeUsageParser.parse(fullBody, planLabel: "Ultra", fetchedAt: fetchedAt)
        XCTAssertEqual(ultraLive.fiveHour?.usedPercent, 41, "unverified Ultra keeps a live 5-hour window")

        let atWeekly = try ClaudeUsageParser.parse(
            #"{"five_hour":null,"seven_day":{"utilization":100,"resets_at":"2026-11-02T03:00:00Z"}}"#,
            planLabel: "Ultra",
            fetchedAt: fetchedAt
        )
        XCTAssertTrue(atWeekly.limitReached, "Ultra weekly limit still blocks")
    }

}
