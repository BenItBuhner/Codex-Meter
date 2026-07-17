import Foundation
import XCTest
@testable import CodexMeter

final class AuthenticationTests: XCTestCase {
    func testDeviceCodePendingThenExchangeStoresTokens() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let tokenStore = MemoryTokenStore()
        let pollCount = LockedBox(0)
        let jwt = try makeJWT([
            "email": "person@example.com",
            "https://api.openai.com/auth": ["chatgpt_account_id": "account-123"]
        ])

        CodexMeterURLProtocol.configure { request in
            switch request.url?.path {
            case "/api/accounts/deviceauth/usercode":
                let body = try requestJSON(request)
                XCTAssertEqual(body["client_id"] as? String, "test-client")
                return try .json([
                    "device_auth_id": "device-123",
                    "user_code": "ABCD-1234",
                    "interval": "1"
                ])
            case "/api/accounts/deviceauth/token":
                let attempt = pollCount.withValue { value -> Int in
                    value += 1
                    return value
                }
                let body = try requestJSON(request)
                XCTAssertEqual(body["device_auth_id"] as? String, "device-123")
                XCTAssertEqual(body["user_code"] as? String, "ABCD-1234")
                if attempt == 1 {
                    return StubbedHTTPResponse(statusCode: 404)
                }
                return try .json([
                    "authorization_code": "authorization-code",
                    "code_challenge": "challenge",
                    "code_verifier": "verifier"
                ])
            case "/oauth/token":
                let form = requestForm(request)
                XCTAssertEqual(form["grant_type"], "authorization_code")
                XCTAssertEqual(form["code"], "authorization-code")
                XCTAssertEqual(form["code_verifier"], "verifier")
                XCTAssertEqual(form["redirect_uri"], "https://auth.test/deviceauth/callback")
                return try .json([
                    "access_token": "access-token",
                    "refresh_token": "refresh-token",
                    "id_token": jwt,
                    "expires_in": 3_600
                ])
            default:
                return StubbedHTTPResponse(statusCode: 500)
            }
        }

        let client = DeviceCodeAuthClient(
            configuration: OpenAIAuthConfiguration(
                issuer: URL(string: "https://auth.test")!,
                clientID: "test-client"
            ),
            tokenStore: tokenStore,
            session: session
        )
        let challenge = try await client.requestChallenge()
        XCTAssertEqual(challenge.verificationURL.absoluteString, "https://auth.test/codex/device")
        XCTAssertEqual(challenge.userCode, "ABCD-1234")

        let tokens = try await client.complete(challenge)
        XCTAssertEqual(pollCount.read(), 2)
        XCTAssertEqual(tokens.accessToken, "access-token")
        XCTAssertEqual(tokens.refreshToken, "refresh-token")
        XCTAssertEqual(tokens.accountID, "account-123")
        XCTAssertEqual(tokens.email, "person@example.com")
        let storedTokens = try await tokenStore.load()
        let saveCount = await tokenStore.saveCount
        XCTAssertEqual(storedTokens, tokens)
        XCTAssertEqual(saveCount, 1)
    }

    func testDeviceCodeCompletionCanBeCancelledExplicitly() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let pollCount = LockedBox(0)

        CodexMeterURLProtocol.configure { request in
            switch request.url?.path {
            case "/api/accounts/deviceauth/usercode":
                return try .json([
                    "device_auth_id": "cancel-device",
                    "user_code": "CANCEL-1",
                    "interval": "1"
                ])
            case "/api/accounts/deviceauth/token":
                pollCount.withValue { $0 += 1 }
                return StubbedHTTPResponse(statusCode: 404)
            default:
                return StubbedHTTPResponse(statusCode: 500)
            }
        }

        let client = DeviceCodeAuthClient(
            configuration: OpenAIAuthConfiguration(issuer: URL(string: "https://auth.test")!),
            tokenStore: MemoryTokenStore(),
            session: session
        )
        let challenge = try await client.requestChallenge()
        let completion = Task { try await client.complete(challenge) }

        while pollCount.read() == 0 {
            try await Task.sleep(for: .milliseconds(10))
        }
        await client.cancel(challenge)

        do {
            _ = try await completion.value
            XCTFail("Expected device-code completion to be cancelled")
        } catch is CancellationError {
            // Expected.
        }
        XCTAssertEqual(pollCount.read(), 1)
    }

    func testDeviceCodeExpiresWithoutPollingAfterDeadline() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let initialDate = Date(timeIntervalSince1970: 1_700_000_000)
        let clock = LockedBox(initialDate)

        CodexMeterURLProtocol.configure { request in
            guard request.url?.path == "/api/accounts/deviceauth/usercode" else {
                return StubbedHTTPResponse(statusCode: 500)
            }
            return try .json([
                "device_auth_id": "expired-device",
                "user_code": "EXPIRE-1",
                "interval": "1"
            ])
        }
        let client = DeviceCodeAuthClient(
            configuration: OpenAIAuthConfiguration(issuer: URL(string: "https://auth.test")!),
            tokenStore: MemoryTokenStore(),
            session: session,
            now: { clock.read() }
        )

        let challenge = try await client.requestChallenge()
        clock.withValue { $0 = initialDate.addingTimeInterval(901) }
        do {
            _ = try await client.complete(challenge)
            XCTFail("Expected the challenge to expire")
        } catch let error as CodexServiceError {
            XCTAssertEqual(error, .deviceCodeExpired)
        }
        XCTAssertEqual(CodexMeterURLProtocol.requests.count, 1)
    }

    func testRefreshPreservesRotatedRefreshTokenWhenResponseOmitsIt() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let previous = AuthTokens(
            accessToken: "old-access",
            refreshToken: "rotated-refresh-token",
            idToken: "old-id",
            expiresAt: .distantPast,
            accountID: "old-account",
            email: "old@example.com"
        )
        let store = MemoryTokenStore(previous)
        let newIDToken = try makeJWT([
            "email": "new@example.com",
            "chatgpt_account_id": "new-account"
        ])

        CodexMeterURLProtocol.configure { request in
            XCTAssertEqual(request.url?.path, "/oauth/token")
            let body = try requestJSON(request)
            XCTAssertEqual(body["grant_type"] as? String, "refresh_token")
            XCTAssertEqual(body["refresh_token"] as? String, "rotated-refresh-token")
            return try .json([
                "access_token": "new-access",
                "id_token": newIDToken,
                "expires_in": 7_200
            ])
        }
        let auth = AuthSession(
            configuration: OpenAIAuthConfiguration(issuer: URL(string: "https://auth.test")!),
            tokenStore: store,
            session: session,
            now: { Date(timeIntervalSince1970: 1_800_000_000) }
        )

        let refreshed = try await auth.validTokens()
        XCTAssertEqual(refreshed.accessToken, "new-access")
        XCTAssertEqual(refreshed.refreshToken, "rotated-refresh-token")
        XCTAssertEqual(refreshed.accountID, "new-account")
        XCTAssertEqual(refreshed.email, "new@example.com")
        let storedTokens = try await store.load()
        let saveCount = await store.saveCount
        XCTAssertEqual(storedTokens, refreshed)
        XCTAssertEqual(saveCount, 1)
    }
}
