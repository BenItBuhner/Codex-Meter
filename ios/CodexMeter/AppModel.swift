#if canImport(BackgroundTasks) && os(iOS)
import BackgroundTasks
#endif
import CodexMeterCore
import Observation
import SwiftUI

enum AppMode: String, Codable, Sendable {
    case signedOut
    case live
    case demo
}

extension AppAppearance {
    var title: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

extension AlertMetric {
    var title: String {
        switch self {
        case .both: "Both"
        case .fiveHour: "5-hour"
        case .weekly: "Weekly / Monthly"
        }
    }
}

extension UsagePaceSensitivity {
    var title: String {
        switch self {
        case .off: "Off"
        case .sensitive: "Sensitive"
        case .balanced: "Balanced"
        case .relaxed: "Relaxed"
        }
    }
}

extension UsagePaceAssessment {
    /// Android's meter-card phrasing: "Est. 1h 20m", or "Est. depleted" once the projection
    /// has passed; nil when no estimate is available.
    func estimateLabel(now: Date = .now) -> String? {
        guard isAvailable, let estimatedExhaustionAt else { return nil }
        let remaining = estimatedExhaustionAt.timeIntervalSince(now)
        guard remaining > 0 else { return "Est. depleted" }
        return "Est. \(Self.compactDuration(remaining))"
    }

    /// Spoken form of `estimateLabel(now:)` for VoiceOver.
    func estimateAccessibilityValue(now: Date = .now) -> String? {
        guard isAvailable, let estimatedExhaustionAt else { return nil }
        let remaining = estimatedExhaustionAt.timeIntervalSince(now)
        guard remaining > 0 else { return "estimated depleted" }
        return "estimated \(Self.compactDuration(remaining)) left"
    }

    private static func compactDuration(_ interval: TimeInterval) -> String {
        let minutes = max(1, Int(interval / 60))
        let days = minutes / 1_440
        let hours = (minutes % 1_440) / 60
        let remainingMinutes = minutes % 60
        if days > 0 {
            return "\(days)d \(hours)h"
        }
        if hours > 0 {
            return "\(hours)h \(remainingMinutes)m"
        }
        return "\(minutes)m"
    }
}

extension NotificationPermissionState {
    var title: String {
        switch self {
        case .notDetermined: "Not requested"
        case .denied: "Denied"
        case .authorized: "Allowed"
        case .provisional: "Provisional"
        case .ephemeral: "Temporary"
        }
    }
}

@MainActor
@Observable
final class AppModel {
    private static let modeDefaultsKey = "codex-meter.session-mode-v1"
    private static let providerDefaultsKey = "codex-meter.selected-provider-v1"
    private static let enabledProvidersDefaultsKey = "codex-meter.enabled-providers-v1"
    private static let providerOrderDefaultsKey = "codex-meter.provider-order-v1"
    private static let onboardingCompleteDefaultsKey = "codex-meter.onboarding-complete-v1"

    /// Claude Code tab state. Independent of the Codex session below.
    let claude: ClaudeUsageModel
    /// Cursor tab state. Independent of the Codex and Claude sessions.
    let cursor: CursorUsageModel
    /// Tab and sign-in state for every provider added through Settings → Accounts → Add account
    /// (Copilot, Kimi, Z.ai and the rest), keyed by provider. Each is independent.
    let addedProviders: [UsageProvider: ProviderUsageModel]
    /// The dashboard tab. Codex stays the default; the choice is remembered.
    var selectedProvider: UsageProvider = .defaultProvider
    /// Providers shown as dashboard tabs (Settings → Providers), in tab order. Chosen during
    /// first-run setup; an upgrade that never chose keeps Codex plus providers already signed
    /// in. With only one on, the tab bar is hidden.
    private(set) var enabledProviders: [UsageProvider] = [.codex]
    /// Every provider in the tab order chosen in Settings → Providers.
    private(set) var providerOrder: [UsageProvider] = UsageProvider.allCases
    var mode: AppMode = .signedOut
    var usage: UsageSnapshot?
    var credits: ResetCreditsSnapshot?
    var fiveHourHistory: UsageHistory = .empty(.fiveHour)
    var weeklyHistory: UsageHistory = .empty(.weekly)
    var monthlyHistory: UsageHistory = .empty(.monthly)
    var settings: AppSettings
    var accountEmail = ""
    var accountPlan = ""
    var visibleError: String?
    var authenticationError: String?
    var authChallenge: DeviceCodeChallenge?
    var resetResultMessage: String?
    var resetResultIsSuccess: Bool?
    var notificationPermissionState: NotificationPermissionState = .notDetermined
    var isUsingCachedData = false
    var isRefreshing = false
    var isAuthenticating = false
    var isRedeemingReset = false
    var isShowingSettings = false
    var isShowingReset = false
    var isShowingSignIn = false
    /// Settings → Accounts → Add account.
    var isAddingAccount = false
    /// First-run setup, shown once on a fresh install.
    var isShowingOnboarding = false
    var diagnosticsUnlocked = false

    private let liveService: LiveCodexService
    private var demoService: DemoCodexService
    private let cache: AppCacheStore
    private let settingsStore: AppSettingsStore
    private let notificationCoordinator: NotificationCoordinator
    private let usageHistoryStore: UsageHistoryStore
    private let refreshEngagementStore: RefreshEngagementStore
    private let defaults: UserDefaults
    private var hasStarted = false
    private var hasFinishedStartup = false
    private var pendingRoute: String?
#if DEBUG
    private var uiTestRefreshCount = 0
#endif
    private var signInTask: Task<Void, Never>?
    private let isPreview: Bool
    /// True once a provider list was saved (setup or Settings → Providers), or a UI test set
    /// one; until then the tabs follow `UsageProvider.migrationDefault(signedIn:)`.
    private var hasProviderChoice: Bool

