import XCTest
@testable import UnicoachiOS

@MainActor
final class NewConversationViewModelTests: XCTestCase {
    /// Counts `onProfileRequired` invocations for the active view model.
    private var profileRequiredCount = 0

    override func setUp() {
        super.setUp()
        profileRequiredCount = 0
    }

    private func makeViewModel(_ client: ConversationClientProtocol) -> NewConversationViewModel {
        NewConversationViewModel(
            conversationClient: client,
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 }
        )
    }

    private func makeMessage(id: String, role: MessageRole, content: String) -> Message {
        Message(id: id, role: role, content: content, createdAt: Date(timeIntervalSince1970: 0))
    }

    private func makeConversation() -> Conversation {
        Conversation(
            id: UUID(),
            name: "Chat",
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            lastActivityAt: nil,
            archivedAt: nil
        )
    }

    private func successScript() -> [ConversationStreamEvent] {
        [
            .conversation(makeConversation(), userMessage: makeMessage(id: "u1", role: .user, content: "Hi")),
            .delta("Hi"),
            .delta(" there"),
            .completed(makeMessage(id: "c1", role: .coach, content: "Hi there")),
        ]
    }

    func testHappyPath() async {
        let mock = MockConversationClient()
        mock.scriptedEvents = successScript()
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertNotNil(vm.userMessage)
        XCTAssertEqual(vm.coachStreamingText, "Hi there")
        XCTAssertNotNil(vm.coachMessage)
        XCTAssertNotNil(vm.conversation)
        XCTAssertEqual(vm.messageText, "")
        XCTAssertFalse(vm.isStreaming)
        XCTAssertNil(vm.errorResponse)
        XCTAssertNil(vm.infrastructureError)
    }

    func testEmptyMessageIsValidationError() async {
        let mock = MockConversationClient()
        let vm = makeViewModel(mock)
        vm.messageText = "   \n  "

        await vm.send()

        XCTAssertEqual(vm.errorResponse?.code, "VALIDATION")
        XCTAssertNotNil(vm.errorResponse?.fieldError(for: "message"))
        XCTAssertEqual(mock.invocationCount, 0)
    }

    func testTooLongMessageIsValidationError() async {
        let mock = MockConversationClient()
        let vm = makeViewModel(mock)
        vm.messageText = String(repeating: "a", count: 100_001)

        await vm.send()

        XCTAssertEqual(vm.errorResponse?.code, "VALIDATION")
        XCTAssertNotNil(vm.errorResponse?.fieldError(for: "message"))
        XCTAssertEqual(mock.invocationCount, 0)
    }

    func testStudentProfileRequiredInvokesCallbackAndPublishesNothing() async {
        let mock = MockConversationClient()
        mock.terminalError = ErrorResponse(code: "student_profile_required", message: "no profile", fieldErrors: nil)
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(profileRequiredCount, 1)
        XCTAssertNil(vm.errorResponse)
        XCTAssertNil(vm.infrastructureError)
        XCTAssertFalse(vm.isStreaming)
    }

    func testTimeoutMapsToInfrastructureTimeout() async {
        let mock = MockConversationClient()
        mock.terminalError = ErrorResponse(code: "TIMEOUT", message: "timed out", fieldErrors: nil)
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(vm.infrastructureError, .timeout)
    }

    func testNetworkErrorMapsToNoConnectivity() async {
        let mock = MockConversationClient()
        mock.terminalError = ErrorResponse(code: "NETWORK_ERROR", message: "offline", fieldErrors: nil)
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(vm.infrastructureError, .noConnectivity)
    }

    func testInStreamErrorRetainsPartialTextAndAllowsRetry() async {
        let mock = MockConversationClient()
        mock.scriptedEvents = [
            .conversation(makeConversation(), userMessage: makeMessage(id: "u1", role: .user, content: "Hi")),
            .delta("partial"),
        ]
        mock.terminalError = ErrorResponse(code: "INTERNAL", message: "boom", fieldErrors: nil)
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(vm.coachStreamingText, "partial")
        XCTAssertEqual(vm.errorResponse?.code, "INTERNAL")
        XCTAssertFalse(vm.isStreaming)
        XCTAssertNil(vm.coachMessage)
    }

    func testRetryAfterFailureSucceeds() async {
        let mock = MockConversationClient()
        mock.scriptedEvents = [
            .conversation(makeConversation(), userMessage: makeMessage(id: "u1", role: .user, content: "Hi")),
            .delta("partial"),
        ]
        mock.terminalError = ErrorResponse(code: "INTERNAL", message: "boom", fieldErrors: nil)
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()
        XCTAssertNil(vm.coachMessage)
        XCTAssertNotNil(vm.errorResponse)

        // Re-script a successful turn.
        mock.scriptedEvents = successScript()
        mock.terminalError = nil
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertNil(vm.errorResponse)
        XCTAssertNotNil(vm.coachMessage)
        XCTAssertEqual(mock.invocationCount, 2)
    }

    func testFirstTurnGuardBlocksSecondSendAfterSuccess() async {
        let mock = MockConversationClient()
        mock.scriptedEvents = successScript()
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()
        XCTAssertEqual(mock.invocationCount, 1)

        vm.messageText = "Again"
        await vm.send()

        XCTAssertEqual(mock.invocationCount, 1)
    }

    func testCancellationStopsMutationAndResetsStreaming() async {
        let mock = MockConversationClient()
        mock.scriptedEvents = successScript()
        mock.perEventDelay = .milliseconds(50)
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        let task = Task { await vm.send() }
        // Cancel before the stream completes.
        try? await Task.sleep(for: .milliseconds(20))
        task.cancel()
        await task.value

        XCTAssertFalse(vm.isStreaming)
        XCTAssertNil(vm.coachMessage)
    }
}
