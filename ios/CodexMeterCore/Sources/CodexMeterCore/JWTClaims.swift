import Foundation

public struct JWTClaims: Codable, Sendable, Equatable {
    public let accountID: String
    public let email: String

    public init(accountID: String, email: String) {
        self.accountID = accountID
        self.email = email
    }

    public static func fromTokens(idToken: String?, accessToken: String?) -> Self {
        let idClaims = parse(idToken)
        let accessClaims = parse(accessToken)
        return Self(
            accountID: idClaims.accountID.isEmpty ? accessClaims.accountID : idClaims.accountID,
            email: idClaims.email.isEmpty ? accessClaims.email : idClaims.email
        )
    }

    public static func parse(_ token: String?) -> Self {
        guard let token else {
            return Self(accountID: "", email: "")
        }
        let components = token.split(separator: ".", omittingEmptySubsequences: false)
        guard components.count == 3,
              let payload = decodeBase64URL(String(components[1])),
              let object = try? JSONSupport.object(from: payload)
        else {
            return Self(accountID: "", email: "")
        }

        var accountID = JSONSupport.string(object["chatgpt_account_id"])
        if accountID.isEmpty,
           let auth = JSONSupport.object(object["https://api.openai.com/auth"]) {
            accountID = JSONSupport.string(auth["chatgpt_account_id"])
        }
        if accountID.isEmpty,
           let organizations = JSONSupport.array(object["organizations"]),
           let first = organizations.first,
           let organization = JSONSupport.object(first) {
            accountID = JSONSupport.string(organization["id"])
        }

        return Self(
            accountID: accountID,
            email: JSONSupport.string(object["email"])
        )
    }

    private static func decodeBase64URL(_ value: String) -> Data? {
        var normalized = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder != 0 {
            normalized.append(String(repeating: "=", count: 4 - remainder))
        }
        return Data(base64Encoded: normalized)
    }
}
