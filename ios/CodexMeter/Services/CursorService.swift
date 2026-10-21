import CodexMeterCore
import Foundation

nonisolated public enum CursorServiceError: Error, Sendable, Equatable {
    case signedOut
    case message(String)
}

extension CursorServiceError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .signedOut:
            "Sign in with Cursor first."
        case let .message(message):
            message
        }
    }
}

/// Cursor sign-in and usage refresh, using the routes the Cursor editor and CLI call for login
/// and the in-editor usage meter. Mirrors Android's `CursorUsageApi`.
///
/// Credentials use their own Keychain item and the usage snapshot its own cache file, so the
/// Codex and Claude sessions are never touched.
public actor CursorService {
    public nonisolated static let keychainAccount = "cursor-tokens"
    /// Opening the tab only re-fetches when the cached data is older than this.
    public nonisolated static let staleAfter: TimeInterval = 5 * 60

    private let tokenStore: any TokenStoring
    private let session: URLSession
    private let cache: AppCacheStore
    private let now: @Sendable () -> Date
    private var activeRefresh: Task<UsageSnapshot, Error>?

    public init(
        tokenStore: any TokenStoring = KeychainTokenStore(account: CursorService.keychainAccount),
        session: URLSession? = nil,
        cache: AppCacheStore = .cursor,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.tokenStore = tokenStore
        self.session = session ?? HTTPClientSupport.session()
        self.cache = cache
        self.now = now
    }

    public func currentTokens() async throws -> AuthTokens? {
        try await tokenStore.load()
    }

    public func cachedSnapshot() async -> AppCacheSnapshot? {
        try? await cache.load()
    }

    /// Polls until the user approves the browser sign-in. Cursor answers 404 while it waits
    /// and 200 with tokens once the login page is approved.
    public func signIn(request: CursorLoginRequest) async throws -> AuthTokens {
        let deadline = now().addingTimeInterval(CursorAPI.pollTimeout)
        var failures = 0
        while true {
            try Task.checkCancellation()
            guard now() < deadline else {
                throw CursorServiceError.message("Sign-in timed out. Start again from the app.")
            }
            do {
                var poll = URLRequest(url: request.pollURL)
                poll.httpMethod = "GET"
                let payload = try await HTTPClientSupport.send(poll, using: session)
                switch payload.statusCode {
                case 200:
                    let response = CursorTokenResponse.parse(payload.data)
                    if !response.accessToken.isEmpty {
                        let tokens = makeTokens(response, previous: nil)
                        try await tokenStore.save(tokens)
                        // A different account may have been signed in before.
                        try? await cache.clear()
                        return tokens
                    }
                    failures += 1
                case 404:
                    failures = 0
                case 403:
                    throw CursorServiceError.message(
                        "Cursor refused this sign-in. Start again from the app."
                    )
                default:
                    failures += 1
                }
            } catch let error as URLError where error.code != .cancelled {
                failures += 1
            }
            if failures >= 5 {
                throw CursorServiceError.message(
                    "Cursor sign-in could not be confirmed. Check your connection and try again."
                )
            }
            try await Task.sleep(for: .seconds(CursorAPI.pollInterval))
        }
    }

    public func refreshUsage() async throws -> UsageSnapshot {
        if let activeRefresh {
            return try await activeRefresh.value
        }
        let task = Task<UsageSnapshot, Error> {
            try await self.performRefresh()
        }
        activeRefresh = task
        defer { activeRefresh = nil }
        return try await task.value
    }

    public func signOut() async {
        activeRefresh?.cancel()
        activeRefresh = nil
        try? await tokenStore.delete()
        try? await cache.clear()
    }

    private func performRefresh() async throws -> UsageSnapshot {
        do {
            var tokens = try await usableTokens()
            var usage = try await post(CursorAPI.usageURL, body: [:], bearer: tokens.accessToken)
            if CursorAPI.isUnauthenticated(statusCode: usage.statusCode, body: usage.data) {
                tokens = try await refreshTokens(tokens)
                usage = try await post(CursorAPI.usageURL, body: [:], bearer: tokens.accessToken)
            }
            if CursorAPI.isUnauthenticated(statusCode: usage.statusCode, body: usage.data) {
                throw CursorServiceError.message(
                    "Cursor rejected the saved sign-in. Sign in with Cursor again."
                )
            }
            guard (200..<300).contains(usage.statusCode) else {
                throw CursorServiceError.message(Self.usageErrorMessage(usage))
            }

            // The profile only decorates the plan badge and email; never fail usage over it.
            var plan = ""
            if let profile = try? await get(CursorAPI.profileURL, bearer: tokens.accessToken),
               (200..<300).contains(profile.statusCode) {
                plan = CursorUsageParser.planLabel(profile: profile.data)
                let email = CursorUsageParser.email(profile: profile.data)
                if !email.isEmpty, email != tokens.email {
                    tokens = AuthTokens(
                        accessToken: tokens.accessToken,
                        refreshToken: tokens.refreshToken,
                        idToken: tokens.idToken,
                        expiresAt: tokens.expiresAt,
                        accountID: tokens.accountID,
                        email: email
                    )
                    try? await tokenStore.save(tokens)
                }
            }
            if plan.isEmpty, let previous = try? await cache.load()?.usage {
                plan = previous.planType
            }

            let snapshot = try CursorUsageParser.parse(usage.data, planLabel: plan, fetchedAt: now())
            guard snapshot.monthly != nil || !snapshot.additionalLimits.isEmpty else {
                throw CursorServiceError.message("Cursor returned no recognizable usage data.")
            }
            try await cache.updateUsage(snapshot, at: now())
            return snapshot
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            await cache.recordUsageError(error.localizedDescription, at: now())
            throw error
        }
    }

    private func usableTokens() async throws -> AuthTokens {
        guard let tokens = try await tokenStore.load(), tokens.isUsable else {
            throw CursorServiceError.signedOut
        }
        guard tokens.expiresAt <= now().addingTimeInterval(CursorAPI.refreshMargin) else {
            return tokens
        }
        do {
            return try await refreshTokens(tokens)
        } catch {
            // A token that has not expired yet still works; only fail once it has.
            if tokens.expiresAt <= now() { throw error }
            return tokens
        }
    }

    private func refreshTokens(_ previous: AuthTokens) async throws -> AuthTokens {
        let payload = try await post(
            CursorAPI.tokenURL,
            body: CursorAPI.refreshBody(refreshToken: previous.refreshToken),
            bearer: nil
        )
        let response = CursorTokenResponse.parse(payload.data)
        if response.shouldLogout || payload.statusCode == 401 {
            throw CursorServiceError.message("Your Cursor sign-in expired. Sign in with Cursor again.")
        }
        guard (200..<300).contains(payload.statusCode), !response.accessToken.isEmpty else {
            throw CursorServiceError.message(HTTPClientSupport.serverMessage(
                from: payload.data,
                fallback: "Cursor sign-in refresh failed (HTTP \(payload.statusCode))."
            ))
        }
        let refreshed = makeTokens(response, previous: previous)
        try await tokenStore.save(refreshed)
        return refreshed
    }

    private func makeTokens(_ response: CursorTokenResponse, previous: AuthTokens?) -> AuthTokens {
        let email = CursorAPI.email(in: response.accessToken)
        return AuthTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken.isEmpty
                ? (previous?.refreshToken ?? response.accessToken) : response.refreshToken,
            idToken: "",
            expiresAt: CursorAPI.expiry(of: response.accessToken)
                ?? now().addingTimeInterval(24 * 60 * 60),
            accountID: response.authID.isEmpty ? (previous?.accountID ?? "") : response.authID,
            email: email.isEmpty ? (previous?.email ?? "") : email
        )
    }

    private func post(_ url: URL, body: [String: String], bearer: String?) async throws -> HTTPPayload {
        var request = try HTTPClientSupport.jsonRequest(url: url, body: body)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("1", forHTTPHeaderField: "Connect-Protocol-Version")
        request.setValue(UUID().uuidString.lowercased(), forHTTPHeaderField: "X-Request-Id")
        if let bearer {
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        return try await HTTPClientSupport.send(request, using: session)
    }

    private func get(_ url: URL, bearer: String) async throws -> HTTPPayload {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        return try await HTTPClientSupport.send(request, using: session)
    }

    nonisolated static func usageErrorMessage(_ payload: HTTPPayload) -> String {
        if CursorAPI.isBotCheckpoint(statusCode: payload.statusCode, body: payload.data) {
            return "Cursor's bot protection blocked the usage check. Try again in a few minutes."
        }
        switch payload.statusCode {
        case 403:
            return "Cursor denied usage access for this account."
        case 404:
            return "The Cursor usage endpoint is unavailable or has changed."
        case 429:
            return "Cursor is rate-limiting usage checks. Try again in a few minutes."
        default:
            return HTTPClientSupport.serverMessage(
                from: payload.data,
                fallback: "Cursor usage refresh failed (HTTP \(payload.statusCode))."
            )
        }
    }
}
