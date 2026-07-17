import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

final class LiveCodexServiceTests: XCTestCase {
    func testRefreshSendsHeadersAndRetriesExactlyOnceAfterUnauthorized() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let counts = LockedBox([String: Int]())
        let store = MemoryTokenStore(validTokens(access: "old-access", account: "account-42"))
        let paths = makeCachePaths()
        defer { paths.remove() }

        CodexMeterURLProtocol.configure { request in
            let path = request.url?.path ?? ""
            let attempt = counts.withValue { counts -> Int in
                counts[path, default: 0] += 1
                return counts[path, default: 0]
            }
            switch path {
            case "/backend-api/wham/usage":
                XCTAssertEqual(request.value(forHTTPHeaderField: "originator"), "codex-meter-ios")
                XCTAssertEqual(request.value(forHTTPHeaderField: "ChatGPT-Account-Id"), "account-42")
                if attempt == 1 {
                    XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer old-access")
                    return StubbedHTTPResponse(statusCode: 401)
                }
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer new-access")
                return try .json(usageResponse())
            case "/oauth/token":
                let body = try requestJSON(request)
                XCTAssertEqual(body["refresh_token"] as? String, "refresh-token")
                return try .json([
                    "access_token": "new-access",
                    "expires_in": 3_600
                ])
            case "/backend-api/wham/rate-limit-reset-credits":
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer new-access")
                XCTAssertEqual(request.value(forHTTPHeaderField: "originator"), "codex-meter-ios")
                return try .json(creditsResponse(count: 2))
            default:
                return StubbedHTTPResponse(statusCode: 500)
            }
        }

        let service = makeLiveService(session: session, store: store, paths: paths)
        let result = try await service.refresh()

