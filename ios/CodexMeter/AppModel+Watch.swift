import CodexMeterCore
import Foundation

extension AppModel {
    /// Every provider tab, in tab order, as the Apple Watch shows it. A provider that is
    /// signed in but hidden from the tab bar still goes to the watch. Read from SwiftUI so the
    /// watch updates whenever usage, sign-in or the tab list changes.
    var watchProviders: [WatchProviderSummary] {
        let hiddenButSignedIn = providerOrder.filter {
            !enabledProviders.contains($0) && isSignedIn($0)
        }
        return (enabledProviders + hiddenButSignedIn).map { provider in
            switch provider {
            case .codex:
                WatchProviderSummary(id: provider.rawValue, name: provider.displayName,
                                     signedIn: mode != .signedOut, usage: usage,
                                     lastError: visibleError)
            case .claudeCode:
                WatchProviderSummary(id: provider.rawValue, name: provider.displayName,
                                     signedIn: claude.phase != .signedOut, usage: claude.usage,
                                     lastError: claude.visibleError)
            case .cursor:
                WatchProviderSummary(id: provider.rawValue, name: provider.displayName,
                                     signedIn: cursor.phase != .signedOut, usage: cursor.usage,
                                     lastError: cursor.visibleError)
            }
        }
    }

    /// A refresh the watch asked for: Codex always, Claude Code and Cursor when their cached
    /// data is old enough that their rate-limited routes allow it.
    func refreshForWatch() async {
        await refresh()
        if isSignedIn(.claudeCode) { await claude.tabOpened() }
        if isSignedIn(.cursor) { await cursor.tabOpened() }
    }
}
