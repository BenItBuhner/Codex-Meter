import CodexMeterCore
import Foundation
import XCTest
@testable import CodexMeter

/// The Mac updater's decisions, against release JSON shaped like GitHub's: which version is
/// newer, which release and assets it trusts, and which checksum it compares with.
final class MacUpdaterTests: XCTestCase {
    private func version(_ text: String) throws -> MacReleaseVersion {
        try XCTUnwrap(MacReleaseVersion(text), "\(text) parses")
    }

    func testVersionsCompareTheSemVerWay() throws {
        XCTAssertLessThan(try version("2.9.0-alpha.9"), try version("2.9.0-alpha.10"))
        XCTAssertLessThan(try version("2.9.0-alpha.38"), try version("2.9.0"))
        XCTAssertLessThan(try version("2.9.0"), try version("3.0.0-alpha.1"))
        XCTAssertLessThan(try version("2.9.0-alpha"), try version("2.9.0-alpha.1"))
        XCTAssertLessThan(try version("2.9.0-alpha.1"), try version("2.9.0-beta"))
        XCTAssertLessThan(try version("2.9.0-1"), try version("2.9.0-alpha"))
        XCTAssertEqual(try version("v2.9"), try version("2.9.0"))
        XCTAssertEqual(try version("2.9.0+build.7").description, "2.9.0")
        XCTAssertEqual(try version(" V3.0.0-alpha.2 ").description, "3.0.0-alpha.2")
        XCTAssertTrue(try version("3.0.0-rc.1").isPrerelease)
        XCTAssertFalse(try version("3.0.0").isPrerelease)
    }

    func testMalformedVersionsAreRefused() {
        for text in ["", "v", "2..0", "2.9.0.1", "2.9.x", "2.9.0-", "2.9.0-alpha..1", "2.9.0-al pha", "２.9.0"] {
            XCTAssertNil(MacReleaseVersion(text), text)
        }
    }

    private func release(_ tag: String, draft: Bool = false, prerelease: Bool = false, page: String? = nil, assetHost: String = "github.com") -> [String: Any] {
        let base = "https://\(assetHost)/BenItBuhner/Codex-Meter/releases/download/\(tag)"
        let plain = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
        return [
            "tag_name": tag,
            "draft": draft,
            "prerelease": prerelease,
            "html_url": page ?? "https://github.com/BenItBuhner/Codex-Meter/releases/tag/\(tag)",
            "body": "Notes for \(tag)",
            "assets": [
                ["name": "CodexMeter-\(plain)-macos.zip", "browser_download_url": "\(base)/CodexMeter-\(plain)-macos.zip", "size": 12_345_678],
                ["name": "CodexMeter-\(plain)-macos.dmg", "browser_download_url": "\(base)/CodexMeter-\(plain)-macos.dmg", "size": 13_000_000],
                ["name": "SHA256SUMS-macos.txt", "browser_download_url": "\(base)/SHA256SUMS-macos.txt", "size": 200],
                ["name": "CodexMeter-\(plain)-windows-x64.zip", "browser_download_url": "\(base)/CodexMeter-\(plain)-windows-x64.zip", "size": 1],
            ],
        ]
    }

    private func parse(_ objects: Any) throws -> [MacRelease] {
        MacReleaseFeed.parse(try JSONSerialization.data(withJSONObject: objects))
    }

    func testAListingKeepsPublishedReleasesNewestFirstWithTheirMacAssets() throws {
        let releases = try parse([
            release("v2.9.0-alpha.9", prerelease: true),
            release("v2.9.0-alpha.38", prerelease: true),
            release("v3.0.0-alpha.1", draft: true),
            release("v2.8.0"),
        ])
        XCTAssertEqual(releases.map(\.tag), ["v2.9.0-alpha.38", "v2.9.0-alpha.9", "v2.8.0"])
        let newest = try XCTUnwrap(releases.first)
        XCTAssertTrue(newest.prerelease)
        XCTAssertEqual(newest.zip?.name, "CodexMeter-2.9.0-alpha.38-macos.zip")
        XCTAssertEqual(newest.zip?.size, 12_345_678)
        XCTAssertEqual(newest.diskImage?.name, "CodexMeter-2.9.0-alpha.38-macos.dmg")
        XCTAssertEqual(newest.checksums?.name, MacRelease.checksumsName)
        XCTAssertEqual(newest.notes, "Notes for v2.9.0-alpha.38")
        XCTAssertFalse(try XCTUnwrap(releases.last).prerelease)
    }

