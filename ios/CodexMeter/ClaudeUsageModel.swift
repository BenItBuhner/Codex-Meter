import CodexMeterCore
import Observation
import SwiftUI

/// State for the Claude Code tab. Independent from the Codex session in `AppModel`: its own
/// Keychain item, cache file, and sign-out. Usage is fetched only when the tab opens with data
/// older than five minutes or on an explicit refresh — never in background refresh, because
/// the usage route rate-limits polling.
@MainActor
@Observable
final class ClaudeUsageModel {
    enum Phase: Equatable {
        case signedOut
        case live
        case demo
    }

    var phase: Phase = .signedOut
    var usage: UsageSnapshot?
    var accountEmail = ""
    var visibleError: String?
    var isUsingCachedData = false
    var isRefreshing = false
    var isShowingSignIn = false

    // Sign-in sheet state.
    var authorization: ClaudeAuthorizationRequest?
    var pastedCode = ""
    var authenticationError: String?
    var isAuthenticating = false

    private let service: ClaudeCodeService
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var demoRefreshCount = 0

    init(service: ClaudeCodeService = ClaudeCodeService(), preview: Bool = false) {
        self.service = service
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
        guard phase != .demo else { return }
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

    /// Called whenever the Claude Code tab becomes visible.
    func tabOpened() async {
        await loadIfNeeded()
        switch phase {
        case .signedOut:
            return
        case .demo:
            if usage == nil { showDemo() }
        case .live:
            if usage == nil || usage?.isStale(at: .now, maxAge: ClaudeCodeService.staleAfter) == true {
                await refresh()
            }
        }
    }

    func refresh() async {
        guard !isRefreshing else { return }
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
        DiagnosticLog.info("refresh", "claude_usage_refresh_started")
        do {
            let snapshot = try await service.refreshUsage()
            usage = snapshot
            visibleError = nil
            isUsingCachedData = false
            if let tokens = try? await service.currentTokens() {
                accountEmail = tokens.email
            }
            DiagnosticLog.info("refresh", "claude_usage_refresh_succeeded", details: [
                "five_hour": snapshot.fiveHour != nil ? "true" : "false",
                "weekly": snapshot.weekly != nil ? "true" : "false",
                "additional_limits": String(snapshot.additionalLimits.count)
            ])
        } catch is CancellationError {
            return
        } catch {
            DiagnosticLog.error("refresh", "claude_usage_refresh_failed", error: error)
            visibleError = error.localizedDescription
            isUsingCachedData = usage != nil
            if (error as? ClaudeServiceError) == .signedOut {
                clearState()
            }
        }
    }

    // MARK: Sign-in

    /// Starts a fresh PKCE attempt and returns the URL to open in the browser.
    func beginSignIn() -> URL {
        let request = ClaudeAuthorizationRequest.make()
        authorization = request
        pastedCode = ""
        authenticationError = nil
        DiagnosticLog.info("oauth", "claude_authorize_opened")
        return request.authorizeURL
    }

    func completeSignIn() async {
        guard let authorization, !isAuthenticating else { return }
        isAuthenticating = true
        authenticationError = nil
        defer { isAuthenticating = false }
        do {
            let tokens = try await service.signIn(pasted: pastedCode, request: authorization)
            DiagnosticLog.info("oauth", "claude_signed_in")
            phase = .live
            accountEmail = tokens.email
            usage = nil
            visibleError = nil
            isUsingCachedData = false
            self.authorization = nil
            pastedCode = ""
            isShowingSignIn = false
            await refresh()
        } catch {
            DiagnosticLog.error("oauth", "claude_sign_in_failed", error: error)
            authenticationError = error.localizedDescription
        }
    }

    func cancelSignIn() {
        authorization = nil
        pastedCode = ""
        authenticationError = nil
        isAuthenticating = false
    }

    func signOut() async {
        DiagnosticLog.info("process", "claude_sign_out")
        await service.signOut()
        clearState()
    }

    // MARK: Demo

    /// Mirrors the Codex demo: local data only, never contacts Anthropic.
    func enterDemo() {
        demoRefreshCount = 0
        showDemo()
    }

    /// Leaving the Codex demo also leaves the Claude demo, then restores any real session.
    func leaveDemo() async {
        guard phase == .demo else { return }
        clearState()
        loadTask = nil
        await loadIfNeeded()
    }

    private func showDemo() {
        phase = .demo
        accountEmail = "demo@local"
        visibleError = nil
        isUsingCachedData = false
        usage = Self.demoSnapshot(at: .now, refreshCount: demoRefreshCount)
    }

    private func clearState() {
        phase = .signedOut
        usage = nil
        accountEmail = ""
        visibleError = nil
        isUsingCachedData = false
        authorization = nil
        pastedCode = ""
        authenticationError = nil
    }

    static func demoSnapshot(at now: Date, refreshCount: Int = 0) -> UsageSnapshot {
        UsageSnapshot(
            planType: "Max 5x (Demo)",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(
                usedPercent: min(100, 27 + refreshCount),
                windowSeconds: ClaudeUsageParser.fiveHourSeconds,
                resetAt: now.addingTimeInterval(3 * 60 * 60 + 12 * 60)
            ),
            weekly: UsageWindow(
                usedPercent: 46,
                windowSeconds: ClaudeUsageParser.sevenDaySeconds,
                resetAt: now.addingTimeInterval(4 * 24 * 60 * 60 + 5 * 60 * 60)
            ),
            additionalLimits: [
                UsageLimit(
                    id: "claude_seven_day_opus",
                    name: "Opus",
                    primary: UsageWindow(
                        usedPercent: 18,
                        windowSeconds: ClaudeUsageParser.sevenDaySeconds,
                        resetAt: now.addingTimeInterval(4 * 24 * 60 * 60 + 5 * 60 * 60)
                    ),
                    secondary: nil
                ),
                UsageLimit(
                    id: "claude_seven_day_sonnet",
                    name: "Sonnet",
                    primary: UsageWindow(
                        usedPercent: 52,
                        windowSeconds: ClaudeUsageParser.sevenDaySeconds,
                        resetAt: now.addingTimeInterval(4 * 24 * 60 * 60 + 5 * 60 * 60)
                    ),
                    secondary: nil
                )
            ],
            fetchedAt: now
        )
    }
}
