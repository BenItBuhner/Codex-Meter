import CodexMeterCore
import SwiftUI

/// First-run setup on a fresh install: welcome, choose providers, connect each one, done.
/// Nothing is preselected; the choice becomes the dashboard tabs.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var step: OnboardingStep = .welcome
    @State private var selection: Set<UsageProvider> = []
    @State private var signingIn: UsageProvider?
    @State private var query = ""

    /// The picked providers in recommended order, as on Android.
    private var chosen: [UsageProvider] {
        ProviderCatalog.recommendedOrder.filter { selection.contains($0) }
    }

    private var steps: [OnboardingStep] {
        OnboardingFlow.steps(for: chosen)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: AppChrome.sectionSpacing) {
                    Text("Step \((steps.firstIndex(of: step) ?? 0) + 1) of \(steps.count)")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tint)
                        .accessibilityIdentifier("onboarding-step")

                    content
                }
                .frame(maxWidth: 560, alignment: .leading)
                .padding(.horizontal, horizontalSizeClass == .regular ? 28 : 20)
                .padding(.vertical, 18)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground))
            .safeAreaInset(edge: .bottom) {
                actions
                    .frame(maxWidth: 560)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity)
                    .background(.bar)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if step != .welcome {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            withAnimation { step = OnboardingFlow.previous(before: step, in: steps) }
                        } label: {
                            Label("Back", systemImage: "chevron.backward")
                        }
                    }
                }
            }
            .modifier(OnboardingProviderSearch(query: $query, enabled: step == .providers))
        }
        .providerSignInSheet($signingIn)
        .interactiveDismissDisabled()
    }

    @ViewBuilder
    private var content: some View {
        switch step {
        case .welcome:
            welcome
        case .providers:
            providers
        case let .connect(provider):
            connect(provider)
        case .done:
            done
        }
    }

    @ViewBuilder
    private var actions: some View {
        VStack(spacing: 10) {
            switch step {
            case .welcome:
                primaryButton("Get started") { advance() }
                Button("Explore demo") {
                    Task { await model.finishOnboardingWithDemo() }
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
            case .providers:
                primaryButton("Continue") {
                    model.chooseProviders(chosen)
                    advance()
                }
                .disabled(selection.isEmpty)
                .accessibilityIdentifier("onboarding-continue")
            case let .connect(provider):
                if model.isSignedIn(provider) {
                    primaryButton("Continue") { advance() }
                        .accessibilityIdentifier("onboarding-continue")
                } else {
                    primaryButton(provider.signInLabel) { signingIn = provider }
                    Button("Skip for now") { advance() }
                        .controlSize(.large)
                        .frame(maxWidth: .infinity)
                }
            case .done:
                primaryButton("Open Codex Meter") { model.finishOnboarding() }
            }
        }
    }

    private var welcome: some View {
        OnboardingHeader(
            systemImage: "gauge.with.dots.needle.67percent",
            title: "Meet Codex Meter",
            message: "Your Codex, Claude Code, Cursor, Copilot and other AI coding limits, reset timing, and credits in one place. Next, pick the ones you use."
        )
        .accessibilityIdentifier("onboarding-welcome")
    }

    private var providers: some View {
        VStack(alignment: .leading, spacing: AppChrome.sectionSpacing) {
            OnboardingHeader(
                systemImage: "square.grid.2x2",
                title: "Choose your providers",
                message: "Pick every service you want to track. Each one gets a tab on the dashboard, and you can add more later in Settings → Accounts."
            )

            let listed = ProviderCatalog.pickerProviders(connected: [UsageProvider](), query: query)
            VStack(spacing: 0) {
                if listed.isEmpty {
                    Text("No providers match \"\(query)\".")
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 14)
                }
                ForEach(listed) { provider in
                    let picked = selection.contains(provider)
                    Button {
                        if picked { selection.remove(provider) } else { selection.insert(provider) }
                    } label: {
                        HStack(spacing: 10) {
                            ProviderPickerRow(provider: provider)
                            Image(systemName: picked ? "checkmark.circle.fill" : "circle")
                                .font(.title3)
                                .foregroundStyle(picked ? Color.accentColor : Color(.tertiaryLabel))
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .pointerHighlight()
                    .padding(.vertical, 10)
                    .accessibilityAddTraits(picked ? .isSelected : [])
                    .accessibilityIdentifier("onboarding-provider-\(provider.rawValue)")
                    if provider != listed.last {
                        Divider().padding(.leading, 36)
                    }
                }
            }
            .padding(.horizontal, AppChrome.cardPadding)
            .padding(.vertical, 4)
            .cardSurface()
        }
    }

    private func connect(_ provider: UsageProvider) -> some View {
        VStack(alignment: .leading, spacing: AppChrome.sectionSpacing) {
            VStack(alignment: .leading, spacing: 14) {
                ProviderMark(provider: provider)
                    .frame(width: 48, height: 48)
                    .foregroundStyle(.tint)
                Text("Connect \(provider.displayName)")
                    .font(.largeTitle.bold())
                    .accessibilityIdentifier("onboarding-connect-title")
                Text(Self.connectMessage(provider))
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 8)

            if model.isSignedIn(provider) {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                        .font(.title3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Connected")
                        let email = model.accountEmail(for: provider)
                        if !email.isEmpty {
                            Text(email)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(AppChrome.cardPadding)
                .cardSurface()
            } else {
                Label("Tokens stay in Keychain on this device. You can sign in later from Settings.", systemImage: "lock.shield")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var done: some View {
        VStack(alignment: .leading, spacing: AppChrome.sectionSpacing) {
            OnboardingHeader(
                systemImage: "checkmark.circle",
                title: "You're all set",
                message: "Your dashboard has a tab for each provider below. Accounts live in Settings."
            )

            VStack(spacing: 0) {
                ForEach(chosen) { provider in
                    ProviderRowLabel(provider: provider, subtitle: doneSubtitle(provider))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 12)
                    if provider != chosen.last {
                        Divider().padding(.leading, 34)
                    }
                }
            }
            .padding(.horizontal, AppChrome.cardPadding)
            .padding(.vertical, 4)
            .cardSurface()
        }
    }

    private func doneSubtitle(_ provider: UsageProvider) -> String {
        guard model.isSignedIn(provider) else { return "Not signed in, sign in from Settings" }
        let email = model.accountEmail(for: provider)
        return email.isEmpty ? "Signed in" : email
    }

    private func primaryButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
    }

    private func advance() {
        withAnimation { step = OnboardingFlow.next(after: step, in: steps) }
    }

    private static func connectMessage(_ provider: UsageProvider) -> String {
        switch provider {
        case .codex:
            "Sign in with ChatGPT to see your Codex 5-hour and weekly limits, usage credits, and reset credits."
        case .claudeCode:
            "Sign in with your Claude account to see the 5-hour and weekly limits Claude Code reports, plus any model-only allocation."
        case .cursor:
            "Sign in with Cursor to see your included usage and when it refreshes, plus any extra spend on top of your plan."
        default:
            "Sign in with the \(provider.accountName) account you use in \(provider.displayName) to see \(ProviderCatalog.iOSEntry(for: provider).meters.lowercased()). Your password is only entered on \(provider.vendorName)'s own page."
        }
    }
}

private struct OnboardingHeader: View {
    let systemImage: String
    let title: String
    let message: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 44, weight: .medium))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)
            Text(title)
                .font(.largeTitle.bold())
            Text(message)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 8)
    }
}

/// Header search on the provider step only, matching Android's toolbar search button.
private struct OnboardingProviderSearch: ViewModifier {
    @Binding var query: String
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic),
                               prompt: "Search providers")
        } else {
            content
        }
    }
}

private struct ProviderRowLabel: View {
    let provider: UsageProvider
    let subtitle: String

    var body: some View {
        HStack(spacing: 12) {
            ProviderMark(provider: provider)
                .frame(width: 22, height: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(provider.displayName)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
