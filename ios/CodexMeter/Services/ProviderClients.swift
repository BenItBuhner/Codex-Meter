import CodexMeterCore
import Foundation

// Sign-in and usage routes for every provider added through Settings → Accounts → Add account.
// A port of Android's `ProviderClient` / `ProviderClients`: client ids, endpoints and headers are
// the ones each provider's own CLI or editor sends, and none of these usage routes is a
// documented public API. Codex, Claude Code and Cursor keep their own services.

// MARK: HTTP

nonisolated struct ProviderResponse: Sendable {
    let status: Int
    let body: String

    var ok: Bool { (200 ..< 300).contains(status) }
    var json: ProviderJSONObject { ProviderJSON.object(body) }
}

/// One request; headers keep insertion order. Bodies and headers are never logged because they
/// carry tokens and device codes.
nonisolated struct ProviderRequest: Sendable {
    var url: String
    var method = "GET"
    var headers: [(String, String)] = [("Accept", "application/json")]
    var body: Data?
    var contentType: String?

    static func get(_ url: String) -> Self { Self(url: url) }

    static func post(_ url: String) -> Self {
        var request = Self(url: url)
        request.method = "POST"
        return request
    }

    func header(_ name: String, _ value: String) -> Self {
        var copy = self
        copy.headers.removeAll { $0.0.caseInsensitiveCompare(name) == .orderedSame }
        copy.headers.append((name, value))
        return copy
    }

    func bearer(_ token: String) -> Self {
        header("Authorization", "Bearer \(token)")
    }

    func json(_ payload: [String: Any]) -> Self {
        var copy = self
        copy.method = "POST"
        copy.body = (try? JSONSerialization.data(withJSONObject: payload)) ?? Data("{}".utf8)
        copy.contentType = "application/json"
        return copy
    }

    func form(_ fields: [(String, String?)]) -> Self {
        var copy = self
        copy.method = "POST"
        copy.body = Data(ProviderHTTP.formEncode(fields).utf8)
        copy.contentType = "application/x-www-form-urlencoded"
        return copy
    }
}

nonisolated enum ProviderHTTP {
    static func send(_ operation: String, _ request: ProviderRequest, session: URLSession) async throws -> ProviderResponse {
        guard let url = URL(string: request.url) else {
            throw ProviderClientError.message("The \(operation) address is invalid.")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        for (name, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: name)
        }
        if let body = request.body {
            urlRequest.httpBody = body
            urlRequest.setValue(request.contentType, forHTTPHeaderField: "Content-Type")
        }
        let started = Date()
        do {
            let payload = try await HTTPClientSupport.send(urlRequest, using: session)
            DiagnosticLog.info("network", "provider_request_finished", details: [
                "operation": operation,
                "status": String(payload.statusCode),
                "duration_ms": String(Int(Date().timeIntervalSince(started) * 1_000))
            ])
            return ProviderResponse(status: payload.statusCode, body: String(decoding: payload.data, as: UTF8.self))
        } catch {
            DiagnosticLog.error("network", "provider_request_failed", error: error, details: ["operation": operation])
            throw error
        }
    }

    static func formEncode(_ fields: [(String, String?)]) -> String {
        fields.compactMap { name, value in
            guard let value else { return nil }
            return "\(encode(name))=\(encode(value))"
        }.joined(separator: "&")
    }

    /// Query string from ordered pairs, for authorize URLs.
    static func query(_ fields: [(String, String)]) -> String {
        fields.map { "\(encode($0.0))=\(encode($0.1))" }.joined(separator: "&")
    }

    private static func encode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    static func readError(_ body: String, fallback: String) -> String {
        HTTPClientSupport.serverMessage(from: Data(body.utf8), fallback: fallback)
    }
}

// MARK: Client protocol

nonisolated enum ProviderClientError: Error, Sendable, Equatable {
    /// No saved sign-in.
    case signedOut
    /// The provider turned the stored credential down; the user has to sign in again.
    case unauthorized(String)
    case message(String)
}

extension ProviderClientError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .signedOut: "Sign in first from Settings → Accounts."
        case let .unauthorized(message), let .message(message): message
        }
    }
}

/// One sign-in attempt's secrets: PKCE values, the device code, and provider extras.
nonisolated final class ProviderSignInSession: @unchecked Sendable {
    let verifier: String
    let challenge: String
    let state = DeviceAuth.randomToken(bytes: 24)
    var device: DeviceAuth.DeviceCode?
    var extra: [String: String] = [:]

    init() {
        verifier = DeviceAuth.randomToken(bytes: 48)
        challenge = DeviceAuth.s256(verifier)
    }
}

/// Usage plus an account email the usage call discovered along the way.
nonisolated struct ProviderFetch: Sendable {
    let snapshot: UsageSnapshot
    var email = ""
}

nonisolated protocol ProviderClient: Sendable {
    var provider: UsageProvider { get }

    func startDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.DeviceCode
    func pollDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.Poll
    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens

    func pasteURL(_ session: ProviderSignInSession) -> String?
    var pasteHint: String { get }
    func finishPaste(_ session: ProviderSignInSession, pasted: String, http: URLSession) async throws -> AuthTokens

    func fromAPIKey(_ key: String, http: URLSession) async throws -> AuthTokens

    func canRefresh(_ tokens: AuthTokens) -> Bool
    func refresh(_ tokens: AuthTokens, http: URLSession) async throws -> AuthTokens
    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch
}

/// Marks a stored credential that is a pasted API key: it never refreshes.
let providerAPIKeyAccount = "api_key"

