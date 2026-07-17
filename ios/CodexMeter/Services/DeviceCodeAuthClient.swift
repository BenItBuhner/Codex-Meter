import Foundation

nonisolated public struct OpenAIAuthConfiguration: Sendable, Equatable {
    public var issuer: URL
    public var clientID: String

    public init(
        issuer: URL = URL(string: "https://auth.openai.com")!,
        clientID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
    ) {
        self.issuer = issuer
        self.clientID = clientID
    }

    var userCodeURL: URL {
        issuer.appendingPathComponent("api/accounts/deviceauth/usercode")
    }

    var deviceTokenURL: URL {
        issuer.appendingPathComponent("api/accounts/deviceauth/token")
    }

    var verificationURL: URL {
        issuer.appendingPathComponent("codex/device")
    }

    var tokenURL: URL {
        issuer.appendingPathComponent("oauth/token")
    }

    var revokeURL: URL {
        issuer.appendingPathComponent("oauth/revoke")
    }

    var deviceRedirectURL: URL {
        issuer.appendingPathComponent("deviceauth/callback")
    }
}

nonisolated public struct DeviceCodeChallenge: Sendable, Equatable, Identifiable {
    public var id: String { deviceAuthID }
    public let verificationURL: URL
    public let userCode: String
    public let expiresAt: Date

    let deviceAuthID: String
    let pollingInterval: TimeInterval

    init(
        verificationURL: URL,
        userCode: String,
        expiresAt: Date,
        deviceAuthID: String,
        pollingInterval: TimeInterval
    ) {
        self.verificationURL = verificationURL
        self.userCode = userCode
        self.expiresAt = expiresAt
        self.deviceAuthID = deviceAuthID
        self.pollingInterval = pollingInterval
    }
}

