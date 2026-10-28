import CodexMeterCore
import SwiftUI

// Keyboard and menu commands. On iPad they appear in the menu bar (iPadOS 26) and in the
// shortcut overlay shown while ⌘ is held; on iPhone they are ignored.

extension AppModel {
    /// The visible tab has a session (live or demo) that can be refreshed.
    var canRefreshSelectedProvider: Bool {
        switch selectedProvider {
        case .codex: mode != .signedOut
        case .claudeCode: claude.phase != .signedOut
        case .cursor: cursor.phase != .signedOut
        default: addedProviders[selectedProvider].map { $0.phase != .signedOut } ?? false
        }
    }

    var isRefreshingSelectedProvider: Bool {
        switch selectedProvider {
        case .codex: isRefreshing
        case .claudeCode: claude.isRefreshing
        case .cursor: cursor.isRefreshing
        default: addedProviders[selectedProvider]?.isRefreshing ?? false
        }
    }

    /// What the visible tab's Refresh button does.
    func refreshSelectedProvider() async {
        guard canRefreshSelectedProvider else { return }
        switch selectedProvider {
        case .codex: await refresh()
        case .claudeCode: await claude.refresh()
        case .cursor: await cursor.refresh()
        default: await addedProviders[selectedProvider]?.refresh()
        }
    }

    /// The tab `offset` places from `current`, wrapping at either end.
    func provider(_ offset: Int, from current: UsageProvider) -> UsageProvider? {
        let providers = enabledProviders
        guard providers.count > 1,
              let index = providers.firstIndex(of: current) else { return nil }
        return providers[(index + offset % providers.count + providers.count) % providers.count]
    }

    /// Selects the tab `offset` places from the current one, wrapping at either end.
    func selectAdjacentProvider(offset: Int) {
        if let next = provider(offset, from: selectedProvider) {
            select(provider: next)
        }
    }
}

/// The tabs of the window in front, published by each window with `focusedSceneValue`, so
/// ⌘1–⌘9 and ⇧⌘] / ⇧⌘[ switch that window's tab directly instead of going through the shared
/// model.
struct WindowTabs: Sendable {
    let current: UsageProvider
    let choose: @MainActor @Sendable (UsageProvider) -> Void
}

extension FocusedValues {
    @Entry var windowTabs: WindowTabs?
}

struct CodexMeterCommands: Commands {
    let model: AppModel
    @FocusedValue(\.windowTabs) private var windowTabs

    var body: some Commands {
        CommandGroup(replacing: .appSettings) {
            Button("Settings…") {
                model.isShowingSettings = true
            }
            .keyboardShortcut(",", modifiers: .command)
        }

        CommandMenu("Usage") {
            UsageMenuItems(model: model, windowTabs: windowTabs)
        }
    }
}

/// A view, not inline command content, so the menu follows the model as tabs are turned on
/// or off and sessions start or end.
private struct UsageMenuItems: View {
    let model: AppModel
    let windowTabs: WindowTabs?
#if os(iOS)
    @Environment(\.openWindow) private var openWindow
    @Environment(\.supportsMultipleWindows) private var supportsMultipleWindows
#endif

    var body: some View {
#if os(iOS)
        // A second iPad window, starting on the tab in front, to keep two providers side by
        // side in Split View or Stage Manager.
        if supportsMultipleWindows {
            let front = windowTabs?.current ?? model.selectedProvider
            Button("Open \(front.displayName) in New Window") {
                openWindow(value: front)
            }
            .keyboardShortcut("n", modifiers: [.command, .option])
            Divider()
        }
#endif
#if os(iOS) && DEBUG
        // UI tests only: folds or unfolds the simulated iPhone Duo (see SimulatedWindow).
        if SimulatedDuo.shared.isEnabled {
            Button(SimulatedDuo.shared.isOpen ? "Fold" : "Unfold") {
                SimulatedDuo.shared.toggle()
            }
            .keyboardShortcut("f", modifiers: [.command, .option, .control])
            Divider()
        }
#endif

        Button("Refresh") {
            Task { await model.refreshSelectedProvider() }
        }
        .keyboardShortcut("r", modifiers: .command)
        .disabled(!model.canRefreshSelectedProvider || model.isRefreshingSelectedProvider)

        Divider()

        // ⌘1 to ⌘9 jump to a tab, in tab order, as in Safari and Mail.
        ForEach(Array(model.enabledProviders.prefix(9).enumerated()), id: \.element) { index, provider in
            Button(provider.displayName) {
                select(provider)
            }
            .keyboardShortcut(KeyEquivalent(Character(String(index + 1))), modifiers: .command)
        }

        if model.enabledProviders.count > 1 {
            Divider()
            Button("Next Provider") {
                selectAdjacent(1)
            }
            .keyboardShortcut("]", modifiers: [.command, .shift])
            Button("Previous Provider") {
                selectAdjacent(-1)
            }
            .keyboardShortcut("[", modifiers: [.command, .shift])
        }
    }

    private func select(_ provider: UsageProvider) {
        if let windowTabs {
            windowTabs.choose(provider)
        } else {
            model.select(provider: provider)
        }
    }

    private func selectAdjacent(_ offset: Int) {
        let current = windowTabs?.current ?? model.selectedProvider
        if let next = model.provider(offset, from: current) {
            select(next)
        }
    }
}
