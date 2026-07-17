import CodexMeterCore
import Foundation

nonisolated public struct AuthTokens: Codable, Sendable, Equatable {
    public let accessToken: String
    public let refreshToken: String
    public let idToken: String
    public let expiresAt: Date
    public let accountID: String
    public let email: String

    public init(
        accessToken: String,
        refreshToken: String,
        idToken: String,
        expiresAt: Date,
        accountID: String = "",
        email: String = ""
    ) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.idToken = idToken
        self.expiresAt = expiresAt
        self.accountID = accountID
        self.email = email
    }

    public var isUsable: Bool {
        !accessToken.isEmpty && !refreshToken.isEmpty
    }

    public func shouldRefresh(at date: Date = Date()) -> Bool {
        expiresAt <= date.addingTimeInterval(5 * 60)
    }

    func merging(
        accessToken: String,
        refreshToken: String?,
        idToken: String?,
        expiresIn: TimeInterval,
        now: Date
    ) throws -> Self {
        let mergedAccess = accessToken.isEmpty ? self.accessToken : accessToken
        let mergedRefresh = refreshToken.flatMap { $0.isEmpty ? nil : $0 } ?? self.refreshToken
        let mergedID = idToken.flatMap { $0.isEmpty ? nil : $0 } ?? self.idToken
        let claims = JWTClaims.fromTokens(idToken: mergedID, accessToken: mergedAccess)

        let result = Self(
            accessToken: mergedAccess,
            refreshToken: mergedRefresh,
            idToken: mergedID,
            expiresAt: now.addingTimeInterval(max(60, expiresIn)),
            accountID: claims.accountID.isEmpty ? accountID : claims.accountID,
            email: claims.email.isEmpty ? email : claims.email
        )
        guard result.isUsable else {
            throw CodexServiceError.authenticationIncomplete
        }
        return result
    }

    static func exchanged(
        accessToken: String,
        refreshToken: String,
        idToken: String,
        expiresIn: TimeInterval,
        now: Date
    ) throws -> Self {
        let claims = JWTClaims.fromTokens(idToken: idToken, accessToken: accessToken)
        let result = Self(
            accessToken: accessToken,
            refreshToken: refreshToken,
            idToken: idToken,
            expiresAt: now.addingTimeInterval(max(60, expiresIn)),
            accountID: claims.accountID,
            email: claims.email
        )
        guard result.isUsable else {
            throw CodexServiceError.authenticationIncomplete
        }
        return result
    }
}
