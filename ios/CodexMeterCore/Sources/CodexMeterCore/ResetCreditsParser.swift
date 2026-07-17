import Foundation

public enum ResetCreditsParser {
    public static func parse(_ string: String?, fetchedAt: Date = Date()) throws -> ResetCreditsSnapshot {
        try parse(Data((string ?? "{}").utf8), fetchedAt: fetchedAt)
    }

    public static func parse(_ data: Data, fetchedAt: Date = Date()) throws -> ResetCreditsSnapshot {
        let root = try JSONSupport.object(from: data)
        let credits = (JSONSupport.array(root["credits"]) ?? []).compactMap { value in
            JSONSupport.object(value).map(parseCredit)
        }
        let fallbackCount = credits.lazy.filter(\.isAvailable).count
        let availableCount = JSONSupport.int(root["available_count"], default: fallbackCount)
        return ResetCreditsSnapshot(
            availableCount: availableCount,
            credits: credits,
            fetchedAt: fetchedAt
        )
    }

    private static func parseCredit(_ object: JSONSupport.Object) -> RateLimitResetCredit {
        RateLimitResetCredit(
            id: JSONSupport.string(object["id"]),
            resetType: JSONSupport.string(object["reset_type"]),
            status: JSONSupport.string(object["status"]),
            grantedAt: parseTimestamp(JSONSupport.string(object["granted_at"])),
            expiresAt: parseTimestamp(JSONSupport.string(object["expires_at"])),
            title: JSONSupport.string(object["title"]),
            description: JSONSupport.string(object["description"])
        )
    }

    public static func parseTimestamp(_ value: String?) -> Date? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty
        else {
            return nil
        }

        let fractionalFormatter = ISO8601DateFormatter()
        fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractionalFormatter.date(from: value) {
            return positiveDate(date)
        }

        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value).flatMap(positiveDate)
    }

    private static func positiveDate(_ date: Date) -> Date? {
        let seconds = date.timeIntervalSince1970
        return seconds.isFinite && seconds > 0 ? date : nil
    }
}
