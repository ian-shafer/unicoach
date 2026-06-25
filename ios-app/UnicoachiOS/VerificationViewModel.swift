import Foundation

@MainActor
class VerificationViewModel: ObservableObject {
    /// The email address being verified. Seeded from the routed user and updated
    /// on a successful change-email so the displayed address tracks the change.
    @Published var email: String
    /// The "verification sent to <new address>" message set when a change-email
    /// succeeds. Distinct from `resendConfirmation` so the two confirmations do
    /// not collide.
    @Published var changeConfirmation: String?

    @Published var isResending = false
    @Published var resendConfirmation: String?
    @Published var resendError: ErrorResponse?

    @Published var isChecking = false
    @Published var recheckMessage: String?

    let authClient: AuthClientProtocol
    let onRecheck: () async -> AppViewModel.VerificationRecheckOutcome
    let onLogout: () async -> Void

    init(
        user: PublicUser,
        authClient: AuthClientProtocol,
        onRecheck: @escaping () async -> AppViewModel.VerificationRecheckOutcome,
        onLogout: @escaping () async -> Void
    ) {
        self.email = user.email
        self.authClient = authClient
        self.onRecheck = onRecheck
        self.onLogout = onLogout
    }

    func resend() async {
        resendError = nil
        resendConfirmation = nil
        isResending = true
        defer { isResending = false }

        do {
            try await authClient.resendVerification()
            resendConfirmation = String(localized: "Verification email sent to \(email).")
        } catch let error as ErrorResponse {
            resendError = error
        } catch {
            resendError = ErrorResponse(code: "SERVER_ERROR", message: String(localized: "An unexpected error occurred."), fieldErrors: nil)
        }
    }

    func checkAgain() async {
        recheckMessage = nil
        isChecking = true
        defer { isChecking = false }

        let outcome = await onRecheck()
        switch outcome {
        case .verified:
            // The screen is torn down before this message would render.
            break
        case .stillUnverified:
            recheckMessage = String(localized: "Your email isn't verified yet. Tap the link in the email, then check again.")
        case .failed:
            recheckMessage = String(localized: "Couldn't check verification status. Please try again.")
        }
    }

    /// Updates the displayed address and confirmation after a successful
    /// change-email. Bound as the `ChangeEmailViewModel.onChanged` callback.
    func onEmailChanged(_ user: PublicUser) {
        email = user.email
        resendConfirmation = nil
        changeConfirmation = String(localized: "Verification email sent to \(user.email).")
    }
}
