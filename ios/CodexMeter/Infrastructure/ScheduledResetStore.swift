import CodexMeterCore
import Foundation

/// Persists the single armed schedule and its last outcome. A schedule is ephemeral
/// device state: it never enters the settings export.
@MainActor
struct ScheduledResetStore {
    static let scheduleKey = "codex-meter.scheduled-reset-v1"
    static let outcomeKey = "codex-meter.scheduled-reset-outcome-v1"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var schedule: ScheduledReset? {
        get { load(ScheduledReset.self, forKey: Self.scheduleKey) }
        nonmutating set { save(newValue, forKey: Self.scheduleKey) }
    }

    var outcome: ScheduledResetOutcome? {
        get { load(ScheduledResetOutcome.self, forKey: Self.outcomeKey) }
        nonmutating set { save(newValue, forKey: Self.outcomeKey) }
    }

    func clear() {
        defaults.removeObject(forKey: Self.scheduleKey)
        defaults.removeObject(forKey: Self.outcomeKey)
    }

    private func load<Value: Decodable>(_ type: Value.Type, forKey key: String) -> Value? {
        guard let data = defaults.data(forKey: key) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(type, from: data)
    }

    private func save(_ value: (some Encodable)?, forKey key: String) {
        guard let value else {
            defaults.removeObject(forKey: key)
            return
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        if let data = try? encoder.encode(value) {
            defaults.set(data, forKey: key)
        }
    }
}
