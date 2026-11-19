@testable import CodexMeterCore
import XCTest

final class WhatsNewTests: XCTestCase {
    func testShowsOncePerReleaseToUpgradesOnly() {
        XCTAssertTrue(WhatsNew.shouldShow(seen: nil, setUp: true))
        XCTAssertTrue(WhatsNew.shouldShow(seen: "2.8", setUp: true))
        XCTAssertFalse(WhatsNew.shouldShow(seen: WhatsNew.release, setUp: true))
        XCTAssertFalse(WhatsNew.shouldShow(seen: nil, setUp: false))
        XCTAssertTrue(WhatsNew.title.contains(WhatsNew.release))
    }

    func testEveryPlatformListsItsOwnFeatures() {
        for platform in [WhatsNew.Platform.iOS, .macOS] {
            let features = WhatsNew.features(for: platform)
            XCTAssertGreaterThanOrEqual(features.count, 8)
            XCTAssertEqual(Set(features.map(\.id)).count, features.count)
            for feature in features {
                XCTAssertFalse(feature.title.hasSuffix("."), feature.title)
                XCTAssertTrue(feature.body.hasSuffix("."), feature.body)
                XCTAssertLessThanOrEqual(feature.body.count, 110, feature.body)
            }
        }
        XCTAssertTrue(WhatsNew.features(for: .iOS).contains { $0.id == "watch" })
        XCTAssertFalse(WhatsNew.features(for: .macOS).contains { $0.id == "watch" })
        XCTAssertTrue(WhatsNew.features(for: .macOS).contains { $0.id == "updates" })
    }
}
