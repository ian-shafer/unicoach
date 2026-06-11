import Foundation

@MainActor
class RegistrationViewModel: ObservableObject {
    @Published var email = ""
    @Published var name = ""
    @Published var password = ""
    
    @Published var isLoading = false
    @Published var errorResponse: ErrorResponse?
    @Published var infrastructureError: InfrastructureError?
    
    private let authClient: AuthClientProtocol
    let onRegisterSuccess: (PublicUser) async -> Void

    init(authClient: AuthClientProtocol, onRegisterSuccess: @escaping (PublicUser) async -> Void) {
        self.authClient = authClient
        self.onRegisterSuccess = onRegisterSuccess
    }
    
    func register() async {
        self.errorResponse = nil
        self.infrastructureError = nil
        
        if email.isEmpty || name.isEmpty || password.isEmpty {
            self.errorResponse = ErrorResponse(code: "VALIDATION", message: String(localized: "All fields are required."), fieldErrors: nil)
            return
        }
        
        if password.count < 8 {
            self.errorResponse = ErrorResponse(code: "VALIDATION", message: String(localized: "Validation error"), fieldErrors: [
                FieldError(field: "password", message: String(localized: "Password must be at least 8 characters."))
            ])
            return
        }
        
        self.isLoading = true
        defer { self.isLoading = false }
        
        let request = RegisterRequest(email: email, password: password, name: name)
        
        do {
            let response = try await authClient.register(request: request)
            await onRegisterSuccess(response.user)
        } catch let error as ErrorResponse {
            if error.code == "TIMEOUT" {
                self.infrastructureError = .timeout
            } else if error.code == "NETWORK_ERROR" {
                self.infrastructureError = .noConnectivity
            } else if error.code == "SERVER_ERROR" {
                self.infrastructureError = .serverError
            } else {
                self.errorResponse = error
            }
        } catch {
            self.infrastructureError = .serverError
        }
    }
}
