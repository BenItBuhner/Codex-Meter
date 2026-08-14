import Foundation

/// Stable keys and merge rules for movable dashboard sections.
public enum DashboardSections {
    public static let fiveHour = "five_hour"
    public static let weekly = "weekly"
    public static let monthly = "monthly"
    public static let usageCredits = "usage_credits"
    public static let usageHistory = "usage_history"
    public static let resetCredits = "reset_credits"

    private static let limitPrefix = "limit:"

    /// A stable key for an additional limit, preferring the API id over display names.
    public static func limitKey(_ limit: UsageLimit) -> String {
        let identity = [limit.id, limit.name, limit.meteredFeature, "additional"]
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }!
        let normalized = identity
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
            .replacingOccurrences(of: ",", with: "_")
        return limitPrefix + normalized
    }

    public static func isLimitKey(_ key: String) -> Bool {
        key.hasPrefix(limitPrefix)
    }

    /// Default order: standard windows, monthly, detected additional limits, credits, history, resets.
    public static func defaultOrder(additionalLimits: [UsageLimit]) -> [String] {
        var result = [fiveHour, weekly, monthly]
        for limit in additionalLimits {
            let key = limitKey(limit)
            if !result.contains(key) {
                result.append(key)
            }
        }
        result.append(contentsOf: [usageCredits, usageHistory, resetCredits])
        return result
    }

    /// Applies a saved order to the currently available section keys.
    ///
    /// Unavailable saved keys are removed. Newly available keys are inserted after the nearest
    /// preceding key in the natural `available` order instead of being dumped at the end.
    public static func resolveOrder(saved: [String], available: [String]) -> [String] {
        let availableKeys = deduplicating(available)
        var result = deduplicating(saved).filter(availableKeys.contains)

        for (index, key) in availableKeys.enumerated() where !result.contains(key) {
            var insertionIndex = 0
            for precedingKey in availableKeys.prefix(index) {
                if let position = result.firstIndex(of: precedingKey) {
                    insertionIndex = max(insertionIndex, result.index(after: position))
                }
            }
            result.insert(key, at: insertionIndex)
        }
        return result
    }

    public static func resolveOrder(savedCSV: String, available: [String]) -> [String] {
        resolveOrder(saved: parseCSV(savedCSV), available: available)
    }

    public static func isHidden(_ hiddenCSV: String, key: String) -> Bool {
        parseCSV(hiddenCSV).contains(key)
    }

    /// Adds or removes a key from a comma-separated hidden-section setting.
    public static func settingHidden(_ hiddenCSV: String, key: String, hidden: Bool) -> String {
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        var keys = deduplicating(parseCSV(hiddenCSV))
        guard !key.isEmpty else {
            return serialize(keys)
        }
        if hidden {
            if !keys.contains(key) {
                keys.append(key)
            }
        } else {
            keys.removeAll { $0 == key }
        }
        return serialize(keys)
    }

    public static func parseCSV(_ csv: String) -> [String] {
        csv.split(separator: ",")
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
