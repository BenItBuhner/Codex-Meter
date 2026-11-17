import Foundation
#if canImport(Darwin)
import Darwin
#endif

/// Launch timing for the CI performance budgets. It does nothing unless the app is launched with
/// `-perf-budget`; then the first screen writes one line to standard error, which the budget
/// scripts read: `codexmeter.first_frame_ms=<milliseconds since the process started>`.
@MainActor
public enum PerfBudgetProbe {
    public static let argument = "-perf-budget"
    public static let marker = "codexmeter.first_frame_ms="
    private static var reported = false

    public static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains(argument)
    }

    /// Call from the first screen's `onAppear`. The report waits one main-queue turn, so it lands
    /// after the frame that shows the screen is committed.
    public static func firstScreenAppeared() {
        guard isEnabled, !reported else { return }
        reported = true
        DispatchQueue.main.async {
            guard let elapsed = millisecondsSinceProcessStart() else { return }
            FileHandle.standardError.write(Data("\(marker)\(elapsed)\n".utf8))
        }
    }

    /// Milliseconds from this process's start (as the kernel recorded it) to `now`.
    public nonisolated static func millisecondsSinceProcessStart(now: Date = Date()) -> Int? {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, u_int(mib.count), &info, &size, nil, 0) == 0, size > 0 else { return nil }
        let start = info.kp_proc.p_un.__p_starttime
        let startMilliseconds = Double(start.tv_sec) * 1_000 + Double(start.tv_usec) / 1_000
        return Int((now.timeIntervalSince1970 * 1_000 - startMilliseconds).rounded())
    }
}
