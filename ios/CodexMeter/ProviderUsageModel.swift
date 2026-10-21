import CodexMeterCore
import Observation
import Foundation

/// State for one added provider's tab and its sign-in screen (Copilot, Kimi, Z.ai and the rest).
/// Each provider is independent: its own Keychain item, cache file and sign-out. Usage is
/// fetched when the tab opens with data older than five minutes or on an explicit refresh.
@MainActor
@Observable
final class ProviderUsageModel {
    enum Phase: Equatable {
        case signedOut
        case live
        case demo
    }

    /// Where the shared sign-in screen is.
    enum SignInStep: Equatable {
        case idle
        /// Asking the provider for a code.
        case starting
        /// Showing a code; polling until the user approves it.
        case device(DeviceAuth.DeviceCode)
        /// Checking a pasted token or key.
        case checking
    }

    let provider: UsageProvider
    let entry: ProviderCatalog.Entry

    var phase: Phase = .signedOut
    var usage: UsageSnapshot?
    var accountEmail = ""
    var visibleError: String?
    var isUsingCachedData = false
    var isRefreshing = false

    var signInStep: SignInStep = .idle
    var signInError: String?

    /// Called on the main actor after a sign-in succeeds, so the app can turn the tab on.
    @ObservationIgnored var onSignedIn: (@MainActor (UsageProvider) -> Void)?

    private let service: ProviderUsageService?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var signInTask: Task<Void, Never>?
    @ObservationIgnored private var attempt: ProviderSignInSession?
    @ObservationIgnored private var demoRefreshCount = 0

    init(provider: UsageProvider, service: ProviderUsageService? = nil, preview: Bool = false) {
        self.provider = provider
        self.entry = ProviderCatalog.iOSEntry(for: provider)
        self.service = service ?? ProviderUsageService(provider: provider)
        if preview {
            loadTask = Task {}
            showDemo()
        }
    }

    var accountPlan: String {
        usage?.planType ?? ""
    }

    var isSignedIn: Bool {
        phase != .signedOut
    }

    var supportsDeviceSignIn: Bool {
        entry.signIn == .deviceCode
    }

    var supportsPaste: Bool {
        entry.signIn == .codePaste
    }

    var pasteHint: String {
        service?.pasteHint ?? ""
    }

    /// Loads Keychain and cache state once, without touching the network.
    func loadIfNeeded() async {
        if let loadTask {
            await loadTask.value
            return
        }
        let task = Task { await self.loadSavedSession() }
        loadTask = task
        await task.value
    }

    private func loadSavedSession() async {
        guard phase != .demo, let service else { return }
        guard let tokens = try? await service.currentTokens(), tokens.isUsable else {
            phase = .signedOut
            return
        }
        phase = .live
        accountEmail = tokens.email
        if let cached = await service.cachedSnapshot() {
            usage = cached.usage
            isUsingCachedData = cached.usage != nil
            if let error = cached.lastError, cached.usage == nil {
                visibleError = error
            }
        }
    }

    /// Called whenever this provider's tab becomes visible.
    func tabOpened() async {
        await loadIfNeeded()
        switch phase {
        case .signedOut:
            return
        case .demo:
            if usage == nil { showDemo() }
        case .live:
            if usage == nil || usage?.isStale(at: .now, maxAge: ProviderUsageService.staleAfter) == true {
                await refresh()
            }
        }
    }

    func refresh() async {
        guard !isRefreshing, let service else { return }
        switch phase {
        case .signedOut:
            return
        case .demo:
            demoRefreshCount += 1
            showDemo()
            return
        case .live:
            break
        }
        isRefreshing = true
        defer { isRefreshing = false }
        DiagnosticLog.info("refresh", "provider_usage_refresh_started", details: ["provider": provider.rawValue])
        do {
            let snapshot = try await service.refreshUsage()
            usage = snapshot
            ProviderWidgetPublisher.publish(provider, demo: false, usage: snapshot)
            visibleError = nil
            isUsingCachedData = false
            if let tokens = try? await service.currentTokens() {
                accountEmail = tokens.email
            }
            DiagnosticLog.info("refresh", "provider_usage_refresh_succeeded", details: ["provider": provider.rawValue])
        } catch is CancellationError {
            return
        } catch {
            DiagnosticLog.error("refresh", "provider_usage_refresh_failed", error: error,
                                details: ["provider": provider.rawValue])
            visibleError = error.localizedDescription
            isUsingCachedData = usage != nil
            if (error as? ProviderClientError) == .signedOut {
                clearState()
            }
        }
    }

    // MARK: Sign-in

    /// Asks the provider for a device code, then polls until the user approves it.
    func startDeviceSignIn() {
        guard let service else { return }
        cancelSignIn()
        let attempt = ProviderSignInSession()
        self.attempt = attempt
        signInStep = .starting
        DiagnosticLog.info("oauth", "provider_device_started", details: ["provider": provider.rawValue])
        signInTask = Task { await self.runDeviceSignIn(service, attempt) }
    }

