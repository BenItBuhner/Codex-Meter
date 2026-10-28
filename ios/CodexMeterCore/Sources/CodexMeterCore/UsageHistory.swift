import Foundation

public enum UsageHistoryKind: String, Codable, Sendable, CaseIterable {
    case fiveHour = "five_hour"
    case weekly
    case monthly
}

/// One locally observed allowance value. It contains no account or credential data.
public struct UsageSample: Codable, Sendable, Equatable, Identifiable {
    public var id: Date { observedAt }

    public let observedAt: Date
    public let usedPercent: Int
    public let resetAt: Date
    public let windowSeconds: Int64

    public init(
        observedAt: Date,
        usedPercent: Int,
        resetAt: Date,
        windowSeconds: Int64
    ) {
        self.observedAt = observedAt
        self.usedPercent = min(100, max(0, usedPercent))
        self.resetAt = resetAt
        self.windowSeconds = max(0, windowSeconds)
    }

    private enum CodingKeys: String, CodingKey {
        case observedAt
        case usedPercent
        case resetAt
        case windowSeconds
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            observedAt: try container.decodeIfPresent(Date.self, forKey: .observedAt)
                ?? Date(timeIntervalSince1970: 0),
            usedPercent: try container.decodeIfPresent(Int.self, forKey: .usedPercent) ?? 0,
            resetAt: try container.decodeIfPresent(Date.self, forKey: .resetAt)
                ?? Date(timeIntervalSince1970: 0),
            windowSeconds: try container.decodeIfPresent(Int64.self, forKey: .windowSeconds) ?? 0
        )
    }

    fileprivate var isValid: Bool {
        observedAt.timeIntervalSince1970.isFinite
            && observedAt.timeIntervalSince1970 > 0
            && resetAt.timeIntervalSince1970.isFinite
            && resetAt > observedAt
            && windowSeconds > 0
    }
}

/// Bounded, local-only samples used by usage charts and trend-aware pace estimates.
public struct UsageHistory: Codable, Sendable, Equatable {
    public let kind: UsageHistoryKind
    public let samples: [UsageSample]

    public init(kind: UsageHistoryKind, samples: [UsageSample]) {
        self.kind = kind
        let maximum = Self.maximumSamples(for: kind)
        let normalized = samples
            .filter(\.isValid)
            .sorted { $0.observedAt < $1.observedAt }
        self.samples = Array(normalized.suffix(maximum))
    }

    public static func empty(_ kind: UsageHistoryKind) -> Self {
        Self(kind: kind, samples: [])
    }

    public func appending(window: UsageWindow, observedAt: Date) -> Self {
        guard observedAt.timeIntervalSince1970.isFinite,
              observedAt.timeIntervalSince1970 > 0,
              window.windowSeconds > 0,
              let resetAt = window.effectiveResetDate(relativeTo: observedAt),
              resetAt > observedAt else {
            return self
        }

        let next = UsageSample(
            observedAt: observedAt,
            usedPercent: window.usedPercent,
            resetAt: resetAt,
            windowSeconds: window.windowSeconds
        )
        var updated = samples
        if let last = updated.last {
            guard observedAt > last.observedAt else {
                return self
            }
            let minimumSpacing: TimeInterval = switch kind {
            case .fiveHour: 5 * 60
            case .weekly: 30 * 60
            case .monthly: 2 * 60 * 60
            }
            if Self.sameWindow(last, next),
               last.usedPercent == next.usedPercent,
               observedAt.timeIntervalSince(last.observedAt) < minimumSpacing {
                updated[updated.count - 1] = next
                return Self(kind: kind, samples: updated)
            }
        }

        updated.append(next)
        return Self(kind: kind, samples: updated)
    }

    /// Samples from the latest reset window, oldest first.
    public var currentWindowSamples: [UsageSample] {
        guard let latest = samples.last else { return [] }
        return samples.filter { Self.sameWindow($0, latest) }
    }

