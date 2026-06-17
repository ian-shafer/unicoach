import Foundation
import os

final class APIClient: @unchecked Sendable {
    /// Inter-chunk idle timeout for streaming requests. Larger than the request
    /// session's `10`: a short timeout would abort a slow time-to-first-token.
    static let streamIdleTimeout: TimeInterval = 60

    let baseURL: URL
    /// The client key baked into the bundle at build time, sent on every request
    /// via `X-Unicoach-Client-Key` when non-nil. Nil on a local build (blank key)
    /// means no header is sent, which the disabled local gate accepts.
    let clientKey: String?
    let session: URLSession
    /// A second session for streaming requests. A dedicated session is used
    /// because a per-request `timeoutInterval` cannot reliably extend a
    /// session-level value, and streaming needs the longer idle timeout.
    let streamSession: URLSession
    private let logger = Logger(subsystem: "com.unicoach.UnicoachiOS", category: "APIClient")

    /// Decoder configured for the server's ISO-8601 `Instant` timestamps (Jackson
    /// `JavaTimeModule`): strings with variable-precision fractional seconds and a
    /// trailing `Z` — e.g. `2025-01-07T22:16:27.092942Z`, or `2025-01-07T22:16:27Z`
    /// on a whole second. The default `.deferredToDate` strategy expects a numeric
    /// timestamp and fails on these; plain `.iso8601` rejects fractional seconds.
    /// So we try a fractional then a no-fraction formatter.
    let jsonDecoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let raw = try container.decode(String.self)
            if let date = APIClient.iso8601WithFraction.date(from: raw)
                ?? APIClient.iso8601NoFraction.date(from: raw) {
                return date
            }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Expected an ISO-8601 date-time, got [\(raw)]"
            )
        }
        return decoder
    }()

    // ISO8601DateFormatter is thread-safe for parsing, so sharing one instance
    // across concurrent decodes is sound.
    nonisolated(unsafe) private static let iso8601WithFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    nonisolated(unsafe) private static let iso8601NoFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    init(baseURL: URL = defaultBackendURL(), clientKey: String? = defaultClientKey(), session: URLSession? = nil) {
        self.baseURL = baseURL
        self.clientKey = clientKey
        if let session = session {
            // Injected (tests): the same session backs both request and stream paths.
            self.session = session
            self.streamSession = session
        } else {
            let requestConfig = URLSessionConfiguration.default
            requestConfig.timeoutIntervalForRequest = 10.0
            self.session = URLSession(configuration: requestConfig)

            let streamConfig = URLSessionConfiguration.default
            streamConfig.timeoutIntervalForRequest = APIClient.streamIdleTimeout
            self.streamSession = URLSession(configuration: streamConfig)
        }
    }

    func post<B: Encodable>(_ path: String, body: B) async throws -> (data: Data, response: HTTPURLResponse) {
        try await perform(method: "POST", path: path, body: body)
    }

    func post(_ path: String) async throws -> (data: Data, response: HTTPURLResponse) {
        try await perform(method: "POST", path: path, body: Never?.none)
    }

    func get(_ path: String) async throws -> (data: Data, response: HTTPURLResponse) {
        try await perform(method: "GET", path: path, body: Never?.none)
    }

    func delete(_ path: String) async throws -> (data: Data, response: HTTPURLResponse) {
        try await perform(method: "DELETE", path: path, body: Never?.none)
    }

    func patch<B: Encodable>(_ path: String, body: B) async throws -> (data: Data, response: HTTPURLResponse) {
        try await perform(method: "PATCH", path: path, body: body)
    }

    func decode<T: Decodable>(data: Data, response: HTTPURLResponse, expectedStatus: Int) throws -> T {
        if response.statusCode == expectedStatus {
            do {
                return try jsonDecoder.decode(T.self, from: data)
            } catch {
                let body = String(decoding: data, as: UTF8.self)
                logger.error("Decode failed for [\(String(describing: T.self), privacy: .public)]: [\(error, privacy: .public)] body=[\(body, privacy: .public)]")
                throw ErrorResponse(code: "DECODE_ERROR", message: "Failed to parse response: [\(error)]", fieldErrors: nil)
            }
        }
        throw decodeError(data: data)
    }

    func expect(data: Data, response: HTTPURLResponse, expectedStatus: Int) throws {
        if response.statusCode != expectedStatus {
            throw decodeError(data: data)
        }
    }

    private func perform<B: Encodable>(method: String, path: String, body: B?) async throws -> (data: Data, response: HTTPURLResponse) {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw URLError(.badURL)
        }

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method
        if let clientKey = clientKey {
            urlRequest.setValue(clientKey, forHTTPHeaderField: "X-Unicoach-Client-Key")
        }
        if let body = body {
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
            urlRequest.httpBody = try JSONEncoder().encode(body)
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: urlRequest)
        } catch {
            throw transportError(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            logger.error("Non-HTTP response: [\(method, privacy: .public)] [\(path, privacy: .public)]")
            throw ErrorResponse(code: "UNKNOWN", message: String(localized: "Unknown response type"), fieldErrors: nil)
        }

        logger.debug("Received HTTP status [\(httpResponse.statusCode, privacy: .public)] for [\(method, privacy: .public)] [\(path, privacy: .public)]")
        return (data, httpResponse)
    }

    /// Issues a streaming `POST` request, returning the live byte stream once
    /// headers arrive. Builds the request exactly as `perform` does (`POST`,
    /// `Content-Type: application/json`, encoded body) plus an `Accept` header.
    /// Connection-phase transport failures map through `transportError`; a
    /// non-`HTTPURLResponse` throws `UNKNOWN`. On a status mismatch, the buffered
    /// body is drained and the decoded `ErrorResponse` (or `SERVER_ERROR` on an
    /// unparseable body) is thrown. SSE semantics remain the caller's job.
    func stream<B: Encodable>(_ path: String, body: B, accept: String, expectedStatus: Int) async throws -> URLSession.AsyncBytes {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw URLError(.badURL)
        }

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        if let clientKey = clientKey {
            urlRequest.setValue(clientKey, forHTTPHeaderField: "X-Unicoach-Client-Key")
        }
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue(accept, forHTTPHeaderField: "Accept")
        urlRequest.httpBody = try JSONEncoder().encode(body)

        let bytes: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (bytes, response) = try await streamSession.bytes(for: urlRequest)
        } catch {
            throw transportError(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            logger.error("Non-HTTP response: [POST] [\(path, privacy: .public)]")
            throw ErrorResponse(code: "UNKNOWN", message: String(localized: "Unknown response type"), fieldErrors: nil)
        }

        logger.debug("Received HTTP status [\(httpResponse.statusCode, privacy: .public)] for [POST] [\(path, privacy: .public)]")

        if httpResponse.statusCode != expectedStatus {
            var data = Data()
            for try await byte in bytes {
                data.append(byte)
            }
            throw decodeError(data: data)
        }
        return bytes
    }

    /// Maps a transport-layer failure into the shared error vocabulary:
    /// `NSURLErrorTimedOut` → `TIMEOUT`, anything else → `NETWORK_ERROR`. Used by
    /// `perform`/`stream` for connection-phase failures and by `ConversationClient`
    /// for failures thrown mid-iteration by `AsyncBytes` (outside `stream()`).
    func transportError(_ error: Error) -> ErrorResponse {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorTimedOut {
            logger.error("Request timed out: [\(error, privacy: .public)]")
            return ErrorResponse(code: "TIMEOUT", message: String(localized: "Network request timed out."), fieldErrors: nil)
        }
        logger.error("Network error: [\(error, privacy: .public)]")
        return ErrorResponse(code: "NETWORK_ERROR", message: error.localizedDescription, fieldErrors: nil)
    }

    private func decodeError(data: Data) -> ErrorResponse {
        do {
            let error = try jsonDecoder.decode(ErrorResponse.self, from: data)
            logger.error("Server error response: code=[\(error.code, privacy: .public)] message=[\(error.message, privacy: .public)]")
            return error
        } catch {
            let body = String(decoding: data, as: UTF8.self)
            logger.error("Unparseable error body: [\(error, privacy: .public)] body=[\(body, privacy: .public)]")
            return ErrorResponse(code: "SERVER_ERROR", message: String(localized: "An unexpected error occurred."), fieldErrors: nil)
        }
    }
}
