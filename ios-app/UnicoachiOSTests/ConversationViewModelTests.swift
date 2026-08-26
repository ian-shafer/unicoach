import XCTest
@testable import UnicoachiOS

@MainActor
final class ConversationViewModelTests: XCTestCase {
    /// Counts `onProfileRequired` invocations for the active view model.
    private var profileRequiredCount = 0
    /// Counts `onBudgetExhausted` invocations — the 402's report upward.
    private var budgetExhaustedCount = 0
    /// Counts `onTurnFinished` invocations — the per-turn "re-read the meter"
    /// report. Counted rather than flagged: the point of most of these cases is
    /// that it fires **once** per terminated turn, and a `Bool` cannot tell one
    /// call from two.
    private var turnFinishedCount = 0

    override func setUp() {
        super.setUp()
        profileRequiredCount = 0
        budgetExhaustedCount = 0
        turnFinishedCount = 0
    }

    private func makeViewModel(_ client: ConversationClientProtocol) -> ConversationViewModel {
        ConversationViewModel(
            conversationClient: client,
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 },
            onBudgetExhausted: { [weak self] in self?.budgetExhaustedCount += 1 },
            onTurnFinished: { [weak self] in self?.turnFinishedCount += 1 }
        )
    }

    /// The 402 both streaming turn endpoints answer once the budget is spent.
    /// Pre-stream and plain JSON, so it reaches the view model as a terminal
    /// `ErrorResponse` with no events before it.
    private func budgetExhausted() -> ErrorResponse {
        ErrorResponse(
            code: "coaching_budget_exhausted",
            message: "Coaching allowance exhausted",
            fieldErrors: nil,
            status: 402
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

    // MARK: - coaching_budget_exhausted (RFC 121)

    /// The deliberate inverse of `student_profile_required` above: the turn the
    /// student wrote is **kept**. It never reached the model, and deleting their
    /// writing to make room for an error message is the defect this arm exists
    /// to remove.
    func testBudgetExhaustedKeepsTheTurnMarksItBlockedAndReportsUpward() async {
        let mock = MockConversationClient()
        mock.scripts = [MockConversationClient.Script(events: [], terminalError: budgetExhausted())]
        let vm = makeViewModel(mock)
        vm.messageText = "Should I apply early?"

        await vm.send()

        XCTAssertEqual(vm.turns.count, 1, "the optimistic turn is KEPT, unlike the profile-required arm")
        XCTAssertEqual(vm.turns[0].userMessage.content, "Should I apply early?")
        XCTAssertEqual(vm.turns[0].failure, .blocked)
        XCTAssertEqual(budgetExhaustedCount, 1)
        XCTAssertEqual(profileRequiredCount, 0)
        XCTAssertNil(vm.validationError)
        XCTAssertFalse(vm.isStreaming)
        XCTAssertNil(vm.conversation, "a refused first turn establishes nothing")
    }

    /// Both streaming call sites are gated server-side, and the arm cannot tell
    /// them apart — an established conversation is refused identically.
    func testBudgetExhaustedOnTheMessageEndpointBehavesIdentically() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.scripts = [
            startScript(conversation: convo),
            MockConversationClient.Script(events: [], terminalError: budgetExhausted()),
        ]
        let vm = makeViewModel(mock)

        vm.messageText = "Hi"
        await vm.send()
        vm.messageText = "More"
        await vm.send()

        XCTAssertEqual(vm.turns.count, 2)
        XCTAssertEqual(vm.turns[1].failure, .blocked)
        XCTAssertEqual(vm.turns[1].userMessage.content, "More")
        XCTAssertEqual(budgetExhaustedCount, 1)
        XCTAssertEqual(mock.postMessageRequests.count, 1)
    }

    /// The whole point of keeping the turn: once the block clears, retrying it
    /// re-sends the words the student already wrote, unchanged.
    func testAnUnblockedTurnRetriesWithTheOriginalText() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.scripts = [
            startScript(conversation: convo),
            MockConversationClient.Script(events: [], terminalError: budgetExhausted()),
        ]
        let vm = makeViewModel(mock)

        vm.messageText = "Hi"
        await vm.send()
        vm.messageText = "Which essay should I write first?"
        await vm.send()
        XCTAssertEqual(vm.turns[1].failure, .blocked)

        // Unblocked: the same turn is re-dispatched.
        mock.scripts = [followUpScript()]
        await vm.retry(vm.turns[1].id)

        XCTAssertNil(vm.turns[1].failure)
        XCTAssertEqual(vm.turns[1].coachMessage?.content, "Sure")
        XCTAssertEqual(mock.postMessageRequests.count, 2)
        XCTAssertEqual(
            mock.postMessageRequests[1].request.message,
            "Which essay should I write first?",
            "the words survived the refusal"
        )
        XCTAssertEqual(budgetExhaustedCount, 1, "a successful retry reports nothing")
    }

    // MARK: - The affordance rule (RFC 121)

    /// A shared `SubscriptionViewModel` in whatever meter state a test needs —
    /// the same object `AuthenticatedRootView` owns and every `ConversationView`
    /// observes, so `budget` here is the real derived value and not a stand-in
    /// value.
    private func makeSubscriptionViewModel(usage: CoachingUsage?) async -> SubscriptionViewModel {
        let usageClient = MockCoachingUsageClient()
        if let usage { usageClient.results = [.success(usage)] }
        let vm = SubscriptionViewModel(
            usageClient: usageClient,
            store: MockSubscriptionStore(),
            recorder: MockTransactionRecorder()
        )
        if usage != nil { await vm.refreshUsage() }
        return vm
    }

    /// **The case the meter alone gets wrong.** `budgetExhausted()`'s handler
    /// refreshes usage from the server, and that refresh can fail — leaving
    /// `usage` nil while the turn is unambiguously refused. The affordance must
    /// still be See options: a Retry here could only reproduce the 402, beside
    /// paywall copy saying so.
    func testBlockedTurnOffersSeeOptionsEvenWhenTheMeterIsMissing() async {
        let mock = MockConversationClient()
        mock.scripts = [MockConversationClient.Script(events: [], terminalError: budgetExhausted())]
        let vm = makeViewModel(mock)
        vm.messageText = "Should I apply early?"

        await vm.send()

        // The post-402 refresh failed: no reading at all.
        let subscription = await makeSubscriptionViewModel(usage: nil)
        XCTAssertEqual(subscription.usageReading, .loading, "no read has landed")
        XCTAssertEqual(subscription.budget, .unknown, "no reading is neither open nor spent")

        XCTAssertEqual(vm.turns[0].failure, .blocked)
        XCTAssertEqual(
            TurnAction(failure: vm.turns[0].failure!, budget: subscription.budget),
            .seeOptions,
            "with no reading, the 402 is the authority: a refused turn offers See options"
        )
        XCTAssertEqual(
            TurnAction(failure: .infrastructure(.noConnectivity), budget: subscription.budget),
            .retry,
            "and an unknown meter never takes an ordinary failure's Retry away"
        )
    }

    /// The other half of the condition: the meter says the budget is spent, so
    /// even a turn that failed for some other reason is spared a Retry that
    /// would only 402.
    func testBlockedTurnOffersSeeOptionsWhenTheMeterSaysExhausted() async {
        let mock = MockConversationClient()
        mock.scripts = [MockConversationClient.Script(events: [], terminalError: budgetExhausted())]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"
        await vm.send()

        let subscription = await makeSubscriptionViewModel(
            usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)
        )
        XCTAssertEqual(subscription.budget, .spent)

        XCTAssertEqual(TurnAction(failure: vm.turns[0].failure!, budget: subscription.budget), .seeOptions)
        XCTAssertEqual(
            TurnAction(failure: .infrastructure(.noConnectivity), budget: subscription.budget),
            .seeOptions,
            "while the budget is known-spent, no failure offers a Retry that would 402"
        )
    }

    /// **The assertion the whole "keep the student's words" argument rests on.**
    /// The turn was refused by the 402 and still carries `.blocked` — that mark
    /// is permanent state on the turn, nothing ever clears it — so the rule must
    /// read the *meter* to know the block has lifted. Asserting this on an
    /// `.infrastructure` failure instead would be trivially true and prove
    /// nothing; the subject here is the refused turn itself, and retrying it
    /// re-sends the words the student already wrote.
    func testOnceUnblockedTheSameTurnOffersRetryAndResendsTheOriginalText() async {
        let mock = MockConversationClient()
        let convo = makeConversation()
        mock.scripts = [
            startScript(conversation: convo),
            MockConversationClient.Script(events: [], terminalError: budgetExhausted()),
        ]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"
        await vm.send()
        vm.messageText = "Which essay should I write first?"
        await vm.send()

        let blocked = await makeSubscriptionViewModel(
            usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)
        )
        XCTAssertEqual(blocked.budget, .spent)
        XCTAssertEqual(TurnAction(failure: vm.turns[1].failure!, budget: blocked.budget), .seeOptions)

        // A purchase (or a new period) lands: the server says the budget is open.
        let unblocked = await makeSubscriptionViewModel(
            usage: CoachingUsage(usedPercent: 10, exhausted: false, resetsAt: nil)
        )
        XCTAssertEqual(unblocked.budget, .open)
        XCTAssertEqual(vm.turns[1].failure, .blocked, "the turn is still marked refused — that never changes")
        XCTAssertEqual(
            TurnAction(failure: vm.turns[1].failure!, budget: unblocked.budget),
            .retry,
            "the refused turn is retryable again: an open meter lifts the block"
        )
        XCTAssertEqual(
            TurnAction(failure: .infrastructure(.noConnectivity), budget: unblocked.budget),
            .retry,
            "an ordinary failure is retryable again"
        )

        mock.scripts = [followUpScript()]
        await vm.retry(vm.turns[1].id)

        XCTAssertNil(vm.turns[1].failure)
        XCTAssertEqual(
            mock.postMessageRequests[1].request.message,
            "Which essay should I write first?",
            "retrying sends the words the student already wrote"
        )
    }

    /// The same lift, in the *copy*: a refused turn must not go on saying "You've
    /// used this period's coaching" under the student's own message once the
    /// meter reports the budget open — that sentence would contradict the Retry
    /// button that has just come back beside it.
    func testARefusedTurnStopsRenderingPaywallCopyOnceTheMeterReportsOpen() async {
        let spent = await makeSubscriptionViewModel(
            usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)
        )
        XCTAssertEqual(
            PaywallCopy.refusedTurnDetail(basis: spent.coachingBasis),
            "You've used your free coaching.",
            "while the budget is spent, the refused turn carries the block's own words"
        )

        let open = await makeSubscriptionViewModel(
            usage: CoachingUsage(usedPercent: 10, exhausted: false, resetsAt: nil)
        )
        let detail = PaywallCopy.refusedTurnDetail(basis: open.coachingBasis)
        XCTAssertNotEqual(detail, "You've used your free coaching.")
        XCTAssertEqual(detail, "This message wasn't sent. Send it again when you're ready.")

        let unknown = await makeSubscriptionViewModel(usage: nil)
        XCTAssertEqual(
            PaywallCopy.refusedTurnDetail(basis: unknown.coachingBasis),
            "You've used your coaching allowance.",
            "with no reading the 402 still stands, in the neutral sentence"
        )
    }

    /// The escalation runs **after** the stream has torn down: `isStreaming` is
    /// already false while `onBudgetExhausted` does its network round trip, so a
    /// slow (or hanging) usage refresh cannot hold the composer disabled and the
    /// send button spinning on a turn that has already failed.
    func testTheBudgetEscalationRunsAfterStreamingHasCleared() async {
        let mock = MockConversationClient()
        mock.scripts = [MockConversationClient.Script(events: [], terminalError: budgetExhausted())]
        let probe = StreamingProbe()
        let vm = ConversationViewModel(
            conversationClient: mock,
            onProfileRequired: {},
            onBudgetExhausted: { probe.observed = probe.viewModel?.isStreaming },
            onTurnFinished: {}
        )
        probe.viewModel = vm
        vm.messageText = "Should I apply early?"

        await vm.send()

        XCTAssertEqual(probe.observed, false, "the escalation must not hold isStreaming across its round trip")
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.turns[0].failure, .blocked, "and the failure was mapped before the escalation ran")
    }

    // MARK: - Per-turn meter refresh (onTurnFinished)

    /// The reason the hook exists: a turn that completed spent coaching, so the
    /// layer above is told exactly once and can re-read the meter beside the
    /// send button instead of leaving launch's number there all session.
    func testACompletedTurnReportsTurnFinishedOnce() async {
        let mock = MockConversationClient()
        mock.scripts = [startScript(conversation: makeConversation())]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(turnFinishedCount, 1)
        XCTAssertEqual(budgetExhaustedCount, 0)
    }

    /// A stream that died mid-reply burned tokens all the same, so the meter is
    /// re-read on failure too. Skipping this would drift the number on exactly
    /// the turns that cost the most.
    func testAFailedTurnAlsoReportsTurnFinished() async {
        let mock = MockConversationClient()
        let error = ErrorResponse(code: "coach_unavailable", message: "Coach unavailable", fieldErrors: nil, status: 503)
        mock.scripts = [MockConversationClient.Script(events: [.delta("Well"), .delta(" then")], terminalError: error)]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(vm.turns[0].failure, .server(error))
        XCTAssertEqual(turnFinishedCount, 1)
    }

    /// The two reports are exclusive. A 402 escalates and does **not** also ask
    /// for an ordinary re-read: the refusal path forces its own invalidating
    /// one, and firing both would mean two GETs for one turn with the paywall
    /// waiting on the loser.
    func testA402ReportsTheEscalationAndNotTurnFinished() async {
        let mock = MockConversationClient()
        mock.scripts = [MockConversationClient.Script(events: [], terminalError: budgetExhausted())]
        let vm = makeViewModel(mock)
        vm.messageText = "Should I apply early?"

        await vm.send()

        XCTAssertEqual(vm.turns[0].failure, .blocked)
        XCTAssertEqual(budgetExhaustedCount, 1)
        XCTAssertEqual(turnFinishedCount, 0, "the refusal re-reads usage itself; a second GET would race it")
    }

    /// The same rule the escalation is held to, for the same reason: the meter
    /// refresh is a network round trip on another layer's behalf, and running
    /// it with `isStreaming` still true would leave the composer disabled and
    /// the send button spinning on a turn that has already finished.
    func testTurnFinishedRunsAfterStreamingHasCleared() async {
        let mock = MockConversationClient()
        mock.scripts = [startScript(conversation: makeConversation())]
        let probe = StreamingProbe()
        let vm = ConversationViewModel(
            conversationClient: mock,
            onProfileRequired: {},
            onBudgetExhausted: {},
            onTurnFinished: { probe.observed = probe.viewModel?.isStreaming }
        )
        probe.viewModel = vm
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(probe.observed, false, "the meter refresh must not hold isStreaming across its round trip")
        XCTAssertFalse(vm.isStreaming)
    }

    /// Regression: every other server code keeps its own message and its Retry.
    func testANon402ServerErrorStillTakesTheKeepAndRetryPath() async {
        let mock = MockConversationClient()
        let error = ErrorResponse(code: "coach_unavailable", message: "Coach unavailable", fieldErrors: nil, status: 503)
        mock.scripts = [MockConversationClient.Script(events: [], terminalError: error)]
        let vm = makeViewModel(mock)
        vm.messageText = "Hi"

        await vm.send()

        XCTAssertEqual(vm.turns.count, 1)
        XCTAssertEqual(vm.turns[0].failure, .server(error))
        XCTAssertEqual(budgetExhaustedCount, 0)
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
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 },
            onBudgetExhausted: { [weak self] in self?.budgetExhaustedCount += 1 },
            onTurnFinished: { [weak self] in self?.turnFinishedCount += 1 }
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

/// Lets an escalation closure look back at the view model that invoked it —
/// the only way to observe `isStreaming` *during* `onBudgetExhausted`.
@MainActor
private final class StreamingProbe {
    weak var viewModel: ConversationViewModel?
    var observed: Bool?
}
