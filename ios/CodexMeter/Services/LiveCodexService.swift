import BackgroundTasks
import CodexMeterCore
import Foundation
import UserNotifications
import WidgetKit

nonisolated public struct CodexBackendConfiguration: Sendable, Equatable {
    public var baseURL: URL
    public var originator: String

    public nonisolated init(
        baseURL: URL = URL(string: "https://chatgpt.com/backend-api/wham")!,
        originator: String = "codex-meter-ios"
    ) {
        self.baseURL = baseURL
        self.originator = originator
    }

    var usageURL: URL { baseURL.appendingPathComponent("usage") }
    var creditsURL: URL { baseURL.appendingPathComponent("rate-limit-reset-credits") }
    var consumeURL: URL { creditsURL.appendingPathComponent("consume") }
}

public actor LiveCodexService: CodexService {
    public nonisolated let deviceCodeAuth: DeviceCodeAuthClient

    private let backend: CodexBackendConfiguration
    private let authSession: AuthSession
    private let session: URLSession
    private let appCache: AppCacheStore
    private let widgetCache: WidgetSnapshotCache
    private let now: @Sendable () -> Date
    private var activeRefresh: Task<CodexRefreshSnapshot, Error>?

    public init(
        backend: CodexBackendConfiguration = .init(),
        authConfiguration: OpenAIAuthConfiguration = .init(),
        tokenStore: any TokenStoring = KeychainTokenStore(),
        session: URLSession? = nil,
        appCache: AppCacheStore = .shared,
        widgetCache: WidgetSnapshotCache = .shared,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        let resolvedSession = session ?? HTTPClientSupport.session()
        self.backend = backend
        self.session = resolvedSession
        self.appCache = appCache
        self.widgetCache = widgetCache
        self.now = now
        self.authSession = AuthSession(
            configuration: authConfiguration,
            tokenStore: tokenStore,
            session: resolvedSession,
            now: now
        )
        self.deviceCodeAuth = DeviceCodeAuthClient(
            configuration: authConfiguration,
            tokenStore: tokenStore,
            session: resolvedSession,
            now: now
        )
    }

    public func refresh() async throws -> CodexRefreshSnapshot {
        if let activeRefresh {
            return try await activeRefresh.value
        }
        let task = Task<CodexRefreshSnapshot, Error> {
            try await self.performRefresh()
        }
        activeRefresh = task
        defer { activeRefresh = nil }
        return try await task.value
    }

    public func refreshUsage() async throws -> UsageSnapshot {
        if let activeRefresh {
            return try await activeRefresh.value.usage
        }
        do {
            let usage = try await fetchUsage()
            let cached = try await appCache.updateUsage(usage, at: now())
            await publishWidgets(
                mode: .live,
                usage: usage,
                credits: cached.credits
            )
            return usage
        } catch {
            await appCache.recordUsageError(error.localizedDescription, at: now())
            throw error
        }
    }

    public func refreshResetCredits() async throws -> ResetCreditsSnapshot {
        if let activeRefresh {
            return try await activeRefresh.value.credits
        }
        do {
            let credits = try await fetchCredits()
            let cached = try await appCache.updateCredits(credits, at: now())
            await publishWidgets(
                mode: .live,
                usage: cached.usage,
                credits: credits
            )
            return credits
        } catch {
            await appCache.recordCreditsError(error.localizedDescription, at: now())
            throw error
        }
    }

    public func consumeReset() async throws -> ResetConsumeResult {
        if let activeRefresh {
            _ = try? await activeRefresh.value
            self.activeRefresh = nil
        }

        let cachedCredits = try? await appCache.load()?.credits
        let credits: ResetCreditsSnapshot?
        if let cachedCredits,
           cachedCredits.availableCount > 0,
           now().timeIntervalSince(cachedCredits.fetchedAt) <= 5 * 60 {
            credits = cachedCredits
        } else {
            do {
                let refreshed = try await fetchCredits()
                let updated = try await appCache.updateCredits(refreshed, at: now())
                await publishWidgets(
                    mode: .live,
                    usage: updated.usage,
                    credits: refreshed
                )
                credits = refreshed
            } catch {
                await appCache.recordCreditsError(error.localizedDescription, at: now())
                if let cachedCredits, cachedCredits.availableCount > 0 {
                    credits = cachedCredits
                } else {
                    throw error
                }
            }
        }

        guard let credits, credits.availableCount > 0 else {
            return ResetConsumeResult(outcome: .noCredit, windowsReset: 0)
        }

        var object: [String: Any] = ["redeem_request_id": UUID().uuidString]
        if let creditID = credits.preferredCreditID(at: now()), !creditID.isEmpty {
            object["credit_id"] = creditID
        }
        let body = try JSONSerialization.data(withJSONObject: object)
        let payload = try await authenticatedRequest(
            method: "POST",
            url: backend.consumeURL,
            body: body
        )
        let data = try ensureBackendSuccess(payload, operation: "Could not apply the Codex reset")
        guard let response = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CodexServiceError.invalidResponse
        }
        let outcome = ResetConsumeOutcome(code: response["code"] as? String ?? "")
        let windowsReset = Int(HTTPClientSupport.number(response["windows_reset"], default: 0))

        if let activeRefresh {
            activeRefresh.cancel()
            _ = try? await activeRefresh.value
            self.activeRefresh = nil
        }

        var refreshWarning: String?
        if outcome == .reset {
            do {
                _ = try await refresh()
            } catch {
                refreshWarning = "The reset succeeded, but the new usage values could not be loaded yet."
                await appCache.recordUsageError(error.localizedDescription, at: now())
            }
        }

        do {
            let refreshed = try await fetchCredits()
            _ = try await appCache.updateCredits(refreshed, at: now())
            let cached = try? await appCache.load()
            await publishWidgets(
                mode: .live,
                usage: cached?.usage,
                credits: refreshed
            )
        } catch {
            await appCache.recordCreditsError(error.localizedDescription, at: now())
        }

        return ResetConsumeResult(
            outcome: outcome,
            windowsReset: windowsReset,
            refreshWarning: refreshWarning
        )
    }

    public func currentTokens() async throws -> AuthTokens? {
        try await authSession.currentTokens()
    }

    public func signOut() async {
        activeRefresh?.cancel()
        activeRefresh = nil
        await authSession.signOut()
        try? await appCache.clear()
        await publishSignedOutWidgets()

        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.removeAllPendingNotificationRequests()
        notificationCenter.removeAllDeliveredNotifications()
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: "com.bukovinafilip.CodexMeter.refresh")
    }

    private func performRefresh() async throws -> CodexRefreshSnapshot {
        do {
            let usage = try await fetchUsage()
            let cachedAfterUsage = try await appCache.updateUsage(usage, at: now())
            await publishWidgets(
                mode: .live,
                usage: usage,
                credits: cachedAfterUsage.credits
            )

            do {
                let credits = try await fetchCredits()
                let enrichedUsage = usageWithCreditCount(usage, credits.availableCount)
                var combined = (try await appCache.load()) ?? AppCacheSnapshot(updatedAt: now())
                combined.usage = enrichedUsage
                combined.credits = credits
                combined.lastError = nil
                combined.resetCreditsError = nil
                combined.updatedAt = now()
                try await appCache.save(combined)
                await publishWidgets(
                    mode: .live,
                    usage: enrichedUsage,
                    credits: credits
                )
                return (enrichedUsage, credits)
            } catch {
                await appCache.recordCreditsError(error.localizedDescription, at: now())
                var retained = (try await appCache.load()) ?? cachedAfterUsage
                let fallbackCredits = retained.credits ?? .summary(
                    availableCount: usage.resetCreditsAvailable ?? 0,
                    fetchedAt: usage.fetchedAt
                )
                let enrichedUsage = usageWithCreditCount(usage, fallbackCredits.availableCount)
                retained.usage = enrichedUsage
                retained.credits = fallbackCredits
                retained.lastError = nil
                retained.updatedAt = now()
                try await appCache.save(retained)
                await publishWidgets(
                    mode: .live,
                    usage: enrichedUsage,
                    credits: fallbackCredits
                )
                return (enrichedUsage, fallbackCredits)
            }
        } catch {
            await appCache.recordUsageError(error.localizedDescription, at: now())
            throw error
        }
    }

    private func fetchUsage() async throws -> UsageSnapshot {
        let payload = try await authenticatedRequest(method: "GET", url: backend.usageURL)
        let data = try ensureBackendSuccess(payload, operation: "Usage refresh failed")
        let fetchedAt = now()
        let parsed = try UsageParser.parse(data, fetchedAt: fetchedAt)
        let usage = UsageSnapshot(
            planType: parsed.planType,
            allowed: parsed.allowed,
            limitReached: parsed.limitReached,
            fiveHour: resolvingResetDate(in: parsed.fiveHour, relativeTo: fetchedAt),
            weekly: resolvingResetDate(in: parsed.weekly, relativeTo: fetchedAt),
            resetCreditsAvailable: parsed.resetCreditsAvailable,
            fetchedAt: fetchedAt
        )
        guard usage.fiveHour != nil || usage.weekly != nil else {
            throw CodexServiceError.invalidResponse
        }
        return usage
    }

    private func resolvingResetDate(
        in window: UsageWindow?,
        relativeTo fetchedAt: Date
    ) -> UsageWindow? {
        guard let window else { return nil }
        return UsageWindow(
            usedPercent: window.usedPercent,
            windowSeconds: window.windowSeconds,
            resetAfterSeconds: window.resetAfterSeconds,
            resetAt: window.effectiveResetDate(relativeTo: fetchedAt)
        )
    }

    private func usageWithCreditCount(_ usage: UsageSnapshot, _ count: Int) -> UsageSnapshot {
        UsageSnapshot(
            planType: usage.planType,
            allowed: usage.allowed,
            limitReached: usage.limitReached,
            fiveHour: usage.fiveHour,
            weekly: usage.weekly,
            resetCreditsAvailable: count,
            fetchedAt: usage.fetchedAt
        )
    }

    private func fetchCredits() async throws -> ResetCreditsSnapshot {
        let payload = try await authenticatedRequest(method: "GET", url: backend.creditsURL)
        let data = try ensureBackendSuccess(payload, operation: "Could not load Codex reset credits")
        return try ResetCreditsParser.parse(data, fetchedAt: now())
    }

    /// Sends one request and, only for a 401, refreshes credentials and retries
    /// that request exactly once.
    private func authenticatedRequest(
        method: String,
        url: URL,
        body: Data? = nil
    ) async throws -> HTTPPayload {
        let initialTokens = try await authSession.validTokens()
        var payload = try await sendBackendRequest(
            method: method,
            url: url,
            body: body,
            tokens: initialTokens
        )
        if payload.statusCode == 401 {
            let refreshedTokens = try await authSession.tokensAfterUnauthorized(initialTokens.accessToken)
            payload = try await sendBackendRequest(
                method: method,
                url: url,
                body: body,
                tokens: refreshedTokens
            )
        }
        return payload
    }

    private func sendBackendRequest(
        method: String,
        url: URL,
        body: Data?,
        tokens: AuthTokens
    ) async throws -> HTTPPayload {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(tokens.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(backend.originator, forHTTPHeaderField: "originator")
        request.setValue("codex-meter-ios/1.0", forHTTPHeaderField: "User-Agent")
        if !tokens.accountID.isEmpty {
            request.setValue(tokens.accountID, forHTTPHeaderField: "ChatGPT-Account-Id")
        }
        if body != nil {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return try await HTTPClientSupport.send(request, using: session)
    }

    private func ensureBackendSuccess(_ payload: HTTPPayload, operation: String) throws -> Data {
        let fallback: String
        switch payload.statusCode {
        case 403:
            fallback = "\(operation): this account is not allowed to use this Codex feature."
        case 404:
            fallback = "\(operation): the private Codex endpoint is unavailable or has changed."
        default:
            fallback = "\(operation) (HTTP \(payload.statusCode))."
        }
        return try HTTPClientSupport.requireSuccess(payload, fallback: fallback)
    }

    private func publishWidgets(
        mode: WidgetSnapshotMode,
        usage: UsageSnapshot?,
        credits: ResetCreditsSnapshot?
    ) async {
        do {
            try await widgetCache.publish(
                mode: mode,
                usage: usage,
                credits: credits,
                now: now()
            )
            WidgetCenter.shared.reloadAllTimelines()
            await appCache.clearWidgetError(at: now())
        } catch {
            await appCache.recordWidgetError(error.localizedDescription, at: now())
        }
    }

    private func publishSignedOutWidgets() async {
        do {
            try await widgetCache.publishSignedOut()
            WidgetCenter.shared.reloadAllTimelines()
            await appCache.clearWidgetError(at: now())
        } catch {
            await appCache.recordWidgetError(error.localizedDescription, at: now())
        }
    }
}
