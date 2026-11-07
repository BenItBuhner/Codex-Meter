import Foundation

public enum UsageParser {
    private static let fiveHours: Int64 = 18_000
    private static let week: Int64 = 604_800
    private static let month: Int64 = 2_592_000
    /// Free-tier accounts report a single ~30-day Codex window; accept 10–45 days so calendar
    /// months and drifting billing periods still classify while staying clear of the weekly
    /// window's 9-day ceiling.
    private static let monthRange: ClosedRange<Int64> = 864_000 ... 3_888_000

    public static func parse(_ string: String, fetchedAt: Date = Date()) throws -> UsageSnapshot {
        try parse(Data(string.utf8), fetchedAt: fetchedAt)
    }

    public static func parse(_ data: Data, fetchedAt: Date = Date()) throws -> UsageSnapshot {
        let root = try JSONSupport.object(from: data)
        let planType = JSONSupport.string(root["plan_type"])

        var nextID = 0
        func candidate(from object: JSONSupport.Object?) -> Candidate? {
            guard let window = parseWindow(object) else {
                return nil
            }
            defer { nextID += 1 }
            return Candidate(id: nextID, window: window)
        }

        let rateLimit = JSONSupport.object(root["rate_limit"])
        let allowed: Bool
        let limitReached: Bool
        let primary: Candidate?
        let secondary: Candidate?
        var primaryCandidates: [Candidate] = []

        if let rateLimit {
            allowed = JSONSupport.bool(rateLimit["allowed"], default: true)
            limitReached = JSONSupport.bool(rateLimit["limit_reached"], default: false)
            primary = candidate(from: JSONSupport.object(rateLimit["primary_window"]))
            secondary = candidate(from: JSONSupport.object(rateLimit["secondary_window"]))
            if let primary { primaryCandidates.append(primary) }
            if let secondary { primaryCandidates.append(secondary) }
        } else {
            allowed = true
            limitReached = false
            primary = nil
            secondary = nil
        }

        var additionalLimits: [UsageLimit] = []
        if let rawAdditionalLimits = JSONSupport.array(root["additional_rate_limits"]) {
            for (index, item) in rawAdditionalLimits.enumerated() {
                guard let item = JSONSupport.object(item) else {
                    continue
                }
                let nested = JSONSupport.object(item["rate_limit"]) ?? item
                let additionalPrimary = parseWindow(JSONSupport.object(nested["primary_window"]))
                let additionalSecondary = parseWindow(JSONSupport.object(nested["secondary_window"]))
                if additionalPrimary != nil || additionalSecondary != nil {
                    let name = JSONSupport.string(item["limit_name"])
                    let feature = JSONSupport.string(item["metered_feature"])
                    let identity = [
                        JSONSupport.string(item["limit_id"]),
                        name,
                        feature,
                        "additional"
                    ].first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }!
                    additionalLimits.append(
                        UsageLimit(
                            id: "\(identity)-\(index)",
                            name: name,
                            meteredFeature: feature,
                            allowed: JSONSupport.bool(nested["allowed"], default: true),
                            limitReached: JSONSupport.bool(nested["limit_reached"], default: false),
                            primary: additionalPrimary,
                            secondary: additionalSecondary
                        )
                    )
                }
            }
        }

        var fiveHour = nearest(
            in: primaryCandidates,
            target: fiveHours,
            range: 10_800 ... 28_800,
            excluding: []
        )
        var weekly = nearest(
            in: primaryCandidates,
            target: week,
            range: 432_000 ... 777_600,
            excluding: fiveHour.map { [$0.id] } ?? []
        )
        var monthly = nearest(
            in: primaryCandidates,
            target: month,
            range: monthRange,
            excluding: [fiveHour?.id, weekly?.id].compactMap { $0 }
        )

        // Go and similar plans sometimes report a single window just outside the
        // standard 5-hour / weekly / 10–45-day buckets. Keep that window visible.
        if fiveHour == nil, weekly == nil, monthly == nil,
           let leftover = longest(in: primaryCandidates, excluding: []) {
            if leftover.window.windowSeconds >= monthRange.lowerBound {
                monthly = leftover
            } else if leftover.window.windowSeconds >= 86_400 {
                weekly = leftover
            } else {
                fiveHour = leftover
            }
        }

        let resetCredits = JSONSupport.object(root["rate_limit_reset_credits"])
        let rawAvailableCount = resetCredits.map {
            JSONSupport.int($0["available_count"], default: -1)
        } ?? -1
        let usageCredits: UsageCredits? = JSONSupport.object(root["credits"]).flatMap { credits in
            guard credits.keys.contains("has_credits")
                    || credits.keys.contains("unlimited")
                    || credits.keys.contains("balance") else {
                return nil
            }
            return UsageCredits(
                hasCredits: JSONSupport.bool(credits["has_credits"], default: false),
                unlimited: JSONSupport.bool(credits["unlimited"], default: false),
                balance: {
                    guard credits.keys.contains("balance"),
                          !(credits["balance"] is NSNull) else {
                        return nil
                    }
                    return JSONSupport.string(credits["balance"])
                }()
            )
        }
        let spendControl = JSONSupport.object(root["spend_control"]).map { control in
            SpendControl(
                reached: JSONSupport.bool(control["reached"], default: false),
                individualLimit: parseSpendControlLimit(JSONSupport.object(control["individual_limit"]))
            )
        }

