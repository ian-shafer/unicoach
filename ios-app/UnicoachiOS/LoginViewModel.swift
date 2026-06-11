import Foundation

@MainActor
class LoginViewModel: ObservableObject {
    @Published var email = ""
    @Published var password = ""
    @Published var isLoading = false
    @Published var errorResponse: ErrorResponse?
    @Published var infrastructureError: InfrastructureError?
    
    let authClient: AuthClientProtocol
    let onLoginSuccess: (PublicUser) async -> Void

    init(authClient: AuthClientProtocol, onLoginSuccess: @escaping (PublicUser) async -> Void) {
        self.authClient = authClient
        self.onLoginSuccess = onLoginSuccess
    }
    
    func login() async {
        errorResponse = nil
        infrastructureError = nil
        
        if email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || password.isEmpty {
            errorResponse = ErrorResponse(code: "VALIDATION", message: String(localized: "Please enter both email and password."), fieldErrors: nil)
            return
        }
        
        isLoading = true
        defer { isLoading = false }
        
        let request = LoginRequest(email: email, password: password)
        do {
            let response = try await authClient.login(request: request)
            await onLoginSuccess(response.user)
        } catch let error as ErrorResponse {
            if error.code == "TIMEOUT" {
                infrastructureError = .timeout
            } else if error.code == "NETWORK_ERROR" {
                infrastructureError = .noConnectivity
            } else if error.code == "SERVER_ERROR" {
                infrastructureError = .serverError
            } else {
                errorResponse = error
            }
        } catch {
            infrastructureError = .serverError
        }
    }
}
