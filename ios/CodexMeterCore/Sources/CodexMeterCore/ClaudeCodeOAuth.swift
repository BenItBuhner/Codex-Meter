import CryptoKit
import Foundation

/// Endpoints and request shapes Claude Code itself uses for its subscription sign-in and the
/// `/usage` screen. Anthropic does not document these routes publicly, so everything that
/// touches them is defensive.
public enum ClaudeCodeAPI {
    /// Claude Code's public OAuth client (PKCE, no secret).
    public static let clientID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    public static let authorizeEndpoint = "https://claude.ai/oauth/authorize"
    public static let tokenURL = URL(string: "https://platform.claude.com/v1/oauth/token")!
    public static let usageURL = URL(string: "https://api.anthropic.com/api/oauth/usage")!
    public static let profileURL = URL(string: "https://api.anthropic.com/api/oauth/profile")!
    /// Claude Code's manual (copy-the-code) redirect. Claude shows `CODE#STATE` on this page.
    public static let manualRedirectURI = "https://platform.claude.com/oauth/code/callback"
    /// Required beta header for OAuth-token access to api.anthropic.com.
    public static let oauthBeta = "oauth-2025-04-20"
    /// Read-only scopes: profile for the plan badge, inference matches Claude Code's grant.
    public static let scope = "user:profile user:inference"

    public static func authorizeURL(redirectURI: String, challenge: String, state: String) -> URL {
        let parameters: [(String, String)] = [
            ("code", "true"),
            ("client_id", clientID),
            ("response_type", "code"),
            ("redirect_uri", redirectURI),
            ("scope", scope),
            ("code_challenge", challenge),
            ("code_challenge_method", "S256"),
            ("state", state)
        ]
        // Every character outside the unreserved set is percent-encoded, so this always parses.
        return URL(string: authorizeEndpoint + "?" + encode(parameters))!
    }

    public static func codeExchangeBody(
        code: String,
        redirectURI: String,
        verifier: String,
        state: String
    ) -> [String: String] {
        [
            "grant_type": "authorization_code",
            "code": stripStateSuffix(code),
            "redirect_uri": redirectURI,
            "client_id": clientID,
            "code_verifier": verifier,
            "state": state
        ]
    }

    public static func refreshBody(refreshToken: String) -> [String: String] {
        [
            "grant_type": "refresh_token",
            "refresh_token": refreshToken,
            "client_id": clientID
        ]
    }

    /// Claude's manual flow shows `code#state`; only the part before `#` is the code.
    public static func stripStateSuffix(_ code: String) -> String {
        let head = code.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map(String.init) ?? ""
        return head.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Sources disagree on whether the token route wants a form or JSON body. Form is sent
    /// first; a 400/415 that is not a definitive grant error gets one JSON retry.
    public static func shouldRetryAsJSON(statusCode: Int, body: Data) -> Bool {
        guard statusCode == 400 || statusCode == 415 else {
            return false
        }
        let text = String(decoding: body, as: UTF8.self).lowercased()
        return !text.contains("invalid_grant") && !text.contains("expired")
    }

    /// `application/x-www-form-urlencoded` encoding that matches Java's `URLEncoder`
    /// (spaces become `+`, everything outside `A-Z a-z 0-9 - . _ ~` is percent-encoded).
    public static func encode(_ parameters: [(String, String)]) -> String {
        parameters
            .map { "\(formEncode($0.0))=\(formEncode($0.1))" }
            .joined(separator: "&")
    }

    static func formEncode(_ value: String) -> String {
        var allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz")
        allowed.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~ ")
        let encoded = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
        return encoded.replacingOccurrences(of: " ", with: "+")
    }
}

/// One in-flight PKCE authorization for Claude's manual-code sign-in.
public struct ClaudeAuthorizationRequest: Sendable, Equatable {
    public let verifier: String
    public let state: String
    public let redirectURI: String

    public init(
        verifier: String,
        state: String,
        redirectURI: String = ClaudeCodeAPI.manualRedirectURI
    ) {
        self.verifier = verifier
        self.state = state
        self.redirectURI = redirectURI
    }

