import CodexMeterCore
import Foundation
@testable import CodexMeter

struct StubbedHTTPResponse: Sendable {
    let statusCode: Int
    let headers: [String: String]
    let body: Data

    init(statusCode: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
    }

    static func json(_ object: Any, statusCode: Int = 200) throws -> Self {
        Self(
            statusCode: statusCode,
            headers: ["Content-Type": "application/json"],
            body: try JSONSerialization.data(withJSONObject: object)
        )
    }
}

final class LockedBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withValue<Result>(_ operation: (inout Value) throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try operation(&value)
    }

    func read() -> Value {
        withValue { $0 }
    }
}

final class URLProtocolRegistry: @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) throws -> StubbedHTTPResponse

    private let lock = NSLock()
    private var handler: Handler?
    private var requests: [URLRequest] = []

    func configure(_ handler: @escaping Handler) {
        lock.lock()
        self.handler = handler
        requests = []
        lock.unlock()
    }

    func reset() {
        lock.lock()
        handler = nil
        requests = []
        lock.unlock()
    }

    func response(for request: URLRequest) throws -> StubbedHTTPResponse {
        let handler: Handler?
        lock.lock()
        requests.append(request)
        handler = self.handler
        lock.unlock()
        guard let handler else {
            throw URLError(.unsupportedURL)
        }
        return try handler(request)
    }

    func recordedRequests() -> [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }
}

final class CodexMeterURLProtocol: URLProtocol, @unchecked Sendable {
    private static let registry = URLProtocolRegistry()

    static func configure(_ handler: @escaping URLProtocolRegistry.Handler) {
        registry.configure(handler)
    }

    static func reset() {
        registry.reset()
    }

    static var requests: [URLRequest] {
        registry.recordedRequests()
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        do {
            let stub = try Self.registry.response(for: request)
            guard let url = request.url,
                  let response = HTTPURLResponse(
                    url: url,
                    statusCode: stub.statusCode,
                    httpVersion: "HTTP/1.1",
                    headerFields: stub.headers
                  )
            else {
                throw URLError(.badServerResponse)
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: stub.body)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

actor MemoryTokenStore: TokenStoring {
    private var tokens: AuthTokens?
    private(set) var saveCount = 0
    private(set) var deleteCount = 0

    init(_ tokens: AuthTokens? = nil) {
        self.tokens = tokens
    }

    func load() async throws -> AuthTokens? {
        tokens
    }

    func save(_ tokens: AuthTokens) async throws {
        self.tokens = tokens
        saveCount += 1
    }

    func delete() async throws {
        tokens = nil
        deleteCount += 1
    }
}

func makeStubbedSession() -> URLSession {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [CodexMeterURLProtocol.self]
    configuration.timeoutIntervalForRequest = 2
    configuration.timeoutIntervalForResource = 3
    return URLSession(configuration: configuration)
}

func makeJWT(_ payload: [String: Any]) throws -> String {
    let header = try JSONSerialization.data(withJSONObject: ["alg": "none", "typ": "JWT"])
    let payload = try JSONSerialization.data(withJSONObject: payload)
    return "\(base64URL(header)).\(base64URL(payload)).signature"
}

private func base64URL(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

func requestJSON(_ request: URLRequest) throws -> [String: Any] {
    guard let body = requestBody(request),
          let object = try JSONSerialization.jsonObject(with: body) as? [String: Any]
    else {
        throw URLError(.cannotParseResponse)
    }
    return object
}

func requestForm(_ request: URLRequest) -> [String: String] {
    guard let body = requestBody(request),
          let string = String(data: body, encoding: .utf8)
    else { return [:] }
    var components = URLComponents()
    components.percentEncodedQuery = string
    return Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map {
        ($0.name, $0.value ?? "")
    })
}

private func requestBody(_ request: URLRequest) -> Data? {
    if let body = request.httpBody {
        return body
    }
    guard let stream = request.httpBodyStream else { return nil }
    stream.open()
    defer { stream.close() }

    var body = Data()
    var buffer = [UInt8](repeating: 0, count: 4_096)
    while stream.hasBytesAvailable {
        let count = stream.read(&buffer, maxLength: buffer.count)
        guard count >= 0 else { return nil }
        if count == 0 { break }
        body.append(buffer, count: count)
    }
    return body
}

func usageResponse(used: Int = 40) -> [String: Any] {
    [
        "plan_type": "plus",
        "rate_limit": [
            "allowed": true,
            "limit_reached": false,
            "primary_window": [
                "used_percent": used,
                "limit_window_seconds": 18_000,
                "reset_after_seconds": 3_600
            ],
            "secondary_window": [
                "used_percent": 60,
                "limit_window_seconds": 604_800,
                "reset_after_seconds": 86_400
            ]
        ]
    ]
}

func creditsResponse(count: Int = 1) -> [String: Any] {
    [
        "available_count": count,
        "credits": (0..<count).map { index in
            [
                "id": "credit-\(index)",
                "reset_type": "rate_limit",
                "status": "available",
                "expires_at": "2030-01-0\(index + 2)T00:00:00Z"
            ]
        }
    ]
}

func temporaryFileURL(_ name: String = UUID().uuidString) -> URL {
    FileManager.default.temporaryDirectory
        .appendingPathComponent("CodexMeterTests-\(name)")
}