        XCTAssertEqual(result.usage.fiveHour?.usedPercent, 40)
        XCTAssertNotNil(result.usage.fiveHour?.resetAt, "reset_after_seconds should become an absolute date")
        XCTAssertEqual(result.credits.availableCount, 2)
        XCTAssertEqual(counts.read()["/backend-api/wham/usage"], 2)
        XCTAssertEqual(counts.read()["/oauth/token"], 1)
        XCTAssertEqual(counts.read()["/backend-api/wham/rate-limit-reset-credits"], 1)
    }

    func testSecondUnauthorizedResponseIsNotRetriedAgain() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let counts = LockedBox([String: Int]())
        let paths = makeCachePaths()
        defer { paths.remove() }

        CodexMeterURLProtocol.configure { request in
            let path = request.url?.path ?? ""
            counts.withValue { $0[path, default: 0] += 1 }
            switch path {
            case "/backend-api/wham/usage":
                return StubbedHTTPResponse(statusCode: 401)
            case "/oauth/token":
                return try .json([
                    "access_token": "new-access",
                    "expires_in": 3_600
                ])
            default:
                return StubbedHTTPResponse(statusCode: 500)
            }
        }
        let service = makeLiveService(
            session: session,
            store: MemoryTokenStore(validTokens()),
            paths: paths
        )

        do {
            _ = try await service.refreshUsage()
            XCTFail("Expected the second 401 to be returned")
        } catch let error as CodexServiceError {
            guard case .http(status: 401, _) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
        }
        XCTAssertEqual(counts.read()["/backend-api/wham/usage"], 2)
        XCTAssertEqual(counts.read()["/oauth/token"], 1)
    }

    func testConcurrentRefreshCallsShareOneNetworkOperation() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let counts = LockedBox([String: Int]())
        let paths = makeCachePaths()
        defer { paths.remove() }

        CodexMeterURLProtocol.configure { request in
            let path = request.url?.path ?? ""
            counts.withValue { $0[path, default: 0] += 1 }
            if path == "/backend-api/wham/usage" {
                Thread.sleep(forTimeInterval: 0.08)
                return try .json(usageResponse())
            }
            if path == "/backend-api/wham/rate-limit-reset-credits" {
                return try .json(creditsResponse())
            }
            return StubbedHTTPResponse(statusCode: 500)
        }
        let service = makeLiveService(
            session: session,
            store: MemoryTokenStore(validTokens()),
            paths: paths
        )

        let snapshots = try await withThrowingTaskGroup(of: UsageSnapshot.self) { group in
            for _ in 0..<6 {
                group.addTask { try await service.refresh().usage }
            }
            var values: [UsageSnapshot] = []
            for try await value in group {
                values.append(value)
            }
            return values
        }

        XCTAssertEqual(snapshots.count, 6)
        XCTAssertTrue(snapshots.dropFirst().allSatisfy { $0 == snapshots.first })
        XCTAssertEqual(counts.read()["/backend-api/wham/usage"], 1)
        XCTAssertEqual(counts.read()["/backend-api/wham/rate-limit-reset-credits"], 1)
    }

    func testUsageRefreshRetainsCacheWhenNetworkFails() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let paths = makeCachePaths()
        defer { paths.remove() }
        let oldUsage = UsageSnapshot(
            planType: "plus",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(usedPercent: 21, windowSeconds: 18_000),
            weekly: nil,
            fetchedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let appCache = AppCacheStore(fileURL: paths.app)
        try await appCache.save(AppCacheSnapshot(usage: oldUsage))

        CodexMeterURLProtocol.configure { _ in
            try .json(["message": "temporary failure"], statusCode: 500)
        }
        let service = makeLiveService(
            session: session,
            store: MemoryTokenStore(validTokens()),
            paths: paths,
            appCache: appCache
        )

        do {
            _ = try await service.refreshUsage()
            XCTFail("Expected usage refresh to fail")
        } catch {
            // Expected.
        }
        let retained = try await appCache.load()
        XCTAssertEqual(retained?.usage, oldUsage)
        XCTAssertEqual(retained?.lastError, "temporary failure")
    }

    func testCreditFailureDoesNotDiscardSuccessfulUsageRefresh() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let paths = makeCachePaths()
        defer { paths.remove() }
        let appCache = AppCacheStore(fileURL: paths.app)
        let oldCredits = ResetCreditsSnapshot.summary(
            availableCount: 3,
            fetchedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        try await appCache.save(AppCacheSnapshot(credits: oldCredits))

        CodexMeterURLProtocol.configure { request in
            switch request.url?.path {
            case "/backend-api/wham/usage":
                return try .json(usageResponse(used: 77))
            case "/backend-api/wham/rate-limit-reset-credits":
                return try .json(["message": "credits temporarily unavailable"], statusCode: 503)
            default:
                return StubbedHTTPResponse(statusCode: 500)
            }
        }
        let service = makeLiveService(
            session: session,
            store: MemoryTokenStore(validTokens()),
            paths: paths,
            appCache: appCache
        )

        let result = try await service.refresh()
        XCTAssertEqual(result.usage.fiveHour?.usedPercent, 77)
        XCTAssertEqual(result.credits.availableCount, 3)
        XCTAssertEqual(result.usage.resetCreditsAvailable, 3)
        let cached = try await appCache.load()
        XCTAssertEqual(cached?.usage?.fiveHour?.usedPercent, 77)
        XCTAssertEqual(cached?.credits, oldCredits)
        XCTAssertEqual(cached?.resetCreditsError, "credits temporarily unavailable")
        XCTAssertNil(cached?.lastError)
    }

    func testResetServerOutcomesMapAndAlwaysRefreshCredits() async throws {
        let cases: [(String, ResetConsumeOutcome)] = [
            ("nothing_to_reset", .nothingToReset),
            ("no_credit", .noCredit),
            ("already_redeemed", .alreadyRedeemed),
            ("future_code", .unknown("future_code"))
        ]

        for (code, expectedOutcome) in cases {
            CodexMeterURLProtocol.reset()
            let session = makeStubbedSession()
            let paths = makeCachePaths(suffix: code)
            let appCache = AppCacheStore(fileURL: paths.app)
            let fetchedAt = Date(timeIntervalSince1970: 1_800_000_000)
            let credit = RateLimitResetCredit(
                id: "preferred-credit",
                resetType: "rate_limit",
                status: RateLimitResetCredit.availableStatus,
                expiresAt: fetchedAt.addingTimeInterval(3_600)
            )
            try await appCache.save(
                AppCacheSnapshot(
                    credits: ResetCreditsSnapshot(
                        availableCount: 1,
                        credits: [credit],
                        fetchedAt: fetchedAt
                    )
                )
            )
            let creditRefreshCount = LockedBox(0)
            CodexMeterURLProtocol.configure { request in
                switch request.url?.path {
                case "/backend-api/wham/rate-limit-reset-credits/consume":
                    let body = try requestJSON(request)
                    XCTAssertEqual(body["credit_id"] as? String, "preferred-credit")
                    XCTAssertNotNil(UUID(uuidString: body["redeem_request_id"] as? String ?? ""))
                    return try .json(["code": code, "windows_reset": 0])
                case "/backend-api/wham/rate-limit-reset-credits":
                    creditRefreshCount.withValue { $0 += 1 }
                    return try .json(creditsResponse(count: 1))
                default:
                    return StubbedHTTPResponse(statusCode: 500)
                }
            }
            let service = makeLiveService(
                session: session,
                store: MemoryTokenStore(validTokens()),
                paths: paths,
                appCache: appCache,
                now: { fetchedAt }
            )

            let result = try await service.consumeReset()
            XCTAssertEqual(result.outcome, expectedOutcome, "code \(code)")
            XCTAssertEqual(creditRefreshCount.read(), 1, "code \(code)")
            session.invalidateAndCancel()
            paths.remove()
        }
        CodexMeterURLProtocol.reset()
    }

    func testResetPreflightFailurePropagatesWithoutUsableCachedCredit() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let paths = makeCachePaths()
        defer { paths.remove() }

        CodexMeterURLProtocol.configure { request in
            XCTAssertEqual(request.url?.path, "/backend-api/wham/rate-limit-reset-credits")
            return try .json(["message": "credit lookup failed"], statusCode: 503)
        }
        let service = makeLiveService(
            session: session,
            store: MemoryTokenStore(validTokens()),
            paths: paths
        )

        do {
            _ = try await service.consumeReset()
            XCTFail("Expected reset-credit preflight failure to propagate")
        } catch let error as CodexServiceError {
            guard case .http(status: 503, message: "credit lookup failed") = error else {
                return XCTFail("Unexpected error: \(error)")
            }
        }
        XCTAssertFalse(CodexMeterURLProtocol.requests.contains {
            $0.url?.path.hasSuffix("/consume") == true
        })
    }

    func testEmptyPreflightReplacesStalePositiveCreditCache() async throws {
        CodexMeterURLProtocol.reset()
        defer { CodexMeterURLProtocol.reset() }
        let session = makeStubbedSession()
        defer { session.invalidateAndCancel() }
        let paths = makeCachePaths()
        defer { paths.remove() }
        let appCache = AppCacheStore(fileURL: paths.app)
        let oldCredits = ResetCreditsSnapshot.summary(
            availableCount: 1,
            fetchedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        try await appCache.save(AppCacheSnapshot(credits: oldCredits))

        CodexMeterURLProtocol.configure { request in
            XCTAssertEqual(request.url?.path, "/backend-api/wham/rate-limit-reset-credits")
            return try .json(creditsResponse(count: 0))
        }
        let currentDate = Date(timeIntervalSince1970: 1_800_000_000)
        let service = makeLiveService(
            session: session,
            store: MemoryTokenStore(validTokens()),
            paths: paths,
            appCache: appCache,
            now: { currentDate }
        )

        let result = try await service.consumeReset()
        let cachedCount = try await appCache.load()?.credits?.availableCount
        XCTAssertEqual(result.outcome, .noCredit)
        XCTAssertEqual(cachedCount, 0)
        XCTAssertFalse(CodexMeterURLProtocol.requests.contains {
            $0.url?.path.hasSuffix("/consume") == true
        })
    }

    func testNotificationDedupeKeysAreStablePerMetricAndWindow() {
        let reset = Date(timeIntervalSince1970: 1_800_000_000)
        let first = NotificationDeduplication.lowUsageIdentifier(metric: .fiveHour, resetDate: reset)
        let duplicate = NotificationDeduplication.lowUsageIdentifier(metric: .fiveHour, resetDate: reset)
        let otherMetric = NotificationDeduplication.lowUsageIdentifier(metric: .weekly, resetDate: reset)
        let otherWindow = NotificationDeduplication.lowUsageIdentifier(
            metric: .fiveHour,
            resetDate: reset.addingTimeInterval(1)
        )

        XCTAssertEqual(first, duplicate)
        XCTAssertNotEqual(first, otherMetric)
        XCTAssertNotEqual(first, otherWindow)
        let token = NotificationDeduplication.windowToken(for: reset)
        XCTAssertFalse(
            NotificationDeduplication.shouldDeliverLowUsage(
                lastDeliveredWindowToken: token,
                resetDate: reset
            )
        )
        XCTAssertTrue(
            NotificationDeduplication.shouldDeliverLowUsage(
                lastDeliveredWindowToken: token,
                resetDate: reset.addingTimeInterval(1)
            )
        )
        XCTAssertNotEqual(
            NotificationDeduplication.resetIdentifier(metric: .fiveHour, resetDate: reset),
            first
        )
    }

    func testNotificationCleanupRemovesOnlyObsoleteWindowIdentifiers() {
        let reset = Date(timeIntervalSince1970: 1_800_000_000)
        let keptLow = NotificationDeduplication.lowUsageIdentifier(
            metric: .fiveHour,
            resetDate: reset
        )
        let obsoleteLow = NotificationDeduplication.lowUsageIdentifier(
            metric: .weekly,
            resetDate: reset
        )
        let keptReset = NotificationDeduplication.resetIdentifier(
            metric: .fiveHour,
            resetDate: reset
        )
        let obsoleteReset = NotificationDeduplication.resetIdentifier(
            metric: .weekly,
            resetDate: reset
        )
        let unrelatedCredit = NotificationDeduplication.creditIdentifier(
            fetchedAt: reset,
            count: 1
        )

        let obsolete = NotificationDeduplication.obsoleteWindowIdentifiers(
            among: [keptLow, obsoleteLow, keptReset, obsoleteReset, unrelatedCredit],
            keepingLow: [keptLow],
            keepingReset: [keptReset]
        )

        XCTAssertEqual(Set(obsolete), [obsoleteLow, obsoleteReset])
    }

    func testBackgroundRefreshRunGateSuppressesExplicitCancellationOnly() {
        var gate = BackgroundRefreshRunGate()
        let activeRun = gate.begin()

        // System expiration does not invalidate the run, allowing its failure
        // path to submit the fallback request.
        XCTAssertTrue(gate.isCurrent(activeRun))

        // App policy cancellation (including sign-out) invalidates the token,
        // so the cancelled operation cannot recreate a request afterward.
        gate.invalidate()
        XCTAssertFalse(gate.isCurrent(activeRun))

        let replacementRun = gate.begin()
        XCTAssertTrue(gate.isCurrent(replacementRun))
        XCTAssertFalse(gate.isCurrent(activeRun))
    }
}

