import XCTest
@testable import UnicoachiOS

@MainActor
class LoginViewModelTests: XCTestCase {
    var viewModel: LoginViewModel!
    var mockClient: MockAuthClient!
    var lastSuccessUser: PublicUser?
    
    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        lastSuccessUser = nil
        viewModel = LoginViewModel(authClient: mockClient) { user in
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
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
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
        class DelayedAuthClient: AuthClientProtocol, @unchecked Sendable {
            var isLoadingWhileRunning = false
            weak var viewModelRef: LoginViewModel?
            
            func login(request: LoginRequest) async throws -> LoginResponse {
                if await viewModelRef?.isLoading == true {
                    isLoadingWhileRunning = true
                }
                return LoginResponse(user: PublicUser(id: UUID(), email: "test@example.com", name: "Test"))
            }
            func register(request: RegisterRequest) async throws -> RegisterResponse { fatalError() }
            func logout() async throws { fatalError() }
            func me() async throws -> MeResponse { fatalError() }
        }
        
        let delayedClient = DelayedAuthClient()
        viewModel = LoginViewModel(authClient: delayedClient) { _ in }
        delayedClient.viewModelRef = viewModel
        
        viewModel.email = "test@example.com"
        viewModel.password = "password123"
        
        await viewModel.login()
        
        XCTAssertTrue(delayedClient.isLoadingWhileRunning)
        XCTAssertFalse(viewModel.isLoading)
    }
}