/// Stored tokens that never expire.
let providerNeverExpires = Date.distantFuture

extension ProviderClient {
    func startDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.DeviceCode {
        throw ProviderClientError.message("No device sign-in for \(provider.displayName).")
    }

    func pollDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.Poll {
        throw ProviderClientError.message("No device sign-in for \(provider.displayName).")
    }

    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens {
        tokens.stored()
    }

    func pasteURL(_ session: ProviderSignInSession) -> String? { nil }

    var pasteHint: String { "Paste what the page shows after you sign in." }

    func finishPaste(_ session: ProviderSignInSession, pasted: String, http: URLSession) async throws -> AuthTokens {
        throw ProviderClientError.message("No paste sign-in for \(provider.displayName).")
    }

    /// Stores a pasted key after checking it against the usage route.
    func fromAPIKey(_ key: String, http: URLSession) async throws -> AuthTokens {
        let clean = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { throw ProviderClientError.message("Paste a key first.") }
        let tokens = AuthTokens(accessToken: clean, refreshToken: clean, idToken: "", expiresAt: providerNeverExpires,
                                accountID: providerAPIKeyAccount)
        _ = try await fetch(tokens, http: http)
        return tokens
    }

    func canRefresh(_ tokens: AuthTokens) -> Bool { false }

    func refresh(_ tokens: AuthTokens, http: URLSession) async throws -> AuthTokens { tokens }

    /// Throws `.unauthorized` on 401/403, a readable error on other failures.
    func check(_ response: ProviderResponse, _ what: String) throws -> ProviderResponse {
        if response.status == 401 {
            throw ProviderClientError.unauthorized("\(provider.displayName) rejected the saved sign-in.")
        }
        if response.ok { return response }
        switch response.status {
        case 403:
            throw ProviderClientError.unauthorized("\(provider.displayName) denied \(what) for this account.")
        case 404:
            throw ProviderClientError.message("The \(provider.displayName) \(what) endpoint is unavailable or has changed.")
        case 429:
            throw ProviderClientError.message("\(provider.displayName) is rate-limiting checks. Try again in a few minutes.")
        default:
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "\(provider.displayName) \(what) failed (HTTP \(response.status))."
            ))
        }
    }

    /// Fails a refresh when the server found nothing to show.
    func nonEmpty(_ snapshot: UsageSnapshot) throws -> ProviderFetch {
        guard snapshot.hasDisplayableData else {
            throw ProviderClientError.message("\(provider.displayName) returned no recognizable usage data.")
        }
        return ProviderFetch(snapshot: snapshot)
    }
}

func isAPIKey(_ tokens: AuthTokens) -> Bool {
    tokens.accountID == providerAPIKeyAccount
}

extension DeviceAuth.Tokens {
    /// Stored form. Tokens that never expire keep the access token as the refresh token too,
    /// because `AuthTokens.isUsable` needs both.
    func stored(accountID: String = "", now: Date = Date()) -> AuthTokens {
        AuthTokens(
            accessToken: accessToken,
            refreshToken: refreshToken.isEmpty ? accessToken : refreshToken,
            idToken: idToken,
            expiresAt: expiresInSeconds > 0 ? now.addingTimeInterval(TimeInterval(expiresInSeconds)) : providerNeverExpires,
            accountID: accountID,
            email: email
        )
    }
}

extension AuthTokens {
    /// A refresh reply merged over the stored tokens; empty fields keep their old values.
    func mergingRefresh(_ fresh: AuthTokens) -> AuthTokens {
        AuthTokens(
            accessToken: fresh.accessToken.isEmpty ? accessToken : fresh.accessToken,
            refreshToken: fresh.refreshToken.isEmpty ? refreshToken : fresh.refreshToken,
            idToken: fresh.idToken.isEmpty ? idToken : fresh.idToken,
            expiresAt: fresh.expiresAt == providerNeverExpires ? expiresAt : fresh.expiresAt,
            accountID: fresh.accountID.isEmpty ? accountID : fresh.accountID,
            email: fresh.email.isEmpty ? email : fresh.email
        )
    }

    func with(email newEmail: String? = nil, idToken newIDToken: String? = nil, expiresAt newExpiry: Date? = nil, accountID newAccount: String? = nil) -> AuthTokens {
        AuthTokens(
            accessToken: accessToken,
            refreshToken: refreshToken,
            idToken: newIDToken ?? idToken,
            expiresAt: newExpiry ?? expiresAt,
            accountID: newAccount ?? accountID,
            email: newEmail ?? email
        )
    }

    /// Fills a missing expiry and email from the access token's JWT claims.
    func withJWTExpiry() -> AuthTokens {
        var result = self
        if expiresAt == providerNeverExpires, let expiry = CursorAPI.expiry(of: accessToken) {
            result = result.with(expiresAt: expiry)
        }
        if result.email.isEmpty {
            let email = CursorAPI.email(in: accessToken)
            if !email.isEmpty { result = result.with(email: email) }
        }
        return result
    }
}

