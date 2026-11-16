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
                zip(old, new).contains { $0 != nil && $0 != $1 } ? feedback(strong: false) : nil
            }
            .sensoryFeedback(trigger: resetTrigger) { _, new in
                new.contains { $0 != nil } ? feedback(strong: true) : nil
            }
        #else
        content
        #endif
    }

    private func feedback(strong: Bool) -> SensoryFeedback? {
        switch model.settings.haptics {
        case .off: nil
        case .light: strong ? .impact(weight: .medium) : .impact(weight: .light)
        case .strong: strong ? .success : .impact(weight: .heavy)
        }
    }
}

extension View {
    func appHaptics() -> some View {
        modifier(AppHaptics())
    }
}
