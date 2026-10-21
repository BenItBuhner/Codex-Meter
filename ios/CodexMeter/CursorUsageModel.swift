import CodexMeterCore
import Observation
import SwiftUI

/// State for the Cursor tab. Independent from the Codex and Claude sessions: its own Keychain
/// item, cache file, and sign-out. Usage is fetched when the tab opens with data older than
/// five minutes or on an explicit refresh.
@MainActor
@Observable
final class CursorUsageModel {
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
    var login: CursorLoginRequest?
    var authenticationError: String?
    var isWaitingForBrowser = false

    private let service: CursorService
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var signInTask: Task<Void, Never>?
    @ObservationIgnored private var demoRefreshCount = 0

    init(service: CursorService = CursorService(), preview: Bool = false) {
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

    /// Called whenever the Cursor tab becomes visible.
    func tabOpened() async {
        await loadIfNeeded()
        switch phase {
        case .signedOut:
            return
        case .demo:
            if usage == nil { showDemo() }
        case .live:
            if usage == nil || usage?.isStale(at: .now, maxAge: CursorService.staleAfter) == true {
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
        DiagnosticLog.info("refresh", "cursor_usage_refresh_started")
        do {
            let snapshot = try await service.refreshUsage()
            usage = snapshot
            visibleError = nil
            isUsingCachedData = false
            if let tokens = try? await service.currentTokens() {
                accountEmail = tokens.email
            }
            DiagnosticLog.info("refresh", "cursor_usage_refresh_succeeded", details: [
                "plan_window": snapshot.weekly != nil ? "weekly"
                    : snapshot.monthly != nil ? "monthly" : "none",
                "additional_limits": String(snapshot.additionalLimits.count)
            ])
        } catch is CancellationError {
            return
        } catch {
            DiagnosticLog.error("refresh", "cursor_usage_refresh_failed", error: error)
            visibleError = error.localizedDescription
            isUsingCachedData = usage != nil
            if (error as? CursorServiceError) == .signedOut {
                clearState()
            }
        }
    }

    // MARK: Sign-in

    /// Starts a fresh login attempt, begins polling for its tokens, and returns the URL to open.
    func beginSignIn() -> URL {
        signInTask?.cancel()
        let request = CursorLoginRequest.make()
        login = request
        authenticationError = nil
        isWaitingForBrowser = true
        DiagnosticLog.info("oauth", "cursor_login_opened")
        signInTask = Task { await self.waitForTokens(request) }
        return request.loginURL
    }

    private func waitForTokens(_ request: CursorLoginRequest) async {
        do {
            let tokens = try await service.signIn(request: request)
            guard login == request else { return }
            DiagnosticLog.info("oauth", "cursor_signed_in")
            phase = .live
            accountEmail = tokens.email
            usage = nil
            visibleError = nil
            isUsingCachedData = false
            login = nil
            isWaitingForBrowser = false
            isShowingSignIn = false
            await refresh()
        } catch is CancellationError {
            return
        } catch {
            guard login == request else { return }
            DiagnosticLog.error("oauth", "cursor_sign_in_failed", error: error)
            authenticationError = error.localizedDescription
            isWaitingForBrowser = false
        }
    }

    func cancelSignIn() {
        signInTask?.cancel()
        signInTask = nil
        login = nil
        authenticationError = nil
        isWaitingForBrowser = false
    }

    func signOut() async {
        DiagnosticLog.info("process", "cursor_sign_out")
        await service.signOut()
        clearState()
    }

    // MARK: Demo

    /// Mirrors the Codex demo: local data only, never contacts Cursor.
    func enterDemo() {
        demoRefreshCount = 0
        showDemo()
    }

    /// Leaving the Codex demo also leaves the Cursor demo, then restores any real session.
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
        cancelSignIn()
        phase = .signedOut
        usage = nil
        accountEmail = ""
        visibleError = nil
        isUsingCachedData = false
    }

    /// A current Cursor plan: one included pool that refreshes weekly, plus additional usage.
    static func demoSnapshot(at now: Date, refreshCount: Int = 0) -> UsageSnapshot {
        let cycle: Int64 = 7 * 24 * 60 * 60
        let reset = now.addingTimeInterval(4 * 24 * 60 * 60 + 4 * 60 * 60)
        func window(_ percent: Int) -> UsageWindow {
            UsageWindow(usedPercent: percent, windowSeconds: cycle, resetAt: reset)
        }
        return UsageSnapshot(
            planType: "Pro (Demo)",
            allowed: true,
            limitReached: false,
            fiveHour: nil,
            weekly: window(min(100, 58 + refreshCount)),
            monthly: nil,
            additionalLimits: [
                UsageLimit(
                    id: CursorUsageParser.limitIDPrefix + "on_demand",
                    name: "Additional usage ($4.20 of $20)",
                    primary: window(21),
                    secondary: nil
                )
            ],
            fetchedAt: now
        )
    }
}
