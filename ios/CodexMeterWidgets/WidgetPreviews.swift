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

#Preview("Medium · Stale", as: .systemMedium) {
    CodexMeterHomeWidget()
} timeline: {
    MeterWidgetEntry(
        date: .now,
        snapshot: .stalePreview,
        configuration: MeterWidgetConfigurationIntent(
            appearance: .dark,
            accent: .purple,
            surfaceOpacity: .strong,
            usageDisplay: .remaining,
            showsPercentSymbol: true,
            tapAction: .refresh
        )
    )
}

#Preview("Large · Fresh", as: .systemLarge) {
    CodexMeterHomeWidget()
} timeline: {
    MeterWidgetEntry(
        date: .now,
        snapshot: .preview,
        configuration: MeterWidgetConfigurationIntent(
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
    AccessoryWidgetEntry(date: .now, snapshot: .preview)
}

#Preview("Weekly Lock Screen", as: .accessoryCircular) {
    WeeklyAccessoryWidget()
} timeline: {
    AccessoryWidgetEntry(date: .now, snapshot: .stalePreview)
}

#Preview("Dual Lock Screen", as: .accessoryRectangular) {
    DualAllowanceAccessoryWidget()
} timeline: {
    AccessoryWidgetEntry(date: .now, snapshot: .preview)
}

#Preview("Dual Inline", as: .accessoryInline) {
    DualAllowanceAccessoryWidget()
} timeline: {
    AccessoryWidgetEntry(date: .now, snapshot: .preview)
}

