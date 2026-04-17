import Foundation
import os

protocol AuthClientProtocol: Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse
}

class AuthClient: AuthClientProtocol, @unchecked Sendable {
    let baseURL: URL
    let session: URLSession
    private let logger = Logger(subsystem: "com.unicoach.UnicoachiOS", category: "AuthClient")
    
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
    
    func register(request: RegisterRequest) async throws -> RegisterResponse {
        logger.debug("Starting registration for [\(request.email)]")
        guard let url = URL(string: "/api/v1/auth/register", relativeTo: baseURL) else {
            throw URLError(.badURL)
        }
        
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        urlRequest.httpBody = try JSONEncoder().encode(request)
        
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: urlRequest)
        } catch let nsError as NSError where nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorTimedOut {
            throw ErrorResponse(code: "TIMEOUT", message: String(localized: "Network request timed out."), fieldErrors: nil)
        } catch {
            throw ErrorResponse(code: "NETWORK_ERROR", message: error.localizedDescription, fieldErrors: nil)
        }
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ErrorResponse(code: "UNKNOWN", message: String(localized: "Unknown response type"), fieldErrors: nil)
        }
        
        logger.debug("Received HTTP status \(httpResponse.statusCode)")
        
        if httpResponse.statusCode == 201 {
            do {
                return try JSONDecoder().decode(RegisterResponse.self, from: data)
            } catch {
                throw ErrorResponse(code: "DECODE_ERROR", message: String(localized: "Failed to parse response: [\(error.localizedDescription)]"), fieldErrors: nil)
            }
        } else {
            do {
                let errorResponse = try JSONDecoder().decode(ErrorResponse.self, from: data)
                throw errorResponse
            } catch let error as ErrorResponse {
                throw error
            } catch {
                throw ErrorResponse(code: "SERVER_ERROR", message: String(localized: "An unexpected error occurred."), fieldErrors: nil)
            }
        }
    }
}
