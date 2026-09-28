import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct SettingsTransferDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.json]

    let settings: AppSettings

    init(settings: AppSettings) {
        self.settings = settings
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        settings = try Self.decode(data)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let envelope = SettingsTransferEnvelope(
            formatVersion: 1,
            exportedAt: .now,
            settings: settings
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return FileWrapper(regularFileWithContents: try encoder.encode(envelope))
    }

    static func decode(_ data: Data) throws -> AppSettings {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let envelope = try? decoder.decode(SettingsTransferEnvelope.self, from: data),
           envelope.formatVersion == 1 {
            return envelope.settings
        }
        return try decoder.decode(AppSettings.self, from: data)
    }
}

private struct SettingsTransferEnvelope: Codable {
    let formatVersion: Int
    let exportedAt: Date
    let settings: AppSettings
}
