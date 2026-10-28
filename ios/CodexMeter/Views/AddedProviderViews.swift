import CodexMeterCore
import SwiftUI
#if os(iOS)
import UIKit
#endif

// The tab, sign-in screen and Add account picker for providers added through Settings →
// Accounts (Copilot, Kimi, Z.ai and the rest). Codex, Claude Code and Cursor keep their own.

// MARK: Dashboard

/// One added provider's tab: its usage windows with the same meter cards as Codex.
struct AddedProviderDashboardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.drawsBarsInSimulatedRail) private var drawsBarsInSimulatedRail
    let provider: UsageProvider
    @State private var isShowingSignIn = false

    var body: some View {
        Group {
            if let state = model.addedProviders[provider] {
                content(state)
            } else {
                ContentUnavailableView(
                    "\(provider.displayName) isn't available on iPhone",
                    systemImage: "iphone.slash",
                    description: Text("Turn this tab off in Settings → Providers.")
                )
            }
        }
        .navigationTitle(provider.displayName)
    }

    private func content(_ state: ProviderUsageModel) -> some View {
        ScrollView {
            LazyVStack(spacing: AppChrome.sectionSpacing) {
                if state.phase == .signedOut {
                    AddedProviderSignedOutView(provider: provider) { isShowingSignIn = true }
                        .frame(maxWidth: AppChrome.signedOutMaxWidth)
                } else {
                    if state.phase == .demo {
                        StatusBanner(
                            message: "Demo data — no \(provider.vendorName) requests",
                            systemImage: "sparkles",
                            tint: .blue
                        )
                    }
                    if let error = state.visibleError {
                        StatusBanner(message: error, systemImage: "exclamationmark.triangle.fill", tint: .orange)
                    }
                    if let usage = state.usage {
                        AddedProviderStatusStrip(provider: provider, usage: usage, isCached: state.isUsingCachedData)
                        ProviderDashboardCards(provider: provider, usage: usage)
                        if let credits = usage.usageCredits, credits.shouldDisplay {
                            AddedProviderBalanceCard(credits: credits)
                        }
                    } else {
                        AddedProviderEmptyCard(state: state)
                    }
                    AddedProviderFootnote(provider: provider, inferred: state.entry.inferred)
                }
            }
            .frame(maxWidth: AppChrome.contentMaxWidth)
            .dashboardColumnsFromWidth()
            .padding(.horizontal, horizontalSizeClass == .regular ? 28 : 16)
            .padding(.vertical, 18)
        }
        .background(Color(.systemGroupedBackground))
        .toolbar {
            if !drawsBarsInSimulatedRail {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if state.phase != .signedOut {
                        NavigationLink {
                            DashboardEditView(provider: provider)
                        } label: {
                            Label("Edit dashboard", systemImage: "slider.horizontal.3")
                        }

                        Button {
                            Task { await state.refresh() }
                        } label: {
                            if state.isRefreshing {
                                ProgressView()
                            } else {
                                Label("Refresh", systemImage: "arrow.clockwise")
                            }
                        }
                        .disabled(state.isRefreshing)
                        .accessibilityLabel(state.isRefreshing
                            ? "Refreshing \(provider.displayName) usage" : "Refresh \(provider.displayName) usage")
                    }
                    Button {
                        model.isShowingSettings = true
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                }
            }
        }
        .refreshable {
            await state.refresh()
        }
        .task {
            await state.tabOpened()
        }
        .sheet(isPresented: $isShowingSignIn, onDismiss: {
            if state.phase == .signedOut { state.cancelSignIn() }
        }) {
            NavigationStack {
                AddedProviderSignInView(provider: provider)
            }
        }
        .onChange(of: state.phase) { _, phase in
            if phase != .signedOut { isShowingSignIn = false }
        }
    }
}

private struct AddedProviderStatusStrip: View {
    let provider: UsageProvider
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
                Label("A \(provider.displayName) limit is used up.", systemImage: "exclamationmark.circle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
    }
}

