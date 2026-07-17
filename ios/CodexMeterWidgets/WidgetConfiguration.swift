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
    static let description = IntentDescription("Choose how the widget looks and what happens when you tap it.")

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
        appearance: WidgetAppearance = .system,
        accent: WidgetAccent = .blue,
        surfaceOpacity: WidgetSurfaceOpacity = .soft,
        usageDisplay: WidgetUsageDisplay = .used,
        showsPercentSymbol: Bool = true,
        tapAction: WidgetTapAction = .open
    ) {
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

