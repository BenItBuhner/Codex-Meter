import CodexMeterCore
import Foundation

extension AppModel {
    /// The Priority tab's selection tag, on iPhone, iPad and the Mac sidebar; provider tabs use
    /// their raw values.
    static let priorityTag = "priority"

    /// The latest usage for any provider, or `nil` when it has none (signed out, not loaded).
    func usageSnapshot(for provider: UsageProvider) -> UsageSnapshot? {
        switch provider {
        case .codex: mode == .signedOut ? nil : usage
        case .claudeCode: claude.phase == .signedOut ? nil : claude.usage
        case .cursor: cursor.phase == .signedOut ? nil : cursor.usage
        default: addedProviders[provider].flatMap { $0.phase == .signedOut ? nil : $0.usage }
        }
    }

    /// Providers the Priority tab covers: every tab, plus providers signed in but hidden from
    /// the tab bar, in tab order.
    var priorityProviders: [UsageProvider] {
        providerOrder.filter { enabledProviders.contains($0) || isSignedIn($0) }
    }

    /// Whether Priority has anything to cover: a connected account or demo data. Before the
    /// first sign-in there is no Priority tab.
    var hasPriorityContent: Bool {
        mode == .demo || priorityProviders.contains { isSignedIn($0) || usageSnapshot(for: $0) != nil }
    }

    /// Providers whose makers' new models and speed the Priority tab follows: real sessions only,
    /// or the demo's providers in demo mode.
    private var modelInsightProviders: [UsageProvider] {
        priorityProviders.filter { isSignedIn($0) }
    }

    /// Everything the Priority tab shows, worked out from data already on the device.
    func priorityReport(now: Date = .now) -> PriorityReport {
        let inputs = priorityProviders.map { provider in
            PriorityInput(provider: provider, usage: usageSnapshot(for: provider),
                          credits: priorityCredits(for: provider))
        }
        let newModels = mode == .demo
            ? Self.demoNewModels(now: now)
            : ModelDiscovery.items(modelDiscoveryState, signedIn: modelInsightProviders, now: now)
        return PriorityReport.build(
            inputs,
            slowdowns: settings.slowdownWarningsEnabled ? modelSlowdowns : [],
            newModels: newModels,
            now: now,
            sensitivity: settings.usagePaceEnabled ? settings.usagePaceSensitivity : .off
        )
    }

    /// Checks OpenRouter's public catalog for new models (every 12 hours) and the followed
    /// models' speed (every 45 minutes). Each store call does nothing when it isn't due. Demo
    /// mode and UI tests make no requests and show sample rows instead.
    func refreshModelInsights() async {
        if mode == .demo {
            modelSlowdowns = Self.demoSlowdowns()
            return
        }
        guard !isUITestingOrPreview else { return }
        let providers = modelInsightProviders
        let store = ModelInsightsStore.shared
        modelDiscoveryState = await store.discoveryState()
        if settings.slowdownWarningsEnabled {
            modelSlowdowns = await store.slowdowns(providers: providers)
        }
        guard providers.contains(where: { ModelDiscovery.maker(for: $0) != nil }) else { return }
        let confirmed = await store.refreshDiscoveryIfDue()
        modelDiscoveryState = await store.discoveryState()
        if settings.notificationsEnabled, settings.notifyNewModels {
            for alert in ModelDiscovery.alerts(confirmed, signedIn: providers) {
                await notificationCoordinator.notifyNewModel(title: alert.title, body: alert.body, identifier: alert.id)
            }
        }
        if settings.slowdownWarningsEnabled {
            await store.refreshSpeedIfDue(providers: providers)
            modelSlowdowns = await store.slowdowns(providers: providers)
        } else {
            modelSlowdowns = []
        }
    }

    /// Pull to refresh on Priority: every connected provider (each skips a rate-limited route
    /// that refreshed recently), then the model checks when due.
    func refreshAllForPriority() async {
        await refreshForWatch()
        await refreshModelInsights()
    }

    private var isUITestingOrPreview: Bool {
        ProcessInfo.processInfo.arguments.contains { $0.hasPrefix("-ui-testing") } || isPreview
    }

    static func demoNewModels(now: Date) -> [PriorityItem] {
        [PriorityItem(provider: .claudeCode, title: "New on OpenRouter: Anthropic: Claude Sonnet 6 (Demo)",
                      detail: ModelDiscovery.reason(.claudeCode), at: now.addingTimeInterval(-6 * 3_600))]
    }

    static func demoSlowdowns() -> [PriorityItem] {
        [PriorityItem(provider: .codex, title: "GPT-6.1 Sol is running slower than usual",
                      detail: "31 tokens/s, usually 74 (OpenRouter, via OpenAI, last 30 minutes; public figures, not your subscription)")]
    }

    /// Codex reset credits, or Claude Code's saved resets in the same shape.
    private func priorityCredits(for provider: UsageProvider) -> ResetCreditsSnapshot? {
        switch provider {
        case .codex: mode != .signedOut ? credits : nil
        case .claudeCode: claude.savedResets?.resetCreditsSnapshot(at: .now)
        default: nil
        }
    }

    /// The limit the Live Activity follows: the tightest one across every connected service.
    func liveUsageState(now: Date = .now) -> LiveUsageState? {
        LiveUsage.pick(priorityProviders.map { PriorityInput(provider: $0, usage: usageSnapshot(for: $0)) },
                       now: now, sensitivity: settings.usagePaceSensitivity)
    }

    /// Keeps the Live Activity in step with the latest usage.
    func syncLiveActivity() async {
        #if os(iOS)
        guard !isUITestingOrPreview else { return }
        await LiveActivityController.shared.sync(liveUsageState(), settings: settings)
        #endif
    }
}
