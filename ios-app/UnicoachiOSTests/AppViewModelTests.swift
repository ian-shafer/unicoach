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
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testCheckSessionOnboardingWhenNoProfile() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .onboarding(user))
    }

    func testCheckSessionProfileFetchUnauthorized() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "UNAUTHORIZED", message: "Unauthorized", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testCheckSessionProfileFetchTimeout() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .noConnectivity)
    }

    func testCheckSessionProfileFetchServerError() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server error", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .serverError)
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
        mockClient.meResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server error", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .serverError)
    }

    func testOnLoginSuccessRoutesToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .onboarding(user))
    }

    func testOnLoginSuccessRoutesToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testOnRegisterSuccessRoutesToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.onRegisterSuccess(user)

        XCTAssertEqual(viewModel.authState, .onboarding(user))
    }

    func testOnOnboardingCompleteTransitionsToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        viewModel.authState = .onboarding(user)

        viewModel.onOnboardingComplete(user)

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testOnStudentProfileRequiredRoutesAuthenticatedToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
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
        viewModel.authState = .authenticated(PublicUser(id: UUID(), email: "test@example.com", name: "Test"))
        mockClient.logoutResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Error", fieldErrors: nil))
        let cookie = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!
        mockCookieStorage.cookies = [cookie]

        await viewModel.logout()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
        XCTAssertEqual(mockCookieStorage.deletedCookies.count, 1)
        XCTAssertEqual(mockCookieStorage.deletedCookies.first?.name, "session")
    }

    func testLogoutClearsCookiesOnSuccess() async {
        viewModel.authState = .authenticated(PublicUser(id: UUID(), email: "test@example.com", name: "Test"))
        mockClient.logoutResult = .success(())
        let cookie1 = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!
        let cookie2 = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "tracking", .value: "456"])!
        mockCookieStorage.cookies = [cookie1, cookie2]

        await viewModel.logout()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
        XCTAssertEqual(mockCookieStorage.deletedCookies.count, 2)
        XCTAssertEqual(mockCookieStorage.cookies?.isEmpty, true)
    }
}
