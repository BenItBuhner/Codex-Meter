#if os(iOS) && DEBUG
import SwiftUI

// UI tests only. A test can't drag a simulator window to a Split View width or fold a
// simulator into an iPhone Duo, so these launch arguments lay the app out in a window of that
// size, with the size classes iOS gives it, inside a larger simulator screen:
//
// - `-ui-testing-window-width 507`: a column that wide, full height (iPad Split View, Slide
//   Over and Stage Manager widths).
// - `-ui-testing-duo folded` or `-ui-testing-duo open`: iPhone Duo's closed outer display
//   (466 × 678 pt, compact width) or open inner display (951 × 669 pt, regular width and
//   height). Usage → Fold or Unfold (⌃⌥⌘F) switches between them while the app runs, the way
//   folding the phone resizes the window mid-session.

/// The simulated iPhone Duo pose, shared so the menu command can switch it.
@MainActor
@Observable
final class SimulatedDuo {
    static let shared = SimulatedDuo()

    /// Whether `-ui-testing-duo` was passed.
    let isEnabled: Bool
    var isOpen: Bool

    /// Window sizes from the iPhone Duo simulator in Xcode 27.1 (closed portrait and open flat).
    static let foldedSize = CGSize(width: 466, height: 678)
    static let openSize = CGSize(width: 951, height: 669)

    var size: CGSize { isOpen ? Self.openSize : Self.foldedSize }

    private init() {
        let pose = SimulatedWindow.argument(after: "-ui-testing-duo")
        isEnabled = pose != nil
        isOpen = pose == "open"
    }

    func toggle() {
        isOpen.toggle()
    }
}

struct SimulatedWindow: ViewModifier {
    private let duo = SimulatedDuo.shared

    private static let width: CGFloat? = {
        guard let value = argument(after: "-ui-testing-window-width").flatMap(Double.init),
              value > 0 else { return nil }
        return CGFloat(value)
    }()

    static func argument(after flag: String) -> String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }

    func body(content: Content) -> some View {
        if duo.isEnabled {
            let size = duo.size
            content
                .environment(\.horizontalSizeClass, duo.isOpen ? .regular : .compact)
                .environment(\.verticalSizeClass, .regular)
                .frame(width: size.width, height: size.height)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                // Against the leading edge, as the width simulation is: away from a screen
                // edge the navigation bar loses its leading margin. Vertically centered, so CI
                // can crop the stills around the window.
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background(Color(.systemGray4).ignoresSafeArea())
                .animation(.smooth(duration: 0.35), value: duo.isOpen)
        } else if let width = Self.width {
            content
                .environment(\.horizontalSizeClass, width < 600 ? .compact : .regular)
                .frame(width: width)
                .clipped()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.systemGray4).ignoresSafeArea())
        } else {
            content
        }
    }
}
#endif
