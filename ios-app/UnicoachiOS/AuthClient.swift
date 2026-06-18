import Foundation
import os

protocol AuthClientProtocol: Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse
    func login(request: LoginRequest) async throws -> LoginResponse
    func logout() async throws
    func me() async throws -> MeResponse
}

class AuthClient: AuthClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "com.unicoachapp.UnicoachiOS", category: "AuthClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func register(request: RegisterRequest) async throws -> RegisterResponse {
        logger.debug("Starting registration for [\(request.email)]")
        let (data, response) = try await apiClient.post("/api/v1/auth/register", body: request)
        return try apiClient.decode(data: data, response: response, expectedStatus: 201)
    }

    func login(request: LoginRequest) async throws -> LoginResponse {
        logger.debug("Starting login for [\(request.email)]")
        let (data, response) = try await apiClient.post("/api/v1/auth/login", body: request)
        return try apiClient.decode(data: data, response: response, expectedStatus: 200)
    }

    func logout() async throws {
        logger.debug("Starting logout")
        let (data, response) = try await apiClient.post("/api/v1/auth/logout")
        try apiClient.expect(data: data, response: response, expectedStatus: 204)
    }

    func me() async throws -> MeResponse {
        logger.debug("Starting /me check")
        let (data, response) = try await apiClient.get("/api/v1/auth/me")
        return try apiClient.decode(data: data, response: response, expectedStatus: 200)
    }
}
