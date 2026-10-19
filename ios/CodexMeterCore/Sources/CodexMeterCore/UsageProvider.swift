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

    /// Resolves a saved id, falling back to `defaultProvider` for unknown or missing values.
    public static func resolve(_ id: String?) -> UsageProvider {
        guard let id else { return defaultProvider }
        let normalized = id
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
        return UsageProvider(rawValue: normalized) ?? defaultProvider
    }
}
