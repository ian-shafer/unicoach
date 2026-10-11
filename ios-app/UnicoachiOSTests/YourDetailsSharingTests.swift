import XCTest
@testable import UnicoachiOS

/// "Your details" as the student's door to who sees what they want to study
/// (RFC 251 §5, Door A). The answer is off until the student says yes, so the
/// rules under test are about what the control shows before any answer, that
/// only `granted` or `refused` is ever written, and that the control never
/// shows an answer the server did not store.
@MainActor
final class YourDetailsSharingTests: XCTestCase {
    private var sharingPermissionsClient: MockSharingPermissionsClient!
    private var profileRequiredCount = 0

    override func setUp() {
        super.setUp()
        sharingPermissionsClient = MockSharingPermissionsClient()
        profileRequiredCount = 0
    }

    private func makeViewModel() -> YourDetailsViewModel {
        YourDetailsViewModel(
            moneyProfileClient: MockMoneyProfileClient(),
            vocabularyClient: MockVocabularyClient(),
            subjectsClient: MockSubjectsClient(),
            intendedSubjectsClient: MockIntendedSubjectsClient(),
            sharingPermissionsClient: sharingPermissionsClient,
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 },
            receiptDuration: .seconds(60)
        )
    }

    private func loaded(_ status: SharingPermissionStatus) async -> YourDetailsViewModel {
        sharingPermissionsClient.fetchResult = .success([.intendedSubjects(status)])
        let viewModel = makeViewModel()
        await viewModel.load()
        return viewModel
    }

    // MARK: - What the control shows

    func testNeverAskedSelectsNeitherAnswer() async {
        let viewModel = await loaded(.notAsked)
        XCTAssertEqual(viewModel.sharingAnswer, .notAsked)
        XCTAssertNil(viewModel.sharingAnswer.choice, "neither segment may be selected before the student answers")
    }

    func testAStoredAnswerIsShownAsItself() async {
        let granted = await loaded(.granted)
        XCTAssertEqual(granted.sharingAnswer.choice, .granted)
        let refused = await loaded(.refused)
        XCTAssertEqual(refused.sharingAnswer.choice, .refused)
    }

    /// A status this build cannot read is NOT "never asked": the screen would
    /// then say nobody sees the subjects over a grant it merely could not read.
    func testAnUnknownStatusIsUnreadableNotNeverAsked() async {
        sharingPermissionsClient.fetchResult = .success([
            PublicSharingPermission(permission: SharingPermission.intendedSubjects.rawValue, status: "paused"),
        ])
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(viewModel.sharingAnswer, .unreadable)
        XCTAssertNil(viewModel.sharingAnswer.choice)
    }

    /// Two conflicting entries are no answer: taking the first would show a
    /// grant the server may equally be reporting as refused.
    func testTwoConflictingEntriesAreUnreadableNotTheFirst() async {
        sharingPermissionsClient.fetchResult = .success([.intendedSubjects(.granted), .intendedSubjects(.refused)])
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(viewModel.sharingAnswer, .unreadable)
        XCTAssertNil(viewModel.sharingAnswer.choice, "neither of two conflicting answers may be selected")
    }

    // MARK: - Writes

    func testChoosingShowThemWritesGrantedAndAdoptsTheResponse() async {
        let viewModel = await loaded(.notAsked)
        // The server's answer, not the request echoed back, is what lands.
        sharingPermissionsClient.updateResult = .success(.intendedSubjects(.granted))
        await viewModel.answerSharing(.granted)
        XCTAssertEqual(sharingPermissionsClient.lastUpdate?.permission, .intendedSubjects)
        XCTAssertEqual(sharingPermissionsClient.lastUpdate?.choice, .granted)
        XCTAssertEqual(viewModel.sharingAnswer, .chosen(.granted))
    }

    func testTheServersAnswerWinsOverTheChoiceSent() async {
        let viewModel = await loaded(.notAsked)
        sharingPermissionsClient.updateResult = .success(.intendedSubjects(.refused))
        await viewModel.answerSharing(.granted)
        XCTAssertEqual(viewModel.sharingAnswer, .chosen(.refused))
    }

    func testWithdrawingAGrantWritesRefused() async {
        let viewModel = await loaded(.granted)
        await viewModel.answerSharing(.refused)
        XCTAssertEqual(sharingPermissionsClient.lastUpdate?.choice, .refused)
        XCTAssertEqual(viewModel.sharingAnswer, .chosen(.refused))
    }

    func testChoosingTheStoredAnswerAgainSendsNothing() async {
        let viewModel = await loaded(.granted)
        await viewModel.answerSharing(.granted)
        XCTAssertEqual(sharingPermissionsClient.updateCallCount, 0)
    }

    func testASecondChoiceWhileAWriteIsOpenSendsNoSecondRequest() async {
        let gate = WriteGate()
        sharingPermissionsClient.updateGate = { await gate.hold() }
        let viewModel = await loaded(.notAsked)

        let inFlight = Task { await viewModel.answerSharing(.granted) }
        await gate.waitUntilHeld()
        await viewModel.answerSharing(.refused)
        XCTAssertEqual(sharingPermissionsClient.updateCallCount, 1, "a write is already open")

        await gate.release()
        _ = await inFlight.value
        XCTAssertEqual(sharingPermissionsClient.updateCallCount, 1)
        XCTAssertEqual(viewModel.sharingAnswer, .chosen(.granted))
    }

    // MARK: - Failures

    func testAFailedWriteKeepsTheStoredAnswerAndSurfacesTheError() async {
        let viewModel = await loaded(.notAsked)
        let failure = ErrorResponse(code: "SERVER_ERROR", message: "An unexpected error occurred.", fieldErrors: nil)
        sharingPermissionsClient.updateResult = .failure(failure)
        await viewModel.answerSharing(.granted)
        XCTAssertEqual(viewModel.actionError, failure)
        XCTAssertEqual(viewModel.sharingAnswer, .notAsked, "only a 200 moves the stored answer")
    }

    /// The permission is an answer like the other two on this screen, so an
    /// unread one fails the screen rather than showing "nobody sees them".
    func testAFailedReadFailsTheScreen() async {
        let failure = ErrorResponse(code: "SERVER_ERROR", message: "An unexpected error occurred.", fieldErrors: nil)
        sharingPermissionsClient.fetchResult = .failure(failure)
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(viewModel.state, .failed(failure))
    }

    func testTheProfileRefusalOnReadEscalates() async {
        sharingPermissionsClient.fetchResult = .failure(ErrorResponse(
            code: "student_profile_required", message: "Student profile required", fieldErrors: nil
        ))
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(profileRequiredCount, 1)
        XCTAssertEqual(viewModel.state, .loading, "a profile gate is routed, not rendered as a failure")
    }

    func testTheProfileRefusalOnWriteEscalatesRatherThanShowingAnAlert() async {
        let viewModel = await loaded(.notAsked)
        sharingPermissionsClient.updateResult = .failure(ErrorResponse(
            code: "student_profile_required", message: "Student profile required", fieldErrors: nil
        ))
        await viewModel.answerSharing(.refused)
        XCTAssertEqual(profileRequiredCount, 1)
        XCTAssertNil(viewModel.actionError)
    }
}
