import CodexMeterCore
import SwiftUI
#if os(macOS)
import AppKit
#endif

/// The full-screen "What's new" page after a big update, laid out like Apple's own welcome screens:
/// the app icon and a large title, each new feature beside its symbol, and a prominent Continue
/// button that stays at the bottom. A sheet everywhere: swiping it down (or Escape on the Mac)
/// closes it the same as Continue.
struct WhatsNewView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dismiss) private var dismiss

    private var features: [WhatsNew.Feature] {
#if os(macOS)
        WhatsNew.features(for: .macOS)
#else
        WhatsNew.features(for: .iOS)
#endif
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                logo
                    .frame(width: 96, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .shadow(color: .black.opacity(0.12), radius: 10, y: 4)
                    .accessibilityHidden(true)
                    .padding(.top, 40)
                    .padding(.bottom, 22)

                Text(WhatsNew.title)
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("whats-new-title")
                    .padding(.bottom, 36)

                VStack(alignment: .leading, spacing: 26) {
                    ForEach(features) { feature in
                        row(feature)
                    }
                }
            }
            .frame(maxWidth: 520)
            .padding(.horizontal, horizontalSizeClass == .regular ? 40 : 28)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom) {
            Button {
                model.finishWhatsNew()
                // Closes the cover or sheet, or goes back when Settings › About opened it.
                dismiss()
            } label: {
                Text(WhatsNew.button)
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .modifier(ProminentGlassButton())
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .accessibilityIdentifier("whats-new-continue")
            .frame(maxWidth: 400)
            .padding(.horizontal, 28)
            .padding(.top, 12)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity)
            // The list scrolls out of sight behind the button, as on Apple's welcome screens.
            .background(.background)
        }
    }

    @ViewBuilder
    private var logo: some View {
#if os(macOS)
        Image(nsImage: NSApplication.shared.applicationIconImage)
            .resizable()
#else
        Image("AppLogo")
            .resizable()
#endif
    }

    private func row(_ feature: WhatsNew.Feature) -> some View {
        HStack(alignment: .top, spacing: 18) {
            Image(systemName: feature.symbol)
                .font(.title)
                .foregroundStyle(.tint)
                .frame(width: 40)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(feature.title)
                    .font(.headline)
                Text(feature.body)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Liquid Glass's prominent button where the system has it (iOS 26, macOS 26), the bordered
/// prominent button on older Macs.
private struct ProminentGlassButton: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, macOS 26, *) {
            content.buttonStyle(.glassProminent)
        } else {
            content.buttonStyle(.borderedProminent)
        }
    }
}
