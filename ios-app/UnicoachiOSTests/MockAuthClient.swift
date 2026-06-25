import Foundation
@testable import UnicoachiOS

class MockAuthClient: AuthClientProtocol, @unchecked Sendable {
    var registerResult: Result<RegisterResponse, Error>?
    var loginResult: Result<LoginResponse, Error>?
    var logoutResult: Result<Void, Error>?
    var meResult: Result<MeResponse, Error>?
    var resendVerificationResult: Result<Void, Error>?
    var changeEmailResult: Result<PublicUser, Error>?
    
    func register(request: RegisterRequest) async throws -> RegisterResponse {
        if let result = registerResult {
            switch result {
            case .success(let response):
                return response
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }
    
    func login(request: LoginRequest) async throws -> LoginResponse {
        if let result = loginResult {
            switch result {
            case .success(let response):
                return response
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }
    
    func logout() async throws {
        if let result = logoutResult {
            switch result {
            case .success:
                return
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }
    
    func me() async throws -> MeResponse {
        if let result = meResult {
            switch result {
            case .success(let response):
                return response
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }

    func resendVerification() async throws {
        if let result = resendVerificationResult {
            switch result {
            case .success:
                return
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }

    func changeEmail(_ email: String) async throws -> PublicUser {
        if let result = changeEmailResult {
            switch result {
            case .success(let user):
                return user
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }
}
