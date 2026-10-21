import CodexMeterCore
import Foundation

nonisolated public enum ClaudeServiceError: Error, Sendable, Equatable {
    case signedOut
    case message(String)
}

extension ClaudeServiceError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .signedOut:
            "Sign in with Claude first."
        case let .message(message):
            message
        }
    }
}

/// Claude Code subscription sign-in and usage refresh, using the routes the Claude Code CLI
/// calls for `/login` and `/usage`.
///
/// Credentials use their own Keychain item and the usage snapshot its own cache file, so the
/// Codex session is never touched. The usage route rate-limits aggressively, so Claude usage
/// is only fetched when the user opens or refreshes the Claude Code tab, never in background
/// refresh.
public actor ClaudeCodeService {
    public nonisolated static let keychainAccount = "claude-code-tokens"
    /// Opening the tab only re-fetches when the cached data is older than this.
    public nonisolated static let staleAfter: TimeInterval = 5 * 60

    private let tokenStore: any TokenStoring
    private let session: URLSession
    private let cache: AppCacheStore
    private let now: @Sendable () -> Date
    private var activeRefresh: Task<UsageSnapshot, Error>?

    public init(
        tokenStore: any TokenStoring = KeychainTokenStore(account: ClaudeCodeService.keychainAccount),
        session: URLSession? = nil,
        cache: AppCacheStore = .claude,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.tokenStore = tokenStore
        self.session = session ?? Self.makeSession()
        self.cache = cache
        self.now = now
    }

    public func currentTokens() async throws -> AuthTokens? {
        try await tokenStore.load()
    }

    public func cachedSnapshot() async -> AppCacheSnapshot? {
        try? await cache.load()
    }

    /// Exchanges the code the user pasted from Claude's callback page for tokens.
    public func signIn(pasted text: String, request: ClaudeAuthorizationRequest) async throws -> AuthTokens {
        let code = try request.pastedCode(text)
        let body = ClaudeCodeAPI.codeExchangeBody(
            code: code,
            redirectURI: request.redirectURI,
            verifier: request.verifier,
            state: request.state
        )
        let payload = try await postToken(body)
        let response = try decodeTokens(
            payload,
            fallback: "Claude sign-in failed (HTTP \(payload.statusCode))."
        )
        let tokens = AuthTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            idToken: "",
            expiresAt: now().addingTimeInterval(response.expiresIn),
            accountID: response.organizationID,
            email: response.email
        )
        guard tokens.isUsable else {
            throw ClaudeAuthorizationError.incompleteCredentials
        }
        try await tokenStore.save(tokens)
        // A different account may have been signed in before; never show its numbers.
        try? await cache.clear()
        return tokens
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
            var usage = try await get(ClaudeCodeAPI.usageURL, tokens: tokens)
            if usage.statusCode == 401 {
                tokens = try await refreshTokens(tokens)
                usage = try await get(ClaudeCodeAPI.usageURL, tokens: tokens)
            }
            guard (200..<300).contains(usage.statusCode) else {
                throw ClaudeServiceError.message(Self.usageErrorMessage(usage))
            }

            // The profile only decorates the plan badge and email; never fail usage over it.
            var plan = ""
            if let profile = try? await get(ClaudeCodeAPI.profileURL, tokens: tokens),
               (200..<300).contains(profile.statusCode) {
                plan = ClaudeUsageParser.planLabel(profile: profile.data)
                let email = ClaudeUsageParser.email(profile: profile.data)
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

            let snapshot = try ClaudeUsageParser.parse(usage.data, planLabel: plan, fetchedAt: now())
            guard snapshot.fiveHour != nil || snapshot.weekly != nil
                    || !snapshot.additionalLimits.isEmpty else {
                throw ClaudeServiceError.message(
                    "Anthropic returned no recognizable Claude Code usage data."
                )
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
            throw ClaudeServiceError.signedOut
        }
        if tokens.shouldRefresh(at: now()) {
            return try await refreshTokens(tokens)
        }
        return tokens
    }

    private func refreshTokens(_ previous: AuthTokens) async throws -> AuthTokens {
        let payload = try await postToken(
            ClaudeCodeAPI.refreshBody(refreshToken: previous.refreshToken)
        )
        if payload.statusCode == 400 || payload.statusCode == 401 {
            throw ClaudeServiceError.message(
                "Claude rejected the saved sign-in. Sign in with Claude again."
            )
        }
        let response = try decodeTokens(
            payload,
            fallback: "Claude session refresh failed (HTTP \(payload.statusCode))."
        )
        let refreshed = AuthTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken.isEmpty ? previous.refreshToken : response.refreshToken,
            idToken: "",
            expiresAt: now().addingTimeInterval(response.expiresIn),
            accountID: response.organizationID.isEmpty ? previous.accountID : response.organizationID,
            email: response.email.isEmpty ? previous.email : response.email
        )
        guard refreshed.isUsable else {
            throw ClaudeAuthorizationError.incompleteCredentials
        }
        try await tokenStore.save(refreshed)
        return refreshed
    }

    /// Form body first; a 400/415 that is not a definitive grant error gets one JSON retry.
    private func postToken(_ body: [String: String]) async throws -> HTTPPayload {
        var form = URLRequest(url: ClaudeCodeAPI.tokenURL)
        form.httpMethod = "POST"
        form.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        form.setValue("application/json", forHTTPHeaderField: "Accept")
        form.httpBody = Data(
            ClaudeCodeAPI.encode(body.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }).utf8
        )
        let payload = try await HTTPClientSupport.send(form, using: session)
        guard ClaudeCodeAPI.shouldRetryAsJSON(statusCode: payload.statusCode, body: payload.data) else {
            return payload
        }
        let json = try HTTPClientSupport.jsonRequest(url: ClaudeCodeAPI.tokenURL, body: body)
        return try await HTTPClientSupport.send(json, using: session)
    }

    private func decodeTokens(_ payload: HTTPPayload, fallback: String) throws -> ClaudeTokenResponse {
        guard (200..<300).contains(payload.statusCode) else {
            throw ClaudeServiceError.message(Self.oauthErrorMessage(payload.data, fallback: fallback))
        }
        return try ClaudeTokenResponse.parse(payload.data)
    }

    private func get(_ url: URL, tokens: AuthTokens) async throws -> HTTPPayload {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(tokens.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(ClaudeCodeAPI.oauthBeta, forHTTPHeaderField: "anthropic-beta")
        return try await HTTPClientSupport.send(request, using: session)
    }

    nonisolated static func usageErrorMessage(_ payload: HTTPPayload) -> String {
        switch payload.statusCode {
        case 401:
            "Claude rejected the saved sign-in. Sign in with Claude again."
        case 403:
            "Claude usage access was denied for this account. Usage limits are only available for Pro, Max, Team, and Enterprise plans."
        case 404:
            "The Claude usage endpoint is unavailable or has changed."
        case 429:
            "Claude is rate-limiting usage checks. Try again in a few minutes."
        default:
            HTTPClientSupport.serverMessage(
                from: payload.data,
                fallback: "Claude usage refresh failed (HTTP \(payload.statusCode))."
            )
        }
    }

    /// OAuth errors use `error_description`; API errors use `error.message`.
    nonisolated static func oauthErrorMessage(_ data: Data, fallback: String) -> String {
        if let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
           let description = object["error_description"] as? String,
           !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return String(description.prefix(240))
        }
        return HTTPClientSupport.serverMessage(from: data, fallback: fallback)
    }

    /// The token route has been slow during incidents, so sign-in gets a longer budget than
    /// the shared 25-second resource timeout.
    nonisolated static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpShouldSetCookies = false
        configuration.httpAdditionalHeaders = [
            "Accept": "application/json",
            "User-Agent": "codex-meter-ios/2.8.0"
        ]
        return URLSession(configuration: configuration)
    }
}
