import Foundation

/// Pure parsing for device-code sign-in (RFC 8628 and the camelCase variants some providers
/// use) and for token responses. Port of Android's `DeviceAuth`.
public enum DeviceAuth {
    /// What the sign-in screen shows: a code to type on the provider's page.
    public struct DeviceCode: Sendable, Equatable {
        public let deviceCode: String
        public let userCode: String
        public let verificationURI: String
        /// Page that already has the code filled in, when the provider gives one.
        public let verificationURIComplete: String
        public let intervalSeconds: Int64
        public let expiresInSeconds: Int64

        public init(
            deviceCode: String,
            userCode: String,
            verificationURI: String,
            verificationURIComplete: String,
            intervalSeconds: Int64,
            expiresInSeconds: Int64
        ) {
            self.deviceCode = deviceCode
            self.userCode = userCode
            self.verificationURI = verificationURI
            self.verificationURIComplete = verificationURIComplete
            self.intervalSeconds = max(1, min(30, intervalSeconds <= 0 ? 5 : intervalSeconds))
            self.expiresInSeconds = expiresInSeconds <= 0 ? 900 : expiresInSeconds
        }

        /// The page to open: the pre-filled one when available.
        public var openURL: String {
            verificationURIComplete.isEmpty ? verificationURI : verificationURIComplete
        }

        public var isValid: Bool {
            !deviceCode.isEmpty && !verificationURI.isEmpty
        }
    }

    public static func parseDeviceCode(_ body: String) -> DeviceCode {
        let object = ProviderJSON.object(body)
        return DeviceCode(
            deviceCode: string(object, "device_code", "deviceCode", "code", "device_auth_id"),
            userCode: string(object, "user_code", "userCode", "code"),
            verificationURI: string(object, "verification_uri", "verificationUri", "verification_url", "verificationUrl"),
            verificationURIComplete: string(object, "verification_uri_complete", "verificationUriComplete",
                                            "verification_url_complete", "verificationUrlComplete"),
            intervalSeconds: object.int("interval", default: 5),
            expiresInSeconds: object.int("expires_in", default: object.int("expiresIn", default: 900))
        )
    }

    /// Outcome of one poll of a device-code token endpoint.
    public enum PollState: Sendable, Equatable {
        case pending
        case slowDown
        case denied
        case expired
        case done
        case failed
    }

    public struct Poll: Sendable, Equatable {
        public let state: PollState
        public let tokens: Tokens?
        public let message: String

        public init(state: PollState, tokens: Tokens? = nil, message: String = "") {
            self.state = state
            self.tokens = tokens
            self.message = message
        }
    }

    /// Reads a token endpoint's reply while the user is still approving the code.
    public static func parsePoll(status: Int, body: String, now: Date = Date()) -> Poll {
        let object = ProviderJSON.object(body)
        var error = string(object, "error", "code").lowercased()
        if let nested = object.object("error") {
            error = string(nested, "code", "type", "message").lowercased()
        }
        let tokens = parseTokens(body, now: now)
        if (200 ..< 300).contains(status), !tokens.accessToken.isEmpty {
            return Poll(state: .done, tokens: tokens)
        }
        if error.contains("authorization_pending") || error.contains("pending") || status == 202 || status == 428 {
            return Poll(state: .pending)
        }
        if error.contains("slow_down") { return Poll(state: .slowDown) }
        if error.contains("access_denied") || error.contains("denied") || status == 403 {
            return Poll(state: .denied, message: "The sign-in was declined.")
        }
        if error.contains("expired") || status == 410 {
            return Poll(state: .expired, message: "The code expired. Start again.")
        }
        let description = string(object, "error_description", "message")
        return Poll(state: .failed, message: description.isEmpty ? "Sign-in failed (HTTP \(status))." : description)
    }

    /// Tokens from any token endpoint. Missing values are empty; expiry is in seconds.
    public struct Tokens: Sendable, Equatable {
        public let accessToken: String
        public let refreshToken: String
        public let idToken: String
        public let expiresInSeconds: Int64
        public let email: String

        public init(accessToken: String, refreshToken: String = "", idToken: String = "", expiresInSeconds: Int64 = 0, email: String = "") {
            self.accessToken = accessToken
            self.refreshToken = refreshToken
            self.idToken = idToken
            self.expiresInSeconds = max(0, expiresInSeconds)
            self.email = email
        }
    }

    public static func parseTokens(_ body: String, now: Date = Date()) -> Tokens {
        var object = ProviderJSON.object(body)
        if let data = object.object("data"), !object.has("access_token"), !object.has("accessToken") {
            object = data
        }
        var email = string(object, "email")
        if email.isEmpty, let user = object.object("user") { email = string(user, "email") }
        if email.isEmpty, let info = object.object("userInfo") { email = string(info, "email") }
        var expiresIn = object.int("expires_in", default: object.int("expiresIn", default: 0))
        if expiresIn <= 0 {
            let expiresAt = ProviderUsageParsers.epochSeconds(ProviderUsageParsers.first(object, "expiresAt", "expires_at"))
            if expiresAt > 0 { expiresIn = max(1, expiresAt - Int64(now.timeIntervalSince1970)) }
        }
        return Tokens(
            accessToken: string(object, "access_token", "accessToken", "token", "key", "api_key"),
            refreshToken: string(object, "refresh_token", "refreshToken"),
            idToken: string(object, "id_token", "idToken"),
            expiresInSeconds: expiresIn,
            email: email.contains("@") ? email : ""
        )
    }

    /// Random URL-safe value for PKCE verifiers, states and one-time ids.
    public static func randomToken(bytes count: Int) -> String {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0 ..< count).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        return CursorLoginRequest.base64URL(Data(bytes))
    }

    /// `base64url(SHA-256(verifier))`.
    public static func s256(_ verifier: String) -> String {
        CursorLoginRequest.challenge(for: verifier)
    }

    /// Pulls the useful part out of whatever the user pasted: a bare token, a JSON object, or a
    /// URL with the value in its query or fragment.
    public static func extractPasted(_ pasted: String?, keys: [String]) -> String {
        let text = (pasted ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return "" }
        if text.hasPrefix("{") {
            let value = string(ProviderJSON.object(text), keys)
            return value.isEmpty ? text : value
        }
        let markers = [text.firstIndex(of: "?"), text.firstIndex(of: "#")].compactMap { $0 }
        if text.hasPrefix("http"), let marker = markers.max(), marker > text.startIndex {
            let query = text[text.index(after: marker)...]
            for pair in query.split(whereSeparator: { $0 == "&" || $0 == "#" }) {
                guard let equals = pair.firstIndex(of: "="), equals > pair.startIndex else { continue }
                let name = String(pair[..<equals])
                if keys.contains(name) {
                    let raw = String(pair[pair.index(after: equals)...])
                    return raw.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? raw
                }
            }
        }
        return text.replacingOccurrences(of: #"\s+"#, with: "", options: .regularExpression)
    }

    static func string(_ object: ProviderJSONObject?, _ keys: String...) -> String {
        string(object, keys)
    }

    static func string(_ object: ProviderJSONObject?, _ keys: [String]) -> String {
        guard let object else { return "" }
        for key in keys {
            if case let .string(value)? = object[key], !value.isEmpty, value != "null" {
                return value
            }
        }
        return ""
    }
}
