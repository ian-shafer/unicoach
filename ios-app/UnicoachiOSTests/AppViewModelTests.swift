import XCTest
@testable import UnicoachiOS

class MockCookieStorage: CookieStorageProtocol, @unchecked Sendable {
    var cookies: [HTTPCookie]? = []
    var deletedCookies: [HTTPCookie] = []

    func deleteCookie(_ cookie: HTTPCookie) {
        deletedCookies.append(cookie)
        cookies?.removeAll { $0.name == cookie.name }
    }
}

@MainActor
class AppViewModelTests: XCTestCase {
    var viewModel: AppViewModel!
    var mockClient: MockAuthClient!
    var mockStudentClient: MockStudentClient!
    var mockCookieStorage: MockCookieStorage!

    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        mockStudentClient = MockStudentClient()
        mockCookieStorage = MockCookieStorage()
        viewModel = AppViewModel(
            cookieStorage: mockCookieStorage,
            authClient: mockClient,
            studentClient: mockStudentClient
        )
    }

    private func makeStudent() -> PublicStudent {
        PublicStudent(
            id: UUID(),
            expectedHighSchoolGraduationDate: "2028",
            version: 1,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0)
        )
    }

    func testCheckSessionAuthenticatedOnSuccess() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testCheckSessionOnboardingWhenNoProfile() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .onboarding(user))
    }

    func testCheckSessionProfileFetchUnauthorized() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testCheckSessionProfileFetchTimeout() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .noConnectivity)
    }

    func testCheckSessionProfileFetchServerError() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server error", fieldErrors: nil, status: 500))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .serverError)
    }

    func testCheckSessionProfileFetchUnexpectedErrorOnUnhandled4xx() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "teapot", message: "I'm a teapot.", fieldErrors: nil, status: 418))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unexpectedError)
    }

    func testCheckSessionUnauthenticatedOn401() async {
        mockClient.meResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testCheckSessionNoConnectivityOnTimeout() async {
        mockClient.meResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .noConnectivity)
    }

    func testCheckSessionServerErrorOnServerFailure() async {
        mockClient.meResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server error", fieldErrors: nil, status: 500))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .serverError)
    }

    func testCheckSessionUnexpectedErrorOnUnhandled4xx() async {
        mockClient.meResult = .failure(ErrorResponse(code: "teapot", message: "I'm a teapot.", fieldErrors: nil, status: 418))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unexpectedError)
    }

    func testCheckSessionUnexpectedErrorOnStatuslessClientError() async {
        mockClient.meResult = .failure(ErrorResponse(code: "DECODE_ERROR", message: "Failed to parse response", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unexpectedError)
    }

    func testOnLoginSuccessRoutesToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .onboarding(user))
    }

    func testOnLoginSuccessRoutesToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testOnRegisterSuccessRoutesToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.onRegisterSuccess(user)

        XCTAssertEqual(viewModel.authState, .onboarding(user))
    }

    func testOnOnboardingCompleteTransitionsToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .onboarding(user)

        viewModel.onOnboardingComplete(user)

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testOnStudentProfileRequiredRoutesAuthenticatedToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)

        viewModel.onStudentProfileRequired()

        XCTAssertEqual(viewModel.authState, .onboarding(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testOnStudentProfileRequiredNoOpFromNonAuthenticated() async {
        viewModel.authState = .unauthenticated

        viewModel.onStudentProfileRequired()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testLogoutTransitionsToUnauthenticatedOnFailure() async {
        viewModel.authState = .authenticated(PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true))
        mockClient.logoutResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Error", fieldErrors: nil))
        let cookie = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!
        mockCookieStorage.cookies = [cookie]

        await viewModel.logout()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
        XCTAssertEqual(mockCookieStorage.deletedCookies.count, 1)
        XCTAssertEqual(mockCookieStorage.deletedCookies.first?.name, "session")
    }

    func testLogoutClearsCookiesOnSuccess() async {
        viewModel.authState = .authenticated(PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true))
        mockClient.logoutResult = .success(())
        let cookie1 = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!
        let cookie2 = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "tracking", .value: "456"])!
        mockCookieStorage.cookies = [cookie1, cookie2]

        await viewModel.logout()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
        XCTAssertEqual(mockCookieStorage.deletedCookies.count, 2)
        XCTAssertEqual(mockCookieStorage.cookies?.isEmpty, true)
    }

    // MARK: - Email verification routing

    func testOnLoginSuccessUnverifiedRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testOnRegisterSuccessUnverifiedRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)

        await viewModel.onRegisterSuccess(user)

        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testCheckSessionUnverifiedRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)
        mockClient.meResult = .success(MeResponse(user: user))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testResolveProfileStateEmailNotVerifiedRaceRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "email_not_verified", message: "Email verification required.", fieldErrors: nil, status: 403))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
    }

    func testRecheckVerificationVerifiedTransitionsToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .verificationRequired(user)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        let outcome = await viewModel.recheckVerification()

        XCTAssertEqual(outcome, .verified)
        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testRecheckVerificationStillUnverifiedLeavesStateUnchanged() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)
        viewModel.authState = .verificationRequired(user)
        mockClient.meResult = .success(MeResponse(user: user))

        let outcome = await viewModel.recheckVerification()

        XCTAssertEqual(outcome, .stillUnverified)
        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testRecheckVerificationUnauthorizedTearsDownToUnauthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)
        viewModel.authState = .verificationRequired(user)
        mockClient.meResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil, status: 401))

        let outcome = await viewModel.recheckVerification()

        XCTAssertEqual(outcome, .failed)
        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testRecheckVerificationTimeoutLeavesStateUnchanged() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)
        viewModel.authState = .verificationRequired(user)
        mockClient.meResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        let outcome = await viewModel.recheckVerification()

        XCTAssertEqual(outcome, .failed)
        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
    }
}
