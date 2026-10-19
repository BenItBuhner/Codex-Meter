import Foundation

/// Stable keys for optional Usage history highlights: the chart guide, the previous-window
/// list, each insight row, and dollar value estimates. Visibility is stored as a
/// comma-separated list of keys the user toggled away from their default, so changing a
/// default later never overrides an explicit user choice. The chart itself and the
/// clear-history action are always shown and have no key here.
public enum HistorySections {
    public static let guide = "guide"
    public static let windowList = "window_list"
    public static let insightPace = "insight_pace"
    public static let insightExhaustion = "insight_exhaustion"
    public static let insightAverage = "insight_average"
    public static let insightPeak = "insight_peak"
    public static let valueEstimates = "value_estimates"

    /// Every customizable highlight, in the order the customize sheet lists them.
    public static let all = [
        guide,
        windowList,
        insightPace,
        insightExhaustion,
        insightAverage,
        insightPeak,
        valueEstimates
    ]

    /// The guide is opt-in extra reading; every other highlight starts visible.
    public static func defaultVisible(_ key: String) -> Bool {
        key != guide
    }

    /// User-facing label for a highlight key.
    public static func label(_ key: String) -> String {
        switch key {
        case guide: "How to read the charts"
        case windowList: "Previous window list"
        case insightPace: "Pace vs. typical"
        case insightExhaustion: "Projected exhaustion"
        case insightAverage: "Average completed window"
        case insightPeak: "Peak burn rate"
        case valueEstimates: "Value estimates ($)"
        default: key
        }
    }

    /// Whether a highlight is visible given the saved override CSV.
    public static func isVisible(_ overridesCSV: String?, key: String) -> Bool {
        defaultVisible(key) != parseCSV(overridesCSV).contains(key)
    }

    /// Returns the override CSV updated so the key resolves to the requested visibility.
    public static func setVisible(_ overridesCSV: String?, key: String, visible: Bool) -> String {
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        var keys = deduplicating(parseCSV(overridesCSV))
        guard !key.isEmpty else {
            return serialize(keys)
        }
        if visible == defaultVisible(key) {
            keys.removeAll { $0 == key }
        } else if !keys.contains(key) {
            keys.append(key)
        }
        return serialize(keys)
    }

    public static func parseCSV(_ csv: String?) -> [String] {
        guard let csv else { return [] }
        return csv.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    public static func serialize(_ keys: [String]) -> String {
        keys
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: ",")
    }

    private static func deduplicating(_ keys: [String]) -> [String] {
        var seen = Set<String>()
        return keys.filter { seen.insert($0).inserted }
    }
}
