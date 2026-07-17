// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CodexMeterCore",
    platforms: [
        .iOS(.v17),
        .watchOS(.v10),
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
