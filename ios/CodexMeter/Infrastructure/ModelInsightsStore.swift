import CodexMeterCore
import Foundation

/// What the new-model and model-speed checks remember between launches.
nonisolated struct ModelInsightsSnapshot: Codable, Sendable, Equatable {
    var discovery = ModelDiscoveryState()
    /// Each maker's newest models, newest first, from the last catalog download.
    var choices: [String: [ModelSpeed.Choice]] = [:]
    var choicesAt: [String: Date] = [:]
    /// The latest endpoint figures per model id, and when they were fetched.
    var latest: [String: ModelSpeed] = [:]
    var latestAt: [String: Date] = [:]
    /// Earlier samples per model id, oldest first.
    var history: [String: [ModelSpeed.Sample]] = [:]
    /// Speed check attempts and failures per provider, for backoff.
    var speedAttemptAt: [String: Date] = [:]
    var speedFailures: [String: Int] = [:]
}

/// New models and model slowdowns for the Priority tab, from OpenRouter's public catalog and
/// endpoint metadata only: anonymous GETs with no key, cookie, account data or prompt. A port
/// of Android's `ModelDiscoveryStore` and `ModelSpeedStore` and the desktop `ModelDiscoveryStore`.
actor ModelInsightsStore {
    static let shared = ModelInsightsStore()
    static let fileName = "model-insights-v1.json"
    private static let choicesTTL: TimeInterval = 86_400
    private static let maxBackoff: TimeInterval = 2 * 3_600
    private static let maxEndpointBytes = 4 * 1_024 * 1_024

    private let fileURL: URL
    private let session: URLSession
    private var memory: ModelInsightsSnapshot?

    init(fileURL: URL? = nil, session: URLSession? = nil) {
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            self.fileURL = base.appendingPathComponent("CodexMeter", isDirectory: true)
                .appendingPathComponent(Self.fileName)
        }
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

    func load() -> ModelInsightsSnapshot {
        if let memory { return memory }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let loaded = (try? Data(contentsOf: fileURL)).flatMap { try? decoder.decode(ModelInsightsSnapshot.self, from: $0) }
            ?? ModelInsightsSnapshot()
        memory = loaded
        return loaded
    }

    private func save(_ snapshot: ModelInsightsSnapshot) {
        memory = snapshot
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(snapshot) else { return }
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: [.atomic])
    }

    /// Checks the catalog when due (every 12 hours) and returns the models it newly confirmed.
    func refreshDiscoveryIfDue(now: Date = Date()) async -> [SeenModel] {
        var snapshot = load()
        if let last = snapshot.discovery.lastFetch, now >= last, now.timeIntervalSince(last) < ModelDiscovery.fetchInterval {
            return []
        }
        // Counted as an attempt even if it fails, so a dead network isn't retried every minute.
        snapshot.discovery.lastFetch = now
        save(snapshot)
        guard let body = await get(ModelDiscovery.catalogURL, maxBytes: ModelDiscovery.maxCatalogBytes) else {
            DiagnosticLog.warn("models", "catalog_failed")
            return []
        }
        let (next, confirmed) = ModelDiscovery.observe(snapshot.discovery, catalog: ModelDiscovery.parseCatalog(body), now: now)
        snapshot = load()
        snapshot.discovery = next
        // The speed check's model lists come from the same download.
        for provider in [UsageProvider.codex, .claudeCode, .grok] {
            guard let author = ModelSpeed.author(for: provider) else { continue }
            let fresh = ModelSpeed.models(by: author, in: body)
            if !fresh.isEmpty {
                snapshot.choices[author] = fresh
                snapshot.choicesAt[author] = now
            }
        }
        save(snapshot)
        return confirmed
    }

    /// Fetches the followed models' figures for each provider when due (every 45 minutes, with
    /// backoff after failures) and adds them to their history.
    func refreshSpeedIfDue(providers: [UsageProvider], now: Date = Date()) async {
        for provider in providers {
            guard let author = ModelSpeed.author(for: provider) else { continue }
            var snapshot = load()
            let failures = snapshot.speedFailures[provider.rawValue] ?? 0
            let wait = failures == 0 ? ModelSpeed.refreshInterval
                : min(Self.maxBackoff, 15 * 60 * pow(2, Double(min(failures - 1, 4))))
            if let last = snapshot.speedAttemptAt[provider.rawValue], now >= last, now.timeIntervalSince(last) < wait {
                continue
            }
            snapshot.speedAttemptAt[provider.rawValue] = now
            save(snapshot)

            var choices = snapshot.choices[author] ?? []
            let stale = snapshot.choicesAt[author].map { now.timeIntervalSince($0) > Self.choicesTTL } ?? true
            if choices.isEmpty || stale, let body = await get(ModelSpeed.modelsURL, maxBytes: ModelDiscovery.maxCatalogBytes) {
                let fresh = ModelSpeed.models(by: author, in: body)
                if !fresh.isEmpty {
                    choices = fresh
                    snapshot = load()
                    snapshot.choices[author] = fresh
                    snapshot.choicesAt[author] = now
                    save(snapshot)
                }
            }
            var changed = false
            var failed = choices.isEmpty
            for choice in choices.prefix(ModelSpeed.tracked) {
                guard let url = ModelSpeed.endpointsURL(choice.id) else { continue }
                guard let body = await get(url, maxBytes: Self.maxEndpointBytes), let speed = ModelSpeed.parse(body) else {
                    failed = true
                    continue
                }
                snapshot = load()
                let featured = speed.featured
                var history = snapshot.history[choice.id] ?? []
                history.append(ModelSpeed.Sample(at: now, throughput: featured?.throughput, latencyMs: featured?.latencyMs))
                snapshot.history[choice.id] = Array(history.suffix(ModelSpeed.historyLimit))
                snapshot.latest[choice.id] = speed
                snapshot.latestAt[choice.id] = now
                save(snapshot)
                changed = true
            }
            snapshot = load()
            snapshot.speedFailures[provider.rawValue] = failed && !changed ? failures + 1 : 0
            save(snapshot)
        }
    }

    /// Significant slowdowns of each provider's followed models right now; empty when normal.
    func slowdowns(providers: [UsageProvider], now: Date = Date()) -> [PriorityItem] {
        let snapshot = load()
        var found: [(Double, PriorityItem)] = []
        for provider in providers {
            guard let author = ModelSpeed.author(for: provider) else { continue }
            for choice in (snapshot.choices[author] ?? []).prefix(ModelSpeed.tracked) {
                guard let speed = snapshot.latest[choice.id], let at = snapshot.latestAt[choice.id],
                      now.timeIntervalSince(at) <= 2 * ModelSpeed.refreshInterval else { continue }
                // The latest sample is the current one; the baseline is everything before it.
                let earlier = Array((snapshot.history[choice.id] ?? []).dropLast())
                if let slow = ModelSpeed.slowdown(speed, earlier: earlier) {
                    found.append((slow.factor, PriorityItem(provider: provider, title: slow.title, detail: slow.detail, at: at)))
                }
            }
        }
        return found.sorted { $0.0 > $1.0 }.map(\.1)
    }

    func discoveryState() -> ModelDiscoveryState {
        load().discovery
    }

    /// An anonymous GET to openrouter.ai: no key, cookie or account header is ever attached.
    private func get(_ url: URL, maxBytes: Int) async -> Data? {
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  http.url?.host == "openrouter.ai", data.count <= maxBytes else { return nil }
            return data
        } catch {
            return nil
        }
    }
}
