import Foundation

/// One "While using AI apps" entry as stored: an app name and the provider it shows.
public struct DesktopAppMapping: Codable, Equatable, Sendable {
    public var app: String
    public var providerID: String

    public init(app: String, providerID: String) {
        self.app = app
        self.providerID = providerID
    }
}

/// One row of the Mac "While using AI apps" list: a built-in app (possibly pointed at another
/// provider) or one the user added, and whether it's switched on.
public struct DesktopAppRule: Equatable, Identifiable, Sendable {
    public let app: String
    public let provider: UsageProvider
    public let isOn: Bool
    public let isBuiltIn: Bool
    /// A built-in app the user pointed at another provider; `originalProvider` is the built-in one.
    public let originalProvider: UsageProvider?

    public var id: String { key }
    public var key: String { DesktopAppList.key(app) }
}

/// The Mac twin of the Windows and Linux `AppMappings` and Android's AppSessionRules: which apps
/// point the menu bar at a provider while they're in front. Only the user's changes are stored,
/// so built-in apps added later still show up.
public struct DesktopAppList: Codable, Equatable, Sendable {
    /// Apps the user added, or built-in ones they pointed at another provider.
    public var added: [DesktopAppMapping] = []
    /// Apps switched off, by `key(_:)`.
    public var off: [String] = []
    /// Built-in apps the user removed, by `key(_:)`.
    public var removed: [String] = []

    public init() {}

    /// The built-in apps, by app name. Names match without case, spaces or separators.
    public static let defaults: [DesktopAppMapping] = [
        .init(app: "ChatGPT", providerID: UsageProvider.codex.rawValue),
        .init(app: "Codex", providerID: UsageProvider.codex.rawValue),
        .init(app: "Claude", providerID: UsageProvider.claudeCode.rawValue),
        .init(app: "Cursor", providerID: UsageProvider.cursor.rawValue),
        .init(app: "Windsurf", providerID: UsageProvider.windsurf.rawValue),
        .init(app: "Kiro", providerID: UsageProvider.kiro.rawValue),
        .init(app: "Antigravity", providerID: UsageProvider.antigravity.rawValue),
        .init(app: "Warp", providerID: UsageProvider.warp.rawValue),
        .init(app: "Grok", providerID: UsageProvider.grok.rawValue),
        .init(app: "Grok Bot", providerID: UsageProvider.grok.rawValue),
        .init(app: "Kimi", providerID: UsageProvider.kimi.rawValue),
    ]

    /// The name an app is matched and stored by: lowercase letters and digits, without ".app".
    public static func key(_ app: String) -> String {
        var name = app.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.lowercased().hasSuffix(".app") { name = String(name.dropLast(4)) }
        return String(name.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(Character.init))
    }

    private static let defaultKeys = Set(defaults.map { key($0.app) })

    public var isCustomized: Bool { !added.isEmpty || !off.isEmpty || !removed.isEmpty }

    /// Every row: the built-in apps the user kept (with their edits), then the apps they added.
    public var rules: [DesktopAppRule] {
        var edits: [String: DesktopAppMapping] = [:]
        var addedOrder: [String] = []
        for mapping in added {
            let key = Self.key(mapping.app)
            guard !key.isEmpty else { continue }
            if edits[key] == nil { addedOrder.append(key) }
            edits[key] = mapping
        }
        let offKeys = Set(off.map(Self.key))
        let removedKeys = Set(removed.map(Self.key))
        var rules: [DesktopAppRule] = []
        for builtIn in Self.defaults {
            let key = Self.key(builtIn.app)
            guard !removedKeys.contains(key),
                  let original = UsageProvider(rawValue: builtIn.providerID) else { continue }
            let edit = edits[key]
            guard let provider = UsageProvider(rawValue: edit?.providerID ?? builtIn.providerID) else { continue }
            rules.append(DesktopAppRule(
                app: edit?.app ?? builtIn.app,
                provider: provider,
                isOn: !offKeys.contains(key),
                isBuiltIn: true,
                originalProvider: provider == original ? nil : original
            ))
        }
        for key in addedOrder where !Self.defaultKeys.contains(key) {
            guard let mapping = edits[key], let provider = UsageProvider(rawValue: mapping.providerID) else { continue }
            rules.append(DesktopAppRule(
                app: mapping.app.trimmingCharacters(in: .whitespacesAndNewlines),
                provider: provider,
                isOn: !offKeys.contains(key),
                isBuiltIn: false,
                originalProvider: nil
            ))
        }
        return rules
    }

