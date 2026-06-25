import XCTest
@testable import UnicoachiOS

@MainActor
class VerificationViewModelTests: XCTestCase {
    var mockClient: MockAuthClient!

    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
    }

    private func makeUser(emailVerified: Bool = false) -> PublicUser {
        PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: emailVerified)
    }

    private func makeViewModel(
        onRecheck: @escaping () async -> AppViewModel.VerificationRecheckOutcome = { .stillUnverified },
        onLogout: @escaping () async -> Void = {}
    ) -> VerificationViewModel {
        VerificationViewModel(
            user: makeUser(),
            authClient: mockClient,
            onRecheck: onRecheck,
            onLogout: onLogout
        )
    }

    func testResendSuccessSetsConfirmation() async {
        let viewModel = makeViewModel()
        mockClient.resendVerificationResult = .success(())

        await viewModel.resend()

        XCTAssertNotNil(viewModel.resendConfirmation)
        XCTAssertNil(viewModel.resendError)
        XCTAssertFalse(viewModel.isResending)
    }

    func testResendFailureSetsError() async {
        let viewModel = makeViewModel()
        mockClient.resendVerificationResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil, status: 401))

        await viewModel.resend()

        XCTAssertEqual(viewModel.resendError?.code, "unauthorized")
        XCTAssertNil(viewModel.resendConfirmation)
        XCTAssertFalse(viewModel.isResending)
    }

    func testCheckAgainTogglesCheckingAndSetsMessageOnStillUnverified() async {
        // A box lets the recheck closure (and the test) observe the view model's
        // in-flight flag mid-call, mirroring LoginViewModelTests' loading probe.
        final class Box: @unchecked Sendable { var viewModel: VerificationViewModel?; var checkingWhileRunning = false }
        let box = Box()
        let viewModel = VerificationViewModel(
            user: makeUser(),
            authClient: mockClient,
            onRecheck: {
                if box.viewModel?.isChecking == true { box.checkingWhileRunning = true }
                return .stillUnverified
            },
            onLogout: {}
        )
        box.viewModel = viewModel

        await viewModel.checkAgain()

        XCTAssertTrue(box.checkingWhileRunning)
        XCTAssertFalse(viewModel.isChecking)
        XCTAssertNotNil(viewModel.recheckMessage)
    }

    func testCheckAgainSetsMessageOnFailure() async {
        let viewModel = makeViewModel(onRecheck: { .failed })

        await viewModel.checkAgain()

        XCTAssertNotNil(viewModel.recheckMessage)
        XCTAssertFalse(viewModel.isChecking)
    }
}
