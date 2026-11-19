import Foundation
#if canImport(Darwin)
import Darwin
#endif

/// Launch timing for the CI performance budgets. It does nothing unless the app is launched with
/// `-perf-budget`; then the first screen logs one line, `codexmeter.first_frame_ms=<milliseconds
/// since the process started>` (-1 when the kernel's start time can't be read), and writes the
/// same line to `tmp/codexmeter-first-frame.txt` in the app's container, which the budget scripts
/// read on the simulator. Launch steps marked with `mark(_:)` before then are logged on a second
/// line, `codexmeter.phases=<name>:<ms> ...`, so a slow launch shows where its time went.
@MainActor
public enum PerfBudgetProbe {
    public static let argument = "-perf-budget"
    public static let marker = "codexmeter.first_frame_ms="
    public static let fileName = "codexmeter-first-frame.txt"
    public static let phasesMarker = "codexmeter.phases="
    private static var reported = false
    private static var phasesClosed = false
    private static var phases: [String] = []
    private static let enabled = ProcessInfo.processInfo.arguments.contains(argument)

    public static var isEnabled: Bool { enabled }

    /// Notes that launch reached `phase`, in milliseconds since the process started. Does nothing
    /// without `-perf-budget`, or once the first frame was reported.
    public static func mark(_ phase: String) {
        guard enabled, !phasesClosed else { return }
        phases.append("\(phase):\(millisecondsSinceProcessStart() ?? -1)")
    }

    /// The marked phases as the report's second line, oldest first.
    static func phasesLine() -> String {
        phasesMarker + phases.joined(separator: " ")
    }

    /// Call from the first screen's `onAppear`. The report waits one main-queue turn, so it lands
    /// after the frame that shows the screen is committed.
    public static func firstScreenAppeared() {
        guard isEnabled, !reported else { return }
        reported = true
        mark("appeared")
        DispatchQueue.main.async {
            phasesClosed = true
            let line = "\(marker)\(millisecondsSinceProcessStart() ?? -1)"
            let phases = phasesLine()
            NSLog("%@", phases)
            NSLog("%@", line)
            let file = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
            try? Data((phases + "\n" + line + "\n").utf8).write(to: file, options: .atomic)
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
