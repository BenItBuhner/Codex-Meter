import AppKit
import CodexMeterCore
import Foundation
import Observation

/// Which window the menu bar follows. Mirrors Android's "Percentage shown" and the Windows tray.
enum MacLiveWindow: String, CaseIterable, Identifiable {
    case auto
    case fiveHour = "five_hour"
    case weekly

    var id: String { rawValue }

    var title: String {
        switch self {
        case .auto: "Auto"
        case .fiveHour: "5-hour usage"
        case .weekly: "Weekly usage"
        }
    }
}

/// What the menu bar item draws.
enum MacMenuBarStyle: String, CaseIterable, Identifiable {
    case ringAndText = "ring_text"
    case ring
    case text

    var id: String { rawValue }

    var title: String {
        switch self {
        case .ringAndText: "Ring and percentage"
        case .ring: "Ring only"
        case .text: "Percentage only"
        }
    }

    var showsRing: Bool { self != .text }
    var showsText: Bool { self != .ring }
}

/// One provider's live value for the menu bar. Like the Android live notification, a used-up
/// window shows how long until it resets instead of 0%.
struct MacLiveReading: Equatable {
    let provider: UsageProvider
    let meter: MacMeter
    let fetchedAt: Date

    var remainingPercent: Int { meter.remainingPercent }
    var isExhausted: Bool { meter.remainingPercent == 0 }
    var fraction: Double { Double(meter.remainingPercent) / 100 }

    func untilReset(now: Date) -> TimeInterval? {
        guard let reset = meter.window.effectiveResetDate(relativeTo: fetchedAt), reset > now else { return nil }
        return reset.timeIntervalSince(now)
    }

    /// "62%", or "2h 14m" until a used-up window resets.
    func compactText(now: Date) -> String {
        if isExhausted, let left = untilReset(now: now) {
            return MacLiveUsage.shortDuration(left)
        }
        return "\(meter.remainingPercent)%"
    }

    /// "Codex · 5-hour 62% left", or "Codex · Weekly resets in 2d 4h".
    func headline(now: Date) -> String {
        if isExhausted {
            if let left = untilReset(now: now) {
                return "\(provider.displayName) · \(meter.title) resets in \(MacLiveUsage.shortDuration(left))"
            }
            return "\(provider.displayName) · \(meter.title) used up"
        }
        return "\(provider.displayName) · \(meter.title) \(meter.remainingPercent)% left"
    }
}

/// The rules the menu bar shares with the Windows tray and the Android live notification: the AI
/// app in front wins, then the chosen provider, then the first in the sidebar; the window is the
/// tighter of 5-hour and weekly unless one is chosen.
enum MacLiveUsage {
    /// Mac apps that point the menu bar at a provider while they're in front, by app name.
    static let knownApps: [String: UsageProvider] = [
        "chatgpt": .codex,
        "codex": .codex,
        "claude": .claudeCode,
        "cursor": .cursor,
        "windsurf": .windsurf,
        "kiro": .kiro,
        "antigravity": .antigravity,
        "warp": .warp,
        "grok": .grok,
        "kimi": .kimi,
    ]

    static func provider(forAppNamed name: String?) -> UsageProvider? {
        guard let name = name?.trimmingCharacters(in: .whitespaces).lowercased(), !name.isEmpty else { return nil }
        return knownApps[name]
    }

    static func chooseProvider(
        chosen: UsageProvider?,
        frontApp: UsageProvider?,
        candidates: [UsageProvider]
    ) -> UsageProvider? {
        if let frontApp, candidates.contains(frontApp) { return frontApp }
        if let chosen, candidates.contains(chosen) { return chosen }
        return candidates.first
    }

    static func chooseMeter(_ snapshot: UsageSnapshot?, window choice: MacLiveWindow) -> MacMeter? {
        guard let snapshot else { return nil }
        let five = snapshot.fiveHour.map { MacMeter(id: "five-hour", title: "5-hour", window: $0) }
        let long = snapshot.weekly.map { MacMeter(id: "weekly", title: "Weekly", window: $0) }
            ?? snapshot.monthly.map { MacMeter(id: "monthly", title: "Monthly", window: $0) }
        switch choice {
        case .fiveHour:
            return five ?? long
        case .weekly:
            return long ?? five
        case .auto:
            guard let five else { return long }
            guard let long else { return five }
            return long.remainingPercent < five.remainingPercent ? long : five
        }
    }

