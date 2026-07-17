import Foundation

public enum UsageParser {
    private static let fiveHours: Int64 = 18_000
    private static let week: Int64 = 604_800

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

        var additionalCandidates: [Candidate] = []
        if let additionalLimits = JSONSupport.array(root["additional_rate_limits"]) {
            for item in additionalLimits {
                guard let item = JSONSupport.object(item) else {
                    continue
                }
                let nested = JSONSupport.object(item["rate_limit"]) ?? item
                if let window = candidate(from: JSONSupport.object(nested["primary_window"])) {
                    additionalCandidates.append(window)
                }
                if let window = candidate(from: JSONSupport.object(nested["secondary_window"])) {
                    additionalCandidates.append(window)
                }
            }
        }

        var fiveHour = nearest(
            in: primaryCandidates,
            target: fiveHours,
            range: 10_800 ... 28_800,
            excluding: nil
        )
        var weekly = nearest(
            in: primaryCandidates,
            target: week,
            range: 432_000 ... 777_600,
            excluding: fiveHour?.id
        )

        if fiveHour == nil {
            if let primary, primary.id != weekly?.id {
                fiveHour = primary
            } else if let secondary, secondary.id != weekly?.id {
                fiveHour = secondary
            }
        }

        if let durationMatchedWeekly = weekly {
            weekly = durationMatchedWeekly
        } else if secondary == nil || secondary?.id == fiveHour?.id {
            weekly = farthestDifferent(in: primaryCandidates, excluding: fiveHour?.id)
        } else {
            weekly = secondary
        }

        if fiveHour == nil {
            fiveHour = nearest(
                in: additionalCandidates,
                target: fiveHours,
                range: 10_800 ... 28_800,
                excluding: nil
            )
        }

        if weekly == nil {
            weekly = nearest(
                in: additionalCandidates,
                target: week,
                range: 432_000 ... 777_600,
                excluding: fiveHour?.id
            )
        }

        if fiveHour == nil, let firstAdditional = additionalCandidates.first {
            fiveHour = firstAdditional
        }
        if weekly == nil {
            weekly = farthestDifferent(in: additionalCandidates, excluding: fiveHour?.id)
        }

        let resetCredits = JSONSupport.object(root["rate_limit_reset_credits"])
        let rawAvailableCount = resetCredits.map {
            JSONSupport.int($0["available_count"], default: -1)
        } ?? -1

        return UsageSnapshot(
            planType: planType,
            allowed: allowed,
            limitReached: limitReached,
            fiveHour: fiveHour?.window,
            weekly: weekly?.window,
            resetCreditsAvailable: rawAvailableCount >= 0 ? rawAvailableCount : nil,
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
        let roundedUsed: Int
        let javaRounded = floor(used + 0.5)
        if javaRounded >= Double(Int.max) {
            roundedUsed = Int.max
        } else if javaRounded <= Double(Int.min) {
            roundedUsed = Int.min
        } else {
            roundedUsed = Int(javaRounded)
        }

        return UsageWindow(
            usedPercent: roundedUsed,
            windowSeconds: duration,
            resetAfterSeconds: resetAfter,
            resetAt: resetAtSeconds > 0
                ? Date(timeIntervalSince1970: TimeInterval(resetAtSeconds))
                : nil
        )
    }

    private static func nearest(
        in candidates: [Candidate],
        target: Int64,
        range: ClosedRange<Int64>,
        excluding excludedID: Int?
    ) -> Candidate? {
        var best: Candidate?
        var bestDistance = Int64.max

        for candidate in candidates
        where candidate.id != excludedID && range.contains(candidate.window.windowSeconds) {
            let distance = absoluteDifference(candidate.window.windowSeconds, target)
            if distance < bestDistance {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private static func farthestDifferent(
        in candidates: [Candidate],
        excluding excludedID: Int?
    ) -> Candidate? {
        var best: Candidate?
        var longestDuration: Int64 = -1
        for candidate in candidates
        where candidate.id != excludedID && candidate.window.windowSeconds > longestDuration {
            best = candidate
            longestDuration = candidate.window.windowSeconds
        }
        return best
    }

    private static func absoluteDifference(_ lhs: Int64, _ rhs: Int64) -> Int64 {
        lhs >= rhs ? lhs - rhs : rhs - lhs
    }
}
