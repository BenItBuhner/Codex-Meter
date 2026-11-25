import CodexMeterCore
import Foundation

/// Surprise refills, saved resets and "usage is back" for every provider, and the official status
/// feeds, the way Android's `ProviderAlerts`, `UsageRefillAlerts` and `ProviderStatusStore` work.
/// Codex keeps its own notifications in `NotificationCoordinator.process`; here it only adds its
/// events to Priority.
extension AppModel {
    /// Hooks each provider's model up to the activity store. Called once, from `init`.
    func wireProviderActivity() {
        claude.onUsageSaved = { [weak self] provider, previous, current in
            self?.providerUsageSaved(provider, previous: previous, current: current)
        }
        claude.onSavedResetsChanging = { [weak self] stored, current in
            self?.savedResetsChanging(.claudeCode, stored: stored, current: current)
        }
        claude.onUserReset = { ProviderActivityStore.shared.markUserReset(.claudeCode) }
        claude.onSignedOut = { [weak self] in self?.forgetActivity(.claudeCode) }
        cursor.onUsageSaved = { [weak self] provider, previous, current in
            self?.providerUsageSaved(provider, previous: previous, current: current)
        }
        cursor.onSignedOut = { [weak self] in self?.forgetActivity(.cursor) }
        for added in addedProviders.values {
            added.onUsageSaved = { [weak self] provider, previous, current in
                self?.providerUsageSaved(provider, previous: previous, current: current)
            }
            added.onSignedOut = { [weak self] provider in self?.forgetActivity(provider) }
        }
        if !isUITestingOrPreview {
            providerEvents = ProviderActivityStore.shared.recent()
        }
    }

    /// A provider other than Codex showed a fresh reading.
    func providerUsageSaved(_ provider: UsageProvider, previous: UsageSnapshot?, current: UsageSnapshot) {
        guard !isUITestingOrPreview else { return }
        let store = ProviderActivityStore.shared
        let event = store.usageSaved(provider, previous: previous, current: current)
        let back = store.usageBack(provider, snapshot: current)
        providerEvents = store.recent()
        let settings = settings
        let coordinator = notificationCoordinator
        Task {
            guard settings.notificationsEnabled else { return }
            if let event, settings.unexpectedRefillAlertsEnabled {
                await coordinator.notifyAccountActivity(event)
            }
            await coordinator.applyUsageBack(back)
        }
    }

    /// Anthropic sent its saved-reset list.
    func savedResetsChanging(_ provider: UsageProvider, stored: ClaudeSavedResets?, current: ClaudeSavedResets) {
        guard !isUITestingOrPreview else { return }
        let store = ProviderActivityStore.shared
        let event = store.savedResetsChanging(provider, stored: stored, current: current)
        providerEvents = store.recent()
        guard let event, settings.notificationsEnabled, settings.creditIncreaseAlertsEnabled else { return }
        let coordinator = notificationCoordinator
        Task { await coordinator.notifyAccountActivity(event) }
    }

    /// Codex's own notifications already cover these; Priority lists them too.
    func recordCodexActivity(previous: UsageSnapshot?, current: UsageSnapshot, credits: ResetCreditsSnapshot) {
        guard !isUITestingOrPreview else { return }
        let store = ProviderActivityStore.shared
        store.usageSaved(.codex, previous: previous, current: current)
        store.codexCreditCount(credits.availableCount, now: current.fetchedAt)
        providerEvents = store.recent()
    }

    /// Forgets a signed-out provider's activity, alerts and status.
    func forgetActivity(_ provider: UsageProvider) {
        guard !isUITestingOrPreview else { return }
        let ids = ProviderActivityStore.shared.clear(provider)
        providerEvents = ProviderActivityStore.shared.recent()
        providerStatuses[provider] = nil
        let coordinator = notificationCoordinator
        Task {
            await coordinator.cancelRequests(ids)
            await ProviderStatusStore.shared.forget(provider)
        }
    }

    /// Removes rows from Priority for good: recent activity and new models.
    func dismissPriorityItems(_ items: [PriorityItem]) {
        let keys = items.compactMap(\.dismissKey)
        let events = keys.filter { $0.hasPrefix(PriorityReport.eventDismissPrefix) }
            .map { String($0.dropFirst(PriorityReport.eventDismissPrefix.count)) }
        let models = keys.filter { $0.hasPrefix(ModelDiscovery.dismissPrefix) }
        if mode == .demo || isUITestingOrPreview {
            dismissedDemoKeys.formUnion(keys)
            return
        }
        if !events.isEmpty {
            ProviderActivityStore.shared.dismiss(keys: events)
            providerEvents = ProviderActivityStore.shared.recent()
        }
        if !models.isEmpty {
            // Shown at once; the store keeps it for later launches.
            modelDiscoveryState = ModelDiscovery.dismiss(modelDiscoveryState, keys: models)
            Task { [weak self] in
                let saved = await ModelInsightsStore.shared.dismissModels(keys: models)
                self?.modelDiscoveryState = saved
            }
        }
    }

    /// Reads the signed-in providers' official status feeds when due. Demo mode and UI tests
    /// show sample statuses and make no requests.
    func refreshProviderStatus(foreground: Bool = true) async {
        if mode == .demo || isUITestingOrPreview {
            providerStatuses = Self.demoStatuses(now: .now)
            return
        }
        let providers = priorityProviders.filter { isSignedIn($0) }
        let store = ProviderStatusStore.shared
        await store.refresh(providers, foreground: foreground)
        providerStatuses = await store.statuses(for: providers)
    }

    static func demoStatuses(now: Date) -> [UsageProvider: ProviderStatus] {
        [.claudeCode: ProviderStatus(
            provider: UsageProvider.claudeCode.rawValue, state: .degraded,
            issues: [ProviderStatus.Issue(component: "Claude Code", state: .degraded,
                                          title: "Elevated errors on Claude Code (Demo)",
                                          startedAt: now.addingTimeInterval(-25 * 60))],
            pageURL: "https://status.claude.com", checkedAt: now
        )]
    }
}
