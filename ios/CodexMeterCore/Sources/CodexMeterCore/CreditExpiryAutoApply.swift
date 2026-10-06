import Foundation

/// Pure planner: unused available reset credits apply in the last minute before expiry.
public struct CreditExpiryAutoApply: Sendable, Equatable, Identifiable {
    public static let applyLead: TimeInterval = 60

    public let creditId: String
    public let expiresAt: Date
    public let triggerAt: Date

    public var id: String { token }

    public var token: String {
        Self.token(creditId: creditId, expiresAt: expiresAt)
    }

    public init(creditId: String, expiresAt: Date) {
        self.creditId = creditId
        self.expiresAt = expiresAt
        self.triggerAt = expiresAt.addingTimeInterval(-Self.applyLead)
    }

    public static func token(creditId: String, expiresAt: Date) -> String {
        "\(creditId):\(Int(expiresAt.timeIntervalSince1970))"
    }

    public static func plan(
        credits: [RateLimitResetCredit],
        now: Date
    ) -> [CreditExpiryAutoApply] {
        credits.compactMap { credit -> CreditExpiryAutoApply? in
            guard credit.isAvailable, let expiresAt = credit.expiresAt, expiresAt > now else {
                return nil
            }
            return CreditExpiryAutoApply(creditId: credit.id, expiresAt: expiresAt)
        }
        .sorted {
            if $0.triggerAt != $1.triggerAt { return $0.triggerAt < $1.triggerAt }
            if $0.expiresAt != $1.expiresAt { return $0.expiresAt < $1.expiresAt }
            return $0.creditId < $1.creditId
        }
    }

    public static func due(
        credits: [RateLimitResetCredit],
        now: Date
    ) -> [CreditExpiryAutoApply] {
        plan(credits: credits, now: now).filter { isDue(expiresAt: $0.expiresAt, now: now) }
    }

    public static func isDue(expiresAt: Date, now: Date) -> Bool {
        expiresAt > now && expiresAt.timeIntervalSince(now) <= applyLead
    }
}
