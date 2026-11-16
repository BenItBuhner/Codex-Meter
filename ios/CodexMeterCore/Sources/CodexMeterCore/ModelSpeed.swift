import Foundation

/// Public speed figures for one model, from OpenRouter's endpoint metadata
/// (`GET /api/v1/models/{author}/{slug}/endpoints`, no key needed). A port of Android's
/// `ModelSpeed`. This is a reference for how a model runs across OpenRouter's providers, not the
/// speed of the user's own subscription: the app sends no test prompts and never labels these
/// numbers as subscription performance. OpenRouter leaves the 30-minute figures null when it has
/// too few samples; those are missing (`nil`), never zero, and never shown. Only a significant
/// slowdown against the model's own recent history is ever surfaced (`slowdown`).
public struct ModelSpeed: Codable, Sendable, Equatable {
    /// One OpenRouter endpoint (a provider serving the model).
    public struct Endpoint: Codable, Sendable, Equatable {
        public let provider: String
        public let tag: String
        /// Median tokens per second over the last 30 minutes.
        public let throughput: Double?
        /// Median time to first token in milliseconds over the last 30 minutes.
        public let latencyMs: Double?
        /// Share of successful requests over the last 30 minutes, 0 to 100.
        public let uptime30m: Double?
    }

    /// One earlier sample of the featured endpoint.
    public struct Sample: Codable, Sendable, Equatable {
        public let at: Date
        public let throughput: Double?
        public let latencyMs: Double?

        public init(at: Date, throughput: Double?, latencyMs: Double?) {
            self.at = at
            self.throughput = throughput
            self.latencyMs = latencyMs
        }
    }

    /// A model running much slower than it usually does (public OpenRouter figures).
    public struct Slowdown: Sendable, Equatable {
        public let modelName: String
        public let title: String
        public let detail: String
        /// How much worse than usual, for ordering: 2 means twice as slow.
        public let factor: Double
    }

    /// A model the speed check can follow.
    public struct Choice: Codable, Sendable, Equatable {
        public let id: String
        public let name: String

        public init(id: String, name: String) {
            self.id = id
            self.name = name
        }
    }

    public static let modelsURL = URL(string: "https://openrouter.ai/api/v1/models")!
    /// Endpoint figures cover the last 30 minutes; asking every 45 is plenty.
    public static let refreshInterval: TimeInterval = 45 * 60
    /// How many of each maker's newest models are followed.
    public static let tracked = 3
    /// Samples kept per model (about a day and a half at one every 45 minutes).
    public static let historyLimit = 48
    /// Samples needed before "usual" means anything.
    static let minBaseline = 6
    /// Throughput under half its usual median, or first-token latency over twice its usual.
    static let slowFactor = 2.0
    /// Uptime under this over the last 30 minutes counts on its own.
    static let uptimeFloor = 90.0

    public let modelID: String
    public let modelName: String
    public let endpoints: [Endpoint]

    public init(modelID: String, modelName: String, endpoints: [Endpoint]) {
        self.modelID = modelID
        self.modelName = modelName.isEmpty ? modelID : modelName
        self.endpoints = endpoints
    }

