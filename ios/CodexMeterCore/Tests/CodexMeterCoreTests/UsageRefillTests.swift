import CodexMeterCore
import XCTest

/// The "usage is back" rules, the same cases as Android's UsageRefill checks.
final class UsageRefillTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_794_000_000)

    private func window(left: Int, resetsIn: TimeInterval?) -> UsageWindow {
        UsageWindow(usedPercent: 100 - left, windowSeconds: 18_000, resetAt: resetsIn.map { now.addingTimeInterval($0) })
    }

    func testAUsedUpLimitIsArmedOnceWithItsResetTime() {
        let reset = now.addingTimeInterval(3_600)
        XCTAssertEqual(UsageRefill.decide(armed: nil, window: window(left: 0, resetsIn: 3_600), observedAt: now, now: now),
                       .arm(.at(reset)))
        XCTAssertEqual(UsageRefill.decide(armed: .at(reset), window: window(left: 0, resetsIn: 3_630), observedAt: now, now: now),
                       .none, "a reset time that moved by seconds is the same reset")
        XCTAssertEqual(UsageRefill.decide(armed: .at(reset), window: window(left: 0, resetsIn: 7_200), observedAt: now, now: now),
                       .arm(.at(now.addingTimeInterval(7_200))), "a reset that moved is armed again")
        XCTAssertEqual(UsageRefill.decide(armed: nil, window: window(left: 0, resetsIn: nil), observedAt: now, now: now),
                       .arm(.unknownReset))
        XCTAssertEqual(UsageRefill.decide(armed: .unknownReset, window: window(left: 0, resetsIn: nil), observedAt: now, now: now),
                       .none)
    }

    func testSomeLeftAgainIsAnnouncedOnlyWhenArmed() {
        XCTAssertEqual(UsageRefill.decide(armed: .at(now), window: window(left: 100, resetsIn: 18_000), observedAt: now, now: now),
                       .back(left: 100))
        XCTAssertEqual(UsageRefill.decide(armed: .unknownReset, window: window(left: 40, resetsIn: nil), observedAt: now, now: now),
                       .back(left: 40))
        XCTAssertEqual(UsageRefill.decide(armed: nil, window: window(left: 40, resetsIn: 60), observedAt: now, now: now), .none)
    }

    func testOldReadingsAndMissingLimitsChangeNothing() {
        XCTAssertEqual(UsageRefill.decide(armed: nil, window: window(left: 0, resetsIn: -60), observedAt: now, now: now), .none,
                       "a reading older than its own reset")
        XCTAssertEqual(UsageRefill.decide(armed: .at(now), window: nil, observedAt: now, now: now), .none)
        XCTAssertEqual(UsageRefill.decide(armed: nil, window: UsageWindow(usedPercent: 100, windowSeconds: 0),
                                          observedAt: now, now: now), .none, "a limit without a window is not a limit")
        XCTAssertFalse(UsageRefill.usedUp(nil))
    }

    func testCopy() {
        XCTAssertEqual(UsageRefill.title("Claude Code"), "Claude Code usage is back")
        XCTAssertEqual(UsageRefill.text("Cursor", label: "Monthly", left: 100),
                       "Your monthly limit refilled to 100% left. You can use Cursor again.")
        XCTAssertEqual(UsageRefill.text("Kimi Code", label: "Daily", left: nil),
                       "Your daily limit has reset. You can use Kimi Code again.")
        XCTAssertEqual(UsageRefill.text("Warp", label: "", left: nil), "Your usage limit has reset. You can use Warp again.")
    }
}
