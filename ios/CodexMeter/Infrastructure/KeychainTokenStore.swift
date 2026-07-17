import Foundation
import Security

nonisolated public protocol TokenStoring: Sendable {
    func load() async throws -> AuthTokens?
    func save(_ tokens: AuthTokens) async throws
    func delete() async throws
}

/// App-only credential persistence. This type is intentionally absent from the
/// widget target and does not use an access group shared with extensions.
public actor KeychainTokenStore: TokenStoring {
    public nonisolated static let defaultService = "com.bukovinafilip.CodexMeter.auth"

    private let service: String
    private let account: String

    public init(
        service: String = KeychainTokenStore.defaultService,
        account: String = "chatgpt-tokens"
    ) {
        self.service = service
        self.account = account
    }

    public func load() async throws -> AuthTokens? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw keychainError(status, operation: "read")
        }
        guard let data = result as? Data else {
            try? await delete()
            throw CodexServiceError.storage("The saved credentials could not be read.")
        }

        do {
            return try JSONDecoder().decode(AuthTokens.self, from: data)
        } catch {
            try? await delete()
            throw CodexServiceError.storage("The saved credentials were invalid and have been removed.")
        }
    }

    public func save(_ tokens: AuthTokens) async throws {
        guard tokens.isUsable else {
            throw CodexServiceError.authenticationIncomplete
        }
        let data = try JSONEncoder().encode(tokens)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let updateStatus = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return
        }
        guard updateStatus == errSecItemNotFound else {
            throw keychainError(updateStatus, operation: "update")
        }

        var addition = baseQuery
        attributes.forEach { addition[$0.key] = $0.value }
        let addStatus = SecItemAdd(addition as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw keychainError(addStatus, operation: "save")
        }
    }

    public func delete() async throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw keychainError(status, operation: "delete")
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false
        ]
    }

    private func keychainError(_ status: OSStatus, operation: String) -> CodexServiceError {
        let detail = SecCopyErrorMessageString(status, nil) as String? ?? "status \(status)"
        return .storage("Could not \(operation) credentials in Keychain (\(detail)).")
    }
}
