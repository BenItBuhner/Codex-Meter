import CodexMeterCore
import Foundation

public typealias CodexRefreshSnapshot = (
    usage: UsageSnapshot,
    credits: ResetCreditsSnapshot
)

/// The app-facing boundary for live and local demo data.
///
/// Credentials deliberately do not appear in the refresh results, which keeps it
/// straightforward to pass the resulting snapshots to the widget cache safely.
nonisolated public protocol CodexService: Sendable {
    func refresh() async throws -> CodexRefreshSnapshot
    func refreshUsage() async throws -> UsageSnapshot
    func refreshResetCredits() async throws -> ResetCreditsSnapshot
    func consumeReset() async throws -> ResetConsumeResult
    func currentTokens() async throws -> AuthTokens?
    func signOut() async
}

nonisolated public enum CodexServiceError: Error, Sendable, Equatable {
    case signedOut
    case invalidURL
    case invalidResponse
    case responseTooLarge
    case authenticationIncomplete
    case deviceCodeUnavailable
    case deviceCodeExpired
    case noResetCredit
    case http(status: Int, message: String)
    case storage(String)
}

extension CodexServiceError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .signedOut:
            "Sign in to ChatGPT first."
        case .invalidURL:
            "Codex Meter could not create a valid server request."
        case .invalidResponse:
            "The server returned an invalid response."
        case .responseTooLarge:
            "The server response was unexpectedly large."
        case .authenticationIncomplete:
            "The authorization server returned incomplete credentials."
        case .deviceCodeUnavailable:
            "Device-code sign-in is not available for this server."
        case .deviceCodeExpired:
            "The one-time sign-in code expired. Start sign-in again."
        case .noResetCredit:
            "No reset credit is currently available."
        case let .http(_, message), let .storage(message):
            message
        }
    }
}
