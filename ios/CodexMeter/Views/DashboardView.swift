import CodexMeterCore
import SwiftUI

struct DashboardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        ScrollView {
            LazyVStack(spacing: AppChrome.sectionSpacing) {
                if model.mode == .signedOut {
                    SignedOutView()
                } else {
                    if model.mode == .demo {
                        StatusBanner(
                            message: "Demo data — no OpenAI requests",
                            systemImage: "sparkles",
                            tint: .blue
                        )
                    }

                    if let error = model.visibleError {
                        StatusBanner(
                            message: error,
                            systemImage: "exclamationmark.triangle.fill",
                            tint: .orange
                        )
                    }

                    if let usage = model.usage {
                        DashboardStatusStrip(
                            plan: model.accountPlan,
                            usage: usage,
                            isCached: model.isUsingCachedData
                        )
                    }

                    DashboardSectionsView()
                    PrivacyFootnote()
                }
            }
            .frame(maxWidth: AppChrome.contentMaxWidth)
            .padding(.horizontal, horizontalSizeClass == .regular ? 28 : 16)
            .padding(.vertical, 18)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Codex Meter")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if model.mode != .signedOut {
                    NavigationLink {
                        DashboardEditView()
                    } label: {
                        Label("Edit dashboard", systemImage: "slider.horizontal.3")
                    }

                    Button {
                        Task { await model.refresh() }
                    } label: {
                        if model.isRefreshing {
                            ProgressView()
                        } else {
                            Label("Refresh", systemImage: "arrow.clockwise")
                        }
                    }
                    .disabled(model.isRefreshing)
                    .accessibilityLabel(model.isRefreshing ? "Refreshing usage" : "Refresh usage")
                }

                Button {
                    model.isShowingSettings = true
                } label: {
                    Label("Settings", systemImage: "gearshape")
                }
            }
        }
        .refreshable {
            guard model.mode != .signedOut else { return }
            await model.refresh()
        }
        .task {
            await model.startIfNeeded()
        }
    }
}

private struct DashboardStatusStrip: View {
    let plan: String
    let usage: UsageSnapshot
    let isCached: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                if !plan.isEmpty {
                    PlanBadge(title: plan)
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
                Label("Usage limit reached for the current window.", systemImage: "exclamationmark.circle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.orange)
            } else if !usage.allowed {
                Label("Codex usage is not currently available for this account.", systemImage: "nosign")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.orange)
            }
        }
        .padding(.horizontal, 4)
    }
}

private struct SignedOutView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 22) {
            Image(systemName: "gauge.with.dots.needle.67percent")
                .font(.system(size: 58, weight: .medium))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("Your Codex allowance at a glance")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text("Connect your ChatGPT account to see current usage limits, purchased usage credits, reset times, and earned reset credits.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            VStack(spacing: 12) {
                Button("Sign in with ChatGPT") {
                    model.isShowingSignIn = true
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)

                Button("Explore demo") {
                    Task { await model.enterDemo() }
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
            }
            .frame(maxWidth: 420)

            Label("Demo mode is local and never contacts OpenAI.", systemImage: "hand.raised")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .cardSurface()
    }
}

private struct PrivacyFootnote: View {
    var body: some View {
        Label {
            Text("Tokens stay in Keychain. Requests go directly to OpenAI; widgets only get a sanitized usage snapshot.")
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