    /// Adds an app, or changes one: `replacing` is the row being edited when the user renamed it.
    /// The app is switched on, as on Android.
    public mutating func set(_ app: String, to provider: UsageProvider, replacing: String? = nil) {
        let name = app.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = Self.key(name)
        guard !key.isEmpty else { return }
        if let replacing, Self.key(replacing) != key { remove(replacing) }
        added.removeAll { Self.key($0.app) == key }
        let builtIn = Self.defaults.first { Self.key($0.app) == key }
        // Pointing a built-in app back at its own provider is the same as not editing it.
        if builtIn?.providerID != provider.rawValue {
            added.append(DesktopAppMapping(app: name, providerID: provider.rawValue))
        }
        off.removeAll { Self.key($0) == key }
        removed.removeAll { Self.key($0) == key }
    }

    /// Removes an added app, or hides a built-in one (Restore Defaults brings it back).
    public mutating func remove(_ app: String) {
        let key = Self.key(app)
        added.removeAll { Self.key($0.app) == key }
        off.removeAll { Self.key($0) == key }
        removed.removeAll { Self.key($0) == key }
        if Self.defaultKeys.contains(key) { removed.append(key) }
    }

    public mutating func setOn(_ app: String, _ isOn: Bool) {
        let key = Self.key(app)
        off.removeAll { Self.key($0) == key }
        if !isOn { off.append(key) }
    }

    public mutating func restoreDefaults() {
        self = DesktopAppList()
    }

    /// The stored form, for UserDefaults.
    public var json: String {
        guard let data = try? JSONEncoder().encode(self) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    /// The stored form back; an empty or damaged value is the built-in list.
    public init(json: String) {
        guard !json.isEmpty,
              let list = try? JSONDecoder().decode(DesktopAppList.self, from: Data(json.utf8)) else {
            self.init()
            return
        }
        self = list
    }

    public var matcher: DesktopAppMatcher { DesktopAppMatcher(rules: rules) }
}

/// Finds the provider of the app in front. A name matches a rule when, without case, spaces or
/// separators, it equals the rule's app or its leading words do ("Cursor Helper", "Grok Desktop");
/// it never matches a name that merely contains one, so "Claudette" stays unmatched. A switched-off
/// app matches nothing and stops the other names from matching.
public struct DesktopAppMatcher: Sendable {
    /// App key to provider; nil for a switched-off app.
    private let rules: [String: UsageProvider?]

    public init(rules: [DesktopAppRule]) {
        var map: [String: UsageProvider?] = [:]
        for rule in rules where !rule.key.isEmpty {
            map[rule.key] = rule.isOn ? rule.provider : nil
        }
        self.rules = map
    }

    /// `nil` when no rule names the app; `.some(nil)` when its rule is switched off.
    public func match(_ appName: String?) -> UsageProvider?? {
        guard var name = appName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
        if name.lowercased().hasSuffix(".app") { name = String(name.dropLast(4)) }
        let words = name.lowercased()
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
        guard !words.isEmpty else { return nil }
        for count in stride(from: words.count, through: 1, by: -1) {
            if let provider = rules[words.prefix(count).joined()] { return .some(provider) }
        }
        return nil
    }

    /// The provider of an app known by several names (its display name first), or nil.
    public func provider(names: [String?]) -> UsageProvider? {
        for name in names {
            if let decided = match(name) { return decided }
        }
        return nil
    }
}
