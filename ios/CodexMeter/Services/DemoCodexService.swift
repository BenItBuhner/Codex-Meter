import CodexMeterCore
import Foundation
import WidgetKit

/// A deterministic, network-free service used by the visible App Store demo
/// path and UI tests.
public actor DemoCodexService: CodexService {
    private let referenceDate: Date
    private let appCache: AppCacheStore
    private let widgetCache: WidgetSnapshotCache
    private var refreshCount = 0
    private var fiveHourUsed = 38
    private var weeklyUsed = 64
    private var availableCredits = 2

    public init(
        referenceDate: Date = Date(),
        appCache: AppCacheStore = .shared,
        widgetCache: WidgetSnapshotCache = .shared
    ) {
        self.referenceDate = referenceDate
        self.appCache = appCache
        self.widgetCache = widgetCache
    }

    public func refresh() async throws -> CodexRefreshSnapshot {
        refreshCount += 1
        if refreshCount > 1 {
            fiveHourUsed = min(100, fiveHourUsed + 1)
            weeklyUsed = min(100, weeklyUsed + (refreshCount.isMultiple(of: 2) ? 1 : 0))
        }
        return try await persistCurrentState()
    }

    public func refreshUsage() async throws -> UsageSnapshot {
        try await refresh().usage
    }

    public func refreshResetCredits() async throws -> ResetCreditsSnapshot {
        let state = try await persistCurrentState()
        return state.credits
    }

    public func consumeReset() async throws -> ResetConsumeResult {
        guard availableCredits > 0 else {
            return ResetConsumeResult(outcome: .noCredit, windowsReset: 0)
        }
        availableCredits -= 1
        fiveHourUsed = 0
        weeklyUsed = 0
        refreshCount += 1
        _ = try await persistCurrentState()
        return ResetConsumeResult(outcome: .reset, windowsReset: 2)
    }

    public func currentTokens() async throws -> AuthTokens? {
        nil
    }

    public func signOut() async {
        refreshCount = 0
        fiveHourUsed = 38
        weeklyUsed = 64
        availableCredits = 2
        try? await appCache.clear()
        do {
            try await widgetCache.publishSignedOut()
            WidgetCenter.shared.reloadAllTimelines()
            await appCache.clearWidgetError(at: referenceDate)
        } catch {
            await appCache.recordWidgetError(error.localizedDescription, at: referenceDate)
        }
    }

    private func persistCurrentState() async throws -> CodexRefreshSnapshot {
        let fetchedAt = referenceDate.addingTimeInterval(TimeInterval(refreshCount * 30))
        let credits = makeCredits(fetchedAt: fetchedAt)
        let usage = UsageSnapshot(
            planType: "Plus (Demo)",
            allowed: true,
            limitReached: false,
            fiveHour: UsageWindow(
                usedPercent: fiveHourUsed,
                windowSeconds: 18_000,
                resetAt: referenceDate.addingTimeInterval(2 * 60 * 60 + 17 * 60)
            ),
            weekly: UsageWindow(
                usedPercent: weeklyUsed,
                windowSeconds: 604_800,
                resetAt: referenceDate.addingTimeInterval(3 * 24 * 60 * 60 + 8 * 60 * 60)
            ),
            resetCreditsAvailable: availableCredits,
            fetchedAt: fetchedAt
        )
        try await appCache.save(
            AppCacheSnapshot(usage: usage, credits: credits, updatedAt: fetchedAt)
        )
        do {
            try await widgetCache.publish(
                mode: .demo,
                usage: usage,
                credits: credits,
                now: fetchedAt
            )
            WidgetCenter.shared.reloadAllTimelines()
            await appCache.clearWidgetError(at: fetchedAt)
        } catch {
            await appCache.recordWidgetError(error.localizedDescription, at: fetchedAt)
        }
        return (usage, credits)
    }

    private func makeCredits(fetchedAt: Date) -> ResetCreditsSnapshot {
        var credits: [RateLimitResetCredit] = []
        for index in 0..<availableCredits {
            let ordinal = index + 1
            let grantedOffset = TimeInterval(-ordinal * 86_400)
            let expiryOffset = TimeInterval((index + 2) * 3 * 86_400)
            credits.append(
                RateLimitResetCredit(
                id: "demo-credit-\(ordinal)",
                resetType: "rate_limit",
                status: RateLimitResetCredit.availableStatus,
                grantedAt: referenceDate.addingTimeInterval(grantedOffset),
                expiresAt: referenceDate.addingTimeInterval(expiryOffset),
                title: "Codex reset",
                description: "Demo reset credit"
            )
            )
        }
        return ResetCreditsSnapshot(
            availableCount: availableCredits,
            credits: credits,
            fetchedAt: fetchedAt
        )
    }
}
