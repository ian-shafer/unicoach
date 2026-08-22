import XCTest
@testable import UnicoachiOS

@MainActor
class LoginViewModelTests: XCTestCase {
    var viewModel: LoginViewModel!
    var mockClient: MockAuthClient!
    var mockGoogleProvider: MockSsoSignInProvider!
    var mockAppleProvider: MockSsoSignInProvider!
    var lastSuccessUser: PublicUser?

    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        mockGoogleProvider = MockSsoSignInProvider(provider: .google)
        mockAppleProvider = MockSsoSignInProvider(provider: .apple)
        lastSuccessUser = nil
        viewModel = LoginViewModel(
            authClient: mockClient,
            googleSignInProvider: mockGoogleProvider,
            appleSignInProvider: mockAppleProvider
        ) { user in
            self.lastSuccessUser = user
        }
    }

    func testEmptyFieldsRejectedLocally() async {
        viewModel.email = "test@example.com"
        viewModel.password = ""

        await viewModel.login()

        XCTAssertEqual(viewModel.errorResponse?.code, "VALIDATION")
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertNil(lastSuccessUser)
    }

    func testSuccessfulLoginInvokesCallback() async {
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.loginResult = .success(LoginResponse(user: expectedUser))

        viewModel.email = "test@example.com"
        viewModel.password = "password123"
        await viewModel.login()

        XCTAssertEqual(lastSuccessUser?.id, expectedUser.id)
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testUnauthorizedSetsErrorResponse() async {
        mockClient.loginResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil))

        viewModel.email = "test@example.com"
        viewModel.password = "password123"
        await viewModel.login()

        XCTAssertEqual(viewModel.errorResponse?.code, "unauthorized")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testServerErrorSetsInfrastructureError() async {
        mockClient.loginResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server Error", fieldErrors: nil))

        viewModel.email = "test@example.com"
        viewModel.password = "password123"
        await viewModel.login()

        XCTAssertEqual(viewModel.infrastructureError, .serverError)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testTimeoutSetsInfrastructureError() async {
        mockClient.loginResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        viewModel.email = "test@example.com"
        viewModel.password = "password123"
        await viewModel.login()

        XCTAssertEqual(viewModel.infrastructureError, .timeout)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testLoadingStateToggles() async {
        class DelayedAuthClient: AuthClientProtocol, SsoAuthenticating, @unchecked Sendable {
            var isLoadingWhileRunning = false
            weak var viewModelRef: LoginViewModel?

            func login(request: LoginRequest) async throws -> LoginResponse {
                if await viewModelRef?.phase == .passwordLoading {
                    isLoadingWhileRunning = true
                }
                return LoginResponse(user: PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true))
            }
            func register(request: RegisterRequest) async throws -> RegisterResponse { fatalError("register unexpected") }
            func signIn(with credential: SsoCredential) async throws -> LoginResponse { fatalError("signIn unexpected") }
            func logout() async throws { fatalError("logout unexpected") }
            func me() async throws -> MeResponse { fatalError("me unexpected") }
            func resendVerification() async throws { fatalError("resendVerification unexpected") }
            func changeEmail(_ email: String) async throws -> PublicUser { fatalError("changeEmail unexpected") }
        }

        let delayedClient = DelayedAuthClient()
        viewModel = LoginViewModel(authClient: delayedClient, googleSignInProvider: mockGoogleProvider, appleSignInProvider: mockAppleProvider) { _ in }
        delayedClient.viewModelRef = viewModel

        viewModel.email = "test@example.com"
        viewModel.password = "password123"

        await viewModel.login()

        XCTAssertTrue(delayedClient.isLoadingWhileRunning)
        XCTAssertEqual(viewModel.phase, .idle)
    }

    // MARK: - Google sign-in

    func testGoogleSignInSuccessInvokesCallback() async {
        let expectedUser = PublicUser(id: UUID(), email: "google@example.com", name: "Google", emailVerified: true)
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .success(LoginResponse(user: expectedUser))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(lastSuccessUser?.id, expectedUser.id)
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testGoogleSignInCancellationIsSilentNoOp() async {
        mockGoogleProvider.signInResult = .success(.cancelled)

        await viewModel.signInWithGoogle()

        XCTAssertNil(viewModel.errorResponse)
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertNil(lastSuccessUser)
        XCTAssertEqual(mockClient.signInCredentials.count, 0)
    }

    func testGoogleSignInProviderFailureShowsBanner() async {
        mockGoogleProvider.signInResult = .failure(GoogleSignInError.sdkError(NSError(domain: "test", code: 1)))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.errorResponse?.code, "GOOGLE_SIGN_IN_FAILED")
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertEqual(mockClient.signInCredentials.count, 0)
    }

    func testGoogleSignInUnauthorizedSetsErrorResponse() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil, status: 401))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.errorResponse?.code, "unauthorized")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testGoogleSignInEmailNotVerifiedSetsErrorResponse() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "email_not_verified", message: "Email not verified", fieldErrors: nil, status: 403))

        await viewModel.signInWithGoogle()

        // A pre-session rejection: an inline banner, NOT the verificationRequired
        // flow. The view model has no such transition; it only sets errorResponse.
        XCTAssertEqual(viewModel.errorResponse?.code, "email_not_verified")
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertNil(lastSuccessUser)
    }

    func testGoogleSignInAccountDisabledSetsErrorResponse() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "account_disabled", message: "Account disabled", fieldErrors: nil, status: 403))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.errorResponse?.code, "account_disabled")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testGoogleSignInServiceUnavailableSetsErrorResponse() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "service_unavailable", message: "Service unavailable", fieldErrors: nil, status: 503))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.errorResponse?.code, "service_unavailable")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testGoogleSignInServerErrorSetsInfrastructureError() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server Error", fieldErrors: nil))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.infrastructureError, .serverError)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testGoogleSignInTimeoutSetsInfrastructureError() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.infrastructureError, .timeout)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testGoogleSignInLoadingPhase() async {
        final class DelayedGoogleProvider: SsoSignInProviding {
            let provider: SsoProvider = .google
            var isLoadingWhileRunning = false
            weak var viewModelRef: LoginViewModel?

            func signIn() async throws -> SsoSignInOutcome {
                if viewModelRef?.phase == .ssoLoading(.google) {
                    isLoadingWhileRunning = true
                }
                return .signedIn(SsoAuthorization(idToken: "id-token", name: nil))
            }
        }

        let delayedProvider = DelayedGoogleProvider()
        viewModel = LoginViewModel(authClient: mockClient, googleSignInProvider: delayedProvider, appleSignInProvider: mockAppleProvider) { _ in }
        delayedProvider.viewModelRef = viewModel
        mockClient.signInResult = .success(LoginResponse(user: PublicUser(id: UUID(), email: "g@example.com", name: "G", emailVerified: true)))

        await viewModel.signInWithGoogle()

        XCTAssertTrue(delayedProvider.isLoadingWhileRunning)
        XCTAssertEqual(viewModel.phase, .idle)
    }

    // MARK: - Apple sign-in

    func testAppleSignInSuccessInvokesCallback() async {
        let expectedUser = PublicUser(id: UUID(), email: "apple@example.com", name: "Apple", emailVerified: true)
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: "Ada")))
        mockClient.signInResult = .success(LoginResponse(user: expectedUser))

        await viewModel.signInWithApple()

        XCTAssertEqual(lastSuccessUser?.id, expectedUser.id)
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testAppleSignInForwardsTokenAndName() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "the-token", name: "Ada Lovelace")))
        mockClient.signInResult = .success(LoginResponse(user: PublicUser(id: UUID(), email: "apple@example.com", name: "Ada Lovelace", emailVerified: true)))

        await viewModel.signInWithApple()

        XCTAssertEqual(mockClient.signInCredentials.count, 1)
        guard case .apple(let idToken, let name) = mockClient.signInCredentials.first else {
            XCTFail("Expected an .apple credential")
            return
        }
        XCTAssertEqual(idToken, "the-token")
        XCTAssertEqual(name, "Ada Lovelace")
    }

    func testAppleSignInCancellationIsSilentNoOp() async {
        mockAppleProvider.signInResult = .success(.cancelled)

        await viewModel.signInWithApple()

        XCTAssertNil(viewModel.errorResponse)
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertNil(lastSuccessUser)
        XCTAssertEqual(mockClient.signInCredentials.count, 0)
    }

    func testAppleSignInProviderFailureShowsBanner() async {
        mockAppleProvider.signInResult = .failure(AppleSignInError.authorizationFailed(NSError(domain: "test", code: 1)))

        await viewModel.signInWithApple()

        XCTAssertEqual(viewModel.errorResponse?.code, "APPLE_SIGN_IN_FAILED")
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertEqual(mockClient.signInCredentials.count, 0)
    }

    func testAppleSignInAccountEmailNotVerifiedShowsClientCopy() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(
            code: "account_email_not_verified",
            message: "The matched account's email is not verified",
            fieldErrors: nil,
            status: 403
        ))

        await viewModel.signInWithApple()

        // Pinned to the whole client copy, not a fragment of it: the server's
        // log-shaped message must not reach the user, and the wording must stay
        // provider-neutral rather than drifting into Apple-specific copy.
        XCTAssertEqual(viewModel.errorResponse?.code, "account_email_not_verified")
        XCTAssertEqual(viewModel.errorResponse?.message, "An unverified account already uses this email. Log in with your password and verify your email, then try again.")
    }

    func testGoogleSignInAccountEmailNotVerifiedShowsClientCopy() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(
            code: "account_email_not_verified",
            message: "The matched account's email is not verified",
            fieldErrors: nil,
            status: 403
        ))

        await viewModel.signInWithGoogle()

        // The Google twin of the Apple case above: identical provider-neutral
        // copy, so the interception cannot regress into Apple-specific wording.
        XCTAssertEqual(viewModel.errorResponse?.code, "account_email_not_verified")
        XCTAssertEqual(viewModel.errorResponse?.message, "An unverified account already uses this email. Log in with your password and verify your email, then try again.")
    }

    func testAppleSignInUnauthorizedSetsErrorResponse() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil, status: 401))

        await viewModel.signInWithApple()

        XCTAssertEqual(viewModel.errorResponse?.code, "unauthorized")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testAppleSignInEmailNotVerifiedSetsErrorResponse() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "email_not_verified", message: "Email not verified", fieldErrors: nil, status: 403))

        await viewModel.signInWithApple()

        // A pre-session rejection: an inline banner, NOT the verificationRequired
        // flow (that is a categorically different state).
        XCTAssertEqual(viewModel.errorResponse?.code, "email_not_verified")
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertNil(lastSuccessUser)
    }

    func testAppleSignInAccountDisabledSetsErrorResponse() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "account_disabled", message: "Account disabled", fieldErrors: nil, status: 403))

        await viewModel.signInWithApple()

        XCTAssertEqual(viewModel.errorResponse?.code, "account_disabled")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testAppleSignInServiceUnavailableSetsErrorResponse() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "service_unavailable", message: "Service unavailable", fieldErrors: nil, status: 503))

        await viewModel.signInWithApple()

        XCTAssertEqual(viewModel.errorResponse?.code, "service_unavailable")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testAppleSignInServerErrorSetsInfrastructureError() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server Error", fieldErrors: nil))

        await viewModel.signInWithApple()

        XCTAssertEqual(viewModel.infrastructureError, .serverError)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testAppleSignInTimeoutSetsInfrastructureError() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        await viewModel.signInWithApple()

        XCTAssertEqual(viewModel.infrastructureError, .timeout)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testAppleSignInLoadingPhase() async {
        final class DelayedAppleProvider: SsoSignInProviding {
            let provider: SsoProvider = .apple
            var isLoadingWhileRunning = false
            weak var viewModelRef: LoginViewModel?

            func signIn() async throws -> SsoSignInOutcome {
                if viewModelRef?.phase == .ssoLoading(.apple) {
                    isLoadingWhileRunning = true
                }
                return .signedIn(SsoAuthorization(idToken: "id-token", name: nil))
            }
        }

        let delayedProvider = DelayedAppleProvider()
        viewModel = LoginViewModel(authClient: mockClient, googleSignInProvider: mockGoogleProvider, appleSignInProvider: delayedProvider) { _ in }
        delayedProvider.viewModelRef = viewModel
        mockClient.signInResult = .success(LoginResponse(user: PublicUser(id: UUID(), email: "a@example.com", name: "A", emailVerified: true)))

        await viewModel.signInWithApple()

        XCTAssertTrue(delayedProvider.isLoadingWhileRunning)
        XCTAssertEqual(viewModel.phase, .idle)
    }

    // MARK: - Re-entrancy

    /// A second tap while the first sign-in's sheet is still presenting must be
    /// refused before any state is written: the phase belongs to the call still
    /// on screen, and clearing it would re-enable every button mid-sheet.
    func testSecondSignInWhileFirstPresentingLeavesPhaseUntouched() async {
        final class ReentrantAppleProvider: SsoSignInProviding {
            let provider: SsoProvider = .apple
            weak var viewModelRef: LoginViewModel?
            private(set) var signInCallCount = 0
            private(set) var phaseAfterSecondTap: SignInPhase?

            func signIn() async throws -> SsoSignInOutcome {
                signInCallCount += 1
                if signInCallCount == 1, let viewModel = viewModelRef {
                    // The user taps the button again while this call's sheet is up.
                    await viewModel.signInWithApple()
                    phaseAfterSecondTap = viewModel.phase
                }
                return .signedIn(SsoAuthorization(idToken: "id-token", name: nil))
            }
        }

        let reentrantProvider = ReentrantAppleProvider()
        viewModel = LoginViewModel(authClient: mockClient, googleSignInProvider: mockGoogleProvider, appleSignInProvider: reentrantProvider) { _ in }
        reentrantProvider.viewModelRef = viewModel
        mockClient.signInResult = .success(LoginResponse(user: PublicUser(id: UUID(), email: "a@example.com", name: "A", emailVerified: true)))

        await viewModel.signInWithApple()

        // The refused tap never reached the provider and left the presenting
        // call's spinner in place.
        XCTAssertEqual(reentrantProvider.signInCallCount, 1)
        XCTAssertEqual(reentrantProvider.phaseAfterSecondTap, .ssoLoading(.apple))
        XCTAssertEqual(viewModel.phase, .idle)
        XCTAssertEqual(mockClient.signInCredentials.count, 1)
    }

    // MARK: - Infrastructure-error retry

    // The full-screen cover's Retry re-runs whichever flow failed. Each test
    // reproduces the cover's own action: clear `infrastructureError`, then
    // `retryLastAttempt()`.

    func testRetryAfterPasswordFailureRerunsPasswordLogin() async {
        mockClient.loginResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))
        viewModel.email = "test@example.com"
        viewModel.password = "password123"
        await viewModel.login()
        XCTAssertEqual(viewModel.infrastructureError, .timeout)

        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.loginResult = .success(LoginResponse(user: expectedUser))
        viewModel.infrastructureError = nil
        await viewModel.retryLastAttempt()

        XCTAssertEqual(lastSuccessUser?.id, expectedUser.id)
        XCTAssertEqual(mockGoogleProvider.signInCallCount, 0)
        XCTAssertEqual(mockAppleProvider.signInCallCount, 0)
    }

    func testRetryAfterGoogleFailureRerunsGoogleSignIn() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))
        await viewModel.signInWithGoogle()
        XCTAssertEqual(viewModel.infrastructureError, .timeout)

        let expectedUser = PublicUser(id: UUID(), email: "g@example.com", name: "G", emailVerified: true)
        mockClient.signInResult = .success(LoginResponse(user: expectedUser))
        viewModel.infrastructureError = nil
        await viewModel.retryLastAttempt()

        XCTAssertEqual(mockGoogleProvider.signInCallCount, 2)
        XCTAssertEqual(mockAppleProvider.signInCallCount, 0)
        XCTAssertEqual(lastSuccessUser?.id, expectedUser.id)
        // The empty password form would have answered with a VALIDATION banner.
        XCTAssertNil(viewModel.errorResponse)
    }

    func testRetryAfterAppleFailureRerunsAppleSignIn() async {
        mockAppleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: "Ada")))
        mockClient.signInResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))
        await viewModel.signInWithApple()
        XCTAssertEqual(viewModel.infrastructureError, .timeout)

        let expectedUser = PublicUser(id: UUID(), email: "apple@example.com", name: "Ada", emailVerified: true)
        mockClient.signInResult = .success(LoginResponse(user: expectedUser))
        viewModel.infrastructureError = nil
        await viewModel.retryLastAttempt()

        XCTAssertEqual(mockAppleProvider.signInCallCount, 2)
        XCTAssertEqual(mockGoogleProvider.signInCallCount, 0)
        XCTAssertEqual(lastSuccessUser?.id, expectedUser.id)
        XCTAssertNil(viewModel.errorResponse)
    }

    /// A later attempt must overwrite the recorded one, so Retry can never
    /// re-run a flow the user has since moved on from.
    func testRetryRerunsTheMostRecentAttempt() async {
        mockGoogleProvider.signInResult = .success(.signedIn(SsoAuthorization(idToken: "id-token", name: nil)))
        mockClient.signInResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))
        await viewModel.signInWithGoogle()

        mockClient.loginResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))
        viewModel.email = "test@example.com"
        viewModel.password = "password123"
        await viewModel.login()

        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.loginResult = .success(LoginResponse(user: expectedUser))
        viewModel.infrastructureError = nil
        await viewModel.retryLastAttempt()

        XCTAssertEqual(lastSuccessUser?.id, expectedUser.id)
        XCTAssertEqual(mockGoogleProvider.signInCallCount, 1)
    }
}