nonisolated enum ProviderClients {
    /// The client for `provider`, or nil for Codex, Claude Code and Cursor.
    static func client(for provider: UsageProvider) -> (any ProviderClient)? {
        switch provider {
        case .codex, .claudeCode, .cursor: nil
        case .copilot: CopilotClient()
        case .kimi: KimiClient()
        case .factory: FactoryClient()
        case .grok: GrokClient()
        case .antigravity: nil
        case .devin: CodeiumClient(provider: .devin, ideName: "devin")
        case .windsurf: CodeiumClient(provider: .windsurf, ideName: "windsurf")
        case .augment: AugmentClient()
        case .cline: ClineClient()
        case .opencodeGo: OpenCodeGoClient()
        case .minimax: MiniMaxClient()
        case .zai: ZaiClient()
        case .kiro: KiroClient()
        case .amp: AmpClient()
        case .openrouter: OpenRouterClient()
        case .kilo: KiloClient()
        case .codebuff: CodebuffClient()
        case .synthetic: KeyQuotaClient(provider: .synthetic, url: "https://api.synthetic.new/v2/quotas", defaultSeconds: 5 * ProviderUsageParsers.hour)
        case .chutes: KeyQuotaClient(provider: .chutes, url: "https://api.chutes.ai/users/me/subscription_usage", defaultSeconds: ProviderUsageParsers.day)
        case .v0: KeyQuotaClient(provider: .v0, url: "https://api.v0.dev/v1/rate-limits", defaultSeconds: ProviderUsageParsers.day)
        case .warp: WarpClient()
        }
    }
}

// MARK: Shared flows

/// RFC 8628 device flow with form-encoded requests (GitHub, Kimi, xAI, WorkOS).
nonisolated protocol StandardDeviceClient: ProviderClient {
    var deviceURL: String { get }
    var tokenURL: String { get }
    var clientID: String { get }
    var scope: String? { get }
    func decorate(_ request: ProviderRequest, _ session: ProviderSignInSession?) -> ProviderRequest
}

extension StandardDeviceClient {
    func decorate(_ request: ProviderRequest, _ session: ProviderSignInSession?) -> ProviderRequest { request }

    func startDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.DeviceCode {
        let response = try await ProviderHTTP.send("\(provider.rawValue)_device", decorate(
            .post(deviceURL).form([("client_id", clientID), ("scope", scope)]), session
        ), session: http)
        let code = DeviceAuth.parseDeviceCode(response.body)
        guard response.ok, code.isValid else {
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "\(provider.displayName) could not start sign-in (HTTP \(response.status))."
            ))
        }
        session.device = code
        return code
    }

    func pollDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.Poll {
        let response = try await ProviderHTTP.send("\(provider.rawValue)_poll", decorate(
            .post(tokenURL).form([
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("device_code", session.device?.deviceCode ?? ""),
                ("client_id", clientID)
            ]), session
        ), session: http)
        return DeviceAuth.parsePoll(status: response.status, body: response.body)
    }

    func canRefresh(_ tokens: AuthTokens) -> Bool {
        !isAPIKey(tokens) && !tokens.refreshToken.isEmpty && tokens.refreshToken != tokens.accessToken
    }

    func refresh(_ tokens: AuthTokens, http: URLSession) async throws -> AuthTokens {
        let response = try await ProviderHTTP.send("\(provider.rawValue)_token_refresh", decorate(
            .post(tokenURL).form([
                ("grant_type", "refresh_token"),
                ("refresh_token", tokens.refreshToken),
                ("client_id", clientID)
            ]), nil
        ), session: http)
        return try mergeRefresh(response, tokens)
    }

    func mergeRefresh(_ response: ProviderResponse, _ tokens: AuthTokens) throws -> AuthTokens {
        let parsed = DeviceAuth.parseTokens(response.body)
        if response.status == 400 || response.status == 401 {
            throw ProviderClientError.unauthorized(
                "Your \(provider.displayName) sign-in expired. Sign in again from Settings → Accounts."
            )
        }
        guard response.ok, !parsed.accessToken.isEmpty else {
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "\(provider.displayName) sign-in refresh failed (HTTP \(response.status))."
            ))
        }
        return tokens.mergingRefresh(parsed.stored().withJWTExpiry())
    }
}

// MARK: GitHub Copilot

nonisolated struct CopilotClient: StandardDeviceClient {
    let provider = UsageProvider.copilot
    let deviceURL = "https://github.com/login/device/code"
    let tokenURL = "https://github.com/login/oauth/access_token"
    let clientID = "Iv1.b507a08c87ecfe98"
    let scope: String? = "read:user"

    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens {
        var login = ""
        if let user = try? await ProviderHTTP.send("copilot_user", .get("https://api.github.com/user")
            .header("Authorization", "token \(tokens.accessToken)"), session: http), user.ok {
            let email = user.json.string("email")
            login = email.contains("@") ? email : user.json.string("login")
        }
        return AuthTokens(accessToken: tokens.accessToken, refreshToken: tokens.accessToken, idToken: "",
                          expiresAt: providerNeverExpires, email: login)
    }

    func canRefresh(_ tokens: AuthTokens) -> Bool { false }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("copilot_usage", .get("https://api.github.com/copilot_internal/user")
            .header("Authorization", "token \(tokens.accessToken)")
            .header("Editor-Version", "vscode/1.96.2")
            .header("Editor-Plugin-Version", "copilot-chat/0.26.7")
            .header("User-Agent", "GitHubCopilotChat/0.26.7")
            .header("X-Github-Api-Version", "2025-04-01"), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.copilot(response.body, now: Date()))
    }
}

// MARK: Kimi Code

