import SwiftUI
import WidgetKit

#Preview("Small · Demo", as: .systemSmall) {
    CodexMeterHomeWidget()
} timeline: {
    MeterWidgetEntry(
        date: .now,
        snapshot: .preview,
        configuration: .preview
    )
}

#Preview("Medium · Weekly focus", as: .systemMedium) {
    CodexMeterHomeWidget()
} timeline: {
    MeterWidgetEntry(
        date: .now,
        snapshot: .stalePreview,
        configuration: MeterWidgetConfigurationIntent(
            allowance: .weekly,
            appearance: .dark,
            accent: .purple,
            surfaceOpacity: .strong,
            usageDisplay: .remaining,
            showsPercentSymbol: true,
            tapAction: .refresh
        )
    )
}

#Preview("Large · Five-hour focus", as: .systemLarge) {
    CodexMeterHomeWidget()
} timeline: {
    MeterWidgetEntry(
        date: .now,
        snapshot: .preview,
        configuration: MeterWidgetConfigurationIntent(
            allowance: .fiveHour,
            accent: .teal,
            surfaceOpacity: .solid
        )
    )
}

#Preview("Extra Large · Fresh", as: .systemExtraLarge) {
    CodexMeterHomeWidget()
} timeline: {
    MeterWidgetEntry(
        date: .now,
        snapshot: .preview,
        configuration: MeterWidgetConfigurationIntent(
            appearance: .light,
            accent: .orange,
            surfaceOpacity: .subtle,
            showsPercentSymbol: false
        )
    )
}

#Preview("Small · Signed out", as: .systemSmall) {
    CodexMeterHomeWidget()
} timeline: {
    MeterWidgetEntry(
        date: .now,
        snapshot: .signedOut,
        configuration: .preview
    )
}

#Preview("Small · Empty", as: .systemSmall) {
    CodexMeterHomeWidget()
} timeline: {
    MeterWidgetEntry(
        date: .now,
        snapshot: .empty,
        configuration: .preview
    )
}

#Preview("Five-hour Lock Screen", as: .accessoryCircular) {
    FiveHourAccessoryWidget()
} timeline: {
    ConfiguredAccessoryWidgetEntry(
        date: .now,
        snapshot: .preview,
        allowance: .fiveHour
    )
}

#Preview("Weekly Lock Screen", as: .accessoryCircular) {
    WeeklyAccessoryWidget()
} timeline: {
    ConfiguredAccessoryWidgetEntry(
        date: .now,
        snapshot: .stalePreview,
        allowance: .weekly
    )
}

#Preview("Both Circular", as: .accessoryCircular) {
    DualAllowanceAccessoryWidget()
} timeline: {
    ConfiguredAccessoryWidgetEntry(
        date: .now,
        snapshot: .preview,
        allowance: .both
    )
}

#Preview("Dual Lock Screen", as: .accessoryRectangular) {
    DualAllowanceAccessoryWidget()
} timeline: {
    ConfiguredAccessoryWidgetEntry(
        date: .now,
        snapshot: .preview,
        allowance: .both
    )
}

#Preview("Weekly Inline", as: .accessoryInline) {
    DualAllowanceAccessoryWidget()
} timeline: {
    ConfiguredAccessoryWidgetEntry(
        date: .now,
        snapshot: .preview,
        allowance: .weekly
    )
}
