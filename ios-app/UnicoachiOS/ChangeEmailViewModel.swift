import Foundation

@MainActor
class ChangeEmailViewModel: ObservableObject {
    @Published var email: String
    @Published var isLoading = false
    @Published var errorResponse: ErrorResponse?

    let authClient: AuthClientProtocol
    let onChanged: (PublicUser) -> Void

    init(email: String, authClient: AuthClientProtocol, onChanged: @escaping (PublicUser) -> Void) {
        self.email = email
        self.authClient = authClient
        self.onChanged = onChanged
    }

    func submit() async {
        errorResponse = nil

        // Local pre-flight only guards against an empty address; all format
        // validation is deferred to the server (mirrors RegistrationViewModel's
        // deference of format checks).
        if email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            errorResponse = ErrorResponse(code: "VALIDATION", message: String(localized: "Please enter an email address."), fieldErrors: nil)
            return
        }

        isLoading = true
        defer { isLoading = false }

        do {
            let user = try await authClient.changeEmail(email)
            onChanged(user)
        } catch let error as ErrorResponse {
            errorResponse = error
        } catch {
            errorResponse = ErrorResponse(code: "SERVER_ERROR", message: String(localized: "An unexpected error occurred."), fieldErrors: nil)
        }
    }
}
