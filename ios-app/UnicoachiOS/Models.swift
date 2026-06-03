import Foundation

struct RegisterRequest: Codable {
    let email: String
    let password: String
    let name: String
}

struct PublicUser: Codable {
    let id: UUID
    let email: String
    let name: String
}

struct RegisterResponse: Codable {
    let user: PublicUser
}

struct FieldError: Codable, Equatable {
    let field: String
    let message: String
}

struct ErrorResponse: Codable, Error, Identifiable {
    var id: String { code }
    let code: String
    let message: String
    let fieldErrors: [FieldError]?
}

extension ErrorResponse {
    func fieldError(for field: String) -> String? {
        fieldErrors?.first(where: { $0.field == field })?.message
    }
}

struct LoginRequest: Codable {
    let email: String
    let password: String
}

struct LoginResponse: Codable {
    let user: PublicUser
}

struct MeResponse: Codable {
    let user: PublicUser
}
