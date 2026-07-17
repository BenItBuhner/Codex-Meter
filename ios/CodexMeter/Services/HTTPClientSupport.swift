import Foundation

nonisolated struct HTTPPayload: Sendable {
    let statusCode: Int
    let data: Data
}

nonisolated enum HTTPClientSupport {
    static let maximumResponseBytes = 2 * 1_024 * 1_024

    static func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 25
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieAcceptPolicy = .onlyFromMainDocumentDomain
        configuration.httpShouldSetCookies = true
        configuration.httpAdditionalHeaders = [
            "Accept": "application/json",
            "User-Agent": "codex-meter-ios/1.0"
        ]
        return URLSession(configuration: configuration)
    }

    static func send(_ request: URLRequest, using session: URLSession) async throws -> HTTPPayload {
        try Task.checkCancellation()
        let (bytes, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw CodexServiceError.invalidResponse
        }
        if response.expectedContentLength > Int64(maximumResponseBytes) {
            throw CodexServiceError.responseTooLarge
        }

        var data = Data()
        if response.expectedContentLength > 0 {
            data.reserveCapacity(min(Int(response.expectedContentLength), maximumResponseBytes))
        }
        for try await byte in bytes {
            try Task.checkCancellation()
            guard data.count < maximumResponseBytes else {
                throw CodexServiceError.responseTooLarge
            }
            data.append(byte)
        }
        return HTTPPayload(statusCode: response.statusCode, data: data)
    }

    static func requireSuccess(_ payload: HTTPPayload, fallback: String) throws -> Data {
        guard (200..<300).contains(payload.statusCode) else {
            throw CodexServiceError.http(
                status: payload.statusCode,
                message: serverMessage(from: payload.data, fallback: fallback)
            )
        }
        return payload.data
    }

    static func serverMessage(from data: Data, fallback: String) -> String {
        guard !data.isEmpty,
              let value = try? JSONSerialization.jsonObject(with: data),
              let object = value as? [String: Any]
        else {
            return fallback
        }

        let nestedError = object["error"] as? [String: Any]
        let candidate = (nestedError?["message"] as? String)
            ?? (object["message"] as? String)
            ?? (object["detail"] as? String)
            ?? fallback
        let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        return String((trimmed.isEmpty ? fallback : trimmed).prefix(240))
    }

    static func jsonRequest(url: URL, body: [String: String]) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }

    static func formRequest(url: URL, fields: [String: String]) -> URLRequest {
        var components = URLComponents()
        components.queryItems = fields
            .sorted(by: { $0.key < $1.key })
            .map { URLQueryItem(name: $0.key, value: $0.value) }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = components.percentEncodedQuery?.data(using: .utf8)
        return request
    }

    static func number(_ value: Any?, default fallback: TimeInterval) -> TimeInterval {
        if let number = value as? NSNumber {
            return number.doubleValue
        }
        if let string = value as? String, let number = Double(string) {
            return number
        }
        return fallback
    }
}