private struct AddedProviderBalanceCard: View {
    let credits: UsageCredits

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: "creditcard.fill")
                .font(.title)
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text("Balance")
                    .font(.headline.bold())
                Text(credits.unlimited ? "Unlimited" : (credits.balance ?? "Credits available"))
                    .font(.title3.bold())
            }
            Spacer(minLength: 0)
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
        .accessibilityElement(children: .combine)
    }
}

private struct AddedProviderEmptyCard: View {
    let state: ProviderUsageModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if state.isRefreshing {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Loading \(state.provider.displayName) usage…")
                }
                .font(.headline)
            } else {
                Label("No \(state.provider.displayName) usage loaded yet", systemImage: "rectangle.slash")
                    .font(.headline)
                Text("Pull to refresh or tap Refresh.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Refresh now") {
                    Task { await state.refresh() }
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(AppChrome.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }
}

private struct AddedProviderSignedOutView: View {
    let provider: UsageProvider
    let signIn: () -> Void

    var body: some View {
        let entry = ProviderCatalog.iOSEntry(for: provider)
        VStack(spacing: 22) {
            ProviderMark(provider: provider)
                .frame(width: 54, height: 54)
                .foregroundStyle(.tint)

            VStack(spacing: 8) {
                Text("Your \(provider.displayName) usage at a glance")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text("Connect your \(provider.accountName) account to see \(entry.meters.lowercased()) and when they reset.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            Button(provider.signInLabel, action: signIn)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: 420)

            Label("Signing in to \(provider.displayName) does not affect your other accounts.", systemImage: "person.2")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .cardSurface()
    }
}

private struct AddedProviderFootnote: View {
    let provider: UsageProvider
    let inferred: Bool

    var body: some View {
        Label {
            Text(inferred
                ? "\(provider.displayName) sign-in stays in Keychain, separate from your other accounts. Support for \(provider.displayName) is experimental: its usage route isn't documented and may change."
                : "\(provider.displayName) sign-in stays in Keychain, separate from your other accounts. Requests go directly to \(provider.vendorName), only when this tab opens with data older than five minutes or when you refresh.")
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

// MARK: Sign-in

/// The shared sign-in screen: a device code to approve on the provider's site, a token to
/// paste back, or an API key, depending on the provider. Port of Android's `SignInActivity`.
struct AddedProviderSignInView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let provider: UsageProvider

    @State private var pasted = ""
    @State private var apiKey = ""
    @State private var browserPage: BrowserPage?
    @State private var copied = false

    var body: some View {
        Group {
            if let state = model.addedProviders[provider] {
                form(state)
            } else {
                Text("\(provider.displayName) can't be connected on iPhone yet.")
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Connect \(provider.displayName)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    model.addedProviders[provider]?.cancelSignIn()
                    dismiss()
                }
            }
        }
        .sheet(item: $browserPage) { page in
            SafariView(url: page.url)
                .ignoresSafeArea()
        }
    }

    private func form(_ state: ProviderUsageModel) -> some View {
        let entry = state.entry
        return Form {
            Section {
                HStack(spacing: 14) {
                    ProviderMark(provider: provider)
                        .frame(width: 34, height: 34)
                        .foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(provider.signInLabel)
                            .font(.headline)
                        Text(entry.meters)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
                if entry.inferred {
                    Label {
                        Text("Experimental. \(provider.displayName)'s usage route isn't documented, so this may stop working or show incomplete data.")
                    } icon: {
                        Image(systemName: "flask")
                    }
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .accessibilityIdentifier("signin-experimental")
                }
            }

            if state.supportsDeviceSignIn {
                deviceSection(state)
            }
            if state.supportsPaste {
                pasteSection(state)
            }
            if entry.signIn == .apiKey || entry.acceptsAPIKey {
                apiKeySection(state, entry: entry)
            }

            if let error = state.signInError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }

            Section {
                Text("Your \(provider.accountName) password is only entered on \(provider.vendorName)'s own page. The sign-in is kept in Keychain on this device and used only to read usage.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func deviceSection(_ state: ProviderUsageModel) -> some View {
        Section {
            switch state.signInStep {
            case .idle:
                Button("Get sign-in code") { state.startDeviceSignIn() }
                    .accessibilityIdentifier("signin-device-start")
            case .starting:
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Asking \(provider.vendorName) for a code…")
                }
            case let .device(code):
                if !code.userCode.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Your code")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text(code.userCode)
                            .font(.system(.title, design: .monospaced).bold())
                            .textSelection(.enabled)
                            .accessibilityIdentifier("signin-user-code")
                    }
                    Button(copied ? "Copied" : "Copy code") {
                        UIPasteboard.general.string = code.userCode
                        copied = true
                    }
                }
                Button("Open \(provider.vendorName) sign-in") {
                    if let url = URL(string: code.openURL) {
                        if !code.userCode.isEmpty { UIPasteboard.general.string = code.userCode }
                        browserPage = BrowserPage(url: url)
                    }
                }
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Waiting for you to approve…")
                        .foregroundStyle(.secondary)
                }
                .font(.subheadline)
            case .checking:
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Checking…")
                }
            }
        } header: {
            Text(state.entry.signInSummary)
        } footer: {
            Text("Codex Meter shows a code; enter it on \(provider.vendorName)'s page and approve. This screen finishes on its own.")
        }
    }

    @ViewBuilder
    private func pasteSection(_ state: ProviderUsageModel) -> some View {
        Section {
            Button("Open \(provider.vendorName) sign-in") {
                browserPage = state.beginPasteSignIn().map(BrowserPage.init(url:))
            }
            TextField("Paste here", text: $pasted, axis: .vertical)
                .lineLimit(1 ... 4)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(.body, design: .monospaced))
                .accessibilityIdentifier("signin-paste")
            Button {
                state.submitPaste(pasted)
            } label: {
                if state.signInStep == .checking {
                    ProgressView()
                } else {
                    Text("Connect")
                }
            }
            .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.signInStep == .checking)
        } header: {
            Text(state.entry.signInSummary)
        } footer: {
            Text(state.pasteHint)
        }
    }

    @ViewBuilder
    private func apiKeySection(_ state: ProviderUsageModel, entry: ProviderCatalog.Entry) -> some View {
        Section {
            SecureField("API key", text: $apiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("signin-api-key")
            Button {
                state.submitAPIKey(apiKey)
            } label: {
                if state.signInStep == .checking {
                    ProgressView()
                } else {
                    Text("Connect with key")
                }
            }
            .disabled(apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state.signInStep == .checking)
            if let url = URL(string: entry.keyURL), !entry.keyURL.isEmpty {
                Button("Create a key on \(provider.vendorName)") { openURL(url) }
            }
        } header: {
            Text(entry.signIn == .apiKey ? "API key" : "Or use an API key")
        } footer: {
            Text("The key is checked once against \(provider.vendorName) and then kept in Keychain.")
        }
    }
}

/// A page to show in the in-app browser sheet.
private struct BrowserPage: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

// MARK: Add account

/// Settings → Accounts → Add account: a searchable list of every provider not yet connected.
/// Port of Android's `AddAccountActivity` / `ProviderPicker`.
struct AddAccountView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    /// Called with the chosen provider; the caller opens its sign-in.
    let choose: (UsageProvider) -> Void

    var body: some View {
        let providers = ProviderCatalog.pickerProviders(connected: model.connectedProviders, query: query)
        List {
            if providers.isEmpty {
                ContentUnavailableView.search(text: query)
            }
            ForEach(providers) { provider in
                Button {
                    choose(provider)
                } label: {
                    ProviderPickerRow(provider: provider)
                }
                .accessibilityIdentifier("add-account-\(provider.rawValue)")
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search providers")
        .navigationTitle("Add account")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }
}

/// One provider in a picker: mark, name, what it meters and how it signs in.
struct ProviderPickerRow: View {
    let provider: UsageProvider

    var body: some View {
        let entry = ProviderCatalog.iOSEntry(for: provider)
        HStack(spacing: 12) {
            ProviderMark(provider: provider)
                .frame(width: 24, height: 24)
                .foregroundStyle(Color.primary)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(provider.displayName)
                        .foregroundStyle(Color.primary)
                    if entry.inferred {
                        Text("Experimental")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .foregroundStyle(Color.orange)
                            .background(Color.orange.opacity(0.14), in: Capsule())
                    }
                }
                Text(entry.pickerSummary)
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
