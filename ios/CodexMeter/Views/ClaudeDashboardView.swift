import CodexMeterCore
import SwiftUI

/// The Claude Code tab: sign-in when signed out, otherwise the same meter cards the Codex
/// dashboard uses for the 5-hour, weekly, and model-specific limits.
struct ClaudeDashboardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        @Bindable var claude = model.claude

        ScrollView {
            LazyVStack(spacing: AppChrome.sectionSpacing) {
                if claude.phase == .signedOut {
                    ClaudeSignedOutView()
                } else {
                    if claude.phase == .demo {
                        StatusBanner(
                            message: "Demo data — no Anthropic requests",
                            systemImage: "sparkles",
                            tint: .blue
                        )
                    }

                    if let error = claude.visibleError {
                        StatusBanner(
                            message: error,
                            systemImage: "exclamationmark.triangle.fill",
                            tint: .orange
                        )
                    }

                    if let usage = claude.usage {
                        ClaudeStatusStrip(usage: usage, isCached: claude.isUsingCachedData)
                        ProviderDashboardCards(provider: .claudeCode, usage: usage)
                    } else {
                        ClaudeEmptyUsageCard()
                    }

                    ClaudePrivacyFootnote()
                }
            }
            .frame(maxWidth: AppChrome.contentMaxWidth)
            .padding(.horizontal, horizontalSizeClass == .regular ? 28 : 16)
            .padding(.vertical, 18)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Claude Code")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if claude.phase != .signedOut {
                    NavigationLink {
                        DashboardEditView(provider: .claudeCode)
                    } label: {
                        Label("Edit dashboard", systemImage: "slider.horizontal.3")
                    }

                    Button {
                        Task { await model.claude.refresh() }
                    } label: {
                        if claude.isRefreshing {
                            ProgressView()
                        } else {
                            Label("Refresh", systemImage: "arrow.clockwise")
                        }
                    }
                    .disabled(claude.isRefreshing)
                    .accessibilityLabel(claude.isRefreshing ? "Refreshing Claude usage" : "Refresh Claude usage")
                }

                Button {
                    model.isShowingSettings = true
                } label: {
                    Label("Settings", systemImage: "gearshape")
                }
            }
        }
        .refreshable {
            await model.claude.refresh()
        }
        .task {
            await model.claude.tabOpened()
        }
        .sheet(isPresented: $claude.isShowingSignIn) {
            NavigationStack {
                ClaudeSignInView()
            }
            .interactiveDismissDisabled(claude.isAuthenticating)
        }
    }
}

private struct ClaudeStatusStrip: View {
    let usage: UsageSnapshot
    let isCached: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                if !usage.planType.isEmpty {
                    PlanBadge(title: usage.planType)
                }
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    HStack(spacing: 6) {
                        Image(systemName: isCached ? "internaldrive" : "checkmark.circle")
                            .foregroundStyle(isCached ? .orange : .secondary)
                        Text(
                            isCached
                                ? "Showing cached data · \(UsageFormat.updated(fetchedAt: usage.fetchedAt, now: context.date))"
                                : UsageFormat.updated(fetchedAt: usage.fetchedAt, now: context.date)
                        )
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityElement(children: .combine)
                }
                Spacer(minLength: 0)
            }

            if usage.limitReached {
                Label("A Claude usage limit is reached for the current window.", systemImage: "exclamationmark.circle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
    }
}

private struct ClaudeEmptyUsageCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let claude = model.claude

        VStack(alignment: .leading, spacing: 10) {
            if claude.isRefreshing {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Loading Claude usage…")
                }
                .font(.headline)
            } else {
                Label("No Claude usage loaded yet", systemImage: "rectangle.slash")
                    .font(.headline)
                Text("Pull to refresh or tap Refresh. Claude rate-limits usage checks, so Codex Meter only asks when you open this tab.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Refresh now") {
                    Task { await model.claude.refresh() }
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }
}

private struct ClaudeSignedOutView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 22) {
            Image(systemName: "gauge.with.dots.needle.33percent")
                .font(.system(size: 58, weight: .medium))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("Your Claude Code limits at a glance")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text("Connect your Claude Pro, Max, Team, or Enterprise account to see the 5-hour and weekly limits Claude Code reports, any model-only allocation such as Fable, and reset times.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            VStack(spacing: 12) {
                Button(UsageProvider.claudeCode.signInLabel) {
                    model.claude.isShowingSignIn = true
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)

                if model.mode == .signedOut {
                    Button("Explore demo") {
                        Task { await model.enterDemo() }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(maxWidth: 420)

            Label("Signing in to Claude does not affect your ChatGPT session.", systemImage: "person.2")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .cardSurface()
    }
}

private struct ClaudePrivacyFootnote: View {
    var body: some View {
        Label {
            Text("Claude tokens stay in Keychain, separate from ChatGPT. Requests go directly to Anthropic, only when this tab opens with data older than five minutes or when you refresh.")
        } icon: {
            Image(systemName: "lock.shield.fill")
                .foregroundStyle(.tint)
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
    }
}
