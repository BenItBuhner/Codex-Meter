import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var page = 0

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                onboardingPage(
                    tag: 0,
                    systemImage: "gauge.with.dots.needle.67percent",
                    title: "Codex allowance at a glance",
                    bodyText: "See your five-hour and weekly Codex windows, reset times, and earned reset credits on iPhone and iPad."
                )
                onboardingPage(
                    tag: 1,
                    systemImage: "lock.shield.fill",
                    title: "Local and private",
                    bodyText: "OAuth tokens stay in the device Keychain. There is no analytics relay. Demo mode never contacts OpenAI."
                )
                onboardingPage(
                    tag: 2,
                    systemImage: "person.badge.key.fill",
                    title: "Connect when you are ready",
                    bodyText: "Sign in with ChatGPT using OpenAI’s device-code flow, or explore offline demo data first."
                )
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .animation(.snappy, value: page)

            VStack(spacing: 12) {
                if page < 2 {
                    Button("Continue") {
                        withAnimation { page += 1 }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                } else {
                    Button("Sign in with ChatGPT") {
                        model.completeOnboarding()
                        model.isShowingSignIn = true
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)

                    Button("Explore demo") {
                        model.completeOnboarding()
                        Task { await model.enterDemo() }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)

                    Button("Skip for now") {
                        model.completeOnboarding()
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 28)
            .padding(.top, 8)
        }
        .background(Color(.systemGroupedBackground))
    }

    private func onboardingPage(
        tag: Int,
        systemImage: String,
        title: String,
        bodyText: String
    ) -> some View {
        VStack(spacing: 22) {
            Spacer(minLength: 24)
            Image(systemName: systemImage)
                .font(.system(size: 56, weight: .medium))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)
            Text(title)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            Text(bodyText)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 12)
            Spacer(minLength: 24)
        }
        .padding(28)
        .tag(tag)
    }
}

#Preview {
    OnboardingView()
        .environment(AppModel.preview)
}
