import Foundation

/// Browser sign-ins that come back to a one-shot server on 127.0.0.1, as on Android and
/// Windows. Only the Mac app runs one: iOS suspends the app while the browser is open.
public enum LoopbackCallback {
    /// The page the browser shows once the app has the code.
    public static let successPage = """
    <!doctype html><html><head><meta charset="utf-8"><title>Codex Meter</title></head>
    <body style="font: 15px -apple-system, sans-serif; padding: 48px; text-align: center">
    <h2>Signed in</h2><p>You can close this tab and go back to Codex Meter.</p></body></html>
    """

    public static let failurePage = """
    <!doctype html><html><head><meta charset="utf-8"><title>Codex Meter</title></head>
    <body style="font: 15px -apple-system, sans-serif; padding: 48px; text-align: center">
    <h2>Sign-in didn't finish</h2><p>Go back to Codex Meter and try again.</p></body></html>
    """

    /// Query parameters of the request line `GET /callback?code=…&state=… HTTP/1.1`, or `nil`
    /// for anything else (a favicon request, another method, a malformed line).
    public static func parameters(requestHead: String, path: String = "/callback") -> [String: String]? {
        guard let line = requestHead.split(whereSeparator: \.isNewline).first else { return nil }
        let parts = line.split(separator: " ")
        guard parts.count >= 2, parts[0] == "GET",
              let components = URLComponents(string: "http://127.0.0.1" + parts[1]),
              components.path == path else { return nil }
        var result: [String: String] = [:]
        for item in components.queryItems ?? [] where result[item.name] == nil {
            result[item.name] = item.value ?? ""
        }
        return result
    }

    /// The HTTP response for the browser.
    public static func response(success: Bool) -> Data {
        let body = Data((success ? successPage : failurePage).utf8)
        let head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: \(body.count)\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        return Data(head.utf8) + body
    }

    /// `http://127.0.0.1:{port}/callback`.
    public static func redirectURI(port: UInt16) -> String {
        "http://127.0.0.1:\(port)/callback"
    }
}

/// OpenRouter's PKCE sign-in, which creates an API key for this app.
public enum OpenRouterOAuth {
    public static let exchangeURL = "https://openrouter.ai/api/v1/auth/keys"

    public static func authorizeURL(redirectURI: String, challenge: String, state: String) -> URL? {
        var components = URLComponents(string: "https://openrouter.ai/auth")
        components?.queryItems = [
            URLQueryItem(name: "callback_url", value: redirectURI + "?state=" + state),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
        ]
        return components?.url
    }
}

/// Antigravity signs in with Google. Its OAuth client is supplied at build time
/// (`ANTIGRAVITY_CLIENT_ID` and `ANTIGRAVITY_CLIENT_SECRET`, read from Info.plist), as on
/// Android, so builds without it simply don't offer Antigravity.
public enum AntigravityOAuth {
    public static let tokenURL = "https://oauth2.googleapis.com/token"
    public static let userInfoURL = "https://www.googleapis.com/oauth2/v2/userinfo"
    public static let prod = "https://cloudcode-pa.googleapis.com"
    public static let daily = "https://daily-cloudcode-pa.googleapis.com"
    public static let scope = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email"

    public static var clientID: String { infoValue("CodexMeterAntigravityClientID") }
    public static var clientSecret: String { infoValue("CodexMeterAntigravityClientSecret") }
    public static var isConfigured: Bool { !clientID.isEmpty && !clientSecret.isEmpty }

    public static func authorizeURL(clientID: String, redirectURI: String, challenge: String, state: String) -> URL? {
        var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")
        components?.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: scope),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "select_account consent"),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
        ]
        return components?.url
    }

    /// An unset build setting leaves `$(NAME)` or nothing in Info.plist; both mean "not set".
    private static func infoValue(_ key: String) -> String {
        let value = (Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return value.hasPrefix("$(") ? "" : value
    }
}
