import Foundation
import XCTest
@testable import CodexMeter

final class AppVersionTests: XCTestCase {
    /// About and diagnostics show the full release version, which the build stamps alongside the
    /// numbers-only short version (2.9.0-alpha.30 next to 2.9.0).
    func testDisplayIsTheFullReleaseVersion() throws {
        let info = try XCTUnwrap(Bundle.main.infoDictionary)
        let short = try XCTUnwrap(info["CFBundleShortVersionString"] as? String)
        let full = try XCTUnwrap(info["CodexMeterReleaseVersion"] as? String)
        XCTAssertFalse(full.isEmpty)
        XCTAssertTrue(full.hasPrefix(short), "\(full) should start with \(short)")
        XCTAssertEqual(AppVersion.display, full)
    }
}
