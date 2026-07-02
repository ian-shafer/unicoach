import Foundation
import os

/// The mutually exclusive loading states of the login screen. The password and
/// Google flows can never run at once (each button is disabled whenever either
/// is loading), so a single phase makes a both-loading state unrepresentable.
enum SignInPhase: Equatable {
    case idle
    case passwordLoading
    case googleLoading
}

@MainActor
class LoginViewModel: ObservableObject {
    @Published var email = ""
    @Published var password = ""
    @Published var phase: SignInPhase = .idle
    @Published var errorResponse: ErrorResponse?
    @Published var infrastructureError: InfrastructureError?

    let authClient: AuthClientProtocol & GoogleAuthenticating
    let googleSignInProvider: GoogleSignInProviding
    let onLoginSuccess: (PublicUser) async -> Void
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "LoginViewModel")

    init(authClient: AuthClientProtocol & GoogleAuthenticating, googleSignInProvider: GoogleSignInProviding, onLoginSuccess: @escaping (PublicUser) async -> Void) {
        self.authClient = authClient
        self.googleSignInProvider = googleSignInProvider
        self.onLoginSuccess = onLoginSuccess
    }

    func login() async {
        errorResponse = nil
        infrastructureError = nil

        if email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || password.isEmpty {
            errorResponse = ErrorResponse(code: "VALIDATION", message: String(localized: "Please enter both email and password."), fieldErrors: nil)
            return
        }

        phase = .passwordLoading
        defer { phase = .idle }

        let request = LoginRequest(email: email, password: password)
        do {
            let response = try await authClient.login(request: request)
            await onLoginSuccess(response.user)
        } catch {
            mapBackendError(error)
        }
    }

    func signInWithGoogle() async {
        errorResponse = nil
        infrastructureError = nil

        phase = .googleLoading
        defer { phase = .idle }

        let idToken: String
        do {
            switch try await googleSignInProvider.signIn() {
            case .cancelled:
                // User dismissed the sheet: silent no-op, no banner, no callback.
                return
            case .signedIn(let token):
                idToken = token
            }
        } catch {
            mapGoogleProviderError(error)
            return
        }

        do {
            let response = try await authClient.signInWithGoogle(idToken: idToken)
            await onLoginSuccess(response.user)
        } catch {
            mapBackendError(error)
        }
    }

    /// Maps a Google-provider (SDK) failure to the view model's inline banner
    /// with the client-synthesized `GOOGLE_SIGN_IN_FAILED` code (UPPERCASE,
    /// disjoint from backend wire codes; only displayed, never branched on).
    private func mapGoogleProviderError(_ error: Error) {
        logger.error("Google sign-in failed: [\(error, privacy: .public)]")
        errorResponse = ErrorResponse(
            code: "GOOGLE_SIGN_IN_FAILED",
            message: String(localized: "Google sign-in failed. Please try again."),
            fieldErrors: nil
        )
    }

    /// Maps a backend/transport failure to the view model's error surfaces,
    /// identically for the password and Google paths. `TIMEOUT` / `NETWORK_ERROR`
    /// / `SERVER_ERROR` and any non-`ErrorResponse` throw become an
    /// `infrastructureError` (full-screen cover); any other `ErrorResponse` — a
    /// pre-session rejection such as `unauthorized` / `email_not_verified` /
    /// `account_disabled` / `service_unavailable` — becomes an inline banner.
    /// `email_not_verified` here is the account's own unverified email,
    /// categorically NOT the app's `verificationRequired` state, so it must not
    /// enter that flow.
    private func mapBackendError(_ error: Error) {
        guard let error = error as? ErrorResponse else {
            logger.error("Backend call failed (non-ErrorResponse): [\(error, privacy: .public)]")
            infrastructureError = .serverError
            return
        }
        if error.code == "TIMEOUT" {
            infrastructureError = .timeout
        } else if error.code == "NETWORK_ERROR" {
            infrastructureError = .noConnectivity
        } else if error.code == "SERVER_ERROR" {
            infrastructureError = .serverError
        } else {
            errorResponse = error
        }
    }
}
