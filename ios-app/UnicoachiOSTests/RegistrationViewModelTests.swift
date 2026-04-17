import XCTest
@testable import UnicoachiOS

class MockAuthClient: AuthClientProtocol, @unchecked Sendable {
    var registerResult: Result<RegisterResponse, Error>?
    
    func register(request: RegisterRequest) async throws -> RegisterResponse {
        if let result = registerResult {
            switch result {
            case .success(let response):
                return response
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }
}

@MainActor
class RegistrationViewModelTests: XCTestCase {
    var viewModel: RegistrationViewModel!
    var mockClient: MockAuthClient!
    
    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        viewModel = RegistrationViewModel(authClient: mockClient)
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
        }
        
        let delayedClient = DelayedAuthClient()
        viewModel = RegistrationViewModel(authClient: delayedClient)
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
