import Foundation
import XCTest
@testable import CodexMeterCore

final class JWTClaimsTests: XCTestCase {
    func testAccountClaimPrecedence() {
        let direct = token(payload: [
            "chatgpt_account_id": "direct",
            "email": "person@example.com",
            "https://api.openai.com/auth": ["chatgpt_account_id": "nested"],
            "organizations": [["id": "organization"]],
        ])
        XCTAssertEqual(
            JWTClaims.parse(direct),
            JWTClaims(accountID: "direct", email: "person@example.com")
        )

        let nested = token(payload: [
            "https://api.openai.com/auth": ["chatgpt_account_id": "nested"],
            "organizations": [["id": "organization"]],
        ])
        XCTAssertEqual(JWTClaims.parse(nested).accountID, "nested")

        let organization = token(payload: ["organizations": [["id": "organization"]]])
        XCTAssertEqual(JWTClaims.parse(organization).accountID, "organization")
    }

    func testMergesIDTokenFirstThenAccessTokenFallback() {
        let id = token(payload: ["email": "person@example.com"])
        let access = token(payload: [
            "chatgpt_account_id": "acct_123",
            "email": "ignored@example.com",
        ])
        XCTAssertEqual(
            JWTClaims.fromTokens(idToken: id, accessToken: access),
            JWTClaims(accountID: "acct_123", email: "person@example.com")
        )
    }

    func testMalformedTokensReturnEmptyClaims() {
        let empty = JWTClaims(accountID: "", email: "")
        XCTAssertEqual(JWTClaims.parse(nil), empty)
        XCTAssertEqual(JWTClaims.parse("one.two"), empty)
        XCTAssertEqual(JWTClaims.parse("one.@@@.three"), empty)
        XCTAssertEqual(JWTClaims.parse("one.W10.three"), empty)
    }

    private func token(payload: [String: Any]) -> String {
        let header = Data("{\"alg\":\"none\"}".utf8).base64URLEncodedString()
        let data = try! JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        return "\(header).\(data.base64URLEncodedString()).x"
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
