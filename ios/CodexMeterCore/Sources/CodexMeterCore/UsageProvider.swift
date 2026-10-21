import Foundation

/// Coding-agent services whose subscription limits Codex Meter can show.
///
/// Codex stays the default and keeps every legacy storage key, so existing installs see no
/// change until they open another provider tab. Raw values are stable identifiers shared with
/// the Android app and stored in preferences; never rename an existing one.
public enum UsageProvider: String, CaseIterable, Codable, Sendable, Identifiable {
    case codex = "codex"
    case claudeCode = "claude_code"
    case cursor = "cursor"

    public static let defaultProvider: UsageProvider = .codex

    public var id: String { rawValue }

    /// Tab label and dashboard name.
    public var displayName: String {
        switch self {
        case .codex: "Codex"
        case .claudeCode: "Claude Code"
        case .cursor: "Cursor"
        }
    }

    /// The account the user signs in with, as in "Sign in with ChatGPT".
    public var accountName: String {
        switch self {
        case .codex: "ChatGPT"
        case .claudeCode: "Claude"
        case .cursor: "Cursor"
        }
    }

    /// The company that reports the usage data, used in empty-state copy.
    public var vendorName: String {
        switch self {
        case .codex: "OpenAI"
        case .claudeCode: "Anthropic"
        case .cursor: "Cursor"
        }
    }

    public var signInLabel: String {
        "Sign in with \(accountName)"
    }

    /// Providers whose tabs are on, in declaration order. Unknown ids are skipped. A missing or
    /// empty list means nothing was ever chosen, so it falls back to `migrationDefault(signedIn:)`.
    public static func enabled(
        from ids: [String]?,
        signedIn: Set<UsageProvider> = []
    ) -> [UsageProvider] {
        let normalized = Set((ids ?? []).map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased(with: Locale(identifier: "en_US_POSIX"))
        })
        let enabled = allCases.filter { normalized.contains($0.rawValue) }
        return enabled.isEmpty ? migrationDefault(signedIn: signedIn) : enabled
    }

    /// Tabs for an install that never chose providers (an upgrade from before provider tabs):
    /// Codex, plus any other provider the user already signed in to. With only Codex on, the
    /// tab bar stays hidden, so upgrading changes nothing on screen.
    public static func migrationDefault(signedIn: Set<UsageProvider>) -> [UsageProvider] {
        allCases.filter { $0 == defaultProvider || signedIn.contains($0) }
    }

    /// The providers picked during setup, in declaration order.
    public static func chosen(_ selection: Set<UsageProvider>) -> [UsageProvider] {
        allCases.filter { selection.contains($0) }
    }

    /// Every provider in the user's tab order: saved ids first (duplicates and unknown ids
    /// skipped), then any provider the saved order doesn't mention, in declaration order.
    public static func order(from ids: [String]?) -> [UsageProvider] {
        var order: [UsageProvider] = []
        for id in ids ?? [] {
            let normalized = id.trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased(with: Locale(identifier: "en_US_POSIX"))
            if let provider = UsageProvider(rawValue: normalized), !order.contains(provider) {
                order.append(provider)
            }
        }
        return order + allCases.filter { !order.contains($0) }
    }

    /// The providers in `enabled`, arranged in `order`.
    public static func inOrder(_ enabled: [UsageProvider], order: [UsageProvider]) -> [UsageProvider] {
        order.filter { enabled.contains($0) }
    }

    /// `enabled` with one provider switched on or off; the last one can't be turned off.
    public static func enabled(
        _ enabled: [UsageProvider],
        setting provider: UsageProvider,
        on: Bool
    ) -> [UsageProvider] {
        let result = allCases.filter { $0 == provider ? on : enabled.contains($0) }
        return result.isEmpty ? enabled : result
    }

    /// The selected tab if it is still on, otherwise the first provider that is.
    public static func visibleSelection(
        _ selected: UsageProvider,
        enabled: [UsageProvider]
    ) -> UsageProvider {
        guard let first = enabled.first, !enabled.contains(selected) else { return selected }
        return first
    }

    /// Resolves a saved id, falling back to `defaultProvider` for unknown or missing values.
    public static func resolve(_ id: String?) -> UsageProvider {
        guard let id else { return defaultProvider }
        let normalized = id
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
        return UsageProvider(rawValue: normalized) ?? defaultProvider
    }
}