    @ObservationIgnored
    private lazy var backgroundRefreshCoordinator = BackgroundRefreshCoordinator { [weak self] in
        guard let self else {
            throw CancellationError()
        }
        return try await self.performBackgroundRefresh()
    }

    init(
        liveService: LiveCodexService = LiveCodexService(),
        demoService: DemoCodexService = DemoCodexService(),
        cache: AppCacheStore = .shared,
        settingsStore: AppSettingsStore = AppSettingsStore(),
        notificationCoordinator: NotificationCoordinator = NotificationCoordinator(),
        usageHistoryStore: UsageHistoryStore = .shared,
        defaults: UserDefaults = .standard,
        refreshEngagementStore: RefreshEngagementStore? = nil,
        claude: ClaudeUsageModel? = nil,
        cursor: CursorUsageModel? = nil,
        preview: Bool = false
    ) {
        self.claude = claude ?? ClaudeUsageModel(preview: preview)
        self.cursor = cursor ?? CursorUsageModel(preview: preview)
        var addedProviders: [UsageProvider: ProviderUsageModel] = [:]
        for provider in UsageProvider.allCases where !provider.isBuiltIn && ProviderCatalog.isAvailableOnIOS(provider) {
            addedProviders[provider] = ProviderUsageModel(provider: provider)
        }
        self.addedProviders = addedProviders
        let savedProviders = defaults.stringArray(forKey: Self.enabledProvidersDefaultsKey) ?? []
        var hasProviderChoice = !savedProviders.isEmpty
        // Sign-in state isn't known yet; `resolveProviderSetup()` adds signed-in providers to
        // the migration default once the sessions load.
        var enabledProviders = UsageProvider.enabled(from: savedProviders)
        var providerOrder = UsageProvider.order(
            from: defaults.stringArray(forKey: Self.providerOrderDefaultsKey)
        )
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-ui-testing-reset-settings") {
            defaults.removeObject(forKey: Self.enabledProvidersDefaultsKey)
            defaults.removeObject(forKey: Self.providerOrderDefaultsKey)
            // The three built-in tabs, in memory only, so the UI tests can walk them.
            enabledProviders = UsageProvider.builtIns
            providerOrder = UsageProvider.allCases
            hasProviderChoice = true
        }
        // In memory only, so a test that narrows or reorders the tabs never leaks into the next
        // one. The listed providers are turned on, in the listed order.
        if let index = arguments.firstIndex(of: "-ui-testing-providers"), index + 1 < arguments.count {
            let ids = arguments[index + 1].split(separator: ",").map(String.init)
            enabledProviders = UsageProvider.enabled(from: ids)
            providerOrder = UsageProvider.order(from: ids)
            hasProviderChoice = true
        }
        if arguments.contains("-ui-testing-onboarding") {
            defaults.removeObject(forKey: Self.onboardingCompleteDefaultsKey)
            defaults.removeObject(forKey: Self.enabledProvidersDefaultsKey)
            defaults.removeObject(forKey: Self.providerOrderDefaultsKey)
            enabledProviders = [.codex]
            providerOrder = UsageProvider.allCases
            hasProviderChoice = false
        }
#endif
        self.hasProviderChoice = hasProviderChoice
        enabledProviders = UsageProvider.inOrder(enabledProviders, order: providerOrder)
        self.providerOrder = providerOrder
        self.enabledProviders = enabledProviders
        self.selectedProvider = UsageProvider.visibleSelection(
            UsageProvider.resolve(defaults.string(forKey: Self.providerDefaultsKey)),
            enabled: enabledProviders
        )
        self.liveService = liveService
        self.demoService = demoService
        self.cache = cache
        self.settingsStore = settingsStore
        self.notificationCoordinator = notificationCoordinator
        self.usageHistoryStore = usageHistoryStore
        self.defaults = defaults
        self.refreshEngagementStore = refreshEngagementStore
            ?? RefreshEngagementStore(defaults: defaults)
        self.settings = settingsStore.settings
        self.isPreview = preview
        self.diagnosticsUnlocked = DiagnosticLog.isUnlocked(defaults: defaults)
        for added in addedProviders.values {
            added.onSignedIn = { [weak self] provider in
                self?.providerConnected(provider)
            }
        }

        if preview {
            let now = Date()
            mode = .demo
            usage = UsageSnapshot(
                planType: "Plus (Demo)",
                allowed: true,
                limitReached: false,
                fiveHour: UsageWindow(
                    usedPercent: 38,
                    windowSeconds: 18_000,
                    resetAt: now.addingTimeInterval(7_200)
                ),
                weekly: UsageWindow(
                    usedPercent: 64,
                    windowSeconds: 604_800,
                    resetAt: now.addingTimeInterval(280_000)
                ),
                resetCreditsAvailable: 2,
                additionalLimits: [
                    UsageLimit(
                        id: "codex-spark",
                        name: "GPT-5.3-Codex-Spark",
                        meteredFeature: "codex_bengalfox",
                        primary: UsageWindow(
                            usedPercent: 24,
                            windowSeconds: 18_000,
                            resetAt: now.addingTimeInterval(10_800)
                        ),
                        secondary: UsageWindow(
                            usedPercent: 42,
                            windowSeconds: 604_800,
                            resetAt: now.addingTimeInterval(432_000)
                        )
                    )
                ],
                usageCredits: UsageCredits(
                    hasCredits: true,
                    unlimited: false,
                    balance: "2500"
                ),
                spendControl: SpendControl(
                    reached: false,
                    individualLimit: SpendControlLimit(
                        source: "workspace_spend_controls",
                        limit: "25000",
                        used: "8000",
                        remaining: "17000",
                        usedPercent: 32,
                        resetAt: now.addingTimeInterval(1_050_000)
                    )
                ),
                fetchedAt: now
            )
            credits = ResetCreditsSnapshot(
                availableCount: 2,
                credits: [
                    RateLimitResetCredit(
                        id: "preview",
                        resetType: "rate_limit",
                        status: RateLimitResetCredit.availableStatus,
                        expiresAt: now.addingTimeInterval(500_000),
                        title: "Codex reset"
                    )
                ],
                fetchedAt: now
            )
        } else {
            _ = backgroundRefreshCoordinator.register()
        }
    }