    private func runDeviceSignIn(_ service: ProviderUsageService, _ attempt: ProviderSignInSession) async {
        do {
            let code = try await service.startDevice(attempt)
            guard self.attempt === attempt else { return }
            signInStep = .device(code)
            var interval = TimeInterval(code.intervalSeconds)
            let deadline = Date().addingTimeInterval(TimeInterval(code.expiresInSeconds))
            var failures = 0
            while true {
                try await Task.sleep(for: .seconds(interval))
                guard self.attempt === attempt else { return }
                guard Date() < deadline else {
                    throw ProviderClientError.message("The code expired. Start again.")
                }
                let poll: DeviceAuth.Poll
                do {
                    poll = try await service.pollDevice(attempt)
                    failures = 0
                } catch let error as URLError where error.code != .cancelled {
                    failures += 1
                    if failures >= 5 { throw error }
                    continue
                }
                switch poll.state {
                case .pending:
                    continue
                case .slowDown:
                    interval += 5
                case .done:
                    guard let tokens = poll.tokens else { continue }
                    let stored = try await service.finishDevice(attempt, tokens: tokens)
                    guard self.attempt === attempt else { return }
                    await signedIn(stored)
                    return
                case .denied, .expired, .failed:
                    throw ProviderClientError.message(poll.message.isEmpty ? "Sign-in failed. Start again." : poll.message)
                }
            }
        } catch is CancellationError {
            return
        } catch {
            guard self.attempt === attempt else { return }
            DiagnosticLog.error("oauth", "provider_sign_in_failed", error: error, details: ["provider": provider.rawValue])
            signInError = error.localizedDescription
            signInStep = .idle
        }
    }

    /// The page that shows a token to paste back, for a fresh attempt.
    func beginPasteSignIn() -> URL? {
        guard let service else { return nil }
        cancelSignIn()
        let attempt = ProviderSignInSession()
        self.attempt = attempt
        return service.pasteURL(attempt)
    }

    func submitPaste(_ text: String) {
        guard let service else { return }
        let attempt = attempt ?? ProviderSignInSession()
        self.attempt = attempt
        run { try await service.finishPaste(attempt, pasted: text) }
    }

    func submitAPIKey(_ key: String) {
        guard let service else { return }
        cancelSignIn()
        let attempt = ProviderSignInSession()
        self.attempt = attempt
        run { try await service.connect(apiKey: key) }
    }

    private func run(_ operation: @escaping @MainActor () async throws -> AuthTokens) {
        signInTask?.cancel()
        let attempt = attempt
        signInError = nil
        signInStep = .checking
        signInTask = Task {
            do {
                let tokens = try await operation()
                guard self.attempt === attempt else { return }
                await self.signedIn(tokens)
            } catch is CancellationError {
                return
            } catch {
                guard self.attempt === attempt else { return }
                DiagnosticLog.error("oauth", "provider_sign_in_failed", error: error,
                                    details: ["provider": self.provider.rawValue])
                self.signInError = error.localizedDescription
                self.signInStep = .idle
            }
        }
    }

    private func signedIn(_ tokens: AuthTokens) async {
        DiagnosticLog.info("oauth", "provider_signed_in", details: ["provider": provider.rawValue])
        attempt = nil
        signInTask = nil
        signInStep = .idle
        signInError = nil
        phase = .live
        accountEmail = tokens.email
        usage = nil
        visibleError = nil
        isUsingCachedData = false
        onSignedIn?(provider)
        await refresh()
    }

    func cancelSignIn() {
        signInTask?.cancel()
        signInTask = nil
        attempt = nil
        signInStep = .idle
        signInError = nil
    }

    func signOut() async {
        DiagnosticLog.info("process", "provider_sign_out", details: ["provider": provider.rawValue])
        await service?.signOut()
        clearState()
        ProviderWidgetPublisher.publishSignedOut(provider)
    }

    // MARK: Demo

    /// Local data only, never contacts the provider.
    func enterDemo() {
        demoRefreshCount = 0
        showDemo()
    }

    func leaveDemo() async {
        guard phase == .demo else { return }
        clearState()
        loadTask = nil
        await loadIfNeeded()
        if phase == .live {
            ProviderWidgetPublisher.publish(provider, demo: false, usage: usage)
        } else {
            ProviderWidgetPublisher.publishSignedOut(provider)
        }
    }

    private func showDemo() {
        phase = .demo
        accountEmail = "demo@local"
        visibleError = nil
        isUsingCachedData = false
        usage = Self.demoSnapshot(for: provider, at: .now, refreshCount: demoRefreshCount)
        ProviderWidgetPublisher.publish(provider, demo: true, usage: usage)
    }

    private func clearState() {
        cancelSignIn()
        phase = .signedOut
        usage = nil
        accountEmail = ""
        visibleError = nil
        isUsingCachedData = false
    }

    static func demoSnapshot(for provider: UsageProvider, at now: Date, refreshCount: Int = 0) -> UsageSnapshot {
        let hour: Int64 = 3_600
        func window(_ percent: Int, _ seconds: Int64, resetIn: TimeInterval) -> UsageWindow {
            UsageWindow(usedPercent: percent, windowSeconds: seconds, resetAt: now.addingTimeInterval(resetIn))
        }
        return UsageSnapshot(
            planType: "Pro (Demo)",
            allowed: true,
            limitReached: false,
            fiveHour: window(min(100, 42 + refreshCount), 5 * hour, resetIn: 2 * 3_600 + 14 * 60),
            weekly: window(27, 7 * 24 * hour, resetIn: 4 * 86_400 + 6 * 3_600),
            monthly: nil,
            fetchedAt: now
        )
    }
}