    /// A fresh request with a 256-bit verifier and state from the system CSPRNG.
    public static func make(redirectURI: String = ClaudeCodeAPI.manualRedirectURI) -> Self {
        Self(
            verifier: randomURLSafeToken(byteCount: 32),
            state: randomURLSafeToken(byteCount: 32),
            redirectURI: redirectURI
        )
    }

    public var codeChallenge: String {
        Self.codeChallenge(for: verifier)
    }

    public var authorizeURL: URL {
        ClaudeCodeAPI.authorizeURL(redirectURI: redirectURI, challenge: codeChallenge, state: state)
    }

    /// Parses what the user pasted from Claude's callback page and checks it belongs to this
    /// request. Accepts `CODE#STATE`, a bare `CODE`, or the full callback URL.
    public func pastedCode(_ text: String) throws -> String {
        let pasted = Self.parsePastedCode(text)
        guard !pasted.code.isEmpty else {
            throw ClaudeAuthorizationError.missingCode
        }
        if let pastedState = pasted.state, !pastedState.isEmpty, pastedState != state {
            throw ClaudeAuthorizationError.stateMismatch
        }
        return pasted.code
    }

    public static func parsePastedCode(_ text: String) -> (code: String, state: String?) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercased().hasPrefix("http"),
           let components = URLComponents(string: trimmed),
           let code = components.queryItems?.first(where: { $0.name == "code" })?.value {
            let state = components.queryItems?.first(where: { $0.name == "state" })?.value
                ?? components.fragment
            return (ClaudeCodeAPI.stripStateSuffix(code), state)
        }
        let parts = trimmed.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
        let code = parts.first.map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) } ?? ""
        let state = parts.count > 1
            ? String(parts[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            : nil
        return (code, state)
    }

    /// RFC 7636 S256: base64url(SHA-256(verifier)) without padding.
    public static func codeChallenge(for verifier: String) -> String {
        base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    static func randomURLSafeToken(byteCount: Int) -> String {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<max(1, byteCount)).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        return base64URL(Data(bytes))
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

public enum ClaudeAuthorizationError: Error, Sendable, Equatable {
    case missingCode
    case stateMismatch
    case incompleteCredentials
}

extension ClaudeAuthorizationError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .missingCode:
            "Paste the code Claude showed after you approved access."
        case .stateMismatch:
            "That code belongs to a different sign-in attempt. Open Claude sign-in again and paste the new code."
        case .incompleteCredentials:
            "Claude returned incomplete credentials."
        }
    }
}

/// The fields Codex Meter keeps from Claude's token endpoint.
public struct ClaudeTokenResponse: Sendable, Equatable {
    public let accessToken: String
    public let refreshToken: String
    public let expiresIn: TimeInterval
    public let email: String
    public let organizationID: String

    public init(
        accessToken: String,
        refreshToken: String,
        expiresIn: TimeInterval,
        email: String = "",
        organizationID: String = ""
    ) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresIn = expiresIn
        self.email = email
        self.organizationID = organizationID
    }

    /// Parses `{access_token, refresh_token, expires_in, account{email_address}, organization{uuid}}`.
    /// A missing refresh token is allowed (refresh responses may omit it); callers merge it.
    public static func parse(_ data: Data) throws -> Self {
        let root = try JSONSupport.object(from: data)
        let access = JSONSupport.string(root["access_token"])
        guard !access.isEmpty else {
            throw ClaudeAuthorizationError.incompleteCredentials
        }
        let account = JSONSupport.object(root["account"])
        let organization = JSONSupport.object(root["organization"])
        var email = JSONSupport.string(account?["email_address"])
        if email.isEmpty {
            email = JSONSupport.string(account?["email"])
        }
        let expiresIn = JSONSupport.double(root["expires_in"]) ?? 3_600
        return Self(
            accessToken: access,
            refreshToken: JSONSupport.string(root["refresh_token"]),
            expiresIn: max(60, expiresIn.isFinite ? expiresIn : 3_600),
            email: email,
            organizationID: JSONSupport.string(organization?["uuid"])
        )
    }
}