    static let preview = AppModel(preview: true)

    func startIfNeeded() async {
        guard !hasStarted, !isPreview else { return }
        hasStarted = true
        apply(history: await usageHistoryStore.load())
        DiagnosticLog.info("process", "started", details: [
            "mode": mode.rawValue,
            "app_version": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        ])
        defer {
            hasFinishedStartup = true
            applyPendingRouteIfNeeded()
        }
        await startSession()
        await resolveProviderSetup()
    }

    private func startSession() async {
        notificationPermissionState = await notificationCoordinator.permissionState()
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-ui-testing-reset-settings") {
            settingsStore.reset()
            settings = settingsStore.settings
            select(provider: UsageProvider.visibleSelection(.defaultProvider, enabled: enabledProviders))
            try? await usageHistoryStore.clear()
            apply(history: .empty)
        }

        if ProcessInfo.processInfo.arguments.contains("-ui-testing-signed-out") {
            clearSessionState()
            return
        }

        if ProcessInfo.processInfo.arguments.contains("-ui-testing-demo") {
            mode = .demo
            defaults.set(AppMode.demo.rawValue, forKey: Self.modeDefaultsKey)
            await enterProviderDemosIfSignedOut()
            await refresh()
            return
        }
#endif

        if let cached = try? await cache.load() {
            apply(cache: cached)
        }

        if defaults.string(forKey: Self.modeDefaultsKey) == AppMode.demo.rawValue {
            mode = .demo
            await enterProviderDemosIfSignedOut()
            if usage == nil {
                await refresh()
            }
            return
        }

        if let tokens = try? await liveService.currentTokens(), tokens.isUsable {
            mode = .live
            apply(tokens: tokens)
            if let pendingRoute, pendingRoute != "dashboard" {
                scheduleBackgroundRefresh()
                return
            }
            if settings.refreshOnLaunch,
               usage == nil || usage?.isStale(at: .now, maxAge: 5 * 60) == true {
                await refresh()
            } else {
                scheduleBackgroundRefresh()
            }
        } else {
            await liveService.signOut()
            try? await usageHistoryStore.clear()
            apply(history: .empty)
            mode = .signedOut
            defaults.set(AppMode.signedOut.rawValue, forKey: Self.modeDefaultsKey)
        }
    }

    func refresh() async {
        guard mode != .signedOut, !isRefreshing else { return }
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-ui-testing-refresh-failure") {
            uiTestRefreshCount += 1
            if uiTestRefreshCount > 1 {
                visibleError = "Demo refresh failed. Showing the last cached snapshot."
                isUsingCachedData = usage != nil
                return
            }
        }
