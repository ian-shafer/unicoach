import Foundation
import os

final class APIClient: @unchecked Sendable {
    let baseURL: URL
    let session: URLSession
    private let logger = Logger(subsystem: "com.unicoach.UnicoachiOS", category: "APIClient")

    /// Decoder configured for the server's ISO-8601 `Instant` timestamps (Jackson
    /// `JavaTimeModule`): strings with variable-precision fractional seconds and a
    /// trailing `Z` — e.g. `2025-01-07T22:16:27.092942Z`, or `2025-01-07T22:16:27Z`
    /// on a whole second. The default `.deferredToDate` strategy expects a numeric
    /// timestamp and fails on these; plain `.iso8601` rejects fractional seconds.
    /// So we try a fractional then a no-fraction formatter.
    private let jsonDecoder: JSONDecoder = {
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

    init(baseURL: URL = URL(string: "http://localhost:8080")!, session: URLSession? = nil) {
        self.baseURL = baseURL
        if let session = session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 10.0
            self.session = URLSession(configuration: config)
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
        if let body = body {
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
            urlRequest.httpBody = try JSONEncoder().encode(body)
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: urlRequest)
        } catch let nsError as NSError where nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorTimedOut {
            logger.error("Request timed out: [\(method, privacy: .public)] [\(path, privacy: .public)]")
            throw ErrorResponse(code: "TIMEOUT", message: String(localized: "Network request timed out."), fieldErrors: nil)
        } catch {
            logger.error("Network error: [\(method, privacy: .public)] [\(path, privacy: .public)]: [\(error, privacy: .public)]")
            throw ErrorResponse(code: "NETWORK_ERROR", message: error.localizedDescription, fieldErrors: nil)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            logger.error("Non-HTTP response: [\(method, privacy: .public)] [\(path, privacy: .public)]")
            throw ErrorResponse(code: "UNKNOWN", message: String(localized: "Unknown response type"), fieldErrors: nil)
        }

        logger.debug("Received HTTP status [\(httpResponse.statusCode, privacy: .public)] for [\(method, privacy: .public)] [\(path, privacy: .public)]")
        return (data, httpResponse)
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
