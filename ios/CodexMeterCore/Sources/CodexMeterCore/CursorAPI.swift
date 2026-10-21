import CryptoKit
import Foundation

/// Endpoints and request shapes the Cursor editor and CLI use for sign-in and the usage meter.
/// Cursor does not document them publicly; they match open-source trackers (CodexBar,
/// quotabar, cpa-cursor-plugin) and the editor's own bundle, so everything that touches them
/// is defensive. Mirrors Android's `CursorApi`.
///
/// Sign-in needs no redirect URI: the browser opens `loginDeepControl` with a PKCE challenge
/// and a random id, and the app polls `auth/poll` with the verifier until Cursor hands back
/// tokens.
public enum CursorAPI {
    public static let loginEndpoint = "https://cursor.com/loginDeepControl"
    public static let pollEndpoint = "https://api2.cursor.sh/auth/poll"
    public static let tokenURL = URL(string: "https://api2.cursor.sh/oauth/token")!
    public static let usageURL = URL(
        string: "https://api2.cursor.sh/aiserver.v1.DashboardService/GetCurrentPeriodUsage"
    )!
    public static let profileURL = URL(string: "https://api2.cursor.sh/auth/full_stripe_profile")!
    /// Cursor's production auth client id, used by the editor for token refresh.
    public static let clientID = "KbZUR41cY7W6zRSdpSUJ7I7mLYBKOCmB"

    /// The editor polls every 500 ms; a phone does fine with one poll per second.
    public static let pollInterval: TimeInterval = 1
    /// Give up on a sign-in the user never finished after five minutes.
    public static let pollTimeout: TimeInterval = 5 * 60
    /// Access tokens last about 60 days; refresh when fewer than this remain.
    public static let refreshMargin: TimeInterval = 7 * 24 * 60 * 60

    /// `{"grant_type":"refresh_token","client_id":…,"refresh_token":…}` as JSON.
    public static func refreshBody(refreshToken: String) -> [String: String] {
        [
            "grant_type": "refresh_token",
            "client_id": clientID,
            "refresh_token": refreshToken
        ]
    }

    /// Cursor answers some rejected sessions with HTTP 200 and an error body, so auth failures
    /// are recognized by body as well as by status.
    public static func isUnauthenticated(statusCode: Int, body: Data) -> Bool {
        if statusCode == 401 { return true }
        guard !body.isEmpty, let object = try? JSONSupport.object(from: body) else {
            return false
        }
        if JSONSupport.bool(object["shouldLogout"], default: false) { return true }
        let code = (JSONSupport.string(object["code"]) + " " + JSONSupport.string(object["error"]))
            .lowercased()
        return code.contains("not_authenticated") || code.contains("unauthenticated")
    }

    /// Vercel's bot checkpoint is an HTML 403, not a sign-in problem.
    public static func isBotCheckpoint(statusCode: Int, body: Data) -> Bool {
        statusCode == 403 && String(decoding: body, as: UTF8.self)
            .contains("Vercel Security Checkpoint")
    }

    /// Expiry from the JWT `exp` claim, or `nil` when it can't be read.
    public static func expiry(of jwt: String) -> Date? {
        guard let claims = claims(jwt),
              let exp = JSONSupport.double(claims["exp"]), exp > 0 else {
            return nil
        }
        return Date(timeIntervalSince1970: exp)
    }

    /// Email claim when Cursor includes one in the access token.
    public static func email(in jwt: String) -> String {
        let email = JSONSupport.string(claims(jwt)?["email"])
        return email.contains("@") ? email : ""
    }

    private static func claims(_ jwt: String) -> [String: Any]? {
        let parts = jwt.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        var base64 = parts[1]
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        guard let data = Data(base64Encoded: base64) else { return nil }
        return try? JSONSupport.object(from: data)
    }
}

/// One browser sign-in attempt: the PKCE pair plus the id Cursor uses to match the poll.
public struct CursorLoginRequest: Sendable, Equatable {
    public let verifier: String
    public let challenge: String
    public let uuid: String

    public init(verifier: String, uuid: String) {
        self.verifier = verifier
        self.challenge = Self.challenge(for: verifier)
        self.uuid = uuid
    }

    public static func make() -> Self {
        var bytes = [UInt8](repeating: 0, count: 32)
        for index in bytes.indices {
            bytes[index] = UInt8.random(in: .min ... .max)
        }
        return Self(
            verifier: base64URL(Data(bytes)),
            uuid: UUID().uuidString.lowercased()
        )
    }

    /// `base64url(SHA-256(verifier))`, the same S256 challenge the editor sends.
    public static func challenge(for verifier: String) -> String {
        base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    public var loginURL: URL {
        URL(string: "\(CursorAPI.loginEndpoint)?challenge=\(challenge)&uuid=\(uuid)&mode=login")!
    }

    public var pollURL: URL {
        URL(string: "\(CursorAPI.pollEndpoint)?uuid=\(uuid)&verifier=\(verifier)")!
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

/// Tokens from a finished poll (`accessToken`) or a refresh (`access_token`).
public struct CursorTokenResponse: Sendable, Equatable {
    public let accessToken: String
    public let refreshToken: String
    public let authID: String
    public let shouldLogout: Bool

    /// The editor stores a refreshed access token as both tokens, so a missing refresh token
    /// falls back to the access token.
    public static func parse(_ data: Data) -> Self {
        guard let object = try? JSONSupport.object(from: data) else {
            return Self(accessToken: "", refreshToken: "", authID: "", shouldLogout: false)
        }
        let access = first(object, "accessToken", "access_token")
        let refresh = first(object, "refreshToken", "refresh_token")
        return Self(
            accessToken: access,
            refreshToken: refresh.isEmpty ? access : refresh,
            authID: first(object, "authId", "auth_id"),
            shouldLogout: JSONSupport.bool(object["shouldLogout"], default: false)
        )
    }

    public static func parse(_ string: String) -> Self {
        parse(Data(string.utf8))
    }

    private static func first(_ object: [String: Any], _ a: String, _ b: String) -> String {
        let value = JSONSupport.string(object[a])
        return value.isEmpty ? JSONSupport.string(object[b]) : value
    }
}
