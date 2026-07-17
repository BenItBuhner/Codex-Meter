import CodexMeterCore
import SwiftUI

struct DashboardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var columns: [GridItem] {
        horizontalSizeClass == .regular
            ? [GridItem(.flexible(), spacing: AppChrome.sectionSpacing), GridItem(.flexible(), spacing: AppChrome.sectionSpacing)]
            : [GridItem(.flexible())]
    }

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

                    LazyVGrid(columns: columns, spacing: AppChrome.sectionSpacing) {
                        UsageMeterCard(
                            title: "5-hour",
                            systemImage: "clock",
                            window: model.usage?.fiveHour,
                            accent: .mint,
                            fetchedAt: model.usage?.fetchedAt ?? .now
                        )
                        UsageMeterCard(
                            title: "Weekly",
                            systemImage: "calendar",
                            window: model.usage?.weekly,
                            accent: .indigo,
                            fetchedAt: model.usage?.fetchedAt ?? .now
                        )
                    }

                    ResetCreditsCard()
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
                Text("Connect your ChatGPT account to see the current five-hour and weekly windows, reset times, and earned reset credits.")
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

private struct ResetCreditsCard: View {
    @Environment(AppModel.self) private var model

    private var count: Int {
        model.credits?.availableCount ?? model.usage?.resetCreditsAvailable ?? 0
    }

    private var nextExpiry: Date? {
        model.credits?.credits
            .filter(\.isAvailable)
            .compactMap(\.expiresAt)
            .filter { $0 > .now }
            .min()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                Image(systemName: "arrow.counterclockwise.circle.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(.tint)
                    .symbolRenderingMode(.hierarchical)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(count)")
                        .font(.largeTitle.bold())
                        .contentTransition(.numericText())
                    Text(count == 1 ? "Reset available" : "Resets available")
                        .font(.headline)
                }
                Spacer(minLength: 0)
            }

            if let nextExpiry {
                Label {
                    Text("Next credit expires \(nextExpiry, style: .relative)")
                } icon: {
                    Image(systemName: "calendar.badge.clock")
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            } else if count > 0, model.credits != nil {
                Text("Expiry details are unavailable for the current inventory.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Button(count > 0 ? "Use 1 reset" : "No resets available") {
                model.isShowingReset = true
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
            .disabled(count == 0)
        }
        .padding(AppChrome.cardPadding)
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
