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
    var mockCookieStorage: MockCookieStorage!
    
    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        mockCookieStorage = MockCookieStorage()
        viewModel = AppViewModel(authClient: mockClient, cookieStorage: mockCookieStorage)
    }
    
    func testCheckSessionAuthenticatedOnSuccess() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockClient.meResult = .success(MeResponse(user: user))
        
        await viewModel.checkSession()
        
        XCTAssertEqual(viewModel.authState, .authenticated(user))
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
