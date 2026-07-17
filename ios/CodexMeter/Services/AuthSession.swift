import Foundation

/// Serializes refresh-token rotation so concurrent API calls cannot overwrite a
/// newly rotated refresh token with an older credential set.
public actor AuthSession {
    private let configuration: OpenAIAuthConfiguration
    private let tokenStore: any TokenStoring
    private let session: URLSession
    private let now: @Sendable () -> Date
    private var refreshTask: Task<AuthTokens, Error>?

    public init(
        configuration: OpenAIAuthConfiguration = .init(),
        tokenStore: any TokenStoring = KeychainTokenStore(),
        session: URLSession? = nil,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.configuration = configuration
        self.tokenStore = tokenStore
        self.session = session ?? HTTPClientSupport.session()
        self.now = now
    }

    public func currentTokens() async throws -> AuthTokens? {
        try await tokenStore.load()
    }

    public func validTokens() async throws -> AuthTokens {
        guard let tokens = try await tokenStore.load(), tokens.isUsable else {
            throw CodexServiceError.signedOut
        }
        if tokens.shouldRefresh(at: now()) {
            return try await refresh(tokens)
        }
        return tokens
    }

    /// Refreshes only when the rejected access token is still current. If a
    /// concurrent request already rotated it, that newer token is reused.
    public func tokensAfterUnauthorized(_ rejectedAccessToken: String) async throws -> AuthTokens {
        guard let current = try await tokenStore.load(), current.isUsable else {
            throw CodexServiceError.signedOut
        }
        if current.accessToken != rejectedAccessToken {
            return current
        }
        return try await refresh(current)
    }

    public func store(_ tokens: AuthTokens) async throws {
        try await tokenStore.save(tokens)
    }

    public func signOut() async {
        let tokens = try? await tokenStore.load()
        if let tokens {
            await revokeBestEffort(tokens)
        }
        try? await tokenStore.delete()
        refreshTask?.cancel()
        refreshTask = nil
    }

    private func refresh(_ previous: AuthTokens) async throws -> AuthTokens {
        if let refreshTask {
            return try await refreshTask.value
        }

        let configuration = configuration
        let session = session
        let tokenStore = tokenStore
        let date = now()
        let task = Task<AuthTokens, Error> {
            let request = try HTTPClientSupport.jsonRequest(
                url: configuration.tokenURL,
                body: [
                    "grant_type": "refresh_token",
                    "refresh_token": previous.refreshToken,
                    "client_id": configuration.clientID
                ]
            )
            let payload = try await HTTPClientSupport.send(request, using: session)
            let data = try HTTPClientSupport.requireSuccess(
                payload,
                fallback: "ChatGPT session refresh failed (HTTP \(payload.statusCode))."
            )
            let refreshed = try TokenResponseDecoder.refreshed(
                from: data,
                previous: previous,
                now: date
            )
            try await tokenStore.save(refreshed)
            return refreshed
        }
        refreshTask = task
        defer { refreshTask = nil }
        return try await task.value
    }

    private func revokeBestEffort(_ tokens: AuthTokens) async {
        guard !tokens.refreshToken.isEmpty else { return }
        let body = [
            "token": tokens.refreshToken,
            "token_type_hint": "refresh_token",
            "client_id": configuration.clientID
        ]

        if let request = try? HTTPClientSupport.jsonRequest(url: configuration.revokeURL, body: body),
           let response = try? await HTTPClientSupport.send(request, using: session),
           (200..<300).contains(response.statusCode) {
            return
        }
        let request = HTTPClientSupport.formRequest(url: configuration.revokeURL, fields: body)
        _ = try? await HTTPClientSupport.send(request, using: session)
    }
}