    func testTheLatestEndpointsSingleObjectParsesToo() throws {
        let releases = try parse(release("v2.9.0"))
        XCTAssertEqual(releases.map(\.tag), ["v2.9.0"])
        XCTAssertTrue(MacReleaseFeed.parse(Data("not json".utf8)).isEmpty)
        XCTAssertTrue(MacReleaseFeed.parse(Data("{\"message\":\"Not Found\"}".utf8)).isEmpty)
    }

    func testReleasesAndAssetsFromAnywhereElseAreIgnored() throws {
        XCTAssertTrue(try parse([release("v9.9.9", page: "https://github.com/someone/fork/releases/tag/v9.9.9")]).isEmpty)
        XCTAssertTrue(try parse([release("v9.9.9", page: "http://github.com/BenItBuhner/Codex-Meter/releases/tag/v9.9.9")]).isEmpty)
        let foreignAssets = try XCTUnwrap(try parse([release("v2.9.1", assetHost: "evil.example.com")]).first)
        XCTAssertNil(foreignAssets.zip)
        XCTAssertNil(foreignAssets.diskImage)
        XCTAssertNil(foreignAssets.checksums)

        let trusted = try XCTUnwrap(URL(string: "https://github.com/benitbuhner/codex-meter/releases/download/v1/a.zip"))
        XCTAssertTrue(MacReleaseFeed.isTrustedPage(trusted))
        let lookalike = try XCTUnwrap(URL(string: "https://github.com.evil.example/BenItBuhner/Codex-Meter/releases/x"))
        XCTAssertFalse(MacReleaseFeed.isTrustedPage(lookalike))
    }

    func testTheChecksumIsTheOneLineForThatFileName() {
        let hash = String(repeating: "ab", count: 32)
        let other = String(repeating: "cd", count: 32)
        let sums = """
        \(other)  CodexMeter-2.9.0-macos.dmg
        \(hash.uppercased()) *CodexMeter-2.9.0-macos.zip
        """
        XCTAssertEqual(MacReleaseFeed.expectedSha256(sums, for: "CodexMeter-2.9.0-macos.zip"), hash)
        XCTAssertEqual(MacReleaseFeed.expectedSha256(sums, for: "CodexMeter-2.9.0-macos.dmg"), other)
        XCTAssertNil(MacReleaseFeed.expectedSha256(sums, for: "CodexMeter-2.9.0-macos.tar"))
        // Two different hashes for one file, or one that isn't 64 hex digits, is no checksum at all.
        XCTAssertNil(MacReleaseFeed.expectedSha256("\(hash)  a.zip\n\(other)  a.zip", for: "a.zip"))
        XCTAssertEqual(MacReleaseFeed.expectedSha256("\(hash)  a.zip\n\(hash)  a.zip", for: "a.zip"), hash)
        XCTAssertNil(MacReleaseFeed.expectedSha256("abc123  a.zip", for: "a.zip"))
        XCTAssertNil(MacReleaseFeed.expectedSha256(String(repeating: "zz", count: 32) + "  a.zip", for: "a.zip"))
    }

    @MainActor
    func testCheckFrequencyLabels() {
        XCTAssertEqual(MacUpdater.frequencyTitle(1), "Hourly")
        XCTAssertEqual(MacUpdater.frequencyTitle(24), "Daily")
        XCTAssertEqual(MacUpdater.frequencyTitle(168), "Weekly")
        XCTAssertEqual(MacUpdater.frequencyTitle(6), "Every 6 hours")
    }
}
