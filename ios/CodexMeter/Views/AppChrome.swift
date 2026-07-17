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