nonisolated struct KimiClient: StandardDeviceClient {
    let provider = UsageProvider.kimi
    let deviceURL = "https://auth.kimi.com/api/oauth/device_authorization"
    let tokenURL = "https://auth.kimi.com/api/oauth/token"
    let clientID = "17e5f671-d194-4dfb-9706-5516cb48c098"
    let scope: String? = nil

    func decorate(_ request: ProviderRequest, _ session: ProviderSignInSession?) -> ProviderRequest {
        var deviceID = session?.extra["device_id"] ?? ""
        if deviceID.isEmpty {
            deviceID = UUID().uuidString.lowercased()
            session?.extra["device_id"] = deviceID
        }
        return request
            .header("X-Msh-Platform", "kimi_cli")
            .header("X-Msh-Version", "1.0.0")
            .header("X-Msh-Device-Name", "Codex Meter")
            .header("X-Msh-Device-Model", "iPhone")
            .header("X-Msh-Os-Version", "iOS \(ProcessInfo.processInfo.operatingSystemVersion.majorVersion)")
            .header("X-Msh-Device-Id", deviceID)
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("kimi_usage",
            .get("https://api.kimi.com/coding/v1/usages").bearer(tokens.accessToken), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.kimi(response.body, now: Date()))
    }
}

// MARK: WorkOS (Factory, Cline)

let workOSDevice = "https://api.workos.com/user_management/authorize/device"
let workOSToken = "https://api.workos.com/user_management/authenticate"

nonisolated struct FactoryClient: StandardDeviceClient {
    let provider = UsageProvider.factory
    let deviceURL = workOSDevice
    let tokenURL = workOSToken
    let clientID = "client_01HNM792M5G5G1A2THWPXKFMXB"
    let scope: String? = nil

    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens {
        tokens.stored().withJWTExpiry()
    }

    func refresh(_ tokens: AuthTokens, http: URLSession) async throws -> AuthTokens {
        try mergeRefresh(try await ProviderHTTP.send("factory_token_refresh", .post(workOSToken).json([
            "client_id": clientID, "grant_type": "refresh_token", "refresh_token": tokens.refreshToken
        ]), session: http), tokens)
    }

    private func factory(_ request: ProviderRequest, _ tokens: AuthTokens) -> ProviderRequest {
        request.bearer(tokens.accessToken)
            .header("x-factory-client", "web-app")
            .header("Origin", "https://app.factory.ai")
            .header("Referer", "https://app.factory.ai/")
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        var plan = ""
        var email = ""
        if let me = try? await ProviderHTTP.send("factory_profile", factory(.get("https://app.factory.ai/api/app/auth/me"), tokens), session: http), me.ok {
            plan = ProviderUsageParsers.factoryPlan(me.body)
            email = ProviderUsageParsers.factoryEmail(me.body)
        }
        var limits = try await ProviderHTTP.send("factory_usage", factory(.get("https://api.factory.ai/api/billing/limits"), tokens), session: http)
        if limits.status == 404 {
            limits = try await ProviderHTTP.send("factory_usage_legacy", factory(
                .get("https://app.factory.ai/api/organization/subscription/usage?useCache=true"), tokens
            ), session: http)
        }
        _ = try check(limits, "usage")
        var result = try nonEmpty(ProviderUsageParsers.factory(limits.body, plan: plan, now: Date()))
        result.email = email
        return result
    }
}

nonisolated struct ClineClient: StandardDeviceClient {
    let provider = UsageProvider.cline
    let deviceURL = workOSDevice
    let tokenURL = workOSToken
    let clientID = "client_01K3A541FN8TA3EPPHTD2325AR"
    let scope: String? = nil

    /// Cline swaps the WorkOS session for its own tokens.
    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens {
        let response = try await ProviderHTTP.send("cline_register", .post("https://api.cline.bot/api/v1/auth/register").json([
            "accessToken": tokens.accessToken, "refreshToken": tokens.refreshToken
        ]), session: http)
        guard response.ok else {
            // Fall back to the WorkOS token; the usage call retries it with Cline's prefix.
            return tokens.stored(accountID: "workos").withJWTExpiry()
        }
        return DeviceAuth.parseTokens(response.body).stored().withJWTExpiry()
    }

    func refresh(_ tokens: AuthTokens, http: URLSession) async throws -> AuthTokens {
        if tokens.accountID == "workos" {
            return try mergeRefresh(try await ProviderHTTP.send("cline_workos_refresh", .post(workOSToken).json([
                "client_id": clientID, "grant_type": "refresh_token", "refresh_token": tokens.refreshToken
            ]), session: http), tokens)
        }
        return try mergeRefresh(try await ProviderHTTP.send("cline_token_refresh", .post("https://api.cline.bot/api/v1/auth/refresh").json([
            "refreshToken": tokens.refreshToken, "grantType": "refresh_token"
        ]), session: http), tokens)
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let url = "https://api.cline.bot/api/v1/users/me/plan/usage-limits"
        var response = try await ProviderHTTP.send("cline_usage", .get(url).bearer(tokens.accessToken), session: http)
        if response.status == 401, !tokens.accessToken.hasPrefix("workos:") {
            response = try await ProviderHTTP.send("cline_usage_workos", .get(url).bearer("workos:\(tokens.accessToken)"), session: http)
        }
        _ = try check(response, "usage")
        return try nonEmpty(ProviderUsageParsers.cline(response.body, now: Date()))
    }
}

// MARK: Grok

