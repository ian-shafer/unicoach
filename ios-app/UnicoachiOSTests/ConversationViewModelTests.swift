import XCTest
@testable import UnicoachiOS

@MainActor
final class ConversationViewModelTests: XCTestCase {
    /// Counts `onProfileRequired` invocations for the active view model.
    private var profileRequiredCount = 0

    override func setUp() {
        super.setUp()
        profileRequiredCount = 0
    }

    private func makeViewModel(_ client: ConversationClientProtocol) -> ConversationViewModel {
        ConversationViewModel(
            conversationClient: client,
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 }
        )
    }

    private func makeMessage(id: String, role: MessageRole, content: String) -> Message {
        Message(id: id, role: role, content: content, createdAt: Date(timeIntervalSince1970: 0))
    }

    private func makeConversation(id: UUID = UUID()) -> Conversation {
        Conversation(
            id: id,
            name: "Chat",
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            lastActivityAt: nil,
            archivedAt: nil
        )
    }

    private func startScript(conversation: Conversation) -> MockConversationClient.Script {
        MockConversationClient.Script(events: [
            .conversation(conversation, userMessage: makeMessage(id: "su1", role: .user, content: "Hi")),
            .delta("Hi"),
            .delta(" there"),
            .completed(makeMessage(id: "c1", role: .coach, content: "Hi there")),
        ])
    }

    private func followUpScript() -> MockConversationClient.Script {
        MockConversationClient.Script(events: [
            .userMessage(makeMessage(id: "su2", role: .user, content: "More")),
            .delta("Sure"),
            .completed(makeMessage(id: "c2", role: .coach, content: "Sure")),
        ])
    }

    // MARK: - First turn

    func testFirstTurnHappyPath() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.scripts = [startScript(conversation: convo)]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(vm.turns.count, 1)
        let turn = vm.turns[0]
        XCTAssertEqual(turn.userMessage.id, "su1")            // reconciled with server copy
        XCTAssertEqual(turn.coachStreamingText, "Hi there")
        XCTAssertEqual(turn.coachMessage?.content, "Hi there")
        XCTAssertEqual(vm.conversation?.id, convo.id)         // established
        XCTAssertEqual(vm.messageText, "")
        XCTAssertFalse(vm.isStreaming)
        XCTAssertNil(vm.validationError)
        XCTAssertNil(turn.failure)
        XCTAssertEqual(mock.streamConversationRequests.count, 1)
        XCTAssertEqual(mock.postMessageRequests.count, 0)
    }

    // MARK: - Follow-up routing

    func testFollowUpDispatchesToPostMessageWithEstablishedId() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.scripts = [startScript(conversation: convo), followUpScript()]
        let vm = makeViewModel(mock)

        vm.messageText = "Hi"
        await vm.send()
        XCTAssertEqual(mock.streamConversationRequests.count, 1)

        vm.messageText = "More"
        await vm.send()

        XCTAssertEqual(vm.turns.count, 2)
        XCTAssertEqual(mock.streamConversationRequests.count, 1)        // not re-created
        XCTAssertEqual(mock.postMessageRequests.count, 1)
        XCTAssertEqual(mock.postMessageRequests[0].conversationId, convo.id)
        XCTAssertEqual(mock.postMessageRequests[0].request.message, "More")
        XCTAssertEqual(vm.turns[1].coachMessage?.content, "Sure")
    }

    // MARK: - Optimistic echo + reconcile

    func testOptimisticEchoThenReconcileDoesNotDuplicate() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        // Delay so we can observe the optimistic bubble before any event lands.
        var script = startScript(conversation: convo)
        script.perEventDelay = .milliseconds(30)
        mock.scripts = [script]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        let task = Task { await vm.send() }
        // Optimistic bubble appears synchronously on append.
        try? await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(vm.turns.count, 1)
        XCTAssertEqual(vm.turns[0].userMessage.content, "Hi")          // synthetic
        XCTAssertNotEqual(vm.turns[0].userMessage.id, "su1")           // not yet reconciled

        await task.value

        XCTAssertEqual(vm.turns.count, 1)                              // no duplicate bubble
        XCTAssertEqual(vm.turns[0].userMessage.id, "su1")              // reconciled
    }

    // MARK: - Multi-turn accumulation

    func testMultiTurnAccumulation() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        let secondFollowUp = MockConversationClient.Script(events: [
            .userMessage(makeMessage(id: "su3", role: .user, content: "Third")),
            .delta("Ok"),
            .completed(makeMessage(id: "c3", role: .coach, content: "Ok")),
        ])
        mock.scripts = [startScript(conversation: convo), followUpScript(), secondFollowUp]
        let vm = makeViewModel(mock)

        vm.messageText = "Hi"
        await vm.send()
        vm.messageText = "More"
        await vm.send()
        vm.messageText = "Third"
        await vm.send()

        XCTAssertEqual(vm.turns.count, 3)
        XCTAssertEqual(vm.turns[0].coachMessage?.content, "Hi there")
        XCTAssertEqual(vm.turns[1].coachMessage?.content, "Sure")
        XCTAssertEqual(vm.turns[2].coachMessage?.content, "Ok")
        XCTAssertEqual(mock.streamConversationRequests.count, 1)
        XCTAssertEqual(mock.postMessageRequests.count, 2)
    }

    // MARK: - Validation

    func testEmptyMessageIsValidationErrorAndAppendsNoTurn() async {
        let mock = MockConversationClient()
        let vm = makeViewModel(mock)
        vm.messageText = "   \n  "

        await vm.send()

        XCTAssertEqual(vm.validationError?.code, "VALIDATION")
        XCTAssertNotNil(vm.validationError?.fieldError(for: "message"))
        XCTAssertTrue(vm.turns.isEmpty)
        XCTAssertEqual(mock.streamConversationRequests.count, 0)
        XCTAssertEqual(mock.postMessageRequests.count, 0)
    }

    func testTooLongMessageIsValidationErrorAndAppendsNoTurn() async {
        let mock = MockConversationClient()
        let vm = makeViewModel(mock)
        vm.messageText = String(repeating: "a", count: 100_001)

        await vm.send()

        XCTAssertEqual(vm.validationError?.code, "VALIDATION")
        XCTAssertTrue(vm.turns.isEmpty)
        XCTAssertEqual(mock.streamConversationRequests.count, 0)
        XCTAssertEqual(mock.postMessageRequests.count, 0)
    }

    // MARK: - Follow-up failure + retry-in-place

    func testFollowUpFailureSetsServerFailureThenRetrySucceeds() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        let failingFollowUp = MockConversationClient.Script(
            events: [.userMessage(makeMessage(id: "su2", role: .user, content: "More"))],
            terminalError: ErrorResponse(code: "coach_unavailable", message: "Coach unavailable", fieldErrors: nil)
        )
        mock.scripts = [startScript(conversation: convo), failingFollowUp]
        let vm = makeViewModel(mock)

        vm.messageText = "Hi"
        await vm.send()
        vm.messageText = "More"
        await vm.send()

        XCTAssertEqual(vm.turns.count, 2)
        XCTAssertEqual(
            vm.turns[1].failure,
            .server(ErrorResponse(code: "coach_unavailable", message: "Coach unavailable", fieldErrors: nil))
        )
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.turns[1].userMessage.content, "More")  // user bubble kept

        // Retry the failed follow-up turn.
        mock.scripts = [followUpScript()]
        await vm.retry(vm.turns[1].id)

        XCTAssertNil(vm.turns[1].failure)
        XCTAssertEqual(vm.turns[1].coachMessage?.content, "Sure")
        XCTAssertEqual(mock.postMessageRequests.count, 2)
        XCTAssertEqual(mock.postMessageRequests[1].conversationId, convo.id)  // same conversation
        XCTAssertEqual(mock.streamConversationRequests.count, 1)              // never re-created
    }

    // MARK: - First-turn failure retry re-creates

    func testFirstTurnFailureLeavesConversationNilAndRetryReCreates() async {
        let mock = MockConversationClient()
        let failingStart = MockConversationClient.Script(
            events: [
                .conversation(makeConversation(), userMessage: makeMessage(id: "su1", role: .user, content: "Hi")),
                .delta("partial"),
            ],
            terminalError: ErrorResponse(code: "coach_failed", message: "Coach failed", fieldErrors: nil)
        )
        mock.scripts = [failingStart]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertNil(vm.conversation)                                // not established
        XCTAssertEqual(
            vm.turns[0].failure,
            .server(ErrorResponse(code: "coach_failed", message: "Coach failed", fieldErrors: nil))
        )

        // Retry re-creates via streamConversation, not postMessage.
        let convo = makeConversation()
        mock.scripts = [startScript(conversation: convo)]
        await vm.retry(vm.turns[0].id)

        XCTAssertNil(vm.turns[0].failure)
        XCTAssertEqual(vm.turns[0].coachMessage?.content, "Hi there")
        XCTAssertEqual(vm.conversation?.id, convo.id)
        XCTAssertEqual(mock.streamConversationRequests.count, 2)     // re-created
        XCTAssertEqual(mock.postMessageRequests.count, 0)            // never posts to soft-deleted id
    }

    // MARK: - student_profile_required

    func testStudentProfileRequiredInvokesCallbackAndRemovesTurn() async {
        let mock = MockConversationClient()
        mock.scripts = [MockConversationClient.Script(
            events: [],
            terminalError: ErrorResponse(code: "student_profile_required", message: "no profile", fieldErrors: nil)
        )]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(profileRequiredCount, 1)
        XCTAssertNil(vm.validationError)
        XCTAssertTrue(vm.turns.isEmpty)                              // optimistic turn removed
        XCTAssertFalse(vm.isStreaming)
    }

    // MARK: - Transport error mapping

    func testTimeoutMapsToInfrastructureTimeoutOnTurn() async {
        let mock = MockConversationClient()
        mock.scripts = [MockConversationClient.Script(
            events: [],
            terminalError: ErrorResponse(code: "TIMEOUT", message: "timed out", fieldErrors: nil)
        )]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(vm.turns.count, 1)
        XCTAssertEqual(vm.turns[0].failure, .infrastructure(.timeout))
        XCTAssertNil(vm.validationError)
    }

    func testNetworkErrorMapsToNoConnectivityOnTurn() async {
        let mock = MockConversationClient()
        mock.scripts = [MockConversationClient.Script(
            events: [],
            terminalError: ErrorResponse(code: "NETWORK_ERROR", message: "offline", fieldErrors: nil)
        )]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(vm.turns[0].failure, .infrastructure(.noConnectivity))
    }

    // MARK: - Gating

    func testSendIsNoOpWhileStreaming() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        var script = startScript(conversation: convo)
        script.perEventDelay = .milliseconds(30)
        mock.scripts = [script]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        let task = Task { await vm.send() }
        try? await Task.sleep(for: .milliseconds(10))
        XCTAssertTrue(vm.isStreaming)

        // A second send while streaming is a no-op.
        vm.messageText = "Again"
        await vm.send()
        XCTAssertEqual(vm.turns.count, 1)

        await task.value
        XCTAssertEqual(mock.streamConversationRequests.count, 1)
    }

    // MARK: - canSend

    func testCanSendFalseOnEmptyMessage() async {
        let mock = MockConversationClient()
        let vm = makeViewModel(mock)

        XCTAssertEqual(vm.messageText, "")
        XCTAssertFalse(vm.canSend)
    }

    func testCanSendFalseOnWhitespaceOnlyMessage() async {
        let mock = MockConversationClient()
        let vm = makeViewModel(mock)
        vm.messageText = "   \n\t"

        XCTAssertFalse(vm.canSend)
    }

    func testCanSendTrueOnNonEmptyMessage() async {
        let mock = MockConversationClient()
        let vm = makeViewModel(mock)
        vm.messageText = "Hello"

        XCTAssertTrue(vm.canSend)
    }

    func testCanSendFalseWhileStreaming() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        var script = startScript(conversation: convo)
        script.perEventDelay = .milliseconds(30)
        mock.scripts = [script]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        let task = Task { await vm.send() }
        try? await Task.sleep(for: .milliseconds(10))
        XCTAssertTrue(vm.isStreaming)
        // Non-empty text but an in-flight stream gates the button off.
        vm.messageText = "Again"
        XCTAssertFalse(vm.canSend)

        await task.value
    }

    func testCanSendTrueAfterCompletedTurn() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.scripts = [startScript(conversation: convo)]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()
        XCTAssertFalse(vm.isStreaming)

        // Multi-turn: a completed turn must NOT disable the composer.
        vm.messageText = "Again"
        XCTAssertTrue(vm.canSend)
    }

    // MARK: - Cancellation

    func testCancellationStopsMutationAndResetsStreaming() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        var script = startScript(conversation: convo)
        script.perEventDelay = .milliseconds(50)
        mock.scripts = [script]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        let task = Task { await vm.send() }
        try? await Task.sleep(for: .milliseconds(20))
        task.cancel()
        await task.value

        XCTAssertFalse(vm.isStreaming)
        XCTAssertNil(vm.turns.first?.coachMessage)
        XCTAssertNil(vm.conversation)
    }

    // MARK: - Seed initializer + history load

    private func makeSeededViewModel(_ client: ConversationClientProtocol, conversation: Conversation) -> ConversationViewModel {
        ConversationViewModel(
            conversation: conversation,
            conversationClient: client,
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 }
        )
    }

    func testSeedInitializerRoutesFirstSendToPostMessage() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.fetchMessagesResult = []
        mock.scripts = [followUpScript()]
        let vm = makeSeededViewModel(mock, conversation: convo)

        XCTAssertEqual(vm.conversation?.id, convo.id)

        await vm.loadHistory()
        vm.messageText = "More"
        await vm.send()

        XCTAssertEqual(mock.streamConversationRequests.count, 0)   // never re-creates
        XCTAssertEqual(mock.postMessageRequests.count, 1)
        XCTAssertEqual(mock.postMessageRequests[0].conversationId, convo.id)
    }

    func testLoadHistoryBuildsOneTurnPerPairInOrder() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.fetchMessagesResult = [
            makeMessage(id: "u_1", role: .user, content: "Hi"),
            makeMessage(id: "c_1", role: .coach, content: "Hello"),
            makeMessage(id: "u_2", role: .user, content: "More"),
            makeMessage(id: "c_2", role: .coach, content: "Sure"),
        ]
        let vm = makeSeededViewModel(mock, conversation: convo)

        await vm.loadHistory()

        XCTAssertEqual(vm.turns.count, 2)
        XCTAssertEqual(vm.turns[0].userMessage.content, "Hi")
        XCTAssertEqual(vm.turns[0].coachMessage?.content, "Hello")
        XCTAssertEqual(vm.turns[1].userMessage.content, "More")
        XCTAssertEqual(vm.turns[1].coachMessage?.content, "Sure")
        XCTAssertEqual(vm.historyLoad, .ready)
        XCTAssertEqual(mock.fetchMessagesCallCount, 1)
        XCTAssertEqual(mock.fetchMessagesRequests[0], convo.id)
    }

    func testLoadHistoryTrailingUserYieldsTurnWithNilCoach() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.fetchMessagesResult = [
            makeMessage(id: "u_1", role: .user, content: "Hi"),
            makeMessage(id: "c_1", role: .coach, content: "Hello"),
            makeMessage(id: "u_2", role: .user, content: "Dangling"),
        ]
        let vm = makeSeededViewModel(mock, conversation: convo)

        await vm.loadHistory()

        XCTAssertEqual(vm.turns.count, 2)
        XCTAssertEqual(vm.turns[1].userMessage.content, "Dangling")
        XCTAssertNil(vm.turns[1].coachMessage)
    }

    func testLoadHistoryLeadingOrphanCoachIsDropped() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        // Contract violation: a coach message with no open turn. Crash-guard drops it.
        mock.fetchMessagesResult = [
            makeMessage(id: "c_0", role: .coach, content: "Orphan"),
            makeMessage(id: "u_1", role: .user, content: "Hi"),
            makeMessage(id: "c_1", role: .coach, content: "Hello"),
        ]
        let vm = makeSeededViewModel(mock, conversation: convo)

        await vm.loadHistory()

        XCTAssertEqual(vm.turns.count, 1)
        XCTAssertEqual(vm.turns[0].userMessage.content, "Hi")
        XCTAssertEqual(vm.turns[0].coachMessage?.content, "Hello")
    }

    func testLoadHistoryFailureThenRetrySucceeds() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.fetchMessagesError = ErrorResponse(code: "not_found", message: "No such conversation", fieldErrors: nil)
        let vm = makeSeededViewModel(mock, conversation: convo)

        await vm.loadHistory()

        XCTAssertEqual(vm.historyLoad, .failed(ErrorResponse(code: "not_found", message: "No such conversation", fieldErrors: nil)))
        XCTAssertTrue(vm.turns.isEmpty)
        XCTAssertEqual(mock.fetchMessagesCallCount, 1)

        // Retry: the guard still holds (turns empty), so the fetch re-runs.
        mock.fetchMessagesError = nil
        mock.fetchMessagesResult = [
            makeMessage(id: "u_1", role: .user, content: "Hi"),
            makeMessage(id: "c_1", role: .coach, content: "Hello"),
        ]
        await vm.loadHistory()

        XCTAssertEqual(vm.historyLoad, .ready)
        XCTAssertEqual(vm.turns.count, 1)
        XCTAssertEqual(vm.turns[0].coachMessage?.content, "Hello")
        XCTAssertEqual(mock.fetchMessagesCallCount, 2)
    }

    func testSendAfterHistoryLoadAppendsAfterLoadedTurns() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.fetchMessagesResult = [
            makeMessage(id: "u_1", role: .user, content: "Hi"),
            makeMessage(id: "c_1", role: .coach, content: "Hello"),
        ]
        mock.scripts = [followUpScript()]
        let vm = makeSeededViewModel(mock, conversation: convo)

        await vm.loadHistory()
        XCTAssertEqual(vm.turns.count, 1)

        vm.messageText = "More"
        await vm.send()

        XCTAssertEqual(vm.turns.count, 2)
        XCTAssertEqual(vm.turns[1].coachMessage?.content, "Sure")
        XCTAssertEqual(mock.postMessageRequests.count, 1)
        XCTAssertEqual(mock.postMessageRequests[0].conversationId, convo.id)
    }

    func testFreshInitializerLoadHistoryIsNoOpAndStartsFresh() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.scripts = [startScript(conversation: convo)]
        let vm = makeViewModel(mock)

        XCTAssertNil(vm.conversation)
        XCTAssertEqual(vm.historyLoad, .ready)

        await vm.loadHistory()
        XCTAssertEqual(mock.fetchMessagesCallCount, 0)   // no fetch on the fresh path
        XCTAssertTrue(vm.turns.isEmpty)

        vm.messageText = "Hi"
        await vm.send()
        XCTAssertEqual(mock.streamConversationRequests.count, 1)   // first send creates
        XCTAssertEqual(mock.postMessageRequests.count, 0)
    }

    // MARK: - isReady (composer gate)

    func testIsReadyFalseWhileSeededHistoryLoading() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        let vm = makeSeededViewModel(mock, conversation: convo)

        XCTAssertFalse(vm.isReady)   // seeded VM starts .loading
    }

    func testIsReadyTrueOnFreshVMAndAfterSuccessfulLoad() async {
        let mock = MockConversationClient()
        let freshVM = makeViewModel(mock)
        XCTAssertTrue(freshVM.isReady)

        let convo = makeConversation()
        mock.fetchMessagesResult = [
            makeMessage(id: "u_1", role: .user, content: "Hi"),
            makeMessage(id: "c_1", role: .coach, content: "Hello"),
        ]
        let seededVM = makeSeededViewModel(mock, conversation: convo)
        await seededVM.loadHistory()
        XCTAssertTrue(seededVM.isReady)
    }
}
