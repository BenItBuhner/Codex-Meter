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

    private var additionalWindows: [AdditionalWindowPresentation] {
        guard model.settings.showAdditionalLimits else { return [] }
        return (model.usage?.additionalLimits ?? []).flatMap { limit in
            var windows: [AdditionalWindowPresentation] = []
            if let primary = limit.primary {
                windows.append(
                    AdditionalWindowPresentation(
                        id: "\(limit.id)-primary",
                        title: "\(Self.limitTitle(limit)) · \(Self.cadenceLabel(primary))",
                        window: primary,
                        accent: .teal
                    )
                )
            }
            if let secondary = limit.secondary {
                windows.append(
                    AdditionalWindowPresentation(
                        id: "\(limit.id)-secondary",
                        title: "\(Self.limitTitle(limit)) · \(Self.cadenceLabel(secondary))",
                        window: secondary,
                        accent: .purple
                    )
                )
            }
            return windows
        }
    }

    private var hasVisibleUsageWindows: Bool {
        (model.settings.showFiveHour && model.usage?.fiveHour != nil)
            || (model.settings.showWeekly && model.usage?.weekly != nil)
            || !additionalWindows.isEmpty
    }

    /// Prefer the detailed credits snapshot; fall back to the usage-endpoint summary count.
    private var shouldShowResetCredits: Bool {
        if let credits = model.credits {
            return credits.shouldDisplay
        }
        guard let count = model.usage?.resetCreditsAvailable else {
            return false
        }
        return count > 0
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

                    if hasVisibleUsageWindows {
                        LazyVGrid(columns: columns, spacing: AppChrome.sectionSpacing) {
                            if model.settings.showFiveHour,
                               let window = model.usage?.fiveHour {
                                UsageMeterCard(
                                    title: "5-hour",
                                    systemImage: "clock",
                                    window: window,
                                    accent: .mint,
                                    fetchedAt: model.usage?.fetchedAt ?? .now
                                )
                            }
                            if model.settings.showWeekly,
                               let window = model.usage?.weekly {
                                UsageMeterCard(
                                    title: "Weekly",
                                    systemImage: "calendar",
                                    window: window,
                                    accent: .indigo,
                                    fetchedAt: model.usage?.fetchedAt ?? .now
                                )
                            }
                            ForEach(additionalWindows) { item in
                                UsageMeterCard(
                                    title: LocalizedStringKey(item.title),
                                    systemImage: item.window.windowSeconds >= 86_400
                                        ? "calendar.badge.clock" : "clock.badge",
                                    window: item.window,
                                    accent: item.accent,
                                    fetchedAt: model.usage?.fetchedAt ?? .now
                                )
                            }
                        }
                    }

                    if model.settings.showUsageCredits,
                       let usageCredits = model.usage?.usageCredits,
                       usageCredits.shouldDisplay {
                        UsageCreditsCard(credits: usageCredits)
                    }
                    if model.settings.showResetCredits, shouldShowResetCredits {
                        ResetCreditsCard()
                    }
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

    private static func cadenceLabel(_ window: UsageWindow) -> String {
        let seconds = window.windowSeconds
        if (432_000 ... 777_600).contains(seconds) {
            return "Weekly"
        }
        if (10_800 ... 28_800).contains(seconds) {
            return "\(max(1, Int((Double(seconds) / 3_600).rounded())))-hour"
        }
        if seconds.isMultiple(of: 86_400) {
            return "\(seconds / 86_400)-day"
        }
        if seconds.isMultiple(of: 3_600) {
            return "\(seconds / 3_600)-hour"
        }
        return "Usage"
    }

    private static func limitTitle(_ limit: UsageLimit) -> String {
        if limit.limitReached {
            return "\(limit.displayName) (limit reached)"
        }
        if !limit.allowed {
            return "\(limit.displayName) (unavailable)"
        }
        return limit.displayName
    }
}

private struct AdditionalWindowPresentation: Identifiable {
    let id: String
    let title: String
    let window: UsageWindow
    let accent: Color
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

private struct UsageCreditsCard: View {
    let credits: UsageCredits

    private var balance: String {
        if credits.unlimited {
            return "Unlimited"
        }
        guard let raw = credits.balance else {
            return credits.hasCredits ? "Available" : "No purchased credits"
        }
        if let number = Double(raw.replacingOccurrences(of: ",", with: "")),
           number.isFinite {
            return number.formatted(
                .number.precision(.fractionLength(0 ... 2))
            ) + " credits"
        }
        return raw
    }

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: "creditcard.fill")
                .font(.system(size: 34))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(balance)
                    .font(.title2.bold())
                    .contentTransition(.numericText())
                Text("Usage-credit balance")
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(AppChrome.cardPadding)
        .cardSurface()
        .accessibilityElement(children: .combine)
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
