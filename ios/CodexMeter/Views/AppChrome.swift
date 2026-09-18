import SwiftUI

enum AppChrome {
    static let cardRadius: CGFloat = 22
    static let bannerRadius: CGFloat = 14
    static let cardPadding: CGFloat = 20
    static let sectionSpacing: CGFloat = 16
    static let contentMaxWidth: CGFloat = 920
}

extension View {
    /// Cards lift off the grouped page background through tone alone, the way iOS grouped
    /// lists do: no stroke, and no shadow blur to composite while the dashboard scrolls.
    func cardSurface() -> some View {
        background(
            Color(.secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: AppChrome.cardRadius, style: .continuous)
        )
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