        return UsageSnapshot(
            planType: planType,
            allowed: allowed,
            limitReached: limitReached,
            fiveHour: fiveHour?.window,
            weekly: weekly?.window,
            monthly: monthly?.window,
            resetCreditsAvailable: rawAvailableCount >= 0 ? rawAvailableCount : nil,
            additionalLimits: additionalLimits,
            usageCredits: usageCredits,
            spendControl: spendControl,
            fetchedAt: fetchedAt
        )
    }

    private struct Candidate {
        let id: Int
        let window: UsageWindow
    }

    private static func parseWindow(_ object: JSONSupport.Object?) -> UsageWindow? {
        guard let object,
              !(object["used_percent"] is NSNull),
              !(object["limit_window_seconds"] is NSNull),
              object.keys.contains("used_percent"),
              object.keys.contains("limit_window_seconds"),
              let used = JSONSupport.double(object["used_percent"]),
              used.isFinite
        else {
            return nil
        }

        let duration = JSONSupport.int64(object["limit_window_seconds"], default: -1)
        guard duration > 0 else {
            return nil
        }

        let resetAfter = JSONSupport.int64(object["reset_after_seconds"], default: 0)
        let resetAtSeconds = JSONSupport.int64(object["reset_at"], default: 0)

        return UsageWindow(
            usedPercent: roundHalfUp(used),
            windowSeconds: duration,
            resetAfterSeconds: resetAfter,
            resetAt: resetAtSeconds > 0
                ? Date(timeIntervalSince1970: TimeInterval(resetAtSeconds))
                : nil
        )
    }

    /// `spend_control.individual_limit`. Amounts arrive as strings (`"25000"`) and percentages as
    /// numbers, but the reverse also appears, so every field goes through the tolerant helpers.
    /// Returns `nil` when nothing usable is present so the card stays hidden.
    private static func parseSpendControlLimit(_ object: JSONSupport.Object?) -> SpendControlLimit? {
        guard let object else {
            return nil
        }
        let limit = amount(object["limit"])
        let used = amount(object["used"])
        let remaining = amount(object["remaining"])

        let usedPercent: Int
        if let percent = JSONSupport.double(object["used_percent"]), percent.isFinite {
            usedPercent = roundHalfUp(percent)
        } else if let percent = JSONSupport.double(object["remaining_percent"]), percent.isFinite {
            usedPercent = 100 - roundHalfUp(percent)
        } else if let limit, let used,
                  let limitValue = Double(limit), let usedValue = Double(used),
                  limitValue > 0, usedValue.isFinite {
            usedPercent = roundHalfUp(usedValue / limitValue * 100)
        } else {
            return nil
        }

        let resetAfter = JSONSupport.int64(object["reset_after_seconds"], default: 0)
        let resetAtSeconds = JSONSupport.int64(object["reset_at"], default: 0)
        return SpendControlLimit(
            source: JSONSupport.string(object["source"]),
            limit: limit,
            used: used,
            remaining: remaining,
            usedPercent: usedPercent,
            resetAfterSeconds: resetAfter,
            resetAt: resetAtSeconds > 0
                ? Date(timeIntervalSince1970: TimeInterval(resetAtSeconds))
                : nil
        )
    }

    /// A credit amount as text, accepting JSON strings and numbers but not booleans or null.
    private static func amount(_ value: Any?) -> String? {
        if let text = value as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        guard JSONSupport.double(value) != nil else {
            return nil
        }
        return JSONSupport.string(value)
    }

    private static func roundHalfUp(_ value: Double) -> Int {
        let rounded = floor(value + 0.5)
        if rounded >= Double(Int.max) {
            return Int.max
        }
        if rounded <= Double(Int.min) {
            return Int.min
        }
        return Int(rounded)
    }

    private static func nearest(
        in candidates: [Candidate],
        target: Int64,
        range: ClosedRange<Int64>,
        excluding excludedIDs: [Int]
    ) -> Candidate? {
        var best: Candidate?
        var bestDistance = Int64.max

        for candidate in candidates
        where !excludedIDs.contains(candidate.id) && range.contains(candidate.window.windowSeconds) {
            let distance = absoluteDifference(candidate.window.windowSeconds, target)
            if distance < bestDistance {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private static func longest(
        in candidates: [Candidate],
        excluding excludedIDs: [Int]
    ) -> Candidate? {
        candidates
            .filter { !excludedIDs.contains($0.id) }
            .max { $0.window.windowSeconds < $1.window.windowSeconds }
    }

    private static func absoluteDifference(_ lhs: Int64, _ rhs: Int64) -> Int64 {
        lhs >= rhs ? lhs - rhs : rhs - lhs
    }
}
