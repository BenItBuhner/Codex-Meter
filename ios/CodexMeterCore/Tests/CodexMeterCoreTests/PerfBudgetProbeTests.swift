import Foundation
import XCTest
@testable import CodexMeterCore

final class PerfBudgetProbeTests: XCTestCase {
    func testProcessStartIsReadFromTheKernel() throws {
        let elapsed = try XCTUnwrap(PerfBudgetProbe.millisecondsSinceProcessStart())
        XCTAssertGreaterThanOrEqual(elapsed, 0)
        XCTAssertLessThan(elapsed, 60 * 60 * 1_000, "the test process started within the hour")
        let later = try XCTUnwrap(PerfBudgetProbe.millisecondsSinceProcessStart(now: Date().addingTimeInterval(2)))
        XCTAssertGreaterThanOrEqual(later - elapsed, 1_900)
    }

    @MainActor
    func testOffWithoutTheLaunchArgument() {
        XCTAssertFalse(PerfBudgetProbe.isEnabled)
        PerfBudgetProbe.mark("launch") // ignored without the argument
        PerfBudgetProbe.firstScreenAppeared() // must not write anything or crash
        XCTAssertEqual(PerfBudgetProbe.phasesLine(), PerfBudgetProbe.phasesMarker)
    }
}
