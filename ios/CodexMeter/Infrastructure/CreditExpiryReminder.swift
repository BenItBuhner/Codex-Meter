import CodexMeterCore
import Foundation

/// Pure scheduling model for reset-credit expiry reminders (parity with upstream 2.2).
nonisolated public struct CreditExpiryReminder: Sendable, Equatable, Identifiable {
    public static let minimumLead: TimeInterval = 60
    public static let maximumLead: TimeInterval = 365 * 24 * 60 * 60

    public let creditId: String
    public let expiresAt: Date
    public let lead: TimeInterval
    public let triggerAt: Date

    public var id: String { token }

    public var token: String {
        Self.token(creditId: creditId, expiresAt: expiresAt, lead: lead)
    }

    public init(creditId: String, expiresAt: Date, lead: TimeInterval) {
        self.creditId = creditId
        self.expiresAt = expiresAt
        self.lead = lead
        self.triggerAt = expiresAt.addingTimeInterval(-lead)
    }

    public static func token(creditId: String, expiresAt: Date, lead: TimeInterval) -> String {
        "\(creditId):\(Int(expiresAt.timeIntervalSince1970)):\(Int(lead))"
    }

    public static func plan(
        credits: [RateLimitResetCredit],
        leadTimes: [TimeInterval],
        now: Date
    ) -> [CreditExpiryReminder] {
        let uniqueLeads = Set(
            leadTimes.filter { $0 >= minimumLead && $0 <= maximumLead }
        )
        guard !uniqueLeads.isEmpty else { return [] }

        var reminders: [CreditExpiryReminder] = []
        for credit in credits where credit.isAvailable {
            guard let expiresAt = credit.expiresAt, expiresAt > now else { continue }
            for lead in uniqueLeads {
                reminders.append(
                    CreditExpiryReminder(
                        creditId: credit.id,
                        expiresAt: expiresAt,
                        lead: lead
                    )
                )
            }
        }

        return reminders.sorted {
            if $0.triggerAt != $1.triggerAt { return $0.triggerAt < $1.triggerAt }
            if $0.expiresAt != $1.expiresAt { return $0.expiresAt < $1.expiresAt }
            if $0.lead != $1.lead { return $0.lead < $1.lead }
            return $0.creditId < $1.creditId
        }
    }

    public static func leadIntervals(minutes: [Int]) -> [TimeInterval] {
        minutes.map { TimeInterval($0 * 60) }
    }
}
