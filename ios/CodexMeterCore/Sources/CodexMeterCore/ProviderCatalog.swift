import Foundation

/// What the Add account picker and the sign-in screen need to know about each provider: how it
/// signs in, what it meters, and whether its usage route is confirmed. Mirrors Android's
/// `ProviderCatalog`, with two iOS differences noted on `iOSSignIn`.
public enum ProviderCatalog {
    /// How a provider's account gets connected on a phone.
    public enum SignIn: Sendable, Equatable {
        /// Codex, Claude Code and Cursor: their own sign-in screens.
        case browser
        /// Browser sign-in that returns to a local port (Android only).
        case loopback
        /// The app shows a code, the user approves it on the provider's site, the app polls.
        case deviceCode
        /// The provider's site shows a token or code that the user pastes back.
        case codePaste
        /// The user creates a key on the provider's site and pastes it.
        case apiKey
    }

    public struct Entry: Sendable, Equatable {
        public let provider: UsageProvider
        public let signIn: SignIn
        /// What the dashboard shows, e.g. "5-hour and weekly limits".
        public let meters: String
        /// How sign-in works, e.g. "GitHub sign-in".
        public let signInSummary: String
        /// Where the user creates a key; also offered as an alternative for device flows.
        public let keyURL: String
        /// Whether the provider also accepts a pasted API key on the sign-in screen.
        public let acceptsAPIKey: Bool
        /// The sign-in flow is known, but it isn't yet confirmed that its token is accepted by
        /// the usage route. The picker and sign-in screen say so.
        public let inferred: Bool

        /// One line for picker rows: "5-hour and weekly limits · GitHub sign-in".
        public var pickerSummary: String {
            "\(meters) · \(signInSummary)"
        }

        /// Whether this provider connects through the shared sign-in screen.
        public var usesSignInScreen: Bool {
            signIn != .browser
        }
    }

    /// Recommended order: the picker lists providers this way.
    private static let entries: [Entry] = [
        Entry(provider: .codex, signIn: .browser, meters: "5-hour and weekly limits, reset credits",
              signInSummary: "ChatGPT sign-in", keyURL: "", acceptsAPIKey: false, inferred: false),
        Entry(provider: .claudeCode, signIn: .browser, meters: "5-hour and weekly limits",
              signInSummary: "Claude sign-in", keyURL: "", acceptsAPIKey: false, inferred: false),
        Entry(provider: .cursor, signIn: .browser, meters: "Included usage and on-demand spend",
              signInSummary: "Cursor sign-in", keyURL: "", acceptsAPIKey: false, inferred: false),
        Entry(provider: .copilot, signIn: .deviceCode, meters: "Monthly premium requests and chat",
              signInSummary: "GitHub sign-in", keyURL: "", acceptsAPIKey: false, inferred: false),
        Entry(provider: .kimi, signIn: .deviceCode, meters: "5-hour and weekly limits",
              signInSummary: "Kimi sign-in", keyURL: "https://www.kimi.com/code/console", acceptsAPIKey: true, inferred: false),
        Entry(provider: .factory, signIn: .deviceCode, meters: "5-hour, weekly and monthly limits",
              signInSummary: "Factory sign-in", keyURL: "", acceptsAPIKey: false, inferred: false),
        Entry(provider: .grok, signIn: .deviceCode, meters: "SuperGrok credits for the billing period",
              signInSummary: "xAI sign-in", keyURL: "", acceptsAPIKey: false, inferred: true),
        Entry(provider: .antigravity, signIn: .loopback, meters: "Gemini and Claude model limits, 5-hour and weekly",
              signInSummary: "Google sign-in", keyURL: "", acceptsAPIKey: false, inferred: false),
        Entry(provider: .devin, signIn: .codePaste, meters: "Daily and weekly quota",
              signInSummary: "Windsurf token", keyURL: "", acceptsAPIKey: false, inferred: true),
        Entry(provider: .windsurf, signIn: .codePaste, meters: "Daily and weekly quota",
              signInSummary: "Windsurf token", keyURL: "", acceptsAPIKey: false, inferred: true),
        Entry(provider: .augment, signIn: .codePaste, meters: "Monthly credits",
              signInSummary: "Augment sign-in", keyURL: "", acceptsAPIKey: false, inferred: false),
        Entry(provider: .cline, signIn: .deviceCode, meters: "5-hour, weekly and monthly limits",
              signInSummary: "Cline sign-in", keyURL: "", acceptsAPIKey: false, inferred: true),
        Entry(provider: .opencodeGo, signIn: .deviceCode, meters: "5-hour, weekly and monthly limits",
              signInSummary: "OpenCode sign-in", keyURL: "https://opencode.ai/auth", acceptsAPIKey: true, inferred: true),
        Entry(provider: .minimax, signIn: .deviceCode, meters: "Coding plan prompts per window",
              signInSummary: "MiniMax sign-in",
              keyURL: "https://platform.minimax.io/user-center/basic-information/interface-key",
              acceptsAPIKey: true, inferred: true),
        Entry(provider: .zai, signIn: .apiKey, meters: "5-hour and weekly limits",
              signInSummary: "API key", keyURL: "https://z.ai/manage-apikey/apikey-list", acceptsAPIKey: true, inferred: false),
        Entry(provider: .kiro, signIn: .deviceCode, meters: "Monthly credits",
              signInSummary: "AWS Builder ID", keyURL: "", acceptsAPIKey: false, inferred: true),
        Entry(provider: .amp, signIn: .apiKey, meters: "Free daily usage and credits",
              signInSummary: "Access token", keyURL: "https://ampcode.com/settings", acceptsAPIKey: true, inferred: true),
        Entry(provider: .openrouter, signIn: .loopback, meters: "Key limit and credit balance",
              signInSummary: "OpenRouter sign-in", keyURL: "https://openrouter.ai/settings/keys", acceptsAPIKey: true, inferred: false),
        Entry(provider: .kilo, signIn: .deviceCode, meters: "Credit balance",
              signInSummary: "Kilo sign-in", keyURL: "https://app.kilo.ai/profile", acceptsAPIKey: true, inferred: false),
        Entry(provider: .codebuff, signIn: .deviceCode, meters: "Subscription credits",
              signInSummary: "Codebuff sign-in", keyURL: "", acceptsAPIKey: false, inferred: true),
        Entry(provider: .synthetic, signIn: .apiKey, meters: "5-hour and weekly limits",
              signInSummary: "API key", keyURL: "https://synthetic.new/billing", acceptsAPIKey: true, inferred: false),
        Entry(provider: .chutes, signIn: .apiKey, meters: "Subscription quota",
              signInSummary: "API key", keyURL: "https://chutes.ai/app/api", acceptsAPIKey: true, inferred: true),
        Entry(provider: .v0, signIn: .apiKey, meters: "Rate limits",
              signInSummary: "API key", keyURL: "https://v0.app/chat/settings/keys", acceptsAPIKey: true, inferred: true),
        Entry(provider: .warp, signIn: .apiKey, meters: "AI request limit",
              signInSummary: "API key", keyURL: "https://app.warp.dev/settings/api-keys", acceptsAPIKey: true, inferred: true),
    ]

