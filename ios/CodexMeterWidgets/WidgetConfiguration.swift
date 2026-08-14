import AppIntents
import Foundation
import SwiftUI

enum WidgetAppearance: String, AppEnum, Sendable {
    case system
    case light
    case dark

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Appearance")
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .system: "System",
        .light: "Light",
        .dark: "Dark"
    ]
}

enum WidgetAccent: String, AppEnum, Sendable {
    case blue
    case indigo
    case purple
    case pink
    case red
    case orange
    case green
    case teal

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Accent")
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .blue: "Blue",
        .indigo: "Indigo",
        .purple: "Purple",
        .pink: "Pink",
        .red: "Red",
        .orange: "Orange",
        .green: "Green",
        .teal: "Teal"
    ]

    var color: Color {
        switch self {
        case .blue: .blue
        case .indigo: .indigo
        case .purple: .purple
        case .pink: .pink
        case .red: .red
        case .orange: .orange
        case .green: .green
        case .teal: .teal
        }
    }
}

enum WidgetSurfaceOpacity: String, AppEnum, Sendable {
    case subtle
    case soft
    case strong
    case solid

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Surface opacity")
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .subtle: "15%",
        .soft: "40%",
        .strong: "70%",
        .solid: "94%"
    ]

    var value: Double {
        switch self {
        case .subtle: 0.15
        case .soft: 0.40
        case .strong: 0.70
        case .solid: 0.94
        }
    }
}

enum WidgetUsageDisplay: String, AppEnum, Sendable {
    case used
    case remaining

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Usage display")
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .used: "Used",
        .remaining: "Remaining"
    ]
}

enum WidgetAllowance: String, AppEnum, Sendable {
    case both
    case fiveHour
    case weekly

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Allowance")
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .both: "Both windows",
        .fiveHour: "5-hour only",
        .weekly: "Weekly / Monthly only"
    ]

    var isSingleMetric: Bool {
        self != .both
    }
}

enum WidgetTapAction: String, AppEnum, Sendable {
    case open
    case refresh
    case reset

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Tap action")
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .open: "Open dashboard",
        .refresh: "Refresh usage",
        .reset: "Confirm reset"
    ]

    var url: URL {
        switch self {
        case .open:
            URL(string: "codexmeter://dashboard")!
        case .refresh:
            URL(string: "codexmeter://refresh")!
        case .reset:
            URL(string: "codexmeter://reset")!
        }
    }
}

struct MeterWidgetConfigurationIntent: WidgetConfigurationIntent, Sendable {
    static let title: LocalizedStringResource = "Configure Codex Meter"
    static let description = IntentDescription("Choose which allowance appears, how the widget looks, and what happens when you tap it.")

    @Parameter(title: "Allowance", default: .both)
    var allowance: WidgetAllowance

    @Parameter(title: "Appearance", default: .system)
    var appearance: WidgetAppearance

    @Parameter(title: "Accent", default: .blue)
    var accent: WidgetAccent

    @Parameter(title: "Surface opacity", default: .soft)
    var surfaceOpacity: WidgetSurfaceOpacity

    @Parameter(title: "Show", default: .used)
    var usageDisplay: WidgetUsageDisplay

    @Parameter(title: "Show percent symbol", default: true)
    var showsPercentSymbol: Bool

    @Parameter(title: "Tap action", default: .open)
    var tapAction: WidgetTapAction

    init() {}

    init(
        allowance: WidgetAllowance = .both,
        appearance: WidgetAppearance = .system,
        accent: WidgetAccent = .blue,
        surfaceOpacity: WidgetSurfaceOpacity = .soft,
        usageDisplay: WidgetUsageDisplay = .used,
        showsPercentSymbol: Bool = true,
        tapAction: WidgetTapAction = .open
    ) {
        self.allowance = allowance
        self.appearance = appearance
        self.accent = accent
        self.surfaceOpacity = surfaceOpacity
        self.usageDisplay = usageDisplay
        self.showsPercentSymbol = showsPercentSymbol
        self.tapAction = tapAction
    }
}

extension MeterWidgetConfigurationIntent {
    static let preview = MeterWidgetConfigurationIntent()
}

struct FiveHourAccessoryConfigurationIntent: WidgetConfigurationIntent, Sendable {
    static let title: LocalizedStringResource = "Configure five-hour allowance"
    static let description = IntentDescription("Choose which Codex allowance this circular widget shows.")

    @Parameter(title: "Allowance", default: .fiveHour)
    var allowance: WidgetAllowance

    init() {}

    init(allowance: WidgetAllowance = .fiveHour) {
        self.allowance = allowance
    }
}

struct WeeklyAccessoryConfigurationIntent: WidgetConfigurationIntent, Sendable {
    static let title: LocalizedStringResource = "Configure weekly allowance"
    static let description = IntentDescription("Choose which Codex allowance this circular widget shows.")

    @Parameter(title: "Allowance", default: .weekly)
    var allowance: WidgetAllowance

    init() {}

    init(allowance: WidgetAllowance = .weekly) {
        self.allowance = allowance
    }
}

struct DualAccessoryConfigurationIntent: WidgetConfigurationIntent, Sendable {
    static let title: LocalizedStringResource = "Configure Codex allowances"
    static let description = IntentDescription("Show both allowance windows or focus this accessory widget on one.")

    @Parameter(title: "Allowance", default: .both)
    var allowance: WidgetAllowance

    init() {}

    init(allowance: WidgetAllowance = .both) {
        self.allowance = allowance
    }
}
