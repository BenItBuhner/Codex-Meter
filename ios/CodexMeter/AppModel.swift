import BackgroundTasks
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
        case .weekly: "Weekly"
        }
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

    var mode: AppMode = .signedOut
    var usage: UsageSnapshot?
    var credits: ResetCreditsSnapshot?
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

    private let liveService: LiveCodexService
    private var demoService: DemoCodexService
    private let cache: AppCacheStore
    private let settingsStore: AppSettingsStore
    private let notificationCoordinator: NotificationCoordinator
    private let defaults: UserDefaults
    private var hasStarted = false
    private var hasFinishedStartup = false
    private var pendingRoute: String?
#if DEBUG
    private var uiTestRefreshCount = 0
#endif
    private var signInTask: Task<Void, Never>?
    private let isPreview: Bool

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
        defaults: UserDefaults = .standard,
        preview: Bool = false
    ) {
        self.liveService = liveService
        self.demoService = demoService
        self.cache = cache
        self.settingsStore = settingsStore
        self.notificationCoordinator = notificationCoordinator
        self.defaults = defaults
        self.settings = settingsStore.settings
        self.isPreview = preview

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
        defer {
            hasFinishedStartup = true
            applyPendingRouteIfNeeded()
        }
        notificationPermissionState = await notificationCoordinator.permissionState()
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-ui-testing-reset-settings") {
            settingsStore.reset()
            settings = settingsStore.settings
        }

        if ProcessInfo.processInfo.arguments.contains("-ui-testing-signed-out") {
            clearSessionState()
            return
        }

        if ProcessInfo.processInfo.arguments.contains("-ui-testing-demo") {
            mode = .demo
            defaults.set(AppMode.demo.rawValue, forKey: Self.modeDefaultsKey)
            await refresh()
            return
        }
#endif

        if let cached = try? await cache.load() {
            apply(cache: cached)
        }

        if defaults.string(forKey: Self.modeDefaultsKey) == AppMode.demo.rawValue {
            mode = .demo
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

        do {
            let snapshot = try await activeService.refresh()
            await apply(refresh: snapshot)
        } catch is CancellationError {
            return
        } catch {
            visibleError = error.localizedDescription
            if let cached = try? await cache.load() {
                apply(cache: cached, preservingVisibleError: true)
            }
        }
    }

    func enterDemo() async {
        mode = .demo
        defaults.set(AppMode.demo.rawValue, forKey: Self.modeDefaultsKey)
        visibleError = nil
        accountEmail = "demo@local"
        accountPlan = "Plus (Demo)"
        await refresh()
    }

    func leaveDemo() async {
        await demoService.signOut()
        await notificationCoordinator.clearAll()
        backgroundRefreshCoordinator.cancel()
        clearSessionState()
    }

    func signOut() async {
        signInTask?.cancel()
        signInTask = nil
        await activeService.signOut()
        await notificationCoordinator.clearAll()
        backgroundRefreshCoordinator.cancel()
        clearSessionState()
    }

    func beginSignIn() async {
        guard !isAuthenticating else { return }
        isAuthenticating = true
        authenticationError = nil
        defer { isAuthenticating = false }

        do {
            authChallenge = try await liveService.deviceCodeAuth.requestChallenge()
        } catch {
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
                await refresh()
            } catch is CancellationError {
                isAuthenticating = false
                signInTask = nil
            } catch {
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
            || previous.creditExpiryLeadMinutes != settings.creditExpiryLeadMinutes {
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
        await refreshNotificationPermissionState()
        guard hasStarted else {
            await startIfNeeded()
            return
        }
        guard mode != .signedOut, settings.refreshOnLaunch else { return }
        if usage == nil || usage?.isStale(at: .now, maxAge: 5 * 60) == true {
            await refresh()
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
        try? backgroundRefreshCoordinator.schedule(
            preferredMinutes: settings.refreshMinutes,
            nextReset: usage?.nextReset(after: .now)
        )
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
        let refreshed = try await liveService.refresh()
        await apply(refresh: refreshed)
        return BackgroundRefreshOutcome(
            preferredMinutes: settings.refreshMinutes,
            nextReset: refreshed.usage.nextReset(after: .now)
        )
    }
}
