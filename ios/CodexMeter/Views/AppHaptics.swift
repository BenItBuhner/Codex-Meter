import CodexMeterCore
import SwiftUI

/// Settings > App > Haptics: a tap when fresh usage lands or a reset is used.
private struct AppHaptics: ViewModifier {
    @Environment(AppModel.self) private var model

    /// Changes whenever any built-in provider's usage is refreshed.
    private var refreshTrigger: [Date?] {
        [model.usage?.fetchedAt, model.claude.usage?.fetchedAt, model.cursor.usage?.fetchedAt]
    }

    private var resetTrigger: [String?] {
        [model.resetResultIsSuccess == true ? model.resetResultMessage : nil, model.claude.resetMessage]
    }

    func body(content: Content) -> some View {
        #if os(iOS)
        content
            // Only a refresh of data already on screen; the first load at launch stays quiet.
            .sensoryFeedback(trigger: refreshTrigger) { old, new in
                guard zip(old, new).contains(where: { $0 != nil && $0 != $1 }) else { return nil }
                return feedback(strong: false)
            }
            .sensoryFeedback(trigger: resetTrigger) { _, new in
                guard new.contains(where: { $0 != nil }) else { return nil }
                return feedback(strong: true)
            }
        #else
        content
        #endif
    }

    private func feedback(strong: Bool) -> SensoryFeedback? {
        switch model.settings.haptics {
        case .off: nil
        case .light: .impact(weight: strong ? .medium : .light)
        case .strong: if strong { .success } else { .impact(weight: .heavy) }
        }
    }
}

extension View {
    func appHaptics() -> some View {
        modifier(AppHaptics())
    }
}