    public static func entry(for provider: UsageProvider) -> Entry {
        entries.first { $0.provider == provider }
            ?? Entry(provider: provider, signIn: .browser, meters: "Usage limits",
                     signInSummary: "\(provider.accountName) sign-in", keyURL: "",
                     acceptsAPIKey: false, inferred: false)
    }

    /// The catalog entry as the iPhone app uses it. iOS can't keep a local callback server
    /// alive while the browser is open, so OpenRouter signs in with a pasted key instead, and
    /// Antigravity (whose Google sign-in only accepts a local callback) isn't offered.
    public static func iOSEntry(for provider: UsageProvider) -> Entry {
        let base = entry(for: provider)
        guard base.signIn == .loopback, base.acceptsAPIKey else { return base }
        return Entry(provider: base.provider, signIn: .apiKey, meters: base.meters,
                     signInSummary: "API key", keyURL: base.keyURL, acceptsAPIKey: true,
                     inferred: base.inferred)
    }

    /// Whether the iPhone app can connect `provider`.
    public static func isAvailableOnIOS(_ provider: UsageProvider) -> Bool {
        iOSEntry(for: provider).signIn != .loopback
    }

    /// Every provider in recommended order.
    public static var recommendedOrder: [UsageProvider] {
        let listed = entries.map(\.provider)
        return listed + UsageProvider.allCases.filter { !listed.contains($0) }
    }

    /// Providers the Add account picker offers on iOS: everything not already connected, in
    /// recommended order, narrowed by `query` (matched against name, company and sign-in).
    public static func pickerProviders(connected: some Collection<UsageProvider>, query: String) -> [UsageProvider] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return recommendedOrder.filter { provider in
            isAvailableOnIOS(provider)
                && !connected.contains(provider)
                && (needle.isEmpty || matches(provider, needle))
        }
    }

    static func matches(_ provider: UsageProvider, _ needle: String) -> Bool {
        let entry = iOSEntry(for: provider)
        let haystack = [provider.displayName, provider.accountName, provider.vendorName, entry.meters, entry.signInSummary]
            .joined(separator: " ")
            .lowercased()
        return haystack.contains(needle)
    }
}

public extension UsageProvider {
    /// Codex, Claude Code and Cursor have their own sign-in screens and dashboards; the rest
    /// share one generic sign-in screen and dashboard.
    var isBuiltIn: Bool {
        self == .codex || self == .claudeCode || self == .cursor
    }

    /// Codex, Claude Code and Cursor, in tab order.
    static let builtIns: [UsageProvider] = [.codex, .claudeCode, .cursor]
}
