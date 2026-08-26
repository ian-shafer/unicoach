import Foundation
import os

/// The mutually exclusive loading states of the login screen. The password and
/// SSO flows can never run at once (each button is disabled whenever either is
/// loading), so a single phase makes a both-loading state unrepresentable, while
/// still distinguishing which SSO button shows its spinner.
enum SignInPhase: Equatable {
    case idle
    case passwordLoading
    case ssoLoading(SsoProvider)
}

/// The flow that produced the current `infrastructureError`, so the cover's
/// Retry re-runs what actually failed rather than always the password form.
/// Deliberately not `SignInPhase`: the phase drops back to `.idle` the instant
/// an attempt ends, whereas this has to outlive the attempt for as long as the
/// cover it explains is up.
enum SignInAttempt: Equatable {
    case password
    case sso(SsoProvider)
}

@MainActor
class LoginViewModel: ObservableObject {
    @Published var email = ""
    @Published var password = ""
    @Published var phase: SignInPhase = .idle
    @Published var errorResponse: ErrorResponse?
    @Published var infrastructureError: InfrastructureError?

    /// Overwritten at the very top of every sign-in entry point, alongside the
    /// error surfaces that same attempt clears, so it can never name a flow
    /// other than the one whose outcome is currently on screen.
    private(set) var lastAttempt: SignInAttempt?

