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

    /// Selects the tab `offset` places from the current one, wrapping at either end.
    func selectAdjacentProvider(offset: Int) {
        let providers = enabledProviders
        guard providers.count > 1,
              let index = providers.firstIndex(of: selectedProvider) else { return }
        let next = (index + offset % providers.count + providers.count) % providers.count
        select(provider: providers[next])
    }
}

struct CodexMeterCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandGroup(replacing: .appSettings) {
            Button("Settings…") {
                model.isShowingSettings = true
            }
            .keyboardShortcut(",", modifiers: .command)
        }

        CommandMenu("Usage") {
            UsageMenuItems(model: model)
        }
    }
}

/// A view, not inline command content, so the menu follows the model as tabs are turned on
/// or off and sessions start or end.
private struct UsageMenuItems: View {
    let model: AppModel
#if os(iOS)
    @Environment(\.openWindow) private var openWindow
    @Environment(\.supportsMultipleWindows) private var supportsMultipleWindows
#endif

    var body: some View {
#if os(iOS)
        // A second iPad window, starting on the tab in front, to keep two providers side by
        // side in Split View or Stage Manager.
        if supportsMultipleWindows {
            Button("Open \(model.selectedProvider.displayName) in New Window") {
                openWindow(value: model.selectedProvider)
            }
            .keyboardShortcut("n", modifiers: [.command, .option])
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
                model.select(provider: provider)
            }
            .keyboardShortcut(KeyEquivalent(Character(String(index + 1))), modifiers: .command)
        }

        if model.enabledProviders.count > 1 {
            Divider()
            Button("Next Provider") {
                model.selectAdjacentProvider(offset: 1)
            }
            .keyboardShortcut("]", modifiers: [.command, .shift])
            Button("Previous Provider") {
                model.selectAdjacentProvider(offset: -1)
            }
            .keyboardShortcut("[", modifiers: [.command, .shift])
        }
    }
}
