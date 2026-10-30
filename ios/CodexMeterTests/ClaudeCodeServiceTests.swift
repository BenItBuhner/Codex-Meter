import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

final class ClaudeCodeServiceTests: XCTestCase {
    func testRefreshSendsOAuthHeadersAndRetriesOnceAfterUnauthorized() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let cacheURL = temporaryFileURL("claude-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: cacheURL) }
        let store = MemoryTokenStore(claudeTokens(access: "old-access"))
        let counts = LockedBox([String: Int]())

        CodexMeterURLProtocol.configure { request in
            let path = request.url?.path ?? ""
            let attempt = counts.withValue { counts -> Int in
                counts[path, default: 0] += 1
                return counts[path, default: 0]
            }
            switch path {
            case "/api/oauth/usage":
                XCTAssertEqual(request.value(forHTTPHeaderField: "anthropic-beta"), "oauth-2025-04-20")
                if attempt == 1 {
                    XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer old-access")
                    return StubbedHTTPResponse(statusCode: 401)
                }
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer new-access")
                return try .json(claudeUsageResponse())
            case "/v1/oauth/token":
                let form = requestForm(request)
                XCTAssertEqual(form["grant_type"], "refresh_token")
                XCTAssertEqual(form["refresh_token"], "refresh-token")
                XCTAssertEqual(form["client_id"], ClaudeCodeAPI.clientID)
                return try .json(["access_token": "new-access", "expires_in": 28_800])
            case "/api/oauth/profile":
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer new-access")
                return try .json([
                    "account": ["email_address": "new@example.com"],
                    "organization": ["rate_limit_tier": "default_claude_max_20x"]
                ])
            default:
                return StubbedHTTPResponse(statusCode: 500)
            }
        }

        let service = ClaudeCodeService(
            tokenStore: store,
            session: session,
            cache: AppCacheStore(fileURL: cacheURL)
        )
        let usage = try await service.refreshUsage()

        XCTAssertEqual(usage.fiveHour?.usedPercent, 6)
        XCTAssertEqual(usage.weekly?.usedPercent, 35)
        XCTAssertEqual(usage.additionalLimits.first?.displayName, "Fable only")
        XCTAssertEqual(usage.planType, "Ultra")
        XCTAssertEqual(counts.read()["/api/oauth/usage"], 2)
        XCTAssertEqual(counts.read()["/v1/oauth/token"], 1)

        let saved = try await store.load()
        XCTAssertEqual(saved?.accessToken, "new-access")
        XCTAssertEqual(saved?.refreshToken, "refresh-token", "a refresh without a new refresh token keeps the old one")
        XCTAssertEqual(saved?.email, "new@example.com")

        let cached = await service.cachedSnapshot()
        XCTAssertEqual(cached?.usage?.weekly?.usedPercent, 35)
    }

    func testRateLimitedUsageShowsFriendlyMessage() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let cacheURL = temporaryFileURL("claude-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: cacheURL) }

        CodexMeterURLProtocol.configure { request in
            request.url?.path == "/api/oauth/usage"
                ? StubbedHTTPResponse(statusCode: 429)
                : StubbedHTTPResponse(statusCode: 500)
        }

        let service = ClaudeCodeService(
            tokenStore: MemoryTokenStore(claudeTokens()),
            session: session,
            cache: AppCacheStore(fileURL: cacheURL)
        )
        do {
            _ = try await service.refreshUsage()
            XCTFail("Expected a rate-limit error")
        } catch {
            XCTAssertEqual(
                error.localizedDescription,
                "Claude is rate-limiting usage checks. Try again in a few minutes."
            )
        }
        let cached = await service.cachedSnapshot()
        XCTAssertEqual(
            cached?.lastError,
            "Claude is rate-limiting usage checks. Try again in a few minutes."
        )
    }

    func testSignInStripsStateAndRetriesTokenExchangeAsJSON() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let cacheURL = temporaryFileURL("claude-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: cacheURL) }
        let store = MemoryTokenStore()
        let attempts = LockedBox(0)

        CodexMeterURLProtocol.configure { request in
            guard request.url?.path == "/v1/oauth/token" else {
                return StubbedHTTPResponse(statusCode: 500)
            }
            let attempt = attempts.withValue { value -> Int in
                value += 1
                return value
            }
            if attempt == 1 {
                XCTAssertEqual(
                    request.value(forHTTPHeaderField: "Content-Type"),
                    "application/x-www-form-urlencoded"
                )
                XCTAssertEqual(requestForm(request)["code"], "auth-code")
                return StubbedHTTPResponse(statusCode: 415)
            }
            let body = try requestJSON(request)
            XCTAssertEqual(body["grant_type"] as? String, "authorization_code")
            XCTAssertEqual(body["code"] as? String, "auth-code")
            XCTAssertEqual(body["state"] as? String, "expected-state")
            XCTAssertEqual(body["code_verifier"] as? String, "verifier")
            XCTAssertEqual(body["redirect_uri"] as? String, ClaudeCodeAPI.manualRedirectURI)
            return try .json([
                "access_token": "access",
                "refresh_token": "refresh",
                "expires_in": 28_800,
                "account": ["email_address": "me@example.com"],
                "organization": ["uuid": "org-1"]
            ])
        }

        let service = ClaudeCodeService(
            tokenStore: store,
            session: session,
            cache: AppCacheStore(fileURL: cacheURL)
        )
        let request = ClaudeAuthorizationRequest(verifier: "verifier", state: "expected-state")

        do {
            _ = try await service.signIn(pasted: "auth-code#other-state", request: request)
            XCTFail("A code from another attempt must be rejected")
        } catch {
            XCTAssertEqual(error as? ClaudeAuthorizationError, .stateMismatch)
        }
        XCTAssertEqual(attempts.read(), 0)

        let tokens = try await service.signIn(pasted: " auth-code#expected-state ", request: request)
        XCTAssertEqual(tokens.accessToken, "access")
        XCTAssertEqual(tokens.email, "me@example.com")
        XCTAssertEqual(tokens.accountID, "org-1")
        XCTAssertEqual(attempts.read(), 2)
        let saved = try await store.load()
        XCTAssertEqual(saved?.refreshToken, "refresh")
    }

    func testClaudeKeychainItemIsSeparateFromCodex() {
        XCTAssertNotEqual(ClaudeCodeService.keychainAccount, "chatgpt-tokens")
        XCTAssertEqual(AppCacheStore.defaultFileName, "app-cache-v1.json")
        XCTAssertNotEqual(AppCacheStore.claudeFileName, AppCacheStore.defaultFileName)
    }
}

private func claudeUsageResponse() -> [String: Any] {
    [
        "five_hour": ["utilization": 6.0, "resets_at": "2026-10-19T18:59:59.943648+00:00"],
        "seven_day": ["utilization": 35.4, "resets_at": "2026-10-23T03:00:00Z"],
        "seven_day_opus": ["utilization": 0.0, "resets_at": NSNull()],
        "limits": [
            [
                "kind": "weekly_scoped",
                "group": "weekly",
                "percent": 38,
                "resets_at": "2026-10-23T03:00:00Z",
                "scope": ["model": ["id": NSNull(), "display_name": "Fable"], "surface": NSNull()]
            ]
        ]
    ]
}

private func claudeTokens(access: String = "access") -> AuthTokens {
    AuthTokens(
        accessToken: access,
        refreshToken: "refresh-token",
        idToken: "",
        expiresAt: .distantFuture,
        accountID: "org-1",
        email: "old@example.com"
    )
}
