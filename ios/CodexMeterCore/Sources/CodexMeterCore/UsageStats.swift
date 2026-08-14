import Foundation

/// Pure aggregation over locally recorded usage samples: per-window statistics,
/// cross-window typical pace, and time interpolation for chart scrubbing.
public enum UsageStats {
    /// Consecutive samples closer than this are too jittery for a burn-rate reading.
    private static let minimumRateSpacing: TimeInterval = 60

    /// Statistics for one reset window's recorded samples.
    public struct WindowStats: Sendable, Equatable {
        public let windowStart: Date
        public let resetAt: Date
        public let firstSampleAt: Date
        public let lastSampleAt: Date
        public let firstPercent: Int
        public let finalPercent: Int
        public let sampleCount: Int
        /// Average burn in percent per hour across the observed sample span.
        public let averageBurnPercentPerHour: Double
        /// Steepest observed burn in percent per hour between adjacent samples.
        public let peakBurnPercentPerHour: Double
        public let exhausted: Bool
        public let complete: Bool
    }

    /// Builds statistics for one window's samples, or `nil` when samples are unusable.
    public static func windowStats(samples: [UsageSample], complete: Bool) -> WindowStats? {
        guard let first = samples.first, let last = samples.last else { return nil }
        let windowStart = last.resetAt.addingTimeInterval(-TimeInterval(last.windowSeconds))
        var averageRate = 0.0
        let span = last.observedAt.timeIntervalSince(first.observedAt)
        let burned = last.usedPercent - first.usedPercent
        if span > 0, burned > 0 {
            averageRate = Double(burned) / hours(span)
        }
        var peakRate = 0.0
        if samples.count > 1 {
            for index in 1..<samples.count {
                let previous = samples[index - 1]
                let current = samples[index]
                let gap = current.observedAt.timeIntervalSince(previous.observedAt)
                let delta = current.usedPercent - previous.usedPercent
                guard gap >= minimumRateSpacing, delta > 0 else { continue }
                peakRate = max(peakRate, Double(delta) / hours(gap))
            }
        }
        return WindowStats(
            windowStart: windowStart,
            resetAt: last.resetAt,
            firstSampleAt: first.observedAt,
            lastSampleAt: last.observedAt,
            firstPercent: first.usedPercent,
            finalPercent: last.usedPercent,
            sampleCount: samples.count,
            averageBurnPercentPerHour: averageRate,
            peakBurnPercentPerHour: peakRate,
            exhausted: last.usedPercent >= 100,
            complete: complete
        )
    }

    /// Per-window statistics, oldest to newest; the final entry is the current window.
    public static func windowBreakdown(_ history: UsageHistory?, maximumWindows: Int) -> [WindowStats] {
        guard let history else { return [] }
        let windows = history.recentWindows(maximum: maximumWindows)
        return windows.enumerated().compactMap { index, samples in
            windowStats(samples: samples, complete: index < windows.count - 1)
        }
    }

    /// Linearly interpolated used percent at a moment in time, or `nil` when the moment
    /// falls outside the observed sample span.
    public static func usedPercent(at date: Date, in samples: [UsageSample]) -> Double? {
        guard let first = samples.first, let last = samples.last else { return nil }
        guard date >= first.observedAt, date <= last.observedAt else { return nil }
        if samples.count == 1 {
            return Double(first.usedPercent)
        }
        for index in 1..<samples.count {
            let left = samples[index - 1]
            let right = samples[index]
            guard date <= right.observedAt else { continue }
            let span = right.observedAt.timeIntervalSince(left.observedAt)
            if span <= 0 {
                return Double(right.usedPercent)
            }
            let ratio = date.timeIntervalSince(left.observedAt) / span
            return Double(left.usedPercent)
                + Double(right.usedPercent - left.usedPercent) * ratio
        }
        return Double(last.usedPercent)
    }

    /// Typical used percent at an elapsed fraction of the window (0...1), averaged across
    /// completed windows. Returns `nil` when no completed window covers that fraction.
    public static func typicalUsedPercent(in history: UsageHistory?, atElapsedFraction fraction: Double) -> Double? {
        guard let history else { return nil }
        let clamped = min(1, max(0, fraction))
        let windows = history.recentWindows(maximum: .max)
        guard windows.count > 1 else { return nil }
        var total = 0.0
        var counted = 0
        for window in windows.dropLast() {
            guard window.count >= 2, let reference = window.last, let first = window.first else {
                continue
            }
            let windowStart = reference.resetAt.addingTimeInterval(-TimeInterval(reference.windowSeconds))
            let moment = windowStart.addingTimeInterval(clamped * TimeInterval(reference.windowSeconds))
            let value: Double
            if let interpolated = usedPercent(at: moment, in: window) {
                value = interpolated
            } else {
                value = moment < first.observedAt
                    ? Double(first.usedPercent)
                    : Double(reference.usedPercent)
            }
            total += value
            counted += 1
        }
        return counted == 0 ? nil : total / Double(counted)
    }

    /// Average final used percent across completed windows, or `nil` when none exist.
    public static func averageFinalPercent(_ history: UsageHistory?) -> Double? {
        guard let history else { return nil }
        let windows = history.recentWindows(maximum: .max)
        let completed = windows.dropLast().compactMap(\.last)
        guard !completed.isEmpty else { return nil }
        let total = completed.reduce(0) { $0 + $1.usedPercent }
        return Double(total) / Double(completed.count)
    }

    /// Steepest burn observed across every recorded window, or 0 when unavailable.
    public static func peakBurnPercentPerHour(_ history: UsageHistory?) -> Double {
        windowBreakdown(history, maximumWindows: .max)
            .map(\.peakBurnPercentPerHour)
            .max() ?? 0
    }

    public static func formatRate(_ percentPerHour: Double) -> String {
        if percentPerHour >= 10 {
            return "\(Int(percentPerHour.rounded()))%/h"
        }
        return String(format: "%.1f%%/h", percentPerHour)
    }

    private static func hours(_ interval: TimeInterval) -> Double {
        interval / 3_600
    }
}
