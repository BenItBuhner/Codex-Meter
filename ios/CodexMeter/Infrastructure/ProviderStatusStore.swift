import CodexMeterCore
import Foundation

/// Polling state for one feed URL (Cursor and Grok share status.x.ai).
nonisolated struct StatusFeedState: Codable, Sendable, Equatable {
    var lastAttempt: Date?
    var failures = 0
    var retryAfterUntil: Date?
    /// A random number in [0, 1) picked per feed, so phones don't hit a feed together.
    var jitter = 0.0
}

nonisolated struct ProviderStatusSnapshot: Codable, Sendable, Equatable {
    var feeds: [String: StatusFeedState] = [:]
    var statuses: [String: ProviderStatus] = [:]
}

/// Reads each signed-in provider's official status feed when `StatusPollPolicy` allows and keeps
/// the result on disk, like Android's `ProviderStatusStore` and the desktop one. Only first-party
/// feeds on `ProviderStatusSources.hosts` are asked, anonymously, with no account data. A failed
/// fetch keeps the last status until it is older than the longest backoff, then reads "unknown",
/// never "down".
actor ProviderStatusStore {
    static let shared = ProviderStatusStore()
    static let fileName = "provider-status-v1.json"
    static let maxFeedBytes = 2 * 1_024 * 1_024

    private let fileURL: URL
    private let session: URLSession
    private var memory: ProviderStatusSnapshot?

    init(fileURL: URL? = nil, session: URLSession? = nil) {
        self.fileURL = fileURL ?? AppCacheStore.defaultDirectoryURL().appendingPathComponent(Self.fileName)
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.httpCookieAcceptPolicy = .never
            configuration.httpShouldSetCookies = false
            configuration.urlCache = nil
            configuration.timeoutIntervalForRequest = 15
            self.session = URLSession(configuration: configuration)
        }
    }

    private func load() -> ProviderStatusSnapshot {
        if let memory { return memory }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let loaded = (try? Data(contentsOf: fileURL)).flatMap { try? decoder.decode(ProviderStatusSnapshot.self, from: $0) }
            ?? ProviderStatusSnapshot()
        memory = loaded
        return loaded
    }

    private func save(_ snapshot: ProviderStatusSnapshot) {
        memory = snapshot
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(snapshot) else { return }
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: [.atomic])
    }

    /// The providers' last statuses, without ones too old to trust.
    func statuses(for providers: [UsageProvider], now: Date = Date()) -> [UsageProvider: ProviderStatus] {
        let snapshot = load()
        var out: [UsageProvider: ProviderStatus] = [:]
        for provider in providers {
            guard let status = snapshot.statuses[provider.rawValue],
                  now.timeIntervalSince(status.checkedAt) <= StatusPollPolicy.maxBackoff else { continue }
            out[provider] = status
        }
        return out
    }

    /// Forgets a provider's status (after signing out).
    func forget(_ provider: UsageProvider) {
        var snapshot = load()
        guard snapshot.statuses.removeValue(forKey: provider.rawValue) != nil else { return }
        save(snapshot)
    }

    /// Asks each due feed once for `providers`. Returns true when a shown status changed.
    @discardableResult
    func refresh(_ providers: [UsageProvider], foreground: Bool, now: Date = Date()) async -> Bool {
        var changed = false
        var byFeed: [String: [(UsageProvider, ProviderStatusSources.Source)]] = [:]
        var feedOrder: [String] = []
        for provider in providers {
            guard let source = ProviderStatusSources.source(for: provider) else { continue }
            if byFeed[source.feedURL] == nil { feedOrder.append(source.feedURL) }
            byFeed[source.feedURL, default: []].append((provider, source))
        }
        for url in feedOrder {
            guard let group = byFeed[url] else { continue }
            var snapshot = load()
            let feed = snapshot.feeds[url] ?? StatusFeedState(jitter: Double.random(in: 0..<1))
            guard StatusPollPolicy.shouldFetch(now: now, lastAttempt: feed.lastAttempt, failures: feed.failures,
                                               retryAfterUntil: feed.retryAfterUntil, foreground: foreground,
                                               random01: feed.jitter) else { continue }
            // Counted before the request, so a dead network isn't asked again on every refresh.
            snapshot.feeds[url] = StatusFeedState(lastAttempt: now, failures: feed.failures,
                                                  retryAfterUntil: feed.retryAfterUntil, jitter: feed.jitter)
            save(snapshot)
            let (body, retryAfter) = await fetch(url, now: now)
            snapshot = load()
            snapshot.feeds[url] = StatusFeedState(lastAttempt: now, failures: body == nil ? min(feed.failures + 1, 10) : 0,
                                                  retryAfterUntil: retryAfter, jitter: Double.random(in: 0..<1))
            for (provider, source) in group {
                let next: ProviderStatus?
                if let body {
                    next = ProviderStatusSources.parse(provider, body: body, now: now)
                } else if let previous = snapshot.statuses[provider.rawValue],
                          now.timeIntervalSince(previous.checkedAt) < StatusPollPolicy.maxBackoff {
                    continue
                } else {
                    next = ProviderStatus.unknown(provider.rawValue, pageURL: source.pageURL, now: now)
                }
                guard let next else { continue }
                let before = snapshot.statuses[provider.rawValue]
                changed = changed || before?.state != next.state
                    || before?.headline(provider.displayName) != next.headline(provider.displayName)
                    || before?.detail != next.detail
                snapshot.statuses[provider.rawValue] = next
            }
            save(snapshot)
            if body == nil {
                DiagnosticLog.warn("status", "feed_failed", details: ["host": URL(string: url)?.host ?? ""])
            }
        }
        return changed
    }

    /// An anonymous GET to an official status host; nil on any failure, redirect elsewhere or
    /// oversized body.
    private func fetch(_ url: String, now: Date) async -> (String?, Date?) {
        guard let address = URL(string: url) else { return (nil, nil) }
        var request = URLRequest(url: address)
        request.setValue("application/json, application/rss+xml, text/xml", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return (nil, nil) }
            let retry = StatusPollPolicy.retryAfterUntil(http.value(forHTTPHeaderField: "Retry-After"), now: now)
            guard (200..<300).contains(http.statusCode), ProviderStatusSources.hosts.contains(http.url?.host ?? ""),
                  data.count <= Self.maxFeedBytes else { return (nil, retry) }
            return (String(data: data, encoding: .utf8), retry)
        } catch {
            return (nil, nil)
        }
    }
}
