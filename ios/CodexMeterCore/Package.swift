// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CodexMeterCore",
    platforms: [
        .iOS("26.0"),
        // The Apple Watch app and its complications share the usage models and watch sync.
        .watchOS("26.0"),
        // `swift test` builds for the macOS host; CryptoKit (Claude PKCE) needs macOS 10.15+.
        .macOS(.v15),
    ],
    products: [
        .library(
            name: "CodexMeterCore",
            targets: ["CodexMeterCore"]
        ),
    ],
    targets: [
        .target(name: "CodexMeterCore"),
        .testTarget(
            name: "CodexMeterCoreTests",
            dependencies: ["CodexMeterCore"],
            resources: [.process("Fixtures")]
        ),
    ],
    swiftLanguageModes: [.v6]
)
