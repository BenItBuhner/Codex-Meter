import CodexMeterCore
import Foundation

/// One usage window as the menu bar and sidebar show it.
struct MacMeter: Identifiable, Equatable {
    let id: String
    let title: String
    let window: UsageWindow

    var remainingPercent: Int { window.remainingPercent }
}

/// What the Mac-only surfaces (sidebar rows, menu bar label and panel) need to know about one
/// provider. The dashboards themselves are the shared iOS views.
struct MacProviderStatus: Equatable {
    let provider: UsageProvider
    let isSignedIn: Bool
    let isDemo: Bool
    let isRefreshing: Bool
    let plan: String
    let email: String
    let error: String?
    let snapshot: UsageSnapshot?

    /// 5-hour, then weekly or monthly, in the order the dashboards show them.
    var meters: [MacMeter] {
        guard let snapshot else { return [] }
        var meters: [MacMeter] = []
        if let window = snapshot.fiveHour {
            meters.append(MacMeter(id: "five-hour", title: "5-hour", window: window))
        }
        if let window = snapshot.weekly {
            meters.append(MacMeter(id: "weekly", title: "Weekly", window: window))
        }
        if let window = snapshot.monthly {
            meters.append(MacMeter(id: "monthly", title: "Monthly", window: window))
        }
        return meters
    }

    /// The meter closest to running out: what the menu bar shows.
    var tightestMeter: MacMeter? {
        meters.min { $0.remainingPercent < $1.remainingPercent }
    }

    var hasData: Bool { snapshot != nil }

    /// "62% left · 5-hour", "Not signed in", or "Waiting for data".
    var summary: String {
        if let meter = tightestMeter {
            return "\(meter.remainingPercent)% left · \(meter.title)"
        }
        if !isSignedIn && !isDemo {
            return "Not signed in"
        }
        if let error {
            return error
        }
        return isRefreshing ? "Refreshing…" : "Waiting for data"
    }
}

/// Remaining-allowance thresholds shared by the menu bar symbol and meter tints.
enum MacMeterLevel: Equatable {
    case healthy
    case low
    case critical

    init(remainingPercent: Int) {
        switch remainingPercent {
        case ..<10: self = .critical
        case ..<25: self = .low
        default: self = .healthy
        }
    }

    /// An SF Symbols gauge whose needle tracks the remaining allowance.
    static func gaugeSymbol(remainingPercent: Int?) -> String {
        guard let remaining = remainingPercent else { return "gauge.with.dots.needle.bottom.50percent" }
        switch remaining {
        case ..<17: return "gauge.with.dots.needle.0percent"
        case ..<42: return "gauge.with.dots.needle.33percent"
        case ..<59: return "gauge.with.dots.needle.50percent"
        case ..<84: return "gauge.with.dots.needle.67percent"
        default: return "gauge.with.dots.needle.100percent"
        }
    }
}

extension AppModel {
    /// Status for any provider. Written without an exhaustive `switch` so providers added to
    /// `UsageProvider` later still compile here; they show as signed out until wired up.
    func macStatus(for provider: UsageProvider) -> MacProviderStatus {
        if provider == .codex {
            return MacProviderStatus(
                provider: provider,
                isSignedIn: mode == .live,
                isDemo: mode == .demo,
                isRefreshing: isRefreshing,
                plan: accountPlan.isEmpty ? (usage.map { UsageFormat.planLabel($0.planType) } ?? "") : accountPlan,
                email: accountEmail,
                error: visibleError,
                snapshot: mode == .signedOut ? nil : usage
            )
        }
        if provider == .claudeCode {
            return MacProviderStatus(
                provider: provider,
                isSignedIn: claude.phase == .live,
                isDemo: claude.phase == .demo,
                isRefreshing: claude.isRefreshing,
                plan: claude.accountPlan,
                email: claude.accountEmail,
                error: claude.visibleError,
                snapshot: claude.phase == .signedOut ? nil : claude.usage
            )
        }
        if provider == .cursor {
            return MacProviderStatus(
                provider: provider,
                isSignedIn: cursor.phase == .live,
                isDemo: cursor.phase == .demo,
                isRefreshing: cursor.isRefreshing,
                plan: cursor.accountPlan,
                email: cursor.accountEmail,
                error: cursor.visibleError,
                snapshot: cursor.phase == .signedOut ? nil : cursor.usage
            )
        }
        return MacProviderStatus(
            provider: provider,
            isSignedIn: isSignedIn(provider),
            isDemo: false,
            isRefreshing: false,
            plan: accountPlan(for: provider),
            email: accountEmail(for: provider),
            error: nil,
            snapshot: nil
        )
    }

    /// Refreshes one provider the way its dashboard's Refresh button does.
    func macRefresh(_ provider: UsageProvider) async {
        if provider == .codex {
            await refresh()
        } else if provider == .claudeCode {
            await claude.refresh()
        } else if provider == .cursor {
            await cursor.refresh()
        }
    }

    /// Refreshes every enabled provider that has an account or demo data.
    func macRefreshAll() async {
        for provider in enabledProviders where macStatus(for: provider).isSignedIn || macStatus(for: provider).isDemo {
            await macRefresh(provider)
        }
    }

    /// Opening the menu bar panel counts as opening each tab: Claude and Cursor re-check only
    /// when their data is older than five minutes, as on iOS.
    func macPanelOpened() async {
        await startIfNeeded()
        for provider in enabledProviders {
            if provider == .claudeCode {
                await claude.tabOpened()
            } else if provider == .cursor {
                await cursor.tabOpened()
            }
        }
    }

    /// Starts a provider's sign-in in the main window.
    func macBeginSignIn(_ provider: UsageProvider) {
        select(provider: provider)
        if provider == .codex {
            isShowingSignIn = true
        } else if provider == .claudeCode {
            claude.isShowingSignIn = true
        } else if provider == .cursor {
            cursor.isShowingSignIn = true
        }
    }

    var macIsRefreshingAny: Bool {
        enabledProviders.contains { macStatus(for: $0).isRefreshing }
    }
}
