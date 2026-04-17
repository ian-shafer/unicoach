import Foundation

@MainActor
class RegistrationViewModel: ObservableObject {
    @Published var email = ""
    @Published var name = ""
    @Published var password = ""
    
    @Published var isLoading = false
    @Published var errorResponse: ErrorResponse?
    
    private let authClient: AuthClientProtocol
    
    init(authClient: AuthClientProtocol = AuthClient()) {
        self.authClient = authClient
    }
    
    func register() async {
        self.errorResponse = nil
        
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
            let _ = try await authClient.register(request: request)
            // Upon success, gracefully handle the next step (routing away, showing success, etc.).
            // In this isolated app, we're just resetting form or completing the register action.
            self.email = ""
            self.name = ""
            self.password = ""
        } catch let error as ErrorResponse {
            self.errorResponse = error
        } catch {
            self.errorResponse = ErrorResponse(code: "UNKNOWN", message: error.localizedDescription, fieldErrors: nil)
        }
    }
}