nonisolated struct GrokClient: StandardDeviceClient {
    let provider = UsageProvider.grok
    let deviceURL = "https://auth.x.ai/oauth2/device/code"
    let tokenURL = "https://auth.x.ai/oauth2/token"
    let clientID = "b1a00492-073a-47ea-816f-4c329264a828"
    let scope: String? = "openid profile email offline_access grok-cli:access api:access"

    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens {
        var stored = tokens.stored().withJWTExpiry()
        if stored.email.isEmpty, !tokens.idToken.isEmpty {
            stored = stored.with(email: CursorAPI.email(in: tokens.idToken))
        }
        return stored
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("grok_usage",
            .get("https://cli-chat-proxy.grok.com/v1/billing?format=credits")
                .bearer(tokens.accessToken)
                .header("x-xai-token-auth", "xai-grok-cli"), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.grok(response.body, now: Date()))
    }
}

// MARK: Devin and Windsurf

nonisolated struct CodeiumClient: ProviderClient {
    let provider: UsageProvider
    let ideName: String

    func pasteURL(_ session: ProviderSignInSession) -> String? {
        "https://www.codeium.com/profile?" + ProviderHTTP.query([
            ("response_type", "token"),
            ("redirect_uri", "vim-show-auth-token"),
            ("state", session.state),
            ("scope", "openid profile email"),
            ("redirect_parameters_type", "query")
        ])
    }

    var pasteHint: String {
        "Sign in with your \(provider.displayName) account, then copy the token the page shows and paste it here."
    }

    func finishPaste(_ session: ProviderSignInSession, pasted: String, http: URLSession) async throws -> AuthTokens {
        let token = DeviceAuth.extractPasted(pasted, keys: ["access_token", "token", "auth_token", "firebase_id_token"])
        guard !token.isEmpty else { throw ProviderClientError.message("Paste the token first.") }
        let response = try await ProviderHTTP.send("\(provider.rawValue)_register",
            .post("https://api.codeium.com/register_user/").json(["firebase_id_token": token]), session: http)
        let apiKey = response.json.string("api_key")
        guard response.ok, !apiKey.isEmpty else {
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "That token wasn't accepted. Copy it again and paste the whole thing."
            ))
        }
        let tokens = AuthTokens(accessToken: apiKey, refreshToken: apiKey, idToken: "", expiresAt: providerNeverExpires,
                                accountID: providerAPIKeyAccount, email: response.json.string("name"))
        _ = try await fetch(tokens, http: http)
        return tokens
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let metadata: [String: Any] = [
            "apiKey": tokens.accessToken, "ideName": ideName, "ideVersion": "1.108.2",
            "extensionName": ideName, "extensionVersion": "1.108.2", "locale": "en"
        ]
        let response = try check(try await ProviderHTTP.send("\(provider.rawValue)_usage",
            .post("https://server.codeium.com/exa.seat_management_pb.SeatManagementService/GetUserStatus")
                .header("Connect-Protocol-Version", "1")
                .json(["metadata": metadata]), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.codeium(response.body, now: Date()))
    }
}

// MARK: Augment

nonisolated struct AugmentClient: ProviderClient {
    let provider = UsageProvider.augment
    static let clientID = "auggie-cli-json-paste"

    func pasteURL(_ session: ProviderSignInSession) -> String? {
        "https://auth.augmentcode.com/authorize?" + ProviderHTTP.query([
            ("response_type", "code"),
            ("code_challenge", session.challenge),
            ("code_challenge_method", "S256"),
            ("client_id", Self.clientID),
            ("state", session.state),
            ("prompt", "login")
        ])
    }

    var pasteHint: String {
        "After you sign in, Augment shows a short block of text that starts with {\"code\". Copy all of it and paste it here."
    }

    func finishPaste(_ session: ProviderSignInSession, pasted: String, http: URLSession) async throws -> AuthTokens {
        let reply = ProviderJSON.object(pasted)
        let code = reply.string("code")
        let state = reply.string("state")
        var tenant = reply.string("tenant_url")
        guard !code.isEmpty, !tenant.isEmpty else {
            throw ProviderClientError.message("Paste the whole text block Augment shows, including the braces.")
        }
        if !state.isEmpty, state != session.state {
            throw ProviderClientError.message("That code is from a different sign-in. Open the page again.")
        }
        guard let host = URL(string: tenant)?.host?.lowercased(), host.hasSuffix(".augmentcode.com"),
              tenant.hasPrefix("https://") else {
            throw ProviderClientError.message("That sign-in points to an unexpected server, so it was not used.")
        }
        if !tenant.hasSuffix("/") { tenant += "/" }
        let response = try await ProviderHTTP.send("augment_exchange", .post(tenant + "token").json([
            "grant_type": "authorization_code", "client_id": Self.clientID, "code_verifier": session.verifier,
            "redirect_uri": "", "code": code
        ]), session: http)
        let tokens = DeviceAuth.parseTokens(response.body)
        guard response.ok, !tokens.accessToken.isEmpty else {
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "Augment sign-in failed (HTTP \(response.status))."
            ))
        }
        let stored = AuthTokens(accessToken: tokens.accessToken, refreshToken: tokens.accessToken, idToken: "",
                                expiresAt: providerNeverExpires, accountID: tenant, email: tokens.email)
        _ = try await fetch(stored, http: http)
        return stored
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let tenant = tokens.accountID
        guard tenant.hasPrefix("https://") else {
            throw ProviderClientError.unauthorized("Sign in to Augment again from Settings → Accounts.")
        }
        let response = try check(try await ProviderHTTP.send("augment_usage",
            .post(tenant + "get-billing-summary").bearer(tokens.accessToken).json([:]), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.augment(response.body, now: Date()))
    }
}

// MARK: OpenCode Go

