import XCTest
@testable import UnicoachiOS

@MainActor
class RegistrationViewModelTests: XCTestCase {
    var viewModel: RegistrationViewModel!
    var mockClient: MockAuthClient!
    var lastSuccessUser: PublicUser?
    
    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        lastSuccessUser = nil
        viewModel = RegistrationViewModel(authClient: mockClient) { user in
            self.lastSuccessUser = user
        }
    }
    
    func testLocalValidationFailsFast() async {
        viewModel.email = "test@example.com"
        viewModel.name = "Test"
        viewModel.password = "short" // less than 8
        
        await viewModel.register()
        
        XCTAssertNotNil(viewModel.errorResponse)
        XCTAssertEqual(viewModel.errorResponse?.code, "VALIDATION")
        XCTAssertEqual(viewModel.errorResponse?.fieldErrors?.first?.field, "password")
        XCTAssertFalse(viewModel.isLoading)
        XCTAssertNil(lastSuccessUser)
    }
    
    func testSuccessfulRegistrationInvokesCallback() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
        mockClient.registerResult = .success(RegisterResponse(user: expectedUser))
        
        viewModel.email = "test@example.com"
        viewModel.name = "Test"
        viewModel.password = "password123"
        
        await viewModel.register()
        
        XCTAssertEqual(lastSuccessUser?.id, expectedUser.id)
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertNil(viewModel.infrastructureError)
    }
    
    func testServerErrorSetsInfrastructureError() async {
        mockClient.registerResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server Error", fieldErrors: nil))
        
        viewModel.email = "test@example.com"
        viewModel.name = "Test"
        viewModel.password = "password123"
        
        await viewModel.register()
        
        XCTAssertEqual(viewModel.infrastructureError, .serverError)
        XCTAssertNil(viewModel.errorResponse)
    }
    
    func testSuccessfulRegistrationTogglesLoading() async throws {

        // Use an injected subclass to test async state before completion
        class DelayedAuthClient: AuthClientProtocol, @unchecked Sendable {
            var isLoadingWhileRunning = false
            weak var viewModelRef: RegistrationViewModel?
            
            func register(request: RegisterRequest) async throws -> RegisterResponse {
                // Read from view model while test is executing to assert loading state
                if await viewModelRef?.isLoading == true {
                    isLoadingWhileRunning = true
                }
                return RegisterResponse(user: PublicUser(id: UUID(), email: "test@example.com", name: "Test"))
            }
            func login(request: LoginRequest) async throws -> LoginResponse { fatalError() }
            func logout() async throws { fatalError() }
            func me() async throws -> MeResponse { fatalError() }
        }
        
        let delayedClient = DelayedAuthClient()
        viewModel = RegistrationViewModel(authClient: delayedClient) { _ in }
        delayedClient.viewModelRef = viewModel
        
        viewModel.email = "test@example.com"
        viewModel.name = "Test"
        viewModel.password = "password123"
        
        await viewModel.register()
        
        XCTAssertTrue(delayedClient.isLoadingWhileRunning)
        XCTAssertFalse(viewModel.isLoading)
        XCTAssertNil(viewModel.errorResponse)
    }
}