    /// The most recent reset windows, oldest window first and oldest sample first.
    public func recentWindows(maximum: Int) -> [[UsageSample]] {
        guard maximum > 0, !samples.isEmpty else { return [] }
        var windows: [[UsageSample]] = []
        var current: [UsageSample] = []
        var previous: UsageSample?

        for sample in samples {
            if let previous, !Self.sameWindow(previous, sample) {
                windows.append(current)
                current = []
            }
            current.append(sample)
            previous = sample
        }
        if !current.isEmpty {
            windows.append(current)
        }
        return Array(windows.suffix(maximum))
    }

    public var completedWindowCount: Int {
        guard var previous = samples.first else { return 0 }
        var count = 0
        for sample in samples.dropFirst() {
            if !Self.sameWindow(previous, sample) {
                count += 1
            }
            previous = sample
        }
        return count
    }

    /// When usage was reset partway through the latest window without moving its reset time.
    /// Claude Code saved resets work this way: usage drops back to zero but the weekly deadline
    /// stays put. The reset is placed halfway between the samples on either side of the drop,
    /// or at the later one when it still reads zero.
    public var inWindowResetAt: Date? {
        let current = currentWindowSamples
        guard current.count >= 2 else { return nil }
        for index in stride(from: current.count - 1, to: 0, by: -1) {
            let before = current[index - 1]
            let after = current[index]
            if Self.isResetDrop(before, after) {
                if after.usedPercent <= 0 { return after.observedAt }
                return before.observedAt.addingTimeInterval(
                    after.observedAt.timeIntervalSince(before.observedAt) / 2
                )
            }
        }
        return nil
    }

    /// Latest-window samples taken after the most recent in-window reset, oldest first.
    public var samplesSinceInWindowReset: [UsageSample] {
        let current = currentWindowSamples
        guard current.count >= 2 else { return current }
        for index in stride(from: current.count - 1, to: 0, by: -1)
        where Self.isResetDrop(current[index - 1], current[index]) {
            return Array(current[index...])
        }
        return current
    }

    /// Usage only falls inside a window when it is reset. A small dip is rounding noise unless
    /// it lands on zero.
    private static func isResetDrop(_ before: UsageSample, _ after: UsageSample) -> Bool {
        guard after.usedPercent < before.usedPercent else { return false }
        return after.usedPercent <= 0 || before.usedPercent - after.usedPercent >= 3
    }

    /// Recent observed burn in percentage points per second.
    public var observedBurnRate: Double {
        let current = samplesSinceInWindowReset
        guard let latest = current.last, current.count >= 2 else { return 0 }
        let minimumSpan: TimeInterval = switch kind {
        case .fiveHour: 10 * 60
        case .weekly: 2 * 60 * 60
        case .monthly: 6 * 60 * 60
        }
        guard let first = current.first(where: {
            latest.observedAt.timeIntervalSince($0.observedAt) >= minimumSpan
                && latest.usedPercent - $0.usedPercent >= 2
        }) else {
            return 0
        }
        let elapsed = latest.observedAt.timeIntervalSince(first.observedAt)
        guard elapsed > 0 else { return 0 }
        return Double(latest.usedPercent - first.usedPercent) / elapsed
    }

    private enum CodingKeys: String, CodingKey {
        case kind
        case samples
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            kind: try container.decodeIfPresent(UsageHistoryKind.self, forKey: .kind) ?? .fiveHour,
            samples: try container.decodeIfPresent([UsageSample].self, forKey: .samples) ?? []
        )
    }

    private static func maximumSamples(for kind: UsageHistoryKind) -> Int {
        switch kind {
        case .fiveHour: 288
        case .weekly: 336
        case .monthly: 372
        }
    }

    private static func sameWindow(_ left: UsageSample, _ right: UsageSample) -> Bool {
        UsageWindow.sameResetWindow(
            leftReset: left.resetAt,
            leftWindowSeconds: left.windowSeconds,
            rightReset: right.resetAt,
            rightWindowSeconds: right.windowSeconds
        )
    }
}