nonisolated struct OpenCodeGoClient: ProviderClient {
    let provider = UsageProvider.opencodeGo
    static let base = "https://opencode.ai/console/auth/device/"

    func startDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.DeviceCode {
        let response = try await ProviderHTTP.send("opencode_device", .post(Self.base + "code").json([
            "client_id": "opencode-cli", "supports_org_scope": true
        ]), session: http)
        let code = DeviceAuth.parseDeviceCode(response.body)
        guard response.ok, code.isValid else {
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "OpenCode could not start sign-in (HTTP \(response.status))."
            ))
        }
        session.device = code
        return code
    }

    func pollDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.Poll {
        let response = try await ProviderHTTP.send("opencode_poll", .post(Self.base + "token").json([
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            "device_code": session.device?.deviceCode ?? "",
            "client_id": "opencode-cli"
        ]), session: http)
        return DeviceAuth.parsePoll(status: response.status, body: response.body)
    }

    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens {
        tokens.stored().withJWTExpiry()
    }

    func canRefresh(_ tokens: AuthTokens) -> Bool {
        !isAPIKey(tokens) && tokens.refreshToken != tokens.accessToken
    }

    func refresh(_ tokens: AuthTokens, http: URLSession) async throws -> AuthTokens {
        let response = try await ProviderHTTP.send("opencode_refresh", .post(Self.base + "token").json([
            "grant_type": "refresh_token", "refresh_token": tokens.refreshToken, "client_id": "opencode-cli"
        ]), session: http)
        let parsed = DeviceAuth.parseTokens(response.body)
        guard response.ok, !parsed.accessToken.isEmpty else {
            throw ProviderClientError.unauthorized("Your OpenCode sign-in expired. Sign in again.")
        }
        return tokens.mergingRefresh(parsed.stored().withJWTExpiry())
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("opencode_usage",
            .get("https://opencode.ai/zen/go/v1/usage").bearer(tokens.accessToken), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.opencodeGo(response.body, now: Date()))
    }
}

// MARK: MiniMax

nonisolated struct MiniMaxClient: ProviderClient {
    let provider = UsageProvider.minimax
    static let clientID = "78257093-7e40-4613-99e0-527b14b39113"
    static let account = "https://account.minimax.io/oauth2/"

    func startDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.DeviceCode {
        let response = try await ProviderHTTP.send("minimax_device", .post(Self.account + "device/code").form([
            ("response_type", "code"),
            ("client_id", Self.clientID),
            ("scope", "group_id profile model.completion"),
            ("code_challenge", session.challenge),
            ("code_challenge_method", "S256"),
            ("state", session.state)
        ]), session: http)
        let parsed = DeviceAuth.parseDeviceCode(response.body)
        // MiniMax polls with the user code itself.
        let code = DeviceAuth.DeviceCode(
            deviceCode: parsed.deviceCode.isEmpty ? parsed.userCode : parsed.deviceCode,
            userCode: parsed.userCode,
            verificationURI: parsed.verificationURI,
            verificationURIComplete: parsed.verificationURIComplete,
            intervalSeconds: parsed.intervalSeconds,
            expiresInSeconds: parsed.expiresInSeconds
        )
        guard response.ok, code.isValid else {
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "MiniMax could not start sign-in (HTTP \(response.status))."
            ))
        }
        session.device = code
        return code
    }

    func pollDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.Poll {
        let response = try await ProviderHTTP.send("minimax_poll", .post(Self.account + "token").form([
            ("grant_type", "urn:ietf:params:oauth:grant-type:user_code"),
            ("client_id", Self.clientID),
            ("user_code", session.device?.userCode ?? ""),
            ("code_verifier", session.verifier)
        ]), session: http)
        return DeviceAuth.parsePoll(status: response.status, body: response.body)
    }

    func canRefresh(_ tokens: AuthTokens) -> Bool {
        !isAPIKey(tokens) && tokens.refreshToken != tokens.accessToken
    }

    func refresh(_ tokens: AuthTokens, http: URLSession) async throws -> AuthTokens {
        let response = try await ProviderHTTP.send("minimax_refresh", .post(Self.account + "token").form([
            ("grant_type", "refresh_token"), ("client_id", Self.clientID), ("refresh_token", tokens.refreshToken)
        ]), session: http)
        let parsed = DeviceAuth.parseTokens(response.body)
        guard response.ok, !parsed.accessToken.isEmpty else {
            throw ProviderClientError.unauthorized("Your MiniMax sign-in expired. Sign in again.")
        }
        return tokens.mergingRefresh(parsed.stored())
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        var response = try await ProviderHTTP.send("minimax_usage",
            .get("https://api.minimax.io/v1/token_plan/remains").bearer(tokens.accessToken), session: http)
        if response.status == 404 {
            response = try await ProviderHTTP.send("minimax_usage_legacy",
                .get("https://api.minimax.io/v1/api/openplatform/coding_plan/remains").bearer(tokens.accessToken), session: http)
        }
        _ = try check(response, "usage")
        if let base = response.json.object("base_resp"), base.int("status_code", default: 0) == 1004 {
            throw ProviderClientError.unauthorized("MiniMax rejected the saved sign-in.")
        }
        return try nonEmpty(ProviderUsageParsers.minimax(response.body, now: Date()))
    }
}

// MARK: Z.ai

