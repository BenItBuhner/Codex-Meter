import Foundation

/// The full-screen "What's new" page shown once after a big update (3.0 first). The same rule and
/// release as Android (`WhatsNew.java`) and the desktop apps (`WhatsNew.cs`); each platform lists
/// its own new features.
public enum WhatsNew {
    /// The release the page is about. Seen once per value; a smaller update keeps it.
    public static let release = "3.0"
    public static let title = "What's new in Codex Meter 3.0"
    public static let button = "Continue"

    public enum Platform: Sendable {
        case iOS
        case macOS
    }

    /// One feature: an SF Symbol, a title and one line.
    public struct Feature: Equatable, Hashable, Identifiable, Sendable {
        public let id: String
        public let symbol: String
        public let title: String
        public let body: String
    }

    /// Whether to show the page on this launch: once per release, and only to someone who used the
    /// app before it. A new install finishes setup and is marked seen instead.
    public static func shouldShow(seen: String?, setUp: Bool) -> Bool {
        setUp && seen != release
    }

    /// Every brand-new 3.0 feature on `platform`, most important first.
    public static func features(for platform: Platform) -> [Feature] {
        var list = [
            Feature(id: "providers", symbol: "square.grid.2x2", title: "Every AI service in one place",
                    body: "Claude Code, Cursor, Copilot, Grok, OpenRouter and more join Codex, each with its own tab."),
            Feature(id: "priority", symbol: "flag", title: "Priority",
                    body: "One tab for what needs you now: limits to watch, used up, coming up, outages and new models."),
            Feature(id: "resets", symbol: "arrow.counterclockwise", title: "Saved resets",
                    body: "Claude Code resets and Codex reset credits, used for you before they expire or when you run low."),
        ]
        switch platform {
        case .iOS:
            list.append(Feature(id: "live", symbol: "bolt", title: "Live Activity and Dynamic Island",
                                body: "Your tightest limit on the Lock Screen and in the Dynamic Island."))
        case .macOS:
            list.append(Feature(id: "live", symbol: "menubar.rectangle", title: "Live usage in the menu bar",
                                body: "The menu bar shows the AI app in front first, from a list you choose."))
        }
        list += [
            Feature(id: "value", symbol: "dollarsign.circle", title: "Estimated value",
                    body: "What your plan buys at API prices, from dated, sourced figures."),
            Feature(id: "status", symbol: "waveform.path.ecg", title: "Outages and slowdowns",
                    body: "Official incidents and slow models, with a link to each status page."),
        ]
        switch platform {
        case .iOS:
            list += [
                Feature(id: "ipad", symbol: "sidebar.left", title: "Made for iPad",
                        body: "A sidebar of every service, dashboards in up to three columns and keyboard shortcuts."),
                Feature(id: "watch", symbol: "applewatch", title: "Apple Watch",
                        body: "Every provider on your wrist, with complications for your watch face."),
            ]
        case .macOS:
            list += [
                Feature(id: "updates", symbol: "arrow.down.circle", title: "Updates in the app",
                        body: "Settings › Updates finds, checks and installs new versions for you."),
                Feature(id: "signin", symbol: "safari", title: "Browser sign-in",
                        body: "Sign in to OpenRouter and Antigravity in your browser."),
            ]
        }
        list += [
            Feature(id: "settings", symbol: "gearshape", title: "Simpler settings",
                    body: "The same groups on every device, with settings export and import."),
            Feature(id: "desktop", symbol: "desktopcomputer", title: "On every device",
                    body: "Codex Meter also runs on Android, Windows and Linux, with the same providers."),
        ]
        return list
    }
}
