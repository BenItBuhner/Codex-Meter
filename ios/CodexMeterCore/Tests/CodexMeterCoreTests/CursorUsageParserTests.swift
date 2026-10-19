import Foundation
import XCTest
@testable import CodexMeterCore

/// Mirrors the Android `testCursorUsageParsing` / `testCursorAuth` self-tests.
final class CursorUsageParserTests: XCTestCase {
    private let fetchedAt = Date(timeIntervalSince1970: 1_000)

    private let fullBody = """
    {"billingCycleStart":"1791676800000","billingCycleEnd":"2026-11-11T00:00:00Z",
     "planUsage":{"totalSpend":500,"includedSpend":400,"bonusSpend":100,"remaining":1500,
       "limit":2000,"totalPercentUsed":25,"autoPercentUsed":10,"apiPercentUsed":15},
     "spendLimitUsage":{"totalSpend":300,"individualLimit":1000,"individualUsed":300,
       "individualRemaining":700,"pooledLimit":"20000","pooledUsed":5000,"limitType":"team"},
     "enabled":true,"serverTimeOffsetMs":123}
    """

    func testMapsPlanPoolsAndOnDemand() throws {
        let snapshot = try CursorUsageParser.parse(fullBody, planLabel: "Pro", fetchedAt: fetchedAt)
        let end = try XCTUnwrap(ClaudeUsageParser.parseResetDate("2026-11-11T00:00:00Z"))

        let plan = try XCTUnwrap(snapshot.monthly)
        XCTAssertEqual(plan.usedPercent, 25)
        XCTAssertEqual(plan.resetAt, end)
        XCTAssertEqual(plan.windowSeconds, Int64(end.timeIntervalSince1970) - 1_791_676_800)
        XCTAssertNil(snapshot.fiveHour)
        XCTAssertNil(snapshot.weekly)
        XCTAssertEqual(snapshot.planType, "Pro")

        XCTAssertEqual(snapshot.additionalLimits.map(\.id),
                       ["cursor_auto", "cursor_api", "cursor_on_demand"])
        XCTAssertEqual(snapshot.additionalLimits[0].name, "Auto + Composer")
        XCTAssertEqual(snapshot.additionalLimits[0].primary?.usedPercent, 10)
        XCTAssertEqual(snapshot.additionalLimits[1].name, "API models")
        XCTAssertEqual(snapshot.additionalLimits[1].primary?.usedPercent, 15)
        let onDemand = snapshot.additionalLimits[2]
        XCTAssertEqual(onDemand.name, "On-demand ($3 of $10)")
        XCTAssertEqual(onDemand.primary?.usedPercent, 30)
        XCTAssertFalse(onDemand.limitReached)
        XCTAssertFalse(snapshot.limitReached)
        XCTAssertTrue(snapshot.allowed)
    }

    func testComputesPercentAndFallsBackToTeamPool() throws {
        let snapshot = try CursorUsageParser.parse(
            """
            {"billingCycleStart":1791676800000,"billingCycleEnd":"1794355200000",
             "planUsage":{"includedSpend":2000,"limit":2000},
             "spendLimitUsage":{"pooledLimit":"5000","pooledUsed":"5000"}}
            """,
            fetchedAt: fetchedAt
        )
        XCTAssertEqual(snapshot.monthly?.usedPercent, 100)
        XCTAssertEqual(snapshot.monthly?.resetAt, Date(timeIntervalSince1970: 1_794_355_200))
        XCTAssertEqual(snapshot.additionalLimits.count, 1)
        XCTAssertEqual(snapshot.additionalLimits[0].name, "Team on-demand ($50 of $50)")
        XCTAssertTrue(snapshot.additionalLimits[0].limitReached)
        XCTAssertTrue(snapshot.limitReached)
    }