/// Implements the same device-code HTTP flow used by the open-source Codex
/// client. `complete(_:)` is cancellable both through Swift task cancellation
/// and the explicit `cancel(_:)` API used by the sign-in sheet.
public actor DeviceCodeAuthClient {
    private let configuration: OpenAIAuthConfiguration
    private let tokenStore: any TokenStoring
    private let session: URLSession
    private let now: @Sendable () -> Date
    private var cancelledChallengeIDs: Set<String> = []

    public init(
        configuration: OpenAIAuthConfiguration = .init(),
        tokenStore: any TokenStoring = KeychainTokenStore(),
        session: URLSession? = nil,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.configuration = configuration
        self.tokenStore = tokenStore
        self.session = session ?? HTTPClientSupport.session()
        self.now = now
    }

    public func requestChallenge() async throws -> DeviceCodeChallenge {
        let request = try HTTPClientSupport.jsonRequest(
            url: configuration.userCodeURL,
            body: ["client_id": configuration.clientID]
        )
        let payload = try await HTTPClientSupport.send(request, using: session)
        if payload.statusCode == 404 {
            throw CodexServiceError.deviceCodeUnavailable
        }
        let data = try HTTPClientSupport.requireSuccess(
            payload,
            fallback: "Could not start ChatGPT device-code sign-in."
        )
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CodexServiceError.invalidResponse
        }

        let deviceAuthID = root["device_auth_id"] as? String ?? ""
        let userCode = (root["user_code"] as? String) ?? (root["usercode"] as? String) ?? ""
        guard !deviceAuthID.isEmpty, !userCode.isEmpty else {
            throw CodexServiceError.invalidResponse
        }
        let interval = max(1, HTTPClientSupport.number(root["interval"], default: 5))
        cancelledChallengeIDs.remove(deviceAuthID)
        return DeviceCodeChallenge(
            verificationURL: configuration.verificationURL,
            userCode: userCode,
            expiresAt: now().addingTimeInterval(15 * 60),
            deviceAuthID: deviceAuthID,
            pollingInterval: interval
        )
    }

    public func complete(_ challenge: DeviceCodeChallenge) async throws -> AuthTokens {
        guard !challenge.deviceAuthID.isEmpty else {
            throw CodexServiceError.invalidResponse
        }
        let authorization = try await pollForAuthorizationCode(challenge)
        try checkCancellation(for: challenge.deviceAuthID)

        let request = HTTPClientSupport.formRequest(
            url: configuration.tokenURL,
            fields: [
                "grant_type": "authorization_code",
                "code": authorization.authorizationCode,
                "redirect_uri": configuration.deviceRedirectURL.absoluteString,
                "client_id": configuration.clientID,
                "code_verifier": authorization.codeVerifier
            ]
        )
        let payload = try await HTTPClientSupport.send(request, using: session)
        let data = try HTTPClientSupport.requireSuccess(
            payload,
            fallback: "Could not finish ChatGPT sign-in."
        )
        let tokens = try TokenResponseDecoder.exchanged(from: data, now: now())
        try await tokenStore.save(tokens)
        cancelledChallengeIDs.remove(challenge.deviceAuthID)
        return tokens
    }

    public func authenticate(
        onChallenge: @Sendable (DeviceCodeChallenge) async -> Void
    ) async throws -> AuthTokens {
        let challenge = try await requestChallenge()
        await onChallenge(challenge)
        return try await complete(challenge)
    }

    public func cancel(_ challenge: DeviceCodeChallenge) {
        cancelledChallengeIDs.insert(challenge.deviceAuthID)
    }

    private func pollForAuthorizationCode(
        _ challenge: DeviceCodeChallenge
    ) async throws -> DeviceAuthorization {
        while now() < challenge.expiresAt {
            try checkCancellation(for: challenge.deviceAuthID)
            let request = try HTTPClientSupport.jsonRequest(
                url: configuration.deviceTokenURL,
                body: [
                    "device_auth_id": challenge.deviceAuthID,
                    "user_code": challenge.userCode
                ]
            )
            let payload = try await HTTPClientSupport.send(request, using: session)

            if (200..<300).contains(payload.statusCode) {
                guard let root = try JSONSerialization.jsonObject(with: payload.data) as? [String: Any],
                      let authorizationCode = root["authorization_code"] as? String,
                      let codeVerifier = root["code_verifier"] as? String,
                      !authorizationCode.isEmpty,
                      !codeVerifier.isEmpty
                else {
                    throw CodexServiceError.invalidResponse
                }
                return DeviceAuthorization(
                    authorizationCode: authorizationCode,
                    codeVerifier: codeVerifier
                )
            }

            guard payload.statusCode == 403 || payload.statusCode == 404 else {
                throw CodexServiceError.http(
                    status: payload.statusCode,
                    message: HTTPClientSupport.serverMessage(
                        from: payload.data,
                        fallback: "ChatGPT device authorization failed (HTTP \(payload.statusCode))."
                    )
                )
            }

            let remaining = challenge.expiresAt.timeIntervalSince(now())
            guard remaining > 0 else { break }
            try await Task.sleep(for: .seconds(min(challenge.pollingInterval, remaining)))
        }
        cancelledChallengeIDs.remove(challenge.deviceAuthID)
        throw CodexServiceError.deviceCodeExpired
    }

    private func checkCancellation(for challengeID: String) throws {
        try Task.checkCancellation()
        if cancelledChallengeIDs.remove(challengeID) != nil {
            throw CancellationError()
        }
    }
}

private struct DeviceAuthorization: Sendable {
    let authorizationCode: String
    let codeVerifier: String
}

nonisolated enum TokenResponseDecoder {
    static func exchanged(from data: Data, now: Date) throws -> AuthTokens {
        let root = try object(from: data)
        return try AuthTokens.exchanged(
            accessToken: root["access_token"] as? String ?? "",
            refreshToken: root["refresh_token"] as? String ?? "",
            idToken: root["id_token"] as? String ?? "",
            expiresIn: HTTPClientSupport.number(root["expires_in"], default: 3_600),
            now: now
        )
    }

    static func refreshed(from data: Data, previous: AuthTokens, now: Date) throws -> AuthTokens {
        let root = try object(from: data)
        return try previous.merging(
            accessToken: root["access_token"] as? String ?? "",
            refreshToken: root["refresh_token"] as? String,
            idToken: root["id_token"] as? String,
            expiresIn: HTTPClientSupport.number(root["expires_in"], default: 3_600),
            now: now
        )
    }

    private static func object(from data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CodexServiceError.invalidResponse
        }
        return object
    }
}
