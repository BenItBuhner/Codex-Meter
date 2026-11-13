#if os(iOS) && DEBUG
import CodexMeterCore
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
//
// iPhone Duo puts the status bar and Dynamic Island in an 84 pt rail on the trailing edge, and
// apps built with the iOS 27.1 SDK get their toolbar buttons and tab bar laid out vertically in
// that rail, top down: Dynamic Island, status, toolbar, tabs. GitHub's runners only have the
// iOS 26 SDK, which can't produce that layout, so the simulation draws the rail itself with
// the dashboard's real actions and tabs, and the dashboards leave their top toolbar empty.

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
    /// The status rail on the trailing edge, measured on the iPhone Duo simulator.
    static let railWidth: CGFloat = 84

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
    let model: AppModel
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
            HStack(spacing: 0) {
                content
                    .environment(\.horizontalSizeClass, duo.isOpen ? .regular : .compact)
                    .environment(\.verticalSizeClass, .regular)
                    .environment(\.drawsBarsInSimulatedRail, true)
                SimulatedDuoRail()
                    .environment(model)
                    .frame(width: SimulatedDuo.railWidth)
            }
            .frame(width: size.width, height: size.height)
            .background(Color(.systemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 44, style: .continuous))
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

/// The trailing status rail of the simulated iPhone Duo: Dynamic Island, status, then the
/// dashboard's toolbar and the provider tabs, laid out vertically as iOS 27.1 does.
private struct SimulatedDuoRail: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 14) {
            Capsule()
                .fill(.black)
                .frame(width: 34, height: 112)
                .padding(.top, 14)

            VStack(spacing: 6) {
                Text(verbatim: "9:41")
                    .font(.system(size: 15, weight: .semibold))
                    .monospacedDigit()
                Image(systemName: "wifi")
                Image(systemName: "battery.100percent")
            }
            .font(.system(size: 13, weight: .semibold))

            VStack(spacing: 4) {
                if model.canRefreshSelectedProvider {
                    railButton("Edit dashboard", systemImage: "slider.horizontal.3") {}
                    railButton("Refresh", systemImage: "arrow.clockwise") {
                        Task { await model.refreshSelectedProvider() }
                    }
                }
                railButton("Settings", systemImage: "gearshape") {
                    model.isShowingSettings = true
                }
            }
            .padding(.vertical, 6)
            .glassEffect(.regular.interactive(), in: Capsule())

            Spacer(minLength: 8)

            if model.enabledProviders.count > 1 {
                VStack(spacing: 2) {
                    ForEach(model.enabledProviders) { provider in
                        tab(provider)
                    }
                }
                .padding(4)
                .glassEffect(.regular.interactive(), in: Capsule())
                .padding(.bottom, 14)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("duo-status-rail")
        .accessibilityValue(SimulatedDuo.shared.isOpen ? "open" : "folded")
    }

    private func railButton(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 19, weight: .medium))
                .frame(width: 48, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    private func tab(_ provider: UsageProvider) -> some View {
        let selected = provider == model.selectedProvider
        return Button {
            model.select(provider: provider)
        } label: {
            VStack(spacing: 3) {
                ProviderMark(provider: provider)
                    .frame(width: 22, height: 22)
                Text(provider.railTitle)
                    .font(.system(size: 9, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .foregroundStyle(selected ? Color.accentColor : Color.primary)
            .frame(width: 60, height: 52)
            .background {
                if selected {
                    Capsule().fill(Color.primary.opacity(0.08))
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(provider.displayName)
    }
}

private extension UsageProvider {
    /// Short tab label for the narrow rail.
    var railTitle: String {
        switch self {
        case .claudeCode: "Claude"
        default: displayName
        }
    }
}
#endif
