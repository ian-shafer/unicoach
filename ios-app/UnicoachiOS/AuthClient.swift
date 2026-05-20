import Foundation
import os

protocol AuthClientProtocol: Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse
    func login(request: LoginRequest) async throws -> LoginResponse
    func logout() async throws
    func me() async throws -> MeResponse
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
        logger.debug("Starting registration for [\\(request.email)]")
        return try await performRequest(method: "POST", path: "/api/v1/auth/register", body: request, expectedStatus: 201)
    }
    
    func login(request: LoginRequest) async throws -> LoginResponse {
        logger.debug("Starting login for [\\(request.email)]")
        return try await performRequest(method: "POST", path: "/api/v1/auth/login", body: request, expectedStatus: 200)
    }
    
    func logout() async throws {
        logger.debug("Starting logout")
        try await performVoidRequest(method: "POST", path: "/api/v1/auth/logout", expectedStatus: 204)
    }
    
    func me() async throws -> MeResponse {
        logger.debug("Starting /me check")
        return try await performRequest(method: "GET", path: "/api/v1/auth/me", expectedStatus: 200)
    }

    private func performRequest<T: Decodable, B: Encodable>(method: String, path: String, body: B?, expectedStatus: Int) async throws -> T {
        let (data, response) = try await execute(method: method, path: path, body: body)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ErrorResponse(code: "UNKNOWN", message: String(localized: "Unknown response type"), fieldErrors: nil)
        }
        
        logger.debug("Received HTTP status \\(httpResponse.statusCode)")
        
        if httpResponse.statusCode == expectedStatus {
            do {
                return try JSONDecoder().decode(T.self, from: data)
            } catch {
                throw ErrorResponse(code: "DECODE_ERROR", message: String(localized: "Failed to parse response: [\\(error.localizedDescription)]"), fieldErrors: nil)
            }
        } else {
            try handleHttpError(data: data, statusCode: httpResponse.statusCode)
            throw ErrorResponse(code: "SERVER_ERROR", message: String(localized: "An unexpected error occurred."), fieldErrors: nil)
        }
    }
    
    private func performRequest<T: Decodable>(method: String, path: String, expectedStatus: Int) async throws -> T {
        return try await performRequest(method: method, path: path, body: Never?.none, expectedStatus: expectedStatus)
    }
    
    private func performVoidRequest<B: Encodable>(method: String, path: String, body: B?, expectedStatus: Int) async throws {
        let (data, response) = try await execute(method: method, path: path, body: body)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ErrorResponse(code: "UNKNOWN", message: String(localized: "Unknown response type"), fieldErrors: nil)
        }
        
        logger.debug("Received HTTP status \\(httpResponse.statusCode)")
        
        if httpResponse.statusCode != expectedStatus {
            try handleHttpError(data: data, statusCode: httpResponse.statusCode)
            throw ErrorResponse(code: "SERVER_ERROR", message: String(localized: "An unexpected error occurred."), fieldErrors: nil)
        }
    }
    
    private func performVoidRequest(method: String, path: String, expectedStatus: Int) async throws {
        try await performVoidRequest(method: method, path: path, body: Never?.none, expectedStatus: expectedStatus)
    }
    
    private func execute<B: Encodable>(method: String, path: String, body: B?) async throws -> (Data, URLResponse) {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw URLError(.badURL)
        }
        
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method
        if let body = body {
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
            urlRequest.httpBody = try JSONEncoder().encode(body)
        }
        
        do {
            return try await session.data(for: urlRequest)
        } catch let nsError as NSError where nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorTimedOut {
            throw ErrorResponse(code: "TIMEOUT", message: String(localized: "Network request timed out."), fieldErrors: nil)
        } catch let nsError as NSError where nsError.domain == NSURLErrorDomain && (nsError.code == NSURLErrorNotConnectedToInternet || nsError.code == NSURLErrorCannotFindHost) {
             throw ErrorResponse(code: "NETWORK_ERROR", message: nsError.localizedDescription, fieldErrors: nil)
        } catch {
            throw ErrorResponse(code: "NETWORK_ERROR", message: error.localizedDescription, fieldErrors: nil)
        }
    }
    
    private func handleHttpError(data: Data, statusCode: Int) throws {
        do {
            let errorResponse = try JSONDecoder().decode(ErrorResponse.self, from: data)
            throw errorResponse
        } catch let error as ErrorResponse {
            throw error
        } catch {
            // Unparseable body
            throw ErrorResponse(code: "SERVER_ERROR", message: String(localized: "An unexpected error occurred."), fieldErrors: nil)
        }
    }
}
