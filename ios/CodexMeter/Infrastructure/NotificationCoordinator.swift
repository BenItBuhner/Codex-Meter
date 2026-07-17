import CodexMeterCore
import Foundation
import UserNotifications

nonisolated public enum NotificationPermissionState: String, Sendable, Equatable {
    case notDetermined
    case denied
    case authorized
    case provisional
    case ephemeral
}

nonisolated public enum NotificationDeduplication {
    public static let identifierPrefix = "codex-meter."
    public static let expiryIdentifierPrefix = "codex-meter.credit-expiry."
    public static let refillIdentifierPrefix = "codex-meter.refill."
    public static let routeUserInfoKey = "codexmeter.route"
    public static let useResetActionIdentifier = "USE_RESET"
    public static let expiryCategoryIdentifier = "CODEX_CREDIT_EXPIRY"

    public static func windowToken(for resetDate: Date) -> Int {
        Int(resetDate.timeIntervalSince1970)
    }

    public static func shouldDeliverLowUsage(
        lastDeliveredWindowToken: Int?,
        resetDate: Date
    ) -> Bool {
        lastDeliveredWindowToken != windowToken(for: resetDate)
    }

    public static func lowUsageIdentifier(metric: AlertMetric, resetDate: Date) -> String {
        "\(identifierPrefix)low.\(metric.rawValue).\(windowToken(for: resetDate))"
    }

    public static func resetIdentifier(metric: AlertMetric, resetDate: Date) -> String {
        "\(identifierPrefix)reset.\(metric.rawValue).\(Int(resetDate.timeIntervalSince1970))"
    }

    public static func creditIdentifier(fetchedAt: Date, count: Int) -> String {
        "\(identifierPrefix)credit.\(Int(fetchedAt.timeIntervalSince1970)).\(count)"
    }

    public static func creditExpiryIdentifier(token: String) -> String {
        "\(expiryIdentifierPrefix)\(token)"
    }

    public static func refillIdentifier(mask: CelebrationDetector.RefillMask, fetchedAt: Date) -> String {
        "\(refillIdentifierPrefix)\(mask.rawValue).\(Int(fetchedAt.timeIntervalSince1970))"
    }

    public static func obsoleteWindowIdentifiers(
        among identifiers: some Sequence<String>,
        keepingLow lowIdentifiers: Set<String>,
        keepingReset resetIdentifiers: Set<String>
    ) -> [String] {
        identifiers.filter { identifier in
            if identifier.hasPrefix("\(identifierPrefix)low.") {
                return !lowIdentifiers.contains(identifier)
            }
            if identifier.hasPrefix("\(identifierPrefix)reset.") {
                return !resetIdentifiers.contains(identifier)
            }
            return false
        }
    }

    public static func obsoleteExpiryIdentifiers(
        among identifiers: some Sequence<String>,
        keeping: Set<String>
    ) -> [String] {
        identifiers.filter { identifier in
            guard identifier.hasPrefix(expiryIdentifierPrefix) else { return false }
            return !keeping.contains(identifier)
        }
    }
}