nonisolated struct ZaiClient: ProviderClient {
    let provider = UsageProvider.zai

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let url = "https://api.z.ai/api/monitor/usage/quota/limit"
        var response = try await ProviderHTTP.send("zai_usage", .get(url).bearer(tokens.accessToken), session: http)
        if response.status == 401 {
            response = try await ProviderHTTP.send("zai_usage_raw", .get(url).header("Authorization", tokens.accessToken), session: http)
        }
        _ = try check(response, "usage")
        let root = response.json
        if root.has("success"), !root.bool("success", default: true) {
            let code = root.int("code", default: 0)
            if [401, 1001, 1002].contains(code) {
                throw ProviderClientError.unauthorized("Z.ai rejected this API key.")
            }
            throw ProviderClientError.message(ProviderHTTP.readError(response.body, fallback: "Z.ai returned an error (code \(code))."))
        }
        return try nonEmpty(ProviderUsageParsers.zai(response.body, now: Date()))
    }
}

// MARK: Kiro (AWS Builder ID)

nonisolated struct KiroClient: ProviderClient {
    let provider = UsageProvider.kiro
    static let oidc = "https://oidc.us-east-1.amazonaws.com/"

    func startDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.DeviceCode {
        let client = try await ProviderHTTP.send("kiro_register", .post(Self.oidc + "client/register").json([
            "clientName": "Codex Meter",
            "clientType": "public",
            "scopes": ["codewhisperer:completions", "codewhisperer:analysis", "codewhisperer:conversations"]
        ]), session: http)
        let clientID = client.json.string("clientId")
        let clientSecret = client.json.string("clientSecret")
        guard client.ok, !clientID.isEmpty else {
            throw ProviderClientError.message("AWS could not start sign-in (HTTP \(client.status)).")
        }
        session.extra["clientId"] = clientID
        session.extra["clientSecret"] = clientSecret
        let response = try await ProviderHTTP.send("kiro_device", .post(Self.oidc + "device_authorization").json([
            "clientId": clientID, "clientSecret": clientSecret, "startUrl": "https://view.awsapps.com/start"
        ]), session: http)
        let code = DeviceAuth.parseDeviceCode(response.body)
        guard response.ok, code.isValid else {
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "AWS could not start sign-in (HTTP \(response.status))."
            ))
        }
        session.device = code
        return code
    }

    func pollDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.Poll {
        let response = try await ProviderHTTP.send("kiro_poll", .post(Self.oidc + "token").json([
            "clientId": session.extra["clientId"] ?? "",
            "clientSecret": session.extra["clientSecret"] ?? "",
            "grantType": "urn:ietf:params:oauth:grant-type:device_code",
            "deviceCode": session.device?.deviceCode ?? ""
        ]), session: http)
        return DeviceAuth.parsePoll(status: response.status, body: response.body)
    }

    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens {
        // The refresh needs the registered client, so it rides along in the id-token slot.
        let client = (try? JSONSerialization.data(withJSONObject: [
            "clientId": session.extra["clientId"] ?? "",
            "clientSecret": session.extra["clientSecret"] ?? ""
        ])).map { String(decoding: $0, as: UTF8.self) } ?? ""
        return tokens.stored().with(idToken: client, accountID: "")
    }

    func canRefresh(_ tokens: AuthTokens) -> Bool {
        tokens.idToken.hasPrefix("{") && tokens.refreshToken != tokens.accessToken
    }

    func refresh(_ tokens: AuthTokens, http: URLSession) async throws -> AuthTokens {
        let client = ProviderJSON.object(tokens.idToken)
        let response = try await ProviderHTTP.send("kiro_refresh", .post(Self.oidc + "token").json([
            "clientId": client.string("clientId"),
            "clientSecret": client.string("clientSecret"),
            "grantType": "refresh_token",
            "refreshToken": tokens.refreshToken
        ]), session: http)
        let parsed = DeviceAuth.parseTokens(response.body)
        guard response.ok, !parsed.accessToken.isEmpty else {
            throw ProviderClientError.unauthorized("Your AWS Builder ID sign-in expired. Sign in again.")
        }
        let refreshed = parsed.stored()
        return tokens.mergingRefresh(AuthTokens(
            accessToken: refreshed.accessToken, refreshToken: parsed.refreshToken, idToken: "",
            expiresAt: refreshed.expiresAt
        ))
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("kiro_usage",
            .get("https://codewhisperer.us-east-1.amazonaws.com/getUsageLimits?origin=AI_EDITOR&resourceType=AGENTIC_REQUEST")
                .bearer(tokens.accessToken), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.kiro(response.body, now: Date()))
    }
}

// MARK: Amp

nonisolated struct AmpClient: ProviderClient {
    let provider = UsageProvider.amp

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("amp_usage",
            .post("https://ampcode.com/api/internal?userDisplayBalanceInfo").bearer(tokens.accessToken)
                .json(["method": "userDisplayBalanceInfo", "params": [String: String]()]), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.amp(response.body, now: Date()))
    }
}

// MARK: OpenRouter

/// On iPhone OpenRouter connects with a pasted key; Android also offers its browser sign-in,
/// which needs a local callback server iOS can't keep running behind the browser.
nonisolated struct OpenRouterClient: ProviderClient {
    let provider = UsageProvider.openrouter

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let key = try check(try await ProviderHTTP.send("openrouter_key",
            .get("https://openrouter.ai/api/v1/key").bearer(tokens.accessToken), session: http), "usage")
        var credits = ""
        // Only management keys can read credits; the key limit still shows.
        if let response = try? await ProviderHTTP.send("openrouter_credits",
            .get("https://openrouter.ai/api/v1/credits").bearer(tokens.accessToken), session: http), response.ok {
            credits = response.body
        }
        return try nonEmpty(ProviderUsageParsers.openrouter(keyBody: key.body, creditsBody: credits, now: Date()))
    }
}

// MARK: Kilo

