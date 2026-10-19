import Foundation
import XCTest
@testable import CodexMeterCore

/// Mirrors the Android `testClaudeUsageParsing` / `testClaudeOAuthRequests` self-tests.
final class ClaudeUsageParserTests: XCTestCase {
    private let fetchedAt = Date(timeIntervalSince1970: 1_000)

    private let fullBody = """
    {
      "five_hour": {"utilization": 6.0, "resets_at": "2026-10-19T18:59:59.943648+00:00"},
      "seven_day": {"utilization": 35.4, "resets_at": "2026-10-23T03:00:00Z"},
      "seven_day_oauth_apps": null,
      "seven_day_opus": {"utilization": 0.0, "resets_at": null},
      "seven_day_sonnet": {"utilization": 100, "resets_at": "2026-10-23T03:00:00+00:00"},
      "iguana_necktie": null,
      "extra_usage": {"is_enabled": true, "monthly_limit": 5000, "used_credits": 1200, "utilization": 24.0}
    }
    """

    func testMapsWindowsModelCapsAndExtraUsage() throws {
        let snapshot = try ClaudeUsageParser.parse(fullBody, planLabel: "Max 20x", fetchedAt: fetchedAt)

        let fiveHour = try XCTUnwrap(snapshot.fiveHour)
        XCTAssertEqual(fiveHour.usedPercent, 6)
        XCTAssertEqual(fiveHour.windowSeconds, 18_000)
        XCTAssertEqual(fiveHour.resetAt, date("2026-10-19T18:59:59Z"))

        let weekly = try XCTUnwrap(snapshot.weekly)
        XCTAssertEqual(weekly.usedPercent, 35)
        XCTAssertEqual(weekly.windowSeconds, 604_800)
        XCTAssertEqual(weekly.remainingPercent, 65)
        XCTAssertEqual(weekly.resetAt, date("2026-10-23T03:00:00Z"))

        XCTAssertNil(snapshot.monthly)
        XCTAssertNil(snapshot.usageCredits)
        XCTAssertNil(snapshot.resetCreditsAvailable)
        XCTAssertEqual(snapshot.planType, "Max 20x")
        XCTAssertEqual(snapshot.fetchedAt, fetchedAt)

        XCTAssertEqual(snapshot.additionalLimits.count, 3)
        let opus = snapshot.additionalLimits[0]
        XCTAssertEqual(opus.id, "claude_seven_day_opus")
        XCTAssertEqual(opus.displayName, "Opus")
        XCTAssertEqual(opus.primary?.usedPercent, 0)
        XCTAssertEqual(opus.primary?.windowSeconds, 604_800)
        XCTAssertNil(opus.primary?.resetAt)
        XCTAssertNil(opus.secondary)

        let sonnet = snapshot.additionalLimits[1]
        XCTAssertEqual(sonnet.displayName, "Sonnet")
        XCTAssertTrue(sonnet.limitReached)

        let extra = snapshot.additionalLimits[2]
        XCTAssertEqual(extra.id, "claude_extra_usage")
        XCTAssertEqual(extra.displayName, "Extra usage")
        XCTAssertEqual(extra.primary?.usedPercent, 24)
        XCTAssertEqual(extra.primary?.windowSeconds, 2_592_000)

        XCTAssertTrue(snapshot.limitReached)
        XCTAssertFalse(snapshot.allowed)
        XCTAssertEqual(DashboardSections.limitKey(opus), "limit:claude_seven_day_opus")
    }

    func testSnapshotSurvivesCodableRoundTrip() throws {
        let snapshot = try ClaudeUsageParser.parse(fullBody, planLabel: "Max 20x", fetchedAt: fetchedAt)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(UsageSnapshot.self, from: encoder.encode(snapshot))
        XCTAssertEqual(decoded.weekly?.usedPercent, 35)
        XCTAssertEqual(decoded.additionalLimits.count, 3)
        XCTAssertEqual(decoded.planType, "Max 20x")
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

    func testEnabledExtraUsageWithoutUtilizationIsSkipped() throws {
        let snapshot = try ClaudeUsageParser.parse(
            #"{"extra_usage": {"is_enabled": true, "utilization": null}, "five_hour_new_thing": {"utilization": 99.6}}"#,
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.additionalLimits.count, 1)
        let limit = try XCTUnwrap(snapshot.additionalLimits.first)
        XCTAssertEqual(limit.id, "claude_five_hour_new_thing")
        XCTAssertEqual(limit.displayName, "New thing")
        XCTAssertEqual(limit.primary?.windowSeconds, 18_000)
        XCTAssertEqual(limit.primary?.usedPercent, 100)
        XCTAssertTrue(snapshot.limitReached)
    }

    func testLimitNames() {
        XCTAssertEqual(ClaudeUsageParser.limitName("opus"), "Opus")
        XCTAssertEqual(ClaudeUsageParser.limitName("haiku"), "Haiku")
        XCTAssertEqual(ClaudeUsageParser.limitName("oauth_apps"), "Connected apps")
        XCTAssertEqual(ClaudeUsageParser.limitName("claude_design"), "Claude design")
        XCTAssertEqual(ClaudeUsageParser.limitName(""), "Additional usage")
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
            "Max 20x"
        )
        XCTAssertEqual(
            ClaudeUsageParser.planLabel(profile: #"{"organization":{"rate_limit_tier":"default_claude_max_5x"}}"#),
            "Max 5x"
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
}