private struct CachePaths {
    let app: URL
    let widget: URL

    func remove() {
        try? FileManager.default.removeItem(at: app)
        try? FileManager.default.removeItem(at: widget)
    }
}

private func makeCachePaths(suffix: String = UUID().uuidString) -> CachePaths {
    CachePaths(
        app: temporaryFileURL("app-\(suffix).json"),
        widget: temporaryFileURL("widget-\(suffix).json")
    )
}

private func validTokens(
    access: String = "old-access",
    account: String = "account-42"
) -> AuthTokens {
    AuthTokens(
        accessToken: access,
        refreshToken: "refresh-token",
        idToken: "",
        expiresAt: .distantFuture,
        accountID: account,
        email: "person@example.com"
    )
}

private func makeLiveService(
    session: URLSession,
    store: MemoryTokenStore,
    paths: CachePaths,
    appCache: AppCacheStore? = nil,
    now: @escaping @Sendable () -> Date = Date.init
) -> LiveCodexService {
    LiveCodexService(
        backend: CodexBackendConfiguration(
            baseURL: URL(string: "https://chat.test/backend-api/wham")!
        ),
        authConfiguration: OpenAIAuthConfiguration(
            issuer: URL(string: "https://auth.test")!
        ),
        tokenStore: store,
        session: session,
        appCache: appCache ?? AppCacheStore(fileURL: paths.app),
        widgetCache: WidgetSnapshotCache(fileURL: paths.widget),
        now: now
    )
}
