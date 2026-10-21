import CodexMeterCore
import SwiftUI

/// The Cursor tab: sign-in when signed out, otherwise the included-usage meter for the billing
/// cycle plus the Auto, API, and on-demand cards, using the same meter cards as Codex.
struct CursorDashboardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        @Bindable var cursor = model.cursor

        ScrollView {
            LazyVStack(spacing: AppChrome.sectionSpacing) {
                if cursor.phase == .signedOut {
                    CursorSignedOutView()
                } else {
                    if cursor.phase == .demo {
                        StatusBanner(
                            message: "Demo data — no Cursor requests",
                            systemImage: "sparkles",
                            tint: .blue
                        )
                    }

                    if let error = cursor.visibleError {
                        StatusBanner(
                            message: error,
                            systemImage: "exclamationmark.triangle.fill",
                            tint: .orange
                        )
                    }

                    if let usage = cursor.usage {
                        CursorStatusStrip(usage: usage, isCached: cursor.isUsingCachedData)
                        CursorMeters(usage: usage)
                    } else {
                        CursorEmptyUsageCard()
                    }

                    CursorPrivacyFootnote()
                }
            }
            .frame(maxWidth: AppChrome.contentMaxWidth)
            .padding(.horizontal, horizontalSizeClass == .regular ? 28 : 16)
            .padding(.vertical, 18)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Cursor")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if cursor.phase != .signedOut {
                    Button {
                        Task { await model.cursor.refresh() }
                    } label: {
                        if cursor.isRefreshing {
                            ProgressView()
                        } else {
                            Label("Refresh", systemImage: "arrow.clockwise")
                        }
                    }
                    .disabled(cursor.isRefreshing)
                    .accessibilityLabel(cursor.isRefreshing ? "Refreshing Cursor usage" : "Refresh Cursor usage")
                }

                Button {
                    model.isShowingSettings = true
                } label: {
                    Label("Settings", systemImage: "gearshape")
                }
            }
        }
        .refreshable {
            await model.cursor.refresh()
        }
        .task {
            await model.cursor.tabOpened()
        }
        .sheet(isPresented: $cursor.isShowingSignIn, onDismiss: {
            if model.cursor.phase == .signedOut {
                model.cursor.cancelSignIn()
            }
        }) {
            NavigationStack {
                CursorSignInView()
            }
        }
    }
}

private struct CursorStatusStrip: View {
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
                Label("Cursor's included usage for this billing cycle is used up.", systemImage: "exclamationmark.circle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
    }
}

private struct CursorMeters: View {
    let usage: UsageSnapshot

    var body: some View {
        VStack(spacing: AppChrome.sectionSpacing) {
            if usage.monthly != nil {
                UsageMeterCard(
                    title: "Included usage",
                    systemImage: "calendar",
                    window: usage.monthly,
                    accent: .indigo,
                    fetchedAt: usage.fetchedAt
                )
            }

            ForEach(usage.additionalLimits) { limit in
                AdditionalLimitCards(limit: limit, fetchedAt: usage.fetchedAt)
            }
        }
    }
}

private struct CursorEmptyUsageCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let cursor = model.cursor

        VStack(alignment: .leading, spacing: 10) {
            if cursor.isRefreshing {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Loading Cursor usage…")
                }
                .font(.headline)
            } else {
                Label("No Cursor usage loaded yet", systemImage: "rectangle.slash")
                    .font(.headline)
                Text("Pull to refresh or tap Refresh.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Refresh now") {
                    Task { await model.cursor.refresh() }
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }
}

private struct CursorSignedOutView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 22) {
            Image(systemName: "gauge.with.dots.needle.50percent")
                .font(.system(size: 58, weight: .medium))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("Your Cursor usage at a glance")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text("Connect your Cursor account to see how much of this billing cycle's included usage is left, Auto and API model pools, on-demand spend, and when it resets.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            VStack(spacing: 12) {
                Button(UsageProvider.cursor.signInLabel) {
                    model.cursor.isShowingSignIn = true
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

            Label("Signing in to Cursor does not affect your other accounts.", systemImage: "person.2")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .cardSurface()
    }
}

private struct CursorPrivacyFootnote: View {
    var body: some View {
        Label {
            Text("Cursor tokens stay in Keychain, separate from your other accounts. Requests go directly to Cursor, only when this tab opens with data older than five minutes or when you refresh.")
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

/// Cursor's browser sign-in: approve the login on cursor.com and Codex Meter picks up the
/// tokens on its own. Nothing to paste; the password is entered only on Cursor's page.
struct CursorSignInView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var browserURL: URL?
    @State private var showingBrowser = false

    var body: some View {
        let cursor = model.cursor

        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 20) {
                    Image(systemName: "person.crop.circle.badge.checkmark")
                        .font(.system(size: 54))
                        .foregroundStyle(.tint)
                        .symbolRenderingMode(.hierarchical)
                        .accessibilityHidden(true)

                    VStack(spacing: 8) {
                        Text(UsageProvider.cursor.signInLabel)
                            .font(.title2.bold())
                        Text("Codex Meter uses the same sign-in as the Cursor editor. Log in on cursor.com and approve the request; this screen finishes on its own. Your password is never visible to this app.")
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }

                    if cursor.isWaitingForBrowser {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Waiting for you to approve in the browser…")
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                        Button("Open Cursor sign-in again") {
                            browserURL = cursor.login?.loginURL
                            showingBrowser = browserURL != nil
                        }
                        .font(.footnote)
                    } else {
                        Button("Open Cursor sign-in") {
                            browserURL = model.cursor.beginSignIn()
                            showingBrowser = true
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    }
                }
                .padding(AppChrome.cardPadding)
                .frame(maxWidth: .infinity)
                .cardSurface()

                if let error = cursor.authenticationError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .bannerSurface(tint: .red)
                }
            }
            .frame(maxWidth: 520)
            .padding(28)
            .frame(maxWidth: .infinity)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Connect Cursor")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    model.cursor.cancelSignIn()
                    dismiss()
                }
            }
        }
        .sheet(isPresented: $showingBrowser) {
            if let browserURL {
                SafariView(url: browserURL)
                    .ignoresSafeArea()
            }
        }
    }
}
