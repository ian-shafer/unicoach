import XCTest
@testable import UnicoachiOS

@MainActor
class ChangeEmailViewModelTests: XCTestCase {
    var mockClient: MockAuthClient!
    var changedUser: PublicUser?

    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        changedUser = nil
    }

    private func makeViewModel(email: String) -> ChangeEmailViewModel {
        ChangeEmailViewModel(email: email, authClient: mockClient) { user in
            self.changedUser = user
        }
    }

    func testEmptyEmailRejectedLocally() async {
        let viewModel = makeViewModel(email: "   ")

        await viewModel.submit()

        XCTAssertEqual(viewModel.errorResponse?.code, "VALIDATION")
        XCTAssertNil(changedUser)
    }

    func testSuccessInvokesOnChanged() async {
        let returnedUser = PublicUser(id: UUID(), email: "new@example.com", name: "Test", emailVerified: false)
        mockClient.changeEmailResult = .success(returnedUser)
        let viewModel = makeViewModel(email: "new@example.com")

        await viewModel.submit()

        XCTAssertEqual(changedUser?.id, returnedUser.id)
        XCTAssertEqual(changedUser?.email, "new@example.com")
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertFalse(viewModel.isLoading)
    }

    func testMalformedEmailDefersToServerValidation() async {
        // A malformed-but-non-empty address passes the local guard and reaches the
        // client; the server's 400 validation_failed maps into errorResponse,
        // confirming format validation is not done locally.
        mockClient.changeEmailResult = .failure(
            ErrorResponse(
                code: "validation_failed",
                message: "Validation failed.",
                fieldErrors: [FieldError(field: "email", message: "Invalid email address.")],
                status: 400
            )
        )
        let viewModel = makeViewModel(email: "not-an-email")

        await viewModel.submit()

        XCTAssertEqual(viewModel.errorResponse?.code, "validation_failed")
        XCTAssertEqual(viewModel.errorResponse?.fieldError(for: "email"), "Invalid email address.")
        XCTAssertNil(changedUser)
    }

    func testConflictMapsToErrorResponse() async {
        mockClient.changeEmailResult = .failure(
            ErrorResponse(code: "conflict", message: "Email already in use.", fieldErrors: nil, status: 409)
        )
        let viewModel = makeViewModel(email: "taken@example.com")

        await viewModel.submit()

        XCTAssertEqual(viewModel.errorResponse?.code, "conflict")
        XCTAssertNil(changedUser)
    }
}