#endif
        isRefreshing = true
        visibleError = nil
        defer { isRefreshing = false }
        DiagnosticLog.info("refresh", "started", details: ["mode": mode.rawValue])

        do {
            let snapshot = try await activeService.refresh()
            refreshEngagementStore.recordRefreshSuccess()
            DiagnosticLog.info("refresh", "finished", details: [
                "plan": snapshot.usage.planType,
                "has_monthly": snapshot.usage.monthly != nil ? "true" : "false",
                "has_weekly": snapshot.usage.weekly != nil ? "true" : "false"
            ])
            await apply(refresh: snapshot)
        } catch is CancellationError {
            return
        } catch {
            refreshEngagementStore.recordRefreshFailure()
            DiagnosticLog.error("refresh", "failed", error: error)
            visibleError = error.localizedDescription
            if let cached = try? await cache.load() {
                apply(cache: cached, preservingVisibleError: true)
            }
            scheduleBackgroundRefresh()
        }
    }

    func enterDemo() async {
        mode = .demo
        defaults.set(AppMode.demo.rawValue, forKey: Self.modeDefaultsKey)
        DiagnosticLog.info("process", "enter_demo")
        visibleError = nil
        accountEmail = "demo@local"
        accountPlan = "Plus (Demo)"
        await enterProviderDemosIfSignedOut()
        await refresh()
    }

    func leaveDemo() async {
        await claude.leaveDemo()
        await cursor.leaveDemo()
        for added in addedProviders.values {
            await added.leaveDemo()
        }
        await demoService.signOut()
        await notificationCoordinator.clearAll()
        backgroundRefreshCoordinator.cancel()
        try? await usageHistoryStore.clear()
        apply(history: .empty)
        clearSessionState()
    }

    func signOut() async {
        signInTask?.cancel()
        signInTask = nil
        DiagnosticLog.info("process", "sign_out")
        if mode == .demo {
            await claude.leaveDemo()
            await cursor.leaveDemo()
            for added in addedProviders.values {
                await added.leaveDemo()
            }
        }
        await activeService.signOut()
        await notificationCoordinator.clearAll()
        backgroundRefreshCoordinator.cancel()
        try? await usageHistoryStore.clear()
        apply(history: .empty)
        clearSessionState()
    }

    func beginSignIn() async {
        guard !isAuthenticating else { return }
        isAuthenticating = true
        authenticationError = nil
        defer { isAuthenticating = false }

        do {
            authChallenge = try await liveService.deviceCodeAuth.requestChallenge()
            DiagnosticLog.info("oauth", "challenge_started")
        } catch {
            DiagnosticLog.error("oauth", "challenge_failed", error: error)
            authenticationError = error.localizedDescription
        }
    }

    func continueSignIn() {
        guard let challenge = authChallenge, signInTask == nil else { return }
        isAuthenticating = true
        authenticationError = nil
        signInTask = Task { [weak self] in
            guard let self else { return }
            do {
                let tokens = try await liveService.deviceCodeAuth.complete(challenge)
                guard !Task.isCancelled else { return }
                mode = .live
                defaults.set(AppMode.live.rawValue, forKey: Self.modeDefaultsKey)
                apply(tokens: tokens)
                authChallenge = nil
                isAuthenticating = false
                signInTask = nil
                DiagnosticLog.info("oauth", "signed_in")
                await refresh()
            } catch is CancellationError {
                isAuthenticating = false
                signInTask = nil
            } catch {
                DiagnosticLog.error("oauth", "sign_in_failed", error: error)
                authenticationError = error.localizedDescription
                authChallenge = nil
                isAuthenticating = false
                signInTask = nil
            }
        }
    }

    func cancelSignIn() {
        signInTask?.cancel()
        signInTask = nil
        if let authChallenge {
            Task { await liveService.deviceCodeAuth.cancel(authChallenge) }
        }
        authChallenge = nil
        isAuthenticating = false
        authenticationError = nil
    }

    func consumeResetCredit() async {
        guard mode != .signedOut, !isRedeemingReset else { return }
        let priorUsage = usage
        let priorFetchedAt = usage?.fetchedAt
        let wasUsingCachedData = isUsingCachedData
        isRedeemingReset = true
        resetResultMessage = nil
        defer { isRedeemingReset = false }

        do {
            let result = try await activeService.consumeReset()
            resetResultMessage = result.userMessage
            resetResultIsSuccess = result.applied
            if result.applied, let priorUsage {
                await notificationCoordinator.markUserReset(usage: priorUsage)
            }
            if let cached = try? await cache.load() {
                apply(cache: cached)
                if result.applied, let refreshedUsage = cached.usage {
                    await recordUsageHistory(refreshedUsage)
                }
                if result.applied {
                    let advanced: Bool
                    if let refreshedAt = cached.usage?.fetchedAt {
                        advanced = priorFetchedAt.map { refreshedAt > $0 } ?? true
                    } else {
                        advanced = false
                    }
                    isUsingCachedData = result.refreshWarning != nil || !advanced
                } else {
                    isUsingCachedData = wasUsingCachedData
                }
                if let refreshWarning = result.refreshWarning {
                    visibleError = refreshWarning
                }
                if let usage, let credits {
                    await notificationCoordinator.process(
                        usage: usage,
                        previousUsage: priorUsage,
                        credits: credits,
                        settings: settings
                    )
                }
            }
        } catch {
            visibleError = error.localizedDescription
            resetResultMessage = error.localizedDescription
            resetResultIsSuccess = false
        }
    }

    func notificationsChanged(enabled: Bool) async {
        if enabled {
            do {
                let allowed = try await notificationCoordinator.requestAuthorization()
                if !allowed {
                    settings.notificationsEnabled = false
                    save(settings: settings)
                    visibleError = "Notifications are disabled in System Settings."
                } else {
                    await applyNotificationSettings()
                }
                notificationPermissionState = await notificationCoordinator.permissionState()
            } catch {
                settings.notificationsEnabled = false
                save(settings: settings)
                visibleError = error.localizedDescription
                notificationPermissionState = await notificationCoordinator.permissionState()
            }
        } else {
            await notificationCoordinator.clearAll()
            notificationPermissionState = await notificationCoordinator.permissionState()
        }
    }

    func sendTestNotification() async {
        do {
            let sent = try await notificationCoordinator.sendTestNotification()
            notificationPermissionState = await notificationCoordinator.permissionState()
            if !sent {
                visibleError = "Enable notifications and allow permission first."
            }
        } catch {
            visibleError = error.localizedDescription
        }
    }

    func refreshNotificationPermissionState() async {
        notificationPermissionState = await notificationCoordinator.permissionState()
    }

    func save(settings: AppSettings) {
        let previous = settingsStore.settings
        self.settings = settings
        settingsStore.settings = settings
        scheduleBackgroundRefresh()
        if previous.notificationsEnabled != settings.notificationsEnabled
            || previous.alertMetric != settings.alertMetric
            || previous.alertThreshold != settings.alertThreshold
            || previous.creditIncreaseAlertsEnabled != settings.creditIncreaseAlertsEnabled
            || previous.unexpectedRefillAlertsEnabled != settings.unexpectedRefillAlertsEnabled
            || previous.creditExpiryRemindersEnabled != settings.creditExpiryRemindersEnabled
            || previous.creditExpiryLeadMinutes != settings.creditExpiryLeadMinutes
            || previous.creditExpiryAutoApplyEnabled != settings.creditExpiryAutoApplyEnabled
            || previous.creditExpiryAutoApplyLeadMinutes != settings.creditExpiryAutoApplyLeadMinutes {
            Task { await applyNotificationSettings() }
        }
    }

    func handle(url: URL) {
        guard url.scheme?.lowercased() == "codexmeter" else { return }
        let route = (url.host ?? url.path)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .lowercased()
        handle(route: route)
    }

    func handle(route: String) {
        let route = route
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .lowercased()
        guard !route.isEmpty else { return }
        guard hasFinishedStartup else {
            pendingRoute = route
            Task { await startIfNeeded() }
            return
        }
        apply(route: route)
    }

    private func apply(route: String) {
        switch route {
        case "dashboard":
            isShowingSettings = false
            isShowingReset = false
            isShowingSignIn = false
        case "refresh":
            isShowingSettings = false
            isShowingReset = false
            Task { await refresh() }
        case "reset":
            isShowingSettings = false
            isShowingSignIn = false
            isShowingReset = mode != .signedOut
            if mode != .signedOut,
               usage == nil || usage?.isStale(at: .now, maxAge: 5 * 60) == true {
                Task { await refresh() }
            }
        case "settings":
            isShowingReset = false
            isShowingSignIn = false
            isShowingSettings = true
        default:
            break
        }
    }

    private func applyPendingRouteIfNeeded() {
        guard let pendingRoute else { return }
        self.pendingRoute = nil
        apply(route: pendingRoute)
    }

    func sceneBecameActive() async {
        refreshEngagementStore.recordForeground()
        await refreshNotificationPermissionState()
        guard hasStarted else {
            await startIfNeeded()
            return
        }
        if selectedProvider == .claudeCode {
            // Claude is never refreshed in the background; foregrounding the visible tab
            // re-checks only when its data is older than five minutes.
            await claude.tabOpened()
        } else if selectedProvider == .cursor {
            await cursor.tabOpened()
        } else if let added = addedProviders[selectedProvider] {
            await added.tabOpened()
        }
        guard mode != .signedOut, settings.refreshOnLaunch else { return }
        if usage == nil || usage?.isStale(at: .now, maxAge: 5 * 60) == true {
            await refresh()
        }
    }

    func sceneBecameInactive() {
        refreshEngagementStore.recordBackground()
        scheduleBackgroundRefresh()
    }

    func clearUsageHistory() async {
        do {
            try await usageHistoryStore.clear()
            apply(history: .empty)
        } catch {
            visibleError = "Local usage history could not be cleared."
        }
    }

    func history(for kind: UsageHistoryKind) -> UsageHistory {
        switch kind {
        case .fiveHour: fiveHourHistory
        case .weekly: weeklyHistory
        case .monthly: monthlyHistory
        }
    }

    /// Pace for a window under the usage-estimate settings. Pass the window's history `kind` for
    /// the Codex 5-hour, weekly, and monthly meters so local samples refine the projection;
    /// additional model limits have no history and use the window alone. Windows from another
    /// provider's snapshot pass that snapshot's `observedAt` instead of the Codex fetch time.
    func usagePace(
        for window: UsageWindow?,
        kind: UsageHistoryKind? = nil,
        observedAt: Date? = nil,
        now: Date = .now
    ) -> UsagePaceAssessment {
        guard settings.usagePaceEnabled, let window,
              let observed = observedAt ?? usage?.fetchedAt else { return .unavailable }
        return UsagePace.assess(
            window: window,
            history: kind.map(history(for:)),
            observedAt: observed,
            now: now,
            sensitivity: settings.usagePaceSensitivity
        )
    }

    // MARK: Provider accounts and first-run setup

    /// A real (not demo) session exists for `provider`.
    func isSignedIn(_ provider: UsageProvider) -> Bool {
        switch provider {
        case .codex: mode == .live
        case .claudeCode: claude.phase == .live
        case .cursor: cursor.phase == .live
        default: addedProviders[provider]?.phase == .live
        }
    }

    func accountEmail(for provider: UsageProvider) -> String {
        switch provider {
        case .codex: accountEmail
        case .claudeCode: claude.accountEmail
        case .cursor: cursor.accountEmail
        default: addedProviders[provider]?.accountEmail ?? ""
        }
    }

    func accountPlan(for provider: UsageProvider) -> String {
        switch provider {
        case .codex: accountPlan
        case .claudeCode: claude.accountPlan
        case .cursor: cursor.accountPlan
        default: addedProviders[provider]?.accountPlan ?? ""
        }
    }

    /// Signs out of one provider; the others keep their sessions. An added provider's tab goes
    /// away with its account (unless it is the last tab), as on Android.
    func signOut(provider: UsageProvider) async {
        switch provider {
        case .codex: await signOut()
        case .claudeCode: await claude.signOut()
        case .cursor: await cursor.signOut()
        default:
            await addedProviders[provider]?.signOut()
            setProvider(provider, enabled: false)
        }
    }

    /// Account row summary, as on Android: "<email> · <plan>", or "API key" / "Signed in" in
    /// place of a missing email.
    func accountSummary(for provider: UsageProvider) -> String {
        let email = accountEmail(for: provider)
        let plan = accountPlan(for: provider)
        let identity = !email.isEmpty ? email
            : (addedProviders[provider]?.usesAPIKey == true ? "API key" : "Signed in")
        return plan.isEmpty ? identity : "\(identity) · \(plan)"
    }

    /// The Settings → Accounts row summary: up to three names, then "A, B + N more".
    var connectedAccountsSummary: String {
        let names = connectedProviders.map(\.displayName)
        switch names.count {
        case 0: return "None connected"
        case 1 ... 3: return names.joined(separator: ", ")
        default: return names.prefix(2).joined(separator: ", ") + " + \(names.count - 2) more"
        }
    }

    /// Accounts in Settings: providers with a real session, in tab order.
    var connectedProviders: [UsageProvider] {
        providerOrder.filter { isSignedIn($0) }
    }

    /// A new account was connected: give it a tab and show it.
    func providerConnected(_ provider: UsageProvider) {
        setProvider(provider, enabled: true)
        select(provider: provider)
    }

    private var signedInProviders: Set<UsageProvider> {
        Set(UsageProvider.allCases.filter { isSignedIn($0) })
    }

    /// After the sessions load: give an upgrade that never chose providers its migration
    /// default, then decide whether this is a fresh install that needs first-run setup.
    private func resolveProviderSetup() async {
        await claude.loadIfNeeded()
        await cursor.loadIfNeeded()
        for added in addedProviders.values {
            await added.loadIfNeeded()
        }
        if !hasProviderChoice {
            let updated = UsageProvider.inOrder(
                UsageProvider.migrationDefault(signedIn: signedInProviders),
                order: providerOrder
            )
            if updated != enabledProviders {
                enabledProviders = updated
                select(provider: UsageProvider.visibleSelection(selectedProvider, enabled: updated))
            }
        }
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-ui-testing-onboarding") {
            isShowingOnboarding = true
            return
        }
        // Other UI-testing launches go straight to the dashboard they set up.
        if arguments.contains(where: { $0.hasPrefix("-ui-testing") }) {
            return
        }
