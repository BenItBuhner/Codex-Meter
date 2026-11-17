import Foundation

/// One model the discovery has seen in OpenRouter's public catalog.
public struct SeenModel: Codable, Sendable, Equatable {
    public var id: String
    public var name: String
    public var firstSeen: Date
    /// Complete catalog responses it has appeared in (counting stops at 2).
    public var sightings: Int
    /// When it counted as new: seen twice, recently listed, not a variant. `nil` for baseline or
    /// old models.
    public var confirmedAt: Date?

    public init(id: String, name: String, firstSeen: Date, sightings: Int, confirmedAt: Date? = nil) {
        self.id = id
        self.name = name
        self.firstSeen = firstSeen
        self.sightings = sightings
        self.confirmedAt = confirmedAt
    }
}

/// What the new-model check remembers between launches.
public struct ModelDiscoveryState: Codable, Sendable, Equatable {
    public var baseline = false
    public var lastFetch: Date?
    public var models: [String: SeenModel] = [:]

    public init() {}
}

/// One entry of OpenRouter's `/api/v1/models` list.
public struct CatalogModel: Sendable, Equatable {
    public let id: String
    public let name: String
    public let created: Date?
}

/// "New models" for the Priority tab and its notification: a port of the Android and desktop
/// `ModelDiscovery`, limited to OpenRouter's public catalog (no key). A model shows only when an
/// explicit allowlist ties its maker to a provider the user is signed in to (Codex to OpenAI,
/// Claude Code to Anthropic, ...), and each row says which one. Tools that offer many makers'
/// models, like Cursor or Copilot, add none. The first complete catalog is a silent baseline, a
/// model must appear in two complete responses, variants (":free", ":beta") and long-listed
/// models are recorded but not announced, and a failed or partial fetch changes nothing.
public enum ModelDiscovery {
    public static let catalogURL = URL(string: "https://openrouter.ai/api/v1/models")!
    public static let maxCatalogBytes = 12 * 1_024 * 1_024
    /// A response with fewer models than this is treated as partial and ignored.
    public static let minCompleteCatalog = 50
    public static let fetchInterval: TimeInterval = 12 * 3_600
    public static let recentlyListed: TimeInterval = 30 * 86_400
    public static let showFor: TimeInterval = 14 * 86_400

    public static let cardNote = "Newly listed in a public catalog, not necessarily newly released. Subscription availability not verified."

    /// OpenRouter maker slug for a provider the user signs in to; "*" for the whole catalog,
    /// `nil` for none.
    public static func maker(for provider: UsageProvider) -> String? {
        switch provider {
        case .codex: "openai"
        case .claudeCode: "anthropic"
        case .grok: "x-ai"
        case .antigravity: "google"
        case .minimax: "minimax"
        case .kimi: "moonshotai"
        case .zai: "z-ai"
        case .openrouter: "*"
        default: nil
        }
    }

    /// The catalog's models, or `nil` when the body isn't a catalog.
    public static func parseCatalog(_ data: Data) -> [CatalogModel]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = root["data"] as? [Any] else { return nil }
        var result: [CatalogModel] = []
        for item in list {
            guard let model = item as? [String: Any], let id = model["id"] as? String,
                  !id.isEmpty, id.count <= 200 else { continue }
            let name = (model["name"] as? String) ?? id
            var created: Date?
            if let unix = (model["created"] as? NSNumber)?.doubleValue, unix > 0 {
                created = Date(timeIntervalSince1970: unix)
            }
            result.append(CatalogModel(id: id, name: String(name.prefix(160)), created: created))
        }
        return result
    }

    /// Folds one catalog response into the state; returns it and the models it confirmed as new.
    public static func observe(
        _ state: ModelDiscoveryState,
        catalog: [CatalogModel]?,
        now: Date
    ) -> (state: ModelDiscoveryState, confirmed: [SeenModel]) {
        guard let catalog, catalog.count >= minCompleteCatalog else { return (state, []) }
        var next = state
        var confirmed: [SeenModel] = []
        for entry in catalog {
            guard state.baseline else {
                next.models[entry.id] = SeenModel(id: entry.id, name: entry.name, firstSeen: now, sightings: 2)
                continue
            }
            guard var seen = next.models[entry.id] else {
                next.models[entry.id] = SeenModel(id: entry.id, name: entry.name, firstSeen: now, sightings: 1)
                continue
            }
            guard seen.sightings < 2 else { continue }
            let announce = !isVariant(entry.id) && (entry.created.map { now.timeIntervalSince($0) <= recentlyListed } ?? false)
            seen.name = entry.name
            seen.sightings = 2
            seen.confirmedAt = announce ? now : nil
            next.models[entry.id] = seen
            if announce { confirmed.append(seen) }
        }
        next.baseline = true
        next.lastFetch = now
        return (next, confirmed)
    }

    /// ":free", ":beta", ":extended" and other suffixed variants of a model already listed.
    public static func isVariant(_ id: String) -> Bool {
        id.contains(":")
    }

    /// The provider a model is shown for, preferring the maker's own subscription over a
    /// whole-catalog one.
    public static func matchedBy(_ modelID: String, signedIn: [UsageProvider]) -> UsageProvider? {
        let maker = modelID.split(separator: "/", maxSplits: 1).count == 2
            ? String(modelID.split(separator: "/", maxSplits: 1)[0]) : ""
        var catalog: UsageProvider?
        for provider in signedIn {
            let scope = self.maker(for: provider)
            if scope == maker { return provider }
            if scope == "*", catalog == nil { catalog = provider }
        }
        return catalog
    }

    /// "Anthropic model, for your Claude Code" or "for your OpenRouter".
    public static func reason(_ provider: UsageProvider) -> String {
        maker(for: provider) == "*"
            ? "for your \(provider.displayName)"
            : "\(provider.vendorName) model, for your \(provider.displayName)"
    }

    /// Recently confirmed models for the user's providers, newest first, as Priority rows.
    public static func items(_ state: ModelDiscoveryState, signedIn: [UsageProvider], now: Date) -> [PriorityItem] {
        state.models.values
            .filter { model in model.confirmedAt.map { now.timeIntervalSince($0) <= showFor } ?? false }
            .sorted { ($0.confirmedAt ?? .distantPast) > ($1.confirmedAt ?? .distantPast) }
            .compactMap { model in
                guard let provider = matchedBy(model.id, signedIn: signedIn) else { return nil }
                return PriorityItem(provider: provider, title: "New on OpenRouter: \(model.name)",
                                    detail: reason(provider), at: model.confirmedAt)
            }
    }

    /// The note under the list, naming the providers it is shown for.
    public static func note(_ items: [PriorityItem]) -> String {
        var names: [String] = []
        for item in items where !names.contains(item.provider.displayName) {
            names.append(item.provider.displayName)
        }
        guard let last = names.last else { return cardNote }
        let shownFor = names.count == 1 ? last : names.dropLast().joined(separator: ", ") + " and " + last
        return "Shown for \(shownFor). Tools that offer many makers' models, like Cursor or Copilot, add none. " + cardNote
    }

    /// The notification text for newly confirmed models: (title, body, identifier).
    public static func alerts(_ confirmed: [SeenModel], signedIn: [UsageProvider]) -> [(title: String, body: String, id: String)] {
        confirmed.compactMap { model in
            guard let provider = matchedBy(model.id, signedIn: signedIn) else { return nil }
            return ("New on OpenRouter: \(model.name)",
                    reason(provider) + ". Newly listed, not necessarily newly released.",
                    "model:" + model.id)
        }
    }
}
