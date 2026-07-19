import SwiftUI
import WidgetKit

@main
struct CodexMeterWidgetsBundle: WidgetBundle {
    var body: some Widget {
        CodexMeterHomeWidget()
        FiveHourAccessoryWidget()
        WeeklyAccessoryWidget()
        DualAllowanceAccessoryWidget()
        UsageLiveActivityWidget()
    }
}