#endif
        let completed = defaults.bool(forKey: Self.onboardingCompleteDefaultsKey)
        let hasExistingSetup = mode != .signedOut || usage != nil
            || claude.phase != .signedOut || cursor.phase != .signedOut
            || addedProviders.values.contains { $0.phase != .signedOut }
        if OnboardingFlow.shouldShow(completed: completed, hasExistingSetup: hasExistingSetup) {
            isShowingOnboarding = true
        } else if !completed {
            // Installs from before setup existed never see it.
            defaults.set(true, forKey: Self.onboardingCompleteDefaultsKey)
        }
    }

    /// Saves the providers picked during setup as the dashboard tabs.
    func chooseProviders(_ chosen: [UsageProvider]) {
        guard !chosen.isEmpty else { return }
        let updated = UsageProvider.inOrder(chosen, order: providerOrder)
        enabledProviders = updated
        hasProviderChoice = true
        defaults.set(updated.map(\.rawValue), forKey: Self.enabledProvidersDefaultsKey)
        DiagnosticLog.info("process", "providers_chosen", details: [
            "providers": updated.map(\.rawValue).joined(separator: ",")
        ])
        select(provider: UsageProvider.visibleSelection(selectedProvider, enabled: updated))
    }

    func finishOnboarding() {
        defaults.set(true, forKey: Self.onboardingCompleteDefaultsKey)
        isShowingOnboarding = false
        DiagnosticLog.info("process", "onboarding_finished")
    }

    /// "Explore demo" from setup: Codex, Claude Code and Cursor get tabs with local demo data.
    func finishOnboardingWithDemo() async {
        if !hasProviderChoice {
            chooseProviders(UsageProvider.builtIns)
        }
        finishOnboarding()
        await enterDemo()
    }

    /// Turns a provider's tab on or off. The last provider can't be turned off, and turning off
    /// the selected tab moves to the first provider still on.
    func setProvider(_ provider: UsageProvider, enabled on: Bool) {
        let updated = UsageProvider.inOrder(
            UsageProvider.enabled(enabledProviders, setting: provider, on: on),
            order: providerOrder
        )
        guard updated != enabledProviders else { return }
        enabledProviders = updated
        hasProviderChoice = true
        defaults.set(updated.map(\.rawValue), forKey: Self.enabledProvidersDefaultsKey)
        DiagnosticLog.info("process", "provider_enabled_changed", details: [
            "provider": provider.rawValue,
            "enabled": on ? "true" : "false"
        ])
        select(provider: UsageProvider.visibleSelection(selectedProvider, enabled: updated))
    }

    /// Moves providers in Settings → Providers; the dashboard tabs follow the new order.
    func moveProviders(fromOffsets source: IndexSet, toOffset destination: Int) {
        var order = providerOrder
        order.move(fromOffsets: source, toOffset: destination)
        setProviderOrder(order)
    }

    /// Moves rows of a list that shows only some providers (Settings → Providers lists tabs
    /// that are on and connected accounts); providers it doesn't show keep their relative order
    /// after the shown ones.
    func moveProviders(_ shown: [UsageProvider], fromOffsets source: IndexSet, toOffset destination: Int) {
        var moved = shown
        moved.move(fromOffsets: source, toOffset: destination)
        setProviderOrder(moved + providerOrder.filter { !moved.contains($0) })
    }

    private func setProviderOrder(_ order: [UsageProvider]) {
        guard order != providerOrder else { return }
        providerOrder = order
        enabledProviders = UsageProvider.inOrder(enabledProviders, order: order)
        defaults.set(order.map(\.rawValue), forKey: Self.providerOrderDefaultsKey)
        DiagnosticLog.info("process", "provider_order_changed", details: [
            "order": order.map(\.rawValue).joined(separator: ",")
        ])
    }

    /// Selects and remembers a dashboard tab. Safe to call again for the current tab.
    func select(provider: UsageProvider) {
        if selectedProvider != provider {
            selectedProvider = provider
        }
        guard defaults.string(forKey: Self.providerDefaultsKey) != provider.rawValue else { return }
        defaults.set(provider.rawValue, forKey: Self.providerDefaultsKey)
        DiagnosticLog.info("process", "provider_selected", details: ["provider": provider.rawValue])
    }

    /// The Codex demo also shows demo Claude and Cursor data, unless a real session exists there.
    private func enterProviderDemosIfSignedOut() async {
        await claude.loadIfNeeded()
        if claude.phase == .signedOut {
            claude.enterDemo()
        }
        await cursor.loadIfNeeded()
        if cursor.phase == .signedOut {
            cursor.enterDemo()
        }
        // Added providers only show demo data when their tab is on.
        for provider in enabledProviders {
            guard let added = addedProviders[provider] else { continue }
            await added.loadIfNeeded()
            if added.phase == .signedOut {
                added.enterDemo()
            }
        }
    }

    private var activeService: any CodexService {
        mode == .demo ? demoService : liveService
    }

    private func apply(refresh: CodexRefreshSnapshot) async {
        let previousUsage = usage
        usage = refresh.usage
        credits = refresh.credits
        accountPlan = UsageFormat.planLabel(refresh.usage.planType)
        visibleError = nil
        isUsingCachedData = false
        await recordUsageHistory(refresh.usage)

        if mode == .live, let tokens = try? await liveService.currentTokens() {
            apply(tokens: tokens)
        }
        if let cached = try? await cache.load() {
            visibleError = cached.resetCreditsError
        }
        await notificationCoordinator.process(
            usage: refresh.usage,
            previousUsage: previousUsage,
            credits: refresh.credits,
            settings: settings
        )
        scheduleBackgroundRefresh()
        await autoApplyExpiringCreditsIfNeeded()
        await autoUseCreditOnLowUsageIfNeeded()
    }

    private static let handledExpiryTokensKey = "codexMeter.autoUse.handledExpiryTokens"
    private static let failedExpiryTokensKey = "codexMeter.autoUse.failedExpiryTokens"
    private static let lowUsageWindowTokenKey = "codexMeter.autoUse.lowUsageWindowToken"
    private static let lowUsageFailedTokenKey = "codexMeter.autoUse.lowUsageFailedToken"

    /// Uses an unused credit once it is inside the chosen lead time before expiry. Each credit is
    /// handled once: any answer from OpenAI is final, and only network errors retry (silently
    /// after the first notice).
    private func autoApplyExpiringCreditsIfNeeded() async {
        guard settings.creditExpiryAutoApplyEnabled, mode != .signedOut, !isRedeemingReset else {
            return
        }
        isRedeemingReset = true
        defer { isRedeemingReset = false }
        var handled = Set(defaults.stringArray(forKey: Self.handledExpiryTokensKey) ?? [])
        for _ in 0..<5 {
            guard let credits else { return }
            let due = CreditExpiryAutoApply.due(
                credits: credits.credits,
                now: .now,
                leadMinutes: settings.creditExpiryAutoApplyLeadMinutes
            ).filter { !handled.contains($0.token) }
            guard let credit = due.first else { return }
            do {
                let priorUsage = usage
                let result = try await activeService.consumeReset(creditID: credit.creditId)
                handled.insert(credit.token)
                defaults.set(Self.prunedTokens(handled), forKey: Self.handledExpiryTokensKey)
                if result.applied, let priorUsage {
                    await notificationCoordinator.markUserReset(usage: priorUsage)
                }
                if settings.notificationsEnabled {
                    await notificationCoordinator.notifyExpiryAutoApply(
                        result: result,
                        expiresAt: credit.expiresAt
                    )
                }
                if let cached = try? await cache.load() {
                    apply(cache: cached)
                    if result.applied, let refreshed = cached.usage {
                        await recordUsageHistory(refreshed)
                    }
                }
            } catch {
                var failed = Set(defaults.stringArray(forKey: Self.failedExpiryTokensKey) ?? [])
                if failed.insert(credit.token).inserted {
                    defaults.set(Self.prunedTokens(failed), forKey: Self.failedExpiryTokensKey)
                    if settings.notificationsEnabled {
                        await notificationCoordinator.notifyExpiryAutoApplyFailure(
                            message: error.localizedDescription
                        )
                    }
                }
                return
            }
        }
    }

    /// Spends one credit when the watched window reaches the chosen remaining level, at most once
    /// per usage window. Runs on the snapshot a refresh just returned.
    private func autoUseCreditOnLowUsageIfNeeded() async {
        guard settings.lowUsageAutoUseEnabled, mode != .signedOut, !isRedeemingReset else {
            return
        }
        let decision = LowUsageAutoUse.evaluate(
            snapshot: usage,
            metric: settings.lowUsageAutoUseMetric,
            threshold: settings.lowUsageAutoUseThreshold,
            availableCredits: credits?.availableCount ?? usage?.resetCreditsAvailable,
            lastFiredToken: defaults.string(forKey: Self.lowUsageWindowTokenKey),
            now: .now
        )
        guard decision.fires else { return }
        isRedeemingReset = true
        defer { isRedeemingReset = false }
        do {
            let priorUsage = usage
            let result = try await activeService.consumeReset(creditID: nil)
            defaults.set(decision.windowToken, forKey: Self.lowUsageWindowTokenKey)
            if result.applied, let priorUsage {
                await notificationCoordinator.markUserReset(usage: priorUsage)
            }
            if settings.notificationsEnabled {
                await notificationCoordinator.notifyLowUsageAutoUse(
                    result: result,
                    windowLabel: decision.windowLabel,
                    remainingPercent: decision.remainingPercent
                )
            }
            if let cached = try? await cache.load() {
                apply(cache: cached)
                if result.applied, let refreshed = cached.usage {
                    await recordUsageHistory(refreshed)
                }
            }
        } catch {
            if defaults.string(forKey: Self.lowUsageFailedTokenKey) != decision.windowToken {
                defaults.set(decision.windowToken, forKey: Self.lowUsageFailedTokenKey)
                if settings.notificationsEnabled {
                    await notificationCoordinator.notifyLowUsageAutoUseFailure(
                        message: error.localizedDescription
                    )
                }
            }
        }
    }

    /// The next time an expiring credit becomes due, so background refresh can aim for it.
    private func nextAutoUseDate(after date: Date) -> Date? {
        guard settings.creditExpiryAutoApplyEnabled, let credits else { return nil }
        return CreditExpiryAutoApply.plan(
            credits: credits.credits,
            now: date,
            leadMinutes: settings.creditExpiryAutoApplyLeadMinutes
        )
        .map { max($0.triggerAt, date.addingTimeInterval(60)) }
        .min()
    }

    /// Keeps tokens for credits that expired less than a day ago.
    private static func prunedTokens(_ tokens: Set<String>) -> [String] {
        let cutoff = Date().addingTimeInterval(-86_400).timeIntervalSince1970
        return tokens.filter { token in
            guard let split = token.lastIndex(of: ":"),
                  let seconds = Double(token[token.index(after: split)...]) else { return false }
            return seconds > cutoff
        }
        .sorted()
    }

    private func apply(
        cache snapshot: AppCacheSnapshot,
        preservingVisibleError: Bool = false
    ) {
        usage = snapshot.usage
        credits = snapshot.credits
        isUsingCachedData = snapshot.usage != nil
        accountPlan = UsageFormat.planLabel(snapshot.usage?.planType)
        guard !preservingVisibleError else { return }
        if let error = snapshot.lastError, snapshot.usage == nil {
            visibleError = error
        } else if let error = snapshot.lastError,
                  snapshot.usage?.isStale(at: .now, maxAge: 15 * 60) == true {
            visibleError = error
        } else {
            visibleError = snapshot.resetCreditsError
        }
    }

    private func apply(tokens: AuthTokens?) {
        accountEmail = tokens?.email ?? ""
        if accountPlan.isEmpty {
            accountPlan = UsageFormat.planLabel(usage?.planType)
        }
    }

    private func clearSessionState() {
        mode = .signedOut
        defaults.set(AppMode.signedOut.rawValue, forKey: Self.modeDefaultsKey)
        usage = nil
        credits = nil
        accountEmail = ""
        accountPlan = ""
        visibleError = nil
        authenticationError = nil
        authChallenge = nil
        resetResultMessage = nil
        resetResultIsSuccess = nil
        isUsingCachedData = false
        isShowingReset = false
    }

    private func scheduleBackgroundRefresh() {
        guard mode == .live, !isPreview else {
            if mode != .live { backgroundRefreshCoordinator.cancel() }
            return
        }
        let now = Date()
        try? backgroundRefreshCoordinator.schedule(
            preferredMinutes: effectiveRefreshMinutes(at: now),
            nextReset: usage?.nextReset(after: now)
        )
    }

    private func effectiveRefreshMinutes(at date: Date) -> Int {
        guard settings.refreshMode == .automatic else {
            return settings.refreshMinutes
        }
        return AdaptiveRefreshPolicy.chooseMinutes(
            snapshot: usage,
            attentionScore: refreshEngagementStore.attentionScore(at: date),
            localHour: Calendar.current.component(.hour, from: date),
            consecutiveFailures: refreshEngagementStore.consecutiveFailures,
            now: date
        )
    }

    private func recordUsageHistory(_ usage: UsageSnapshot) async {
        guard let snapshot = try? await usageHistoryStore.record(usage) else { return }
        apply(history: snapshot)
    }

    private func apply(history: LocalUsageHistorySnapshot) {
        fiveHourHistory = history.fiveHour
        weeklyHistory = history.weekly
        monthlyHistory = history.monthly
    }

    func unlockDiagnostics() {
        diagnosticsUnlocked = true
        DiagnosticLog.unlock(defaults: defaults)
    }

    private func applyNotificationSettings() async {
        guard settings.notificationsEnabled else {
            await notificationCoordinator.clearAll()
            return
        }
        guard let usage else { return }
        let credits = credits ?? .summary(
            availableCount: usage.resetCreditsAvailable ?? 0,
            fetchedAt: usage.fetchedAt
        )
        await notificationCoordinator.process(
            usage: usage,
            credits: credits,
            settings: settings
        )
    }

    private func performBackgroundRefresh() async throws -> BackgroundRefreshOutcome {
        if mode != .live {
            guard let tokens = try await liveService.currentTokens(), tokens.isUsable else {
                throw CodexServiceError.signedOut
            }
            mode = .live
            defaults.set(AppMode.live.rawValue, forKey: Self.modeDefaultsKey)
            apply(tokens: tokens)
        }
        do {
            let refreshed = try await liveService.refresh()
            refreshEngagementStore.recordRefreshSuccess()
            await apply(refresh: refreshed)
            let now = Date()
            let nextReset = [refreshed.usage.nextReset(after: now), nextAutoUseDate(after: now)]
                .compactMap { $0 }
                .min()
            return BackgroundRefreshOutcome(
                preferredMinutes: effectiveRefreshMinutes(at: now),
                nextReset: nextReset
            )
        } catch {
            refreshEngagementStore.recordRefreshFailure()
            let now = Date()
            return BackgroundRefreshOutcome(
                preferredMinutes: effectiveRefreshMinutes(at: now),
                nextReset: usage?.nextReset(after: now),
                succeeded: false
            )
        }
    }
}