    let authClient: AuthClientProtocol & SsoAuthenticating
    let googleSignInProvider: SsoSignInProviding
    let appleSignInProvider: SsoSignInProviding
    let onLoginSuccess: (PublicUser) async -> Void
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "LoginViewModel")

    init(
        authClient: AuthClientProtocol & SsoAuthenticating,
        googleSignInProvider: SsoSignInProviding,
        appleSignInProvider: SsoSignInProviding,
        onLoginSuccess: @escaping (PublicUser) async -> Void
    ) {
        self.authClient = authClient
        self.googleSignInProvider = googleSignInProvider
        self.appleSignInProvider = appleSignInProvider
        self.onLoginSuccess = onLoginSuccess
    }

    func login() async {
        lastAttempt = .password
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
        await signIn(with: googleSignInProvider)
    }

    func signInWithApple() async {
        await signIn(with: appleSignInProvider)
    }

    /// Re-runs the flow that produced the current `infrastructureError`, which
    /// the full-screen cover's Retry offers as its only action.
    func retryLastAttempt() async {
        switch lastAttempt {
        case .password:
            await login()
        case .sso(.google):
            await signInWithGoogle()
        case .sso(.apple):
            await signInWithApple()
        case nil:
            // Only a failed attempt raises the cover, and every attempt records
            // itself above, so there is always one to re-run. Report the
            // impossible state rather than re-running an arbitrary flow.
            logger.error("Retry requested with no recorded sign-in attempt")
        }
    }

    /// Shared body for both SSO sign-in entry points, parameterized by the
    /// provider to drive: Google and Apple differ only in the conformer called
    /// and the `SsoProvider` tag it carries, so one body owns this screen's
    /// sign-in policy and the two entry points cannot drift apart.
    /// `.cancelled` and `.alreadyPresenting` are ordinary outcomes, not
    /// failures, so neither raises a banner or the cover; the error surfaces
    /// this attempt cleared on entry stay cleared.
    private func signIn(with provider: SsoSignInProviding) async {
        // A sign-in already owns the screen and the phase. This call cannot
        // clear a phase it does not own, so it returns before touching any
        // state — the provider's own `.alreadyPresenting` guard still covers a
        // sheet that outlived its phase. The guard precedes `lastAttempt` so a
        // refused call cannot overwrite the attempt the presenting one recorded.
        guard phase == .idle else { return }

        lastAttempt = .sso(provider.provider)
        errorResponse = nil
        infrastructureError = nil

        phase = .ssoLoading(provider.provider)
        defer { phase = .idle }

        let credential: SsoCredential
        do {
            switch try await provider.signIn() {
            case .cancelled:
                // User dismissed the sheet: silent no-op, no banner, no callback.
                return
            case .alreadyPresenting:
                // The provider's sheet is already up and owns the outcome:
                // silent no-op, and in particular no banner for a failure
                // that did not happen.
                return
            case .signedIn(let authorization):
                credential = provider.provider.credential(from: authorization)
            }
        } catch {
            mapProviderError(error, provider: provider.provider)
            return
        }

        do {
            let response = try await authClient.signIn(with: credential)
            await onLoginSuccess(response.user)
        } catch {
            mapBackendError(error)
        }
    }

    /// Maps an SSO-provider (SDK/controller) failure to the view model's inline
    /// banner, wording it from the provider's own surface strings. The code is
    /// client-synthesized and only displayed, never branched on.
    private func mapProviderError(_ error: Error, provider: SsoProvider) {
        // `String(describing:)` rather than interpolating the `Error` directly:
        // os.Logger's `Error` overload prints `localizedDescription`, which for
        // these non-`LocalizedError` enums is a bare "error 3" and drops the
        // associated values that are the entire diagnosis.
        logger.error("SSO sign-in failed [provider=\(provider.rawValue, privacy: .public)] [error=\(String(describing: error), privacy: .public)]")
        errorResponse = ErrorResponse(
            code: provider.signInFailureCode,
            message: provider.signInFailureMessage,
            fieldErrors: nil
        )
    }

    /// Maps a backend/transport failure to the view model's error surfaces,
    /// identically for the password and SSO paths. `TIMEOUT` / `NETWORK_ERROR`
    /// / `SERVER_ERROR` and any non-`ErrorResponse` throw become an
    /// `infrastructureError` (full-screen cover); a pre-session rejection this
    /// screen's routes word for a user becomes an inline banner carrying the
    /// server's own message. Every other code — one those routes do not emit, so
    /// its message was written for a log rather than a user — becomes an inline
    /// banner with client copy instead.
    /// `email_not_verified` here is the account's own unverified email,
    /// categorically NOT the app's `verificationRequired` state, so it must not
    /// enter that flow. `account_email_not_verified` is a *different* account's
    /// unverified email matched by this sign-in's address; the server message
    /// names no way out, so it is replaced with provider-neutral client copy.
    private func mapBackendError(_ error: Error) {
        guard let error = error as? ErrorResponse else {
            logger.error("Backend call failed (non-ErrorResponse): [\(error, privacy: .public)]")
            infrastructureError = .serverError
            return
        }
        // Listed case by case rather than closed with `default`, so a code added
        // to `ServerErrorCode` fails this switch to compile and forces a
        // decision here instead of silently taking the banner path.
        switch error.knownCode {
        case .timeout:
            infrastructureError = .timeout
        case .networkError:
            infrastructureError = .noConnectivity
        case .serverError:
            infrastructureError = .serverError
        case .accountEmailNotVerified:
            // `ServerErrorCode.accountEmailNotVerified` is ErrorCode.ACCOUNT_EMAIL_NOT_VERIFIED's
            // wire form, owned by rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt
            // and emitted by AuthRoutes.kt's SSO linking refusal.
            //
            // This is the one backend rejection whose wording the client
            // replaces, so record what the server actually said before the
            // banner overwrites it: without this line a user report of that
            // banner names no server refusal to trace it back to.
            logger.error(
                "SSO linking refused; replacing the server message with client copy [code=\(error.code, privacy: .public)] [status=\(String(describing: error.status), privacy: .public)] [serverMessage=\(error.message, privacy: .public)]"
            )
            errorResponse = unverifiedEmailOwnedElsewhereBanner(from: error)
        case .unauthorized, .emailNotVerified, .accountDisabled, .serviceUnavailable:
            // The allowlist: exactly the codes this screen's two routes answer
            // with a message written for a user — AuthRoutes.kt's
            // `respondLoginUnauthorized` and `respondSsoLoginOutcome` — so the
            // server's own wording is shown verbatim.
            errorResponse = error
        case .studentAlreadyExists,
             .studentProfileRequired,
             .subscriptionNotFound,
             .subscriptionOwnedByOtherAccount,
             .versionConflict,
             .conflict,
             .notFound,
             .validationFailed,
             .payloadTooLarge,
             .coachingBudgetExhausted,
             .decodeError,
             .none:
            // Every other code — one these two routes do not emit (the four
            // subscription/validation codes belong to RFC 119's purchase rail),
            // or one this client has no case for at all — carries copy written
            // for a log, so it is recorded and replaced rather than surfaced
            // raw.
            logger.error("Unrecognized backend error code: [\(error.code, privacy: .public)] message=[\(error.message, privacy: .public)]")
            errorResponse = ErrorResponse(
                code: error.code,
                message: String(localized: "Sign-in failed. Please try again."),
                fieldErrors: error.fieldErrors,
                status: error.status
            )
        }
    }


    /// The client's replacement copy for `account_email_not_verified`: a
    /// *different*, still-unverified account already holds the address the
    /// provider just proved this signer owns. Disclosure is safe — the provider
    /// proved ownership — and an unverified account can still log in and reach
    /// `VerificationRequiredView`'s resend button, so that is the remedy this
    /// copy names.
    private func unverifiedEmailOwnedElsewhereBanner(from error: ErrorResponse) -> ErrorResponse {
        ErrorResponse(
            code: error.code,
            message: String(
                localized: "An unverified account already uses this email. Log in with your password and verify your email, then try again."
            ),
            fieldErrors: error.fieldErrors,
            status: error.status
        )
    }
}