/// Evaluates usage and credit snapshots into ordinary local notifications.
/// Identifiers include the metric and reset window, making repeated refreshes
/// naturally idempotent. Includes 2.1/2.2-style refill and credit-expiry alerts.
public actor NotificationCoordinator {
    private static let knownCreditCountKey = "codex-meter.notification.known-credit-count"
    private static let lowUsageStatePrefix = "codex-meter.notification.low-window."
    private static let creditExpiryAnnouncedKey = "codex-meter.notification.credit-expiry-announced"
    private static let userResetFiveHourUntilKey = "codex-meter.notification.user-reset-five-hour-until"
    private static let userResetWeeklyUntilKey = "codex-meter.notification.user-reset-weekly-until"
    private static let categoriesRegisteredKey = "codex-meter.notification.categories-registered"

    private let center: UNUserNotificationCenter
    private let defaults: UserDefaults

    public init(
        center: UNUserNotificationCenter = .current(),
        defaults: UserDefaults = .standard
    ) {
        self.center = center
        self.defaults = defaults
    }

    public func permissionState() async -> NotificationPermissionState {
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        case .authorized: return .authorized
        case .provisional: return .provisional
        case .ephemeral: return .ephemeral
        @unknown default: return .denied
        }
    }

    @discardableResult
    public func requestAuthorization() async throws -> Bool {
        await registerCategoriesIfNeeded()
        return try await center.requestAuthorization(options: [.alert, .badge, .sound])
    }

    public func process(
        usage: UsageSnapshot,
        previousUsage: UsageSnapshot? = nil,
        credits: ResetCreditsSnapshot,
        settings: AppSettings,
        now: Date = Date()
    ) async {
        await registerCategoriesIfNeeded()

        guard settings.notificationsEnabled else {
            await removeMeterRequests(includeDelivered: true)
            defaults.set(credits.availableCount, forKey: Self.knownCreditCountKey)
            return
        }

        var currentLowIdentifiers: Set<String> = []
        var currentResetIdentifiers: Set<String> = []
        if settings.alertMetric.includes(.fiveHour), let window = usage.fiveHour {
            await processWindow(
                window,
                metric: .fiveHour,
                label: "5-hour",
                fetchedAt: usage.fetchedAt,
                threshold: settings.alertThreshold,
                now: now,
                currentLowIdentifiers: &currentLowIdentifiers,
                currentResetIdentifiers: &currentResetIdentifiers
            )
        }
        if settings.alertMetric.includes(.weekly), let window = usage.weekly {
            await processWindow(
                window,
                metric: .weekly,
                label: "Weekly",
                fetchedAt: usage.fetchedAt,
                threshold: settings.alertThreshold,
                now: now,
                currentLowIdentifiers: &currentLowIdentifiers,
                currentResetIdentifiers: &currentResetIdentifiers
            )
        }
        await removeObsoleteWindowRequests(
            keepingLow: currentLowIdentifiers,
            keepingReset: currentResetIdentifiers
        )

        if settings.unexpectedRefillAlertsEnabled {
            await processUnexpectedRefills(
                previous: previousUsage,
                current: usage
            )
        }

        await processCreditIncrease(credits, enabled: settings.creditIncreaseAlertsEnabled)
        await processCreditExpiryReminders(
            credits: credits,
            settings: settings,
            now: now
        )
    }

    /// Suppresses false “surprise refill” alerts after the user redeems a reset credit.
    public func markUserReset(usage: UsageSnapshot, now: Date = Date()) {
        if let deadline = CelebrationDetector.userResetSuppressionDeadline(
            snapshot: usage,
            window: usage.fiveHour,
            now: now
        ) {
            defaults.set(deadline.timeIntervalSince1970, forKey: Self.userResetFiveHourUntilKey)
        } else {
            defaults.removeObject(forKey: Self.userResetFiveHourUntilKey)
        }
        if let deadline = CelebrationDetector.userResetSuppressionDeadline(
            snapshot: usage,
            window: usage.weekly,
            now: now
        ) {
            defaults.set(deadline.timeIntervalSince1970, forKey: Self.userResetWeeklyUntilKey)
        } else {
            defaults.removeObject(forKey: Self.userResetWeeklyUntilKey)
        }
    }

    @discardableResult
    public func sendTestNotification() async throws -> Bool {
        let state = await permissionState()
        guard state == .authorized || state == .provisional || state == .ephemeral else {
            return false
        }
        let content = UNMutableNotificationContent()
        content.title = "Codex Meter notifications are working"
        content.body = "Low usage, scheduled resets, surprise refills, and reset-credit alerts are ready."
        content.sound = .default
        try await center.add(
            UNNotificationRequest(
                identifier: "\(NotificationDeduplication.identifierPrefix)test",
                content: content,
                trigger: nil
            )
        )
        return true
    }

    public func clearAll() async {
        await removeMeterRequests(includeDelivered: true)
        defaults.removeObject(forKey: Self.knownCreditCountKey)
        defaults.removeObject(forKey: Self.lowUsageStatePrefix + AlertMetric.fiveHour.rawValue)
        defaults.removeObject(forKey: Self.lowUsageStatePrefix + AlertMetric.weekly.rawValue)
        defaults.removeObject(forKey: Self.creditExpiryAnnouncedKey)
        defaults.removeObject(forKey: Self.userResetFiveHourUntilKey)
        defaults.removeObject(forKey: Self.userResetWeeklyUntilKey)
    }

    private func processWindow(
        _ window: UsageWindow,
        metric: AlertMetric,
        label: String,
        fetchedAt: Date,
        threshold: Int,
        now: Date,
        currentLowIdentifiers: inout Set<String>,
        currentResetIdentifiers: inout Set<String>
    ) async {
        guard let resetDate = window.effectiveResetDate(relativeTo: fetchedAt), resetDate > now else {
            return
        }
        let resetIdentifier = NotificationDeduplication.resetIdentifier(
            metric: metric,
            resetDate: resetDate
        )
        currentResetIdentifiers.insert(resetIdentifier)

        let resetContent = UNMutableNotificationContent()
        resetContent.title = "Codex \(label) usage reset"
        resetContent.body = "Your \(label.lowercased()) allowance should be available again. Open Codex Meter to refresh."
        resetContent.sound = .default
        let components = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute, .second],
            from: resetDate
        )
        try? await center.add(
            UNNotificationRequest(
                identifier: resetIdentifier,
                content: resetContent,
                trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            )
        )
        guard window.hasRemainingAllowance(atOrBelow: threshold) else { return }
        let identifier = NotificationDeduplication.lowUsageIdentifier(
            metric: metric,
            resetDate: resetDate
        )
        currentLowIdentifiers.insert(identifier)
        let stateKey = Self.lowUsageStatePrefix + metric.rawValue
        let previousWindowToken = defaults.object(forKey: stateKey) == nil
            ? nil
            : defaults.integer(forKey: stateKey)
        guard NotificationDeduplication.shouldDeliverLowUsage(
            lastDeliveredWindowToken: previousWindowToken,
            resetDate: resetDate
        ) else {
            return
        }
        let lowContent = UNMutableNotificationContent()
        lowContent.title = "\(label) Codex usage is low"
        lowContent.body = "\(window.remainingPercent)% remaining in the current \(label.lowercased()) window."
        lowContent.sound = .default
        do {
            try await center.add(
                UNNotificationRequest(identifier: identifier, content: lowContent, trigger: nil)
            )
            defaults.set(NotificationDeduplication.windowToken(for: resetDate), forKey: stateKey)
        } catch {
            // Leave the state unset so a later refresh can retry delivery.
        }
    }

    private func processUnexpectedRefills(
        previous: UsageSnapshot?,
        current: UsageSnapshot
    ) async {
        let raw = CelebrationDetector.detectUnexpectedRefills(previous: previous, current: current)
        guard !raw.isEmpty else { return }

        let fiveHourUntil = date(forKey: Self.userResetFiveHourUntilKey)
        let weeklyUntil = date(forKey: Self.userResetWeeklyUntilKey)
        let filtered = CelebrationDetector.withoutUserResetRefills(
            raw,
            observedAt: current.fetchedAt,
            fiveHourSuppressUntil: fiveHourUntil,
            weeklySuppressUntil: weeklyUntil
        )

        if fiveHourUntil != nil || weeklyUntil != nil {
            clearSuppressionIfNeeded(
                window: .fiveHour,
                before: raw,
                after: filtered,
                observedAt: current.fetchedAt,
                suppressUntil: fiveHourUntil,
                key: Self.userResetFiveHourUntilKey
            )
            clearSuppressionIfNeeded(
                window: .weekly,
                before: raw,
                after: filtered,
                observedAt: current.fetchedAt,
                suppressUntil: weeklyUntil,
                key: Self.userResetWeeklyUntilKey
            )
        }

        guard !filtered.isEmpty else { return }

        let title: String
        let body: String
        if filtered.contains(.fiveHour) && filtered.contains(.weekly) {
            title = "Codex allowance refilled"
            body = "Your five-hour and weekly windows refilled before their scheduled reset."
        } else if filtered.contains(.fiveHour) {
            title = "5-hour Codex allowance refilled"
            body = "Your five-hour window refilled before its scheduled reset."
        } else {
            title = "Weekly Codex allowance refilled"
            body = "Your weekly window refilled before its scheduled reset."
        }

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        try? await center.add(
            UNNotificationRequest(
                identifier: NotificationDeduplication.refillIdentifier(
                    mask: filtered,
                    fetchedAt: current.fetchedAt
                ),
                content: content,
                trigger: nil
            )
        )
    }

    private func processCreditIncrease(
        _ credits: ResetCreditsSnapshot,
        enabled: Bool
    ) async {
        let count = credits.availableCount
        guard defaults.object(forKey: Self.knownCreditCountKey) != nil else {
            defaults.set(count, forKey: Self.knownCreditCountKey)
            return
        }
        let previous = defaults.integer(forKey: Self.knownCreditCountKey)
        defaults.set(count, forKey: Self.knownCreditCountKey)
        let added = CelebrationDetector.resetCreditsAdded(previous: previous, current: count)
        guard enabled, added > 0 else { return }

        let content = UNMutableNotificationContent()
        content.title = added == 1 ? "Codex reset credit added" : "Codex reset credits added"
        content.body = added == 1
            ? "One Codex reset credit was added. You now have \(count)."
            : "\(added) Codex reset credits were added. You now have \(count)."
        content.sound = .default
        content.userInfo = [NotificationDeduplication.routeUserInfoKey: "reset"]
        try? await center.add(
            UNNotificationRequest(
                identifier: NotificationDeduplication.creditIdentifier(
                    fetchedAt: credits.fetchedAt,
                    count: count
                ),
                content: content,
                trigger: nil
            )
        )
    }

    private func processCreditExpiryReminders(
        credits: ResetCreditsSnapshot,
        settings: AppSettings,
        now: Date
    ) async {
        pruneAnnouncedExpiryTokens(using: credits)

        guard settings.creditExpiryRemindersEnabled else {
            await removeExpiryRequests(includeDelivered: false)
            return
        }

        let leads = CreditExpiryReminder.leadIntervals(
            minutes: settings.effectiveCreditExpiryLeadMinutes
        )
        let planned = CreditExpiryReminder.plan(
            credits: credits.credits,
            leadTimes: leads,
            now: now
        )
        let announced = announcedExpiryTokens()
        var keeping: Set<String> = []

        for reminder in planned {
            let identifier = NotificationDeduplication.creditExpiryIdentifier(token: reminder.token)
            keeping.insert(identifier)
            guard !announced.contains(reminder.token) else { continue }

            // If the trigger is already past but the credit is still valid, deliver soon.
            let fireDate = max(now.addingTimeInterval(1), reminder.triggerAt)
            guard fireDate < reminder.expiresAt else { continue }

            let content = UNMutableNotificationContent()
            content.title = "Codex reset credit expires soon"
            content.body = "One reset credit expires \(UsageFormat.absolute(reminder.expiresAt, relativeTo: now)) (\(UsageFormat.relative(until: reminder.expiresAt, from: now))). Use it before it expires."
            content.sound = .default
            content.categoryIdentifier = NotificationDeduplication.expiryCategoryIdentifier
            content.userInfo = [
                NotificationDeduplication.routeUserInfoKey: "reset",
                "creditId": reminder.creditId,
                "token": reminder.token
            ]

            let components = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute, .second],
                from: fireDate
            )
            try? await center.add(
                UNNotificationRequest(
                    identifier: identifier,
                    content: content,
                    trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
                )
            )
        }

        await removeObsoleteExpiryRequests(keeping: keeping)
    }

    /// Marks an expiry reminder as announced after delivery so rescheduling will not repeat it.
    public func markCreditExpiryAnnounced(token: String) {
        var tokens = announcedExpiryTokens()
        tokens.insert(token)
        defaults.set(Array(tokens), forKey: Self.creditExpiryAnnouncedKey)
    }

    private func pruneAnnouncedExpiryTokens(using credits: ResetCreditsSnapshot) {
        let availableIds = Set(
            credits.credits.filter(\.isAvailable).map(\.id)
        )
        let activeExpiryTimestamps = Set(
            credits.credits.compactMap { credit -> Int? in
                guard credit.isAvailable, let expiresAt = credit.expiresAt else { return nil }
                return Int(expiresAt.timeIntervalSince1970)
            }
        )
        let pruned = announcedExpiryTokens().filter { token in
            // token format: creditId:expiresAt:lead
            let parts = token.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
            guard parts.count == 3 else { return false }
            let creditId = String(parts[0])
            let expires = Int(parts[1]) ?? -1
            return availableIds.contains(creditId) && activeExpiryTimestamps.contains(expires)
        }
        defaults.set(Array(pruned), forKey: Self.creditExpiryAnnouncedKey)
    }

    private func announcedExpiryTokens() -> Set<String> {
        Set(defaults.stringArray(forKey: Self.creditExpiryAnnouncedKey) ?? [])
    }

    private func date(forKey key: String) -> Date? {
        guard defaults.object(forKey: key) != nil else { return nil }
        return Date(timeIntervalSince1970: defaults.double(forKey: key))
    }

    private func clearSuppressionIfNeeded(
        window: CelebrationDetector.RefillMask,
        before: CelebrationDetector.RefillMask,
        after: CelebrationDetector.RefillMask,
        observedAt: Date,
        suppressUntil: Date?,
        key: String
    ) {
        guard let suppressUntil else { return }
        let shouldClear = observedAt >= suppressUntil
            || (before.contains(window) && !after.contains(window))
        if shouldClear {
            defaults.removeObject(forKey: key)
        }
    }

    private func removeObsoleteWindowRequests(
        keepingLow lowIdentifiers: Set<String>,
        keepingReset resetIdentifiers: Set<String>
    ) async {
        let pending = await center.pendingNotificationRequests()
        let obsoletePending = NotificationDeduplication.obsoleteWindowIdentifiers(
            among: pending.map(\.identifier),
            keepingLow: lowIdentifiers,
            keepingReset: resetIdentifiers
        )
        if !obsoletePending.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: obsoletePending)
        }

        let delivered = await center.deliveredNotifications()
        let obsoleteDelivered = NotificationDeduplication.obsoleteWindowIdentifiers(
            among: delivered.map(\.request.identifier),
            keepingLow: lowIdentifiers,
            keepingReset: resetIdentifiers
        )
        if !obsoleteDelivered.isEmpty {
            center.removeDeliveredNotifications(withIdentifiers: obsoleteDelivered)
        }
    }

    private func removeObsoleteExpiryRequests(keeping: Set<String>) async {
        let pending = await center.pendingNotificationRequests()
        let obsolete = NotificationDeduplication.obsoleteExpiryIdentifiers(
            among: pending.map(\.identifier),
            keeping: keeping
        )
        if !obsolete.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: obsolete)
        }
    }

    private func removeExpiryRequests(includeDelivered: Bool) async {
        let pending = await center.pendingNotificationRequests()
        let identifiers = pending.map(\.identifier).filter {
            $0.hasPrefix(NotificationDeduplication.expiryIdentifierPrefix)
        }
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        if includeDelivered {
            let delivered = await center.deliveredNotifications()
            center.removeDeliveredNotifications(withIdentifiers: delivered.map(\.request.identifier).filter {
                $0.hasPrefix(NotificationDeduplication.expiryIdentifierPrefix)
            })
        }
    }

    private func removeMeterRequests(includeDelivered: Bool = false) async {
        let pending = await center.pendingNotificationRequests()
        let identifiers = pending.map(\.identifier).filter {
            $0.hasPrefix(NotificationDeduplication.identifierPrefix)
        }
        center.removePendingNotificationRequests(withIdentifiers: identifiers)

        if includeDelivered {
            let delivered = await center.deliveredNotifications()
            center.removeDeliveredNotifications(withIdentifiers: delivered.map(\.request.identifier).filter {
                $0.hasPrefix(NotificationDeduplication.identifierPrefix)
            })
        }
    }

    private func registerCategoriesIfNeeded() async {
        let useReset = UNNotificationAction(
            identifier: NotificationDeduplication.useResetActionIdentifier,
            title: "Use reset",
            options: [.foreground]
        )
        let category = UNNotificationCategory(
            identifier: NotificationDeduplication.expiryCategoryIdentifier,
            actions: [useReset],
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories([category])
        defaults.set(true, forKey: Self.categoriesRegisteredKey)
    }
}