nonisolated struct KiloClient: ProviderClient {
    let provider = UsageProvider.kilo
    static let base = "https://api.kilo.ai/api/device-auth/codes"

    func startDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.DeviceCode {
        let response = try await ProviderHTTP.send("kilo_device", .post(Self.base).json([:]), session: http)
        let code = DeviceAuth.parseDeviceCode(response.body)
        guard response.ok, !code.deviceCode.isEmpty else {
            throw ProviderClientError.message(ProviderHTTP.readError(
                response.body, fallback: "Kilo could not start sign-in (HTTP \(response.status))."
            ))
        }
        let page = code.verificationURI.isEmpty ? "https://app.kilo.ai/device-auth?code=\(code.deviceCode)" : code.verificationURI
        let device = DeviceAuth.DeviceCode(deviceCode: code.deviceCode, userCode: code.userCode, verificationURI: page,
                                           verificationURIComplete: code.verificationURIComplete, intervalSeconds: 3,
                                           expiresInSeconds: code.expiresInSeconds)
        session.device = device
        return device
    }

    func pollDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.Poll {
        let response = try await ProviderHTTP.send("kilo_poll", .get("\(Self.base)/\(session.device?.deviceCode ?? "")"), session: http)
        return DeviceAuth.parsePoll(status: response.status, body: response.body)
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("kilo_usage",
            .get("https://api.kilo.ai/api/profile/balance").bearer(tokens.accessToken), session: http), "balance")
        return try nonEmpty(ProviderUsageParsers.kilo(response.body, now: Date()))
    }
}

// MARK: Codebuff

nonisolated struct CodebuffClient: ProviderClient {
    let provider = UsageProvider.codebuff
    static let base = "https://www.codebuff.com/api/"

    func startDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.DeviceCode {
        let fingerprint = "codex-meter-" + DeviceAuth.randomToken(bytes: 12)
        let response = try await ProviderHTTP.send("codebuff_device", .post(Self.base + "auth/cli/code").json([
            "fingerprintId": fingerprint
        ]), session: http)
        let body = response.json
        let login = body.string("loginUrl")
        guard response.ok, !login.isEmpty else {
            throw ProviderClientError.message("Codebuff could not start sign-in (HTTP \(response.status)).")
        }
        session.extra["fingerprintId"] = fingerprint
        session.extra["fingerprintHash"] = body.string("fingerprintHash")
        session.extra["expiresAt"] = body.string("expiresAt")
        let device = DeviceAuth.DeviceCode(deviceCode: fingerprint, userCode: "", verificationURI: login,
                                           verificationURIComplete: login, intervalSeconds: 3, expiresInSeconds: 900)
        session.device = device
        return device
    }

    func pollDevice(_ session: ProviderSignInSession, http: URLSession) async throws -> DeviceAuth.Poll {
        let response = try await ProviderHTTP.send("codebuff_poll", .get(Self.base + "auth/cli/status?" + ProviderHTTP.query([
            ("fingerprintId", session.extra["fingerprintId"] ?? ""),
            ("fingerprintHash", session.extra["fingerprintHash"] ?? ""),
            ("expiresAt", session.extra["expiresAt"] ?? "")
        ])), session: http)
        let user = response.json.object("user")
        let token = user?.string("authToken") ?? ""
        if response.ok, !token.isEmpty {
            return DeviceAuth.Poll(state: .done, tokens: DeviceAuth.Tokens(
                accessToken: token, refreshToken: token, email: user?.string("email") ?? ""
            ))
        }
        // Codebuff answers 401 until the browser approves the login.
        return DeviceAuth.Poll(state: .pending)
    }

    func finishDevice(_ session: ProviderSignInSession, tokens: DeviceAuth.Tokens, http: URLSession) async throws -> AuthTokens {
        AuthTokens(accessToken: tokens.accessToken, refreshToken: tokens.accessToken, idToken: "", expiresAt: providerNeverExpires,
                   accountID: session.extra["fingerprintId"] ?? "", email: tokens.email)
    }

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("codebuff_usage",
            .post(Self.base + "v1/usage").bearer(tokens.accessToken).json([
                "fingerprintId": tokens.accountID, "authToken": tokens.accessToken
            ]), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.codebuff(response.body, now: Date()))
    }
}

// MARK: Key-only providers

nonisolated struct KeyQuotaClient: ProviderClient {
    let provider: UsageProvider
    let url: String
    let defaultSeconds: Int64

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let response = try check(try await ProviderHTTP.send("\(provider.rawValue)_usage",
            .get(url).bearer(tokens.accessToken), session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.keyQuota(response.body, defaultSeconds: defaultSeconds, now: Date()))
    }
}

nonisolated struct WarpClient: ProviderClient {
    let provider = UsageProvider.warp

    func fetch(_ tokens: AuthTokens, http: URLSession) async throws -> ProviderFetch {
        let query: [String: Any] = [
            "operationName": "GetRequestLimitInfo",
            "variables": [String: String](),
            "query": "query GetRequestLimitInfo { user { user { requestLimitInfo { isUnlimited nextRefreshTime requestLimit requestsUsedSinceLastRefresh } } } }"
        ]
        let response = try check(try await ProviderHTTP.send("warp_usage",
            .post("https://app.warp.dev/graphql/v2?op=GetRequestLimitInfo").bearer(tokens.accessToken).json(query),
            session: http), "usage")
        return try nonEmpty(ProviderUsageParsers.keyQuota(response.body, defaultSeconds: ProviderUsageParsers.month, now: Date()))
    }
}
