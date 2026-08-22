import Foundation
import os

protocol AuthClientProtocol: Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse
    func login(request: LoginRequest) async throws -> LoginResponse
    func logout() async throws
    func me() async throws -> MeResponse
    func resendVerification() async throws
    func changeEmail(_ email: String) async throws -> PublicUser
}

/// Narrow protocol for the SSO sign-in POST, kept off `AuthClientProtocol` so
/// the preview/test conformers that never build a `LoginViewModel` need no stub.
/// Only conformers that construct a `LoginViewModel` (whose `authClient` is typed
/// `AuthClientProtocol & SsoAuthenticating`) must adopt it. Replaces RFC 90's
/// Google-only `GoogleAuthenticating`.
protocol SsoAuthenticating {
    func signIn(with credential: SsoCredential) async throws -> LoginResponse
}

class AuthClient: AuthClientProtocol, SsoAuthenticating, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "AuthClient")

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

    /// One transport call for every provider: the credential supplies its own
    /// route and request body, and the shared response contract lives in
    /// `postSsoLogin`. A new provider adds a `SsoCredential` case and nothing
    /// here.
    func signIn(with credential: SsoCredential) async throws -> LoginResponse {
        logger.debug("Starting SSO sign-in [provider=\(credential.provider.rawValue, privacy: .public)]")
        return try await postSsoLogin(credential.path, body: credential.requestBody)
    }

    /// Every SSO login endpoint answers `200` with a `LoginResponse` body and
    /// sets the session cookie, so one method holds that contract for all of
    /// them.
    private func postSsoLogin<B: Encodable>(_ path: String, body: B) async throws -> LoginResponse {
        let (data, response) = try await apiClient.post(path, body: body)
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

    func resendVerification() async throws {
        logger.debug("Starting resend-verification")
        let (data, response) = try await apiClient.post("/api/v1/auth/resend-verification")
        try apiClient.expect(data: data, response: response, expectedStatus: 204)
    }

    func changeEmail(_ email: String) async throws -> PublicUser {
        logger.debug("Starting change-email")
        let (data, response) = try await apiClient.post("/api/v1/auth/change-email", body: ChangeEmailRequest(email: email))
        let decoded: ChangeEmailResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return decoded.user
    }
}