    /// Parses an endpoints response; `nil` when it isn't one.
    public static func parse(_ data: Data) -> ModelSpeed? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let body = root["data"] as? [String: Any] else { return nil }
        let endpoints = ((body["endpoints"] as? [Any]) ?? []).compactMap { item -> Endpoint? in
            guard let endpoint = item as? [String: Any] else { return nil }
            return Endpoint(
                provider: (endpoint["provider_name"] as? String) ?? "",
                tag: (endpoint["tag"] as? String) ?? "",
                throughput: metric(endpoint["throughput_last_30m"]),
                latencyMs: metric(endpoint["latency_last_30m"]),
                uptime30m: number(endpoint["uptime_last_30m"])
            )
        }
        return ModelSpeed(modelID: (body["id"] as? String) ?? "",
                          modelName: shortName((body["name"] as? String) ?? ""),
                          endpoints: endpoints)
    }

    /// "Anthropic: Claude Opus 5.5" to "Claude Opus 5.5".
    static func shortName(_ name: String) -> String {
        guard let range = name.range(of: ": "), range.lowerBound > name.startIndex else { return name }
        return String(name[range.upperBound...])
    }

    /// A 30-minute metric: a number, or an object of percentiles (the median, p50, is used).
    static func metric(_ value: Any?) -> Double? {
        if let object = value as? [String: Any] {
            return number(object["p50"] ?? object["median"])
        }
        return number(value)
    }

    static func number(_ value: Any?) -> Double? {
        // Numbers only: a string or a JSON boolean is not a figure.
        guard value is NSNumber, let double = JSONSupport.double(value) else { return nil }
        return double >= 0 && double.isFinite ? double : nil
    }

    /// The endpoint whose figures are shown: the maker's own endpoint when it has any data,
    /// otherwise the endpoint with the most metrics. `nil` when no endpoint has any.
    public var featured: Endpoint? {
        let author = modelID.split(separator: "/").first.map(String.init) ?? ""
        var best: Endpoint?
        var bestScore = 0
        for endpoint in endpoints {
            var score = (endpoint.throughput == nil ? 0 : 2) + (endpoint.latencyMs == nil ? 0 : 2)
                + (endpoint.uptime30m == nil ? 0 : 1)
            guard score > 0 else { continue }
            if Self.isFirstParty(author, endpoint), !endpoint.tag.contains("/") {
                score += 10
            }
            if score > bestScore {
                best = endpoint
                bestScore = score
            }
        }
        return best
    }

    static func isFirstParty(_ author: String, _ endpoint: Endpoint) -> Bool {
        let provider = endpoint.provider.lowercased()
        switch author {
        case "anthropic": return provider == "anthropic"
        case "openai": return provider == "openai"
        case "x-ai": return provider == "xai"
        default: return false
        }
    }

    /// A significant slowdown of `now` against `earlier` samples, or `nil` when the figures are
    /// normal, missing or there is too little history to tell.
    public static func slowdown(_ now: ModelSpeed?, earlier: [Sample]) -> Slowdown? {
        guard let now, let endpoint = now.featured else { return nil }
        let via = " (OpenRouter, via \(endpoint.provider), last 30 minutes; public figures, not your subscription)"
        if let uptime = endpoint.uptime30m, uptime < uptimeFloor {
            return Slowdown(modelName: now.modelName, title: "\(now.modelName) is failing more often than usual",
                            detail: String(format: "%.0f%% of requests succeeded", uptime) + via,
                            factor: 100 / max(1, uptime))
        }
        let usualTps = median(earlier.compactMap(\.throughput))
        let usualLatency = median(earlier.compactMap(\.latencyMs))
        var tpsFactor = 0.0
        if let tps = endpoint.throughput, tps > 0, let usualTps { tpsFactor = usualTps / tps }
        var latencyFactor = 0.0
        if let latency = endpoint.latencyMs, let usualLatency, usualLatency > 0 { latencyFactor = latency / usualLatency }
        if tpsFactor >= slowFactor, tpsFactor >= latencyFactor, let tps = endpoint.throughput, let usualTps {
            return Slowdown(modelName: now.modelName, title: "\(now.modelName) is running slower than usual",
                            detail: "\(Int(tps.rounded())) tokens/s, usually \(Int(usualTps.rounded()))" + via,
                            factor: tpsFactor)
        }
        if latencyFactor >= slowFactor, let latency = endpoint.latencyMs, let usualLatency {
            return Slowdown(modelName: now.modelName, title: "\(now.modelName) is slow to start responding",
                            detail: "\(seconds(latency)) to the first token, usually \(seconds(usualLatency))" + via,
                            factor: latencyFactor)
        }
        return nil
    }

    /// The median of positive values, or `nil` under `minBaseline` of them.
    static func median(_ values: [Double]) -> Double? {
        let sorted = values.filter { $0 > 0 }.sorted()
        guard sorted.count >= minBaseline else { return nil }
        let count = sorted.count
        return count % 2 == 1 ? sorted[count / 2] : (sorted[count / 2 - 1] + sorted[count / 2]) / 2
    }

    static func seconds(_ ms: Double) -> String {
        ms < 1_000 ? "\(Int(ms.rounded())) ms" : String(format: "%.1f s", ms / 1_000)
    }

    /// The models by `author` ("anthropic", "openai", "x-ai") in OpenRouter's model list, newest
    /// first, without variants or aliases.
    public static func models(by author: String, in data: Data, limit: Int = 30) -> [Choice] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = root["data"] as? [Any] else { return [] }
        var found: [(Double, Choice)] = []
        for item in list {
            guard let model = item as? [String: Any], let id = model["id"] as? String,
                  id.hasPrefix(author + "/"), !id.contains(":") else { continue }
            let created = (model["created"] as? NSNumber)?.doubleValue ?? 0
            found.append((created, Choice(id: id, name: shortName((model["name"] as? String) ?? id))))
        }
        return found.sorted { $0.0 > $1.0 }.prefix(limit).map(\.1)
    }

    /// OpenRouter's author slug for a provider whose models it follows, or `nil`.
    public static func author(for provider: UsageProvider) -> String? {
        switch provider {
        case .codex: "openai"
        case .claudeCode: "anthropic"
        case .grok: "x-ai"
        default: nil
        }
    }

    /// True for an id shaped like "author/model", safe to put in the endpoints path.
    public static func isSafeModelID(_ id: String) -> Bool {
        id.range(of: "^[a-z0-9-]+/[a-z0-9._-]+$", options: .regularExpression) != nil
    }

    public static func endpointsURL(_ modelID: String) -> URL? {
        guard isSafeModelID(modelID) else { return nil }
        return URL(string: "https://openrouter.ai/api/v1/models/\(modelID)/endpoints")
    }
}
