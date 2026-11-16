#if os(macOS)
import CodexMeterCore
import Foundation
import Network

/// A one-shot HTTP server on 127.0.0.1 for browser sign-ins that return to a local port
/// (OpenRouter, Antigravity). It answers the first `/callback` request, then stops. Only the
/// Mac app uses it; iOS suspends the app while the browser is in front.
nonisolated final class LoopbackServer: @unchecked Sendable {
    enum Failure: LocalizedError {
        case couldNotStart
        case timedOut

        var errorDescription: String? {
            switch self {
            case .couldNotStart: "Codex Meter couldn't open a local port for the sign-in."
            case .timedOut: "The sign-in took too long. Start again."
            }
        }
    }

    private let listener: NWListener
    private let queue = DispatchQueue(label: "codex-meter.loopback")
    private let lock = NSLock()
    private var continuation: CheckedContinuation<[String: String], Error>?
    private var result: Result<[String: String], Error>?
    private var connections: [NWConnection] = []

    private(set) var port: UInt16 = 0

    var redirectURI: String { LoopbackCallback.redirectURI(port: port) }

    private init(listener: NWListener) {
        self.listener = listener
    }

    /// Binds a free port on the loopback interface only.
    static func start() async throws -> LoopbackServer {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: .any)
        parameters.acceptLocalOnly = true
        guard let listener = try? NWListener(using: parameters) else { throw Failure.couldNotStart }
        let server = LoopbackServer(listener: listener)
        try await server.run()
        return server
    }

    private func run() async throws {
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            let once = OnceBox()
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if once.claim() { continuation.resume(returning: self?.listener.port?.rawValue ?? 0) }
                case .failed, .cancelled:
                    if once.claim() { continuation.resume(throwing: Failure.couldNotStart) }
                    self?.finish(.failure(Failure.couldNotStart))
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
        guard port != 0 else { throw Failure.couldNotStart }
        self.port = port
    }

    /// The callback's query parameters, once the browser comes back.
    func waitForCallback(timeout: Duration = .seconds(300)) async throws -> [String: String] {
        let timer = Task { [weak self] in
            try await Task.sleep(for: timeout)
            self?.finish(.failure(Failure.timedOut))
        }
        defer { timer.cancel() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if let result {
                    lock.unlock()
                    continuation.resume(with: result)
                } else {
                    self.continuation = continuation
                    lock.unlock()
                }
            }
        } onCancel: {
            finish(.failure(CancellationError()))
        }
    }

    func stop() {
        finish(.failure(CancellationError()))
    }

    private func finish(_ outcome: Result<[String: String], Error>) {
        lock.lock()
        guard result == nil else {
            lock.unlock()
            return
        }
        result = outcome
        let waiting = continuation
        continuation = nil
        let open = connections
        connections = []
        lock.unlock()
        waiting?.resume(with: outcome)
        listener.cancel()
        // Let the success page reach the browser before the socket closes.
        queue.asyncAfter(deadline: .now() + 1) {
            open.forEach { $0.cancel() }
        }
    }

    private func accept(_ connection: NWConnection) {
        lock.lock()
        connections.append(connection)
        lock.unlock()
        connection.start(queue: queue)
        receive(on: connection, buffer: Data())
    }

    private func receive(on connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            let head = String(decoding: buffer, as: UTF8.self)
            if head.contains("\r\n\r\n") || isComplete || error != nil || buffer.count >= 16_384 {
                self.respond(to: head, on: connection)
            } else {
                self.receive(on: connection, buffer: buffer)
            }
        }
    }

    private func respond(to head: String, on connection: NWConnection) {
        guard let parameters = LoopbackCallback.parameters(requestHead: head) else {
            // A favicon or a stray request: answer it and keep waiting for the callback.
            let notFound = Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
            connection.send(content: notFound, completion: .contentProcessed { _ in connection.cancel() })
            return
        }
        let success = parameters["code"].map { !$0.isEmpty } ?? false
        connection.send(content: LoopbackCallback.response(success: success),
                        completion: .contentProcessed { _ in })
        finish(.success(parameters))
    }
}

/// Resumes a continuation only once, from whichever listener state arrives first.
private nonisolated final class OnceBox: @unchecked Sendable {
    private let lock = NSLock()
    private var claimed = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if claimed { return false }
        claimed = true
        return true
    }
}
#endif