    /// "2d 4h", "3h 12m" or "45m".
    static func shortDuration(_ interval: TimeInterval) -> String {
        let minutes = Int((max(0, interval) / 60).rounded(.up))
        let days = minutes / 1_440
        let hours = minutes % 1_440 / 60
        if days > 0 { return hours > 0 ? "\(days)d \(hours)h" : "\(days)d" }
        if hours > 0 { return minutes % 60 > 0 ? "\(hours)h \(minutes % 60)m" : "\(hours)h" }
        return "\(max(1, minutes))m"
    }
}

/// Notices when an AI app (ChatGPT, Codex, Claude, Cursor, ...) becomes the active app, the Mac
/// version of Android's "While using AI apps". Only app names are read.
@MainActor
@Observable
final class MacFrontmostApp {
    static let shared = MacFrontmostApp()

    /// The provider of the AI app in front, or nil when another app is.
    private(set) var provider: UsageProvider?

    @ObservationIgnored private var observer: NSObjectProtocol?

    private init() {
        let front = NSWorkspace.shared.frontmostApplication
        provider = Self.provider(bundleID: front?.bundleIdentifier, name: front?.localizedName)
        observer = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil,
            queue: .main
        ) { note in
            let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            let bundleID = app?.bundleIdentifier
            let name = app?.localizedName
            MainActor.assumeIsolated {
                MacFrontmostApp.shared.activated(bundleID: bundleID, name: name)
            }
        }
    }

    private func activated(bundleID: String?, name: String?) {
        // Opening our own menu bar panel or window keeps whatever was showing.
        guard name != nil, bundleID != Bundle.main.bundleIdentifier else { return }
        let next = Self.provider(bundleID: bundleID, name: name)
        if next != provider { provider = next }
    }

    private static func provider(bundleID: String?, name: String?) -> UsageProvider? {
        guard bundleID != Bundle.main.bundleIdentifier else { return nil }
        return MacLiveUsage.provider(forAppNamed: name)
    }
}

/// The menu bar ring as an image: drawn on demand at the screen's scale, so it stays sharp. A
/// healthy ring is a template image and takes the menu bar's own color; low and critical rings are
/// orange and red, the way the battery icon turns red.
enum MacMenuBarRing {
    static func image(fraction: Double?, level: MacMeterLevel?, exhausted: Bool, size: CGFloat = 16) -> NSImage {
        let image = NSImage(size: NSSize(width: size, height: size), flipped: false) { rect in
            let stroke = max(2.2, size * 0.16)
            let inset = stroke / 2 + 0.5
            let bounds = rect.insetBy(dx: inset, dy: inset)
            let colored = level != nil && level != .healthy
            let label = NSColor.labelColor
            let track = NSBezierPath(ovalIn: bounds)
            track.lineWidth = stroke
            (exhausted ? NSColor.systemRed.withAlphaComponent(0.45) : label.withAlphaComponent(colored ? 0.25 : 0.3)).setStroke()
            track.stroke()
            if let fraction, fraction > 0 {
                let arc = NSBezierPath()
                let center = NSPoint(x: bounds.midX, y: bounds.midY)
                let sweep = max(8, min(360, fraction * 360))
                arc.appendArc(withCenter: center, radius: bounds.width / 2, startAngle: 90, endAngle: 90 - sweep, clockwise: true)
                arc.lineWidth = stroke
                arc.lineCapStyle = .round
                let color: NSColor = switch level ?? .healthy {
                case .critical: .systemRed
                case .low: .systemOrange
                default: label
                }
                color.setStroke()
                arc.stroke()
            }
            return true
        }
        image.isTemplate = !(level == .low || level == .critical || exhausted)
        return image
    }
}
