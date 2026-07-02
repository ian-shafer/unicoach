import XCTest
@testable import UnicoachiOS

@MainActor
class LoginViewModelTests: XCTestCase {
    var viewModel: LoginViewModel!
    var mockClient: MockAuthClient!
    var mockGoogleProvider: MockGoogleSignInProvider!
    var lastSuccessUser: PublicUser?

    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        mockGoogleProvider = MockGoogleSignInProvider()
        lastSuccessUser = nil
        viewModel = LoginViewModel(authClient: mockClient, googleSignInProvider: mockGoogleProvider) { user in
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
        class DelayedAuthClient: AuthClientProtocol, GoogleAuthenticating, @unchecked Sendable {
            var isLoadingWhileRunning = false
            weak var viewModelRef: LoginViewModel?

            func login(request: LoginRequest) async throws -> LoginResponse {
                if await viewModelRef?.phase == .passwordLoading {
                    isLoadingWhileRunning = true
                }
                return LoginResponse(user: PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true))
            }
            func register(request: RegisterRequest) async throws -> RegisterResponse { fatalError() }
            func signInWithGoogle(idToken: String) async throws -> LoginResponse { fatalError() }
            func logout() async throws { fatalError() }
            func me() async throws -> MeResponse { fatalError() }
            func resendVerification() async throws { fatalError() }
            func changeEmail(_ email: String) async throws -> PublicUser { fatalError() }
        }

        let delayedClient = DelayedAuthClient()
        viewModel = LoginViewModel(authClient: delayedClient, googleSignInProvider: mockGoogleProvider) { _ in }
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
        mockGoogleProvider.signInResult = .success(.signedIn("id-token"))
        mockClient.signInWithGoogleResult = .success(LoginResponse(user: expectedUser))

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
        XCTAssertEqual(mockClient.signInWithGoogleCallCount, 0)
    }

    func testGoogleSignInProviderFailureShowsBanner() async {
        mockGoogleProvider.signInResult = .failure(GoogleSignInError.sdkError(NSError(domain: "test", code: 1)))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.errorResponse?.code, "GOOGLE_SIGN_IN_FAILED")
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertEqual(mockClient.signInWithGoogleCallCount, 0)
    }

    func testGoogleSignInUnauthorizedSetsErrorResponse() async {
        mockGoogleProvider.signInResult = .success(.signedIn("id-token"))
        mockClient.signInWithGoogleResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil, status: 401))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.errorResponse?.code, "unauthorized")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testGoogleSignInEmailNotVerifiedSetsErrorResponse() async {
        mockGoogleProvider.signInResult = .success(.signedIn("id-token"))
        mockClient.signInWithGoogleResult = .failure(ErrorResponse(code: "email_not_verified", message: "Email not verified", fieldErrors: nil, status: 403))

        await viewModel.signInWithGoogle()

        // A pre-session rejection: an inline banner, NOT the verificationRequired
        // flow. The view model has no such transition; it only sets errorResponse.
        XCTAssertEqual(viewModel.errorResponse?.code, "email_not_verified")
        XCTAssertNil(viewModel.infrastructureError)
        XCTAssertNil(lastSuccessUser)
    }

    func testGoogleSignInAccountDisabledSetsErrorResponse() async {
        mockGoogleProvider.signInResult = .success(.signedIn("id-token"))
        mockClient.signInWithGoogleResult = .failure(ErrorResponse(code: "account_disabled", message: "Account disabled", fieldErrors: nil, status: 403))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.errorResponse?.code, "account_disabled")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testGoogleSignInServiceUnavailableSetsErrorResponse() async {
        mockGoogleProvider.signInResult = .success(.signedIn("id-token"))
        mockClient.signInWithGoogleResult = .failure(ErrorResponse(code: "service_unavailable", message: "Service unavailable", fieldErrors: nil, status: 503))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.errorResponse?.code, "service_unavailable")
        XCTAssertNil(viewModel.infrastructureError)
    }

    func testGoogleSignInServerErrorSetsInfrastructureError() async {
        mockGoogleProvider.signInResult = .success(.signedIn("id-token"))
        mockClient.signInWithGoogleResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server Error", fieldErrors: nil))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.infrastructureError, .serverError)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testGoogleSignInTimeoutSetsInfrastructureError() async {
        mockGoogleProvider.signInResult = .success(.signedIn("id-token"))
        mockClient.signInWithGoogleResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        await viewModel.signInWithGoogle()

        XCTAssertEqual(viewModel.infrastructureError, .timeout)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testGoogleSignInLoadingStateToggles() async {
        final class DelayedGoogleProvider: GoogleSignInProviding {
            var isLoadingWhileRunning = false
            weak var viewModelRef: LoginViewModel?

            func signIn() async throws -> GoogleSignInOutcome {
                if viewModelRef?.phase == .googleLoading {
                    isLoadingWhileRunning = true
                }
                return .signedIn("id-token")
            }
        }

        let delayedProvider = DelayedGoogleProvider()
        viewModel = LoginViewModel(authClient: mockClient, googleSignInProvider: delayedProvider) { _ in }
        delayedProvider.viewModelRef = viewModel
        mockClient.signInWithGoogleResult = .success(LoginResponse(user: PublicUser(id: UUID(), email: "g@example.com", name: "G", emailVerified: true)))

        await viewModel.signInWithGoogle()

        XCTAssertTrue(delayedProvider.isLoadingWhileRunning)
        XCTAssertEqual(viewModel.phase, .idle)
    }
}
