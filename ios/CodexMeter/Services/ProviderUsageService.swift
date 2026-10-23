import CodexMeterCore
import Foundation

/// Sign-in storage and usage refresh for one provider added through Settings → Accounts.
/// Mirrors Android's `ProviderUsageApi`: each provider keeps its own Keychain item and cache
/// file, so signing one out never touches another.
actor ProviderUsageService {
    /// Opening the tab only re-fetches when the cached data is older than this.
    nonisolated static let staleAfter: TimeInterval = 5 * 60
    /// Tokens this close to expiry are refreshed before the usage call.
    nonisolated static let refreshMargin: TimeInterval = 5 * 60

    nonisolated let provider: UsageProvider
    private let client: any ProviderClient
    private let tokenStore: any TokenStoring
    private let session: URLSession
    private let cache: AppCacheStore
    private let now: @Sendable () -> Date
    private var activeRefresh: Task<UsageSnapshot, Error>?

    nonisolated static func keychainAccount(for provider: UsageProvider) -> String {
        "provider-\(provider.rawValue)-tokens"
    }

    nonisolated static func cacheFileName(for provider: UsageProvider) -> String {
        "provider-\(provider.rawValue)-cache-v1.json"
    }

    /// Nil for Codex, Claude Code, Cursor and providers the iPhone app can't connect.
    init?(
        provider: UsageProvider,
        tokenStore: (any TokenStoring)? = nil,
        session: URLSession? = nil,
        cache: AppCacheStore? = nil,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        guard let client = ProviderClients.client(for: provider) else { return nil }
        self.provider = provider
        self.client = client
        self.tokenStore = tokenStore ?? KeychainTokenStore(account: Self.keychainAccount(for: provider))
        self.session = session ?? HTTPClientSupport.session()
        self.cache = cache ?? AppCacheStore(fileURL: AppCacheStore.defaultDirectoryURL()
            .appendingPathComponent(Self.cacheFileName(for: provider)))
        self.now = now
    }

    func currentTokens() async throws -> AuthTokens? {
        try await tokenStore.load()
    }

    func cachedSnapshot() async -> AppCacheSnapshot? {
        try? await cache.load()
    }

    // MARK: Sign-in

    func startDevice(_ attempt: ProviderSignInSession) async throws -> DeviceAuth.DeviceCode {
        try await client.startDevice(attempt, http: session)
    }

    func pollDevice(_ attempt: ProviderSignInSession) async throws -> DeviceAuth.Poll {
        try await client.pollDevice(attempt, http: session)
    }

    func finishDevice(_ attempt: ProviderSignInSession, tokens: DeviceAuth.Tokens) async throws -> AuthTokens {
        try await store(client.finishDevice(attempt, tokens: tokens, http: session))
    }

    nonisolated func pasteURL(_ attempt: ProviderSignInSession) -> URL? {
        client.pasteURL(attempt).flatMap(URL.init(string:))
    }

    nonisolated var pasteHint: String {
        client.pasteHint
    }

    func finishPaste(_ attempt: ProviderSignInSession, pasted: String) async throws -> AuthTokens {
        try await store(client.finishPaste(attempt, pasted: pasted, http: session))
    }

    func connect(apiKey: String) async throws -> AuthTokens {
        try await store(client.fromAPIKey(apiKey, http: session))
    }

    private func store(_ tokens: AuthTokens) async throws -> AuthTokens {
        guard tokens.isUsable else {
            throw ProviderClientError.message("\(provider.displayName) didn't return a usable sign-in.")
        }
        try await tokenStore.save(tokens)
        // A different account may have been signed in before.
        try? await cache.clear()
        return tokens
    }

    func signOut() async {
        activeRefresh?.cancel()
        activeRefresh = nil
        try? await tokenStore.delete()
        try? await cache.clear()
    }

    // MARK: Usage

    func refreshUsage() async throws -> UsageSnapshot {
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

    private func performRefresh() async throws -> UsageSnapshot {
        do {
            var tokens = try await usableTokens()
            var fetched: ProviderFetch
            do {
                fetched = try await client.fetch(tokens, http: session)
            } catch let ProviderClientError.unauthorized(message) {
                guard client.canRefresh(tokens) else {
                    throw ProviderClientError.unauthorized("\(message) Sign in again from Settings → Accounts.")
                }
                tokens = try await refreshTokens(tokens)
                fetched = try await client.fetch(tokens, http: session)
            }

            var snapshot = fetched.snapshot
            if snapshot.planType.isEmpty, let previous = try? await cache.load()?.usage, !previous.planType.isEmpty {
                snapshot = snapshot.replacingPlan(previous.planType)
            }
            if tokens.email.isEmpty, !fetched.email.isEmpty {
                try? await tokenStore.save(tokens.with(email: fetched.email))
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
            throw ProviderClientError.signedOut
        }
        guard client.canRefresh(tokens), tokens.expiresAt <= now().addingTimeInterval(Self.refreshMargin) else {
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
        let refreshed = try await client.refresh(previous, http: session)
        guard refreshed.isUsable else {
            throw ProviderClientError.unauthorized(
                "Your \(provider.displayName) sign-in expired. Sign in again from Settings → Accounts."
            )
        }
        try await tokenStore.save(refreshed)
        return refreshed
    }
}

extension UsageSnapshot {
    /// The same snapshot with another plan label.
    func replacingPlan(_ plan: String) -> UsageSnapshot {
        UsageSnapshot(
            planType: plan,
            allowed: allowed,
            limitReached: limitReached,
            fiveHour: fiveHour,
            weekly: weekly,
            monthly: monthly,
            resetCreditsAvailable: resetCreditsAvailable,
            additionalLimits: additionalLimits,
            usageCredits: usageCredits,
            spendControl: spendControl,
            fetchedAt: fetchedAt
        )
    }
}