    func testLightUsageAndMissingCycle() throws {
        let light = try CursorUsageParser.parse(#"{"planUsage":{"totalPercentUsed":0.36}}"#)
        XCTAssertEqual(light.monthly?.usedPercent, 1)
        XCTAssertEqual(light.monthly?.windowSeconds, CursorUsageParser.defaultCycleSeconds)
        XCTAssertNil(light.monthly?.resetAt)

        let empty = try CursorUsageParser.parse(#"{"enabled":false}"#)
        XCTAssertNil(empty.monthly)
        XCTAssertTrue(empty.additionalLimits.isEmpty)
        XCTAssertEqual(CursorUsageParser.dollars(1_250), "$12.50")
    }

    func testPlanLabelsAndEmail() {
        XCTAssertEqual(CursorUsageParser.planLabel(profile: #"{"membershipType":"pro_plus"}"#), "Pro+")
        XCTAssertEqual(CursorUsageParser.planLabel(profile: #"{"membershipType":"ultra"}"#), "Ultra")
        XCTAssertEqual(CursorUsageParser.planLabel(profile: #"{"membershipType":"free"}"#), "Hobby")
        XCTAssertEqual(CursorUsageParser.planLabel(profile: #"{"membership_type":"team"}"#), "Teams")
        XCTAssertEqual(CursorUsageParser.planLabel(profile: "nope"), "")
        XCTAssertEqual(CursorUsageParser.email(profile: Data(#"{"email":"me@example.com"}"#.utf8)),
                       "me@example.com")
    }

    func testLoginRequestAndTokens() throws {
        let login = CursorLoginRequest.make()
        XCTAssertEqual(login.verifier.count, 43)
        XCTAssertEqual(login.challenge, CursorLoginRequest.challenge(for: login.verifier))
        // RFC 7636 appendix B test vector.
        XCTAssertEqual(
            CursorLoginRequest.challenge(for: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        )
        XCTAssertEqual(
            login.loginURL.absoluteString,
            "https://cursor.com/loginDeepControl?challenge=\(login.challenge)&uuid=\(login.uuid)&mode=login"
        )
        XCTAssertEqual(
            login.pollURL.absoluteString,
            "https://api2.cursor.sh/auth/poll?uuid=\(login.uuid)&verifier=\(login.verifier)"
        )

        let polled = CursorTokenResponse.parse(
            #"{"authId":"auth0|user_01ABC","accessToken":"a","refreshToken":"r"}"#
        )
        XCTAssertEqual(polled.accessToken, "a")
        XCTAssertEqual(polled.refreshToken, "r")
        XCTAssertEqual(polled.authID, "auth0|user_01ABC")
        let refreshed = CursorTokenResponse.parse(#"{"access_token":"new"}"#)
        XCTAssertEqual(refreshed.refreshToken, "new")
        XCTAssertTrue(CursorTokenResponse.parse(#"{"shouldLogout":true}"#).shouldLogout)
        XCTAssertEqual(CursorAPI.refreshBody(refreshToken: "r")["client_id"], CursorAPI.clientID)
    }

    func testJWTClaimsAndAuthErrors() {
        let payload = Data(#"{"sub":"auth0|user_01ABC","exp":1800000000,"email":"a@b.co"}"#.utf8)
            .base64EncodedString()
            .replacingOccurrences(of: "=", with: "")
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        let jwt = "eyJhbGciOiJIUzI1NiJ9.\(payload).sig"
        XCTAssertEqual(CursorAPI.expiry(of: jwt), Date(timeIntervalSince1970: 1_800_000_000))
        XCTAssertEqual(CursorAPI.email(in: jwt), "a@b.co")
        XCTAssertNil(CursorAPI.expiry(of: "not-a-jwt"))

        XCTAssertTrue(CursorAPI.isUnauthenticated(statusCode: 401, body: Data()))
        XCTAssertTrue(CursorAPI.isUnauthenticated(
            statusCode: 200, body: Data(#"{"error":"not_authenticated"}"#.utf8)))
        XCTAssertFalse(CursorAPI.isUnauthenticated(
            statusCode: 200, body: Data(#"{"planUsage":{}}"#.utf8)))
        let checkpoint = Data("<html>Vercel Security Checkpoint</html>".utf8)
        XCTAssertTrue(CursorAPI.isBotCheckpoint(statusCode: 403, body: checkpoint))
        XCTAssertFalse(CursorAPI.isUnauthenticated(statusCode: 403, body: checkpoint))
    }

    func testProviderEntry() {
        XCTAssertEqual(UsageProvider.resolve("cursor"), .cursor)
        XCTAssertEqual(UsageProvider.cursor.signInLabel, "Sign in with Cursor")
        XCTAssertEqual(UsageProvider.allCases, [.codex, .claudeCode, .cursor])
    }
}
