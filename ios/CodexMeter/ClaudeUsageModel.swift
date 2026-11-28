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
    /// Saved usage-limit resets Anthropic reported with the last usage refresh.
    var savedResets: ClaudeSavedResets?
    var isClaimingReset = false
    var isCheckingResets = false
    /// The answer to the last reset use or Check with Anthropic, shown under the resets.
    var resetMessage: String?

    /// Reads the current settings for the automatic reset rules; set by `AppModel`.
    @ObservationIgnored var settingsProvider: (@MainActor () -> AppSettings)?
    /// Called after a fresh reading is shown, with the one it replaced, for refill alerts.
    @ObservationIgnored var onUsageSaved: (@MainActor (UsageProvider, UsageSnapshot?, UsageSnapshot) -> Void)?
    /// Called when Anthropic sends its saved-reset list, with the list shown before it.
    @ObservationIgnored var onSavedResetsChanging: (@MainActor (ClaudeSavedResets?, ClaudeSavedResets) -> Void)?
    /// Called when the person spends a saved reset in the app.
    @ObservationIgnored var onUserReset: (@MainActor () -> Void)?
    /// Called when a live account is signed out, so its alerts and activity are forgotten.
    @ObservationIgnored var onSignedOut: (@MainActor () -> Void)?
    @ObservationIgnored private let refillHold = RefillHoldState()

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
        ClaudeUsageParser.currentPlanName(usage?.planType ?? "")
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
            savedResets = cached.claudeSavedResets
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
            let previous = usage
            let storedResets = savedResets
            let sentResets = await service.cachedSavedResets()
            if let sentResets { onSavedResetsChanging?(storedResets, sentResets) }
            if refillHold.shouldHold(previous: previous, current: snapshot, again: { [weak self] in
                await self?.refresh()
            }) {
                // Looks refilled early: keep the last reading until the second look.
                savedResets = sentResets ?? savedResets
                return
            }
            usage = snapshot
            savedResets = sentResets
            ProviderWidgetPublisher.publish(.claudeCode, demo: false, usage: snapshot)
            onUsageSaved?(.claudeCode, previous, snapshot)
            visibleError = nil
            isUsingCachedData = false
            if let tokens = try? await service.currentTokens() {
                accountEmail = tokens.email
            }
            DiagnosticLog.info("refresh", "claude_usage_refresh_succeeded", details: [
                "five_hour": snapshot.fiveHour != nil ? "true" : "false",
                "weekly": snapshot.weekly != nil ? "true" : "false",
                "additional_limits": String(snapshot.additionalLimits.count),
                "saved_resets": String(savedResets?.availableCount(at: .now) ?? -1)
            ])
            await runSavedResetAutoUse()
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
        ProviderWidgetPublisher.publishSignedOut(.claudeCode)
    }

    // MARK: Saved resets

    /// Spends one saved reset. Anthropic spends nothing when no limit is in the way.
    func useSavedReset(grantID: String) async {
        guard !isClaimingReset else { return }
        isClaimingReset = true
        defer { isClaimingReset = false }
        if phase == .demo {
            savedResets = savedResets?.afterUse(grantID)
            resetMessage = "Demo: reset applied to 2 usage windows."
            return
        }
        guard phase == .live else { return }
        DiagnosticLog.info("reset", "claude_saved_reset_claim_started")
        do {
            let result = try await service.claimSavedReset(grantID: grantID)
            DiagnosticLog.info("reset", "claude_saved_reset_claim_finished", details: ["result": result.result.rawValue])
            resetMessage = result.userMessage
            if result.applied, let snapshot = await service.cachedSnapshot() {
                refillHold.skipForUserReset()
                onUserReset?()
                let storedResets = savedResets
                usage = snapshot.usage ?? usage
                savedResets = snapshot.claudeSavedResets ?? savedResets?.afterUse(grantID)
                if let savedResets { onSavedResetsChanging?(storedResets, savedResets) }
            } else {
                savedResets = await service.cachedSavedResets() ?? savedResets
            }
        } catch {
            DiagnosticLog.error("reset", "claude_saved_reset_claim_failed", error: error)
            resetMessage = error.localizedDescription
        }
    }

    /// Re-reads usage and saved resets from Anthropic and says what it found.
    func checkWithAnthropic() async {
        guard !isCheckingResets else { return }
        isCheckingResets = true
        defer { isCheckingResets = false }
        if phase == .live {
            await refresh()
        }
        resetMessage = Self.describeCheck(savedResets, error: visibleError, at: .now)
    }

    static func describeCheck(_ resets: ClaudeSavedResets?, error: String?, at now: Date) -> String {
        guard let resets else {
            if let error { return "Anthropic didn't answer: \(error)" }
            return "Anthropic didn't list saved resets for this account. If claude.ai shows some, sign out of Claude here and sign in again so Codex Meter gets Claude Code's permission to see them."
        }
        guard resets.eligible else {
            return "Anthropic isn't offering saved resets on this account right now."
        }
        let title = resets.availableTitle(at: now)
        let held = resets.unavailableMessage(at: now)
        return held.isEmpty ? "\(title). One can be used now." : "\(title). \(held)."
    }

    /// The two automatic rules, run after each successful refresh.
    private func runSavedResetAutoUse() async {
        guard phase == .live, let settings = settingsProvider?(), !isClaimingReset else { return }
        let defaults = UserDefaults.standard
        let decision = ClaudeResetAutoUse.evaluate(
            status: savedResets,
            usage: usage,
            expiryEnabled: settings.claudeResetExpiryAutoUseEnabled,
            leadMinutes: settings.claudeResetExpiryLeadMinutes,
            lowUsageEnabled: settings.claudeResetLowUsageAutoUseEnabled,
            threshold: settings.claudeResetLowUsageThreshold,
            lastExpiryKey: defaults.string(forKey: Self.expiryAttemptKey),
            lastLowUsageToken: defaults.string(forKey: Self.lowUsageAttemptKey),
            fiveHourWaitMinutes: settings.claudeResetFiveHourWaitMinutes,
            weeklyWaitMinutes: settings.claudeResetWeeklyWaitMinutes,
            now: .now
        )
        RefillWaitStore.save(
            RefillWait.after(RefillWaitStore.load(RefillWaitStore.claudeKey, defaults: defaults),
                             claude: decision, now: .now),
            RefillWaitStore.claudeKey, defaults: defaults
        )
        guard decision.fires, let grant = decision.grant else { return }
        // Remember the attempt first, so a failure is never retried in a loop.
        defaults.set(decision.attemptKey, forKey: decision.outcome == .fireExpiring
            ? Self.expiryAttemptKey : Self.lowUsageAttemptKey)
        DiagnosticLog.info("reset", "claude_saved_reset_auto_use", details: ["rule": decision.outcome.rawValue])
        await useSavedReset(grantID: grant.id)
    }

    private static let expiryAttemptKey = "codex-meter.claude-reset-expiry-attempt-v1"
    private static let lowUsageAttemptKey = "codex-meter.claude-reset-low-usage-attempt-v1"

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
        if phase == .live {
            ProviderWidgetPublisher.publish(.claudeCode, demo: false, usage: usage)
        } else {
            ProviderWidgetPublisher.publishSignedOut(.claudeCode)
        }
    }

    private func showDemo() {
        phase = .demo
        accountEmail = "demo@local"
        visibleError = nil
        isUsingCachedData = false
        usage = Self.demoSnapshot(at: .now, refreshCount: demoRefreshCount)
        savedResets = Self.demoSavedResets(at: .now)
        ProviderWidgetPublisher.publish(.claudeCode, demo: true, usage: usage)
    }

    private func clearState() {
        refillHold.reset()
        if phase == .live { onSignedOut?() }
        phase = .signedOut
        usage = nil
        savedResets = nil
        resetMessage = nil
        accountEmail = ""
        visibleError = nil
        isUsingCachedData = false
        authorization = nil
        pastedCode = ""
        authenticationError = nil
    }

    static func demoSavedResets(at now: Date) -> ClaudeSavedResets {
        ClaudeSavedResets(
            eligible: true,
            grants: [
                .init(id: "demo_full", label: "Full reset", resetsTotal: 2, resetsLeft: 2,
                      endsAt: now.addingTimeInterval(9 * 24 * 60 * 60), clears: ["five_hour", "seven_day"],
                      usableNow: true, type: "full"),
                .init(id: "demo_fable", label: "Fable reset", resetsTotal: 1, resetsLeft: 1,
                      endsAt: now.addingTimeInterval(2 * 24 * 60 * 60), clears: ["seven_day_fable"],
                      unavailableReason: "not_limited", scope: "Fable")
            ],
            nextGrantID: "demo_full",
            fetchedAt: now
        )
    }

    static func demoSnapshot(at now: Date, refreshCount: Int = 0) -> UsageSnapshot {
        UsageSnapshot(
            planType: "Max (Demo)",
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
                    id: "claude_weekly_scoped_fable",
                    name: "Fable only",
                    primary: UsageWindow(
                        usedPercent: 31,
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
