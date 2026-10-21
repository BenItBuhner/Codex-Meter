import SwiftUI

enum AppChrome {
    static let cardRadius: CGFloat = 22
    static let bannerRadius: CGFloat = 14
    static let cardPadding: CGFloat = 20
    static let sectionSpacing: CGFloat = 16
    static let contentMaxWidth: CGFloat = 920
}

extension View {
    func cardSurface() -> some View {
        background(.background, in: RoundedRectangle(cornerRadius: AppChrome.cardRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AppChrome.cardRadius, style: .continuous)
                    .stroke(.separator.opacity(0.18), lineWidth: 0.5)
            }
    }

    func bannerSurface(tint: Color) -> some View {
        padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: AppChrome.bannerRadius, style: .continuous))
    }

    /// Animates changes of `value` unless Reduce Motion is on, in which case the new state
    /// is shown immediately.
    func motionAnimation(_ animation: Animation, value: some Equatable) -> some View {
        modifier(ReducedMotionAnimation(animation: animation, value: value))
    }

    /// Rolls digits with `.numericText()` unless Reduce Motion is on.
    func numericTextTransition() -> some View {
        modifier(ReducedMotionNumericText())
    }
}

private struct ReducedMotionAnimation<Value: Equatable>: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let animation: Animation
    let value: Value

    func body(content: Content) -> some View {
        content.animation(reduceMotion ? nil : animation, value: value)
    }
}

private struct ReducedMotionNumericText: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.contentTransition(reduceMotion ? .identity : .numericText())
    }
}

struct StatusBanner: View {
    let message: String
    let systemImage: String
    var tint: Color = .blue
    var emphasizesTint = true

    var body: some View {
        Label(message, systemImage: systemImage)
            .font(.subheadline.weight(emphasizesTint ? .semibold : .regular))
            .foregroundStyle(emphasizesTint ? AnyShapeStyle(tint) : AnyShapeStyle(.secondary))
            .bannerSurface(tint: tint)
            .accessibilityAddTraits(.isStaticText)
    }
}

struct PlanBadge: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(.tint.opacity(0.14), in: Capsule())
            .accessibilityLabel("Plan \(title)")
    }
}
