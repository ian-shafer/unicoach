import XCTest
@testable import UnicoachiOS

/// The parent's coach on iOS (RFC 258): the door on the home, the starter, and
/// a thread driven end to end through `SupporterThreadClient` and the shared
/// `ConversationViewModel`.
@MainActor
final class SupporterCoachTests: XCTestCase {
    private let ava = LinkedStudent(
        linkId: UUID(uuidString: "66660000-0000-0000-0000-000000000001")!,
        studentId: UUID(uuidString: "55550000-0000-0000-0000-000000000001")!,
        name: "Ava",
        relationship: .parent,
        otherSupporterNames: []
    )

    func testTheHomeOffersTheCoachOnlyWithASelectedStudent() {
        XCTAssertNil(SupporterCoachEntry.title(for: nil), "every conversation is about one student")
        XCTAssertEqual(SupporterCoachEntry.title(for: ava), "Talk to your coach about Ava")
    }

    private func conversation(_ name: String, about studentId: UUID) -> SupporterConversation {
        SupporterConversation(id: UUID(), studentId: studentId, name: name, createdAt: Date(), updatedAt: Date())
    }

    /// The list is this student's conversations only, and is read again on
    /// every load: a conversation started in a pushed thread is on the list the
    /// parent returns to, not only after leaving the coach.
    func testTheListIsReReadSoANewConversationAppearsOnReturn() async {
        let mock = MockSupporterConversationClient()
        let earlier = conversation("Earlier", about: ava.studentId)
        let otherStudents = conversation("About Noah", about: UUID())
        mock.conversations = [earlier, otherStudents]
        let listing = SupporterCoachListing(client: mock, studentId: ava.studentId)

        await listing.load()
        XCTAssertEqual(listing.state, .loaded([earlier]))

        let started = conversation("Costs", about: ava.studentId)
        mock.conversations = [started, earlier, otherStudents]
        await listing.load()

        XCTAssertEqual(listing.state, .loaded([started, earlier]))
        XCTAssertEqual(mock.listCalls, 2)
    }

    /// A failed first read is `.unavailable` — never `.loaded([])`, which
    /// would tell a parent with conversations that they have none; a failed
    /// re-read keeps the list already on screen rather than blanking it.
    func testAFailedListReadIsUnavailableFirstAndKeepsTheListAfter() async {
        let mock = MockSupporterConversationClient()
        let listing = SupporterCoachListing(client: mock, studentId: ava.studentId)
        mock.listError = URLError(.notConnectedToInternet)

        await listing.load()
        XCTAssertEqual(listing.state, .unavailable)
        XCTAssertNotEqual(listing.state, .loaded([]), "a failure is not an empty list")

        let earlier = conversation("Earlier", about: ava.studentId)
        mock.listError = nil
        mock.conversations = [earlier]
        await listing.load()
        mock.listError = URLError(.notConnectedToInternet)
        await listing.load()

        XCTAssertEqual(listing.state, .loaded([earlier]))
    }

    /// Retry from the notice: a read that succeeds replaces `.unavailable`
    /// with the real answer, empty or not, and one that fails again stays
    /// `.unavailable`.
    func testRetryAfterAnUnavailableListResolvesIt() async {
        let mock = MockSupporterConversationClient()
        let listing = SupporterCoachListing(client: mock, studentId: ava.studentId)
        mock.listError = URLError(.timedOut)
        await listing.load()
        await listing.load()
        XCTAssertEqual(listing.state, .unavailable, "a second failure is still unavailable")

        mock.listError = nil
        mock.conversations = []
        await listing.load()
        XCTAssertEqual(listing.state, .loaded([]), "a real empty answer is loaded, not unavailable")

        let earlier = conversation("Earlier", about: ava.studentId)
        mock.conversations = [earlier]
        await listing.load()
        XCTAssertEqual(listing.state, .loaded([earlier]))
    }

    /// A cancelled read is not a result: before any answer it leaves
    /// `.loading`, and after one it leaves the list on screen.
    func testACancelledListReadRecordsNothing() async {
        let mock = MockSupporterConversationClient()
        let listing = SupporterCoachListing(client: mock, studentId: ava.studentId)
        mock.listError = CancellationError()

        await listing.load()
        XCTAssertEqual(listing.state, .loading)

        let earlier = conversation("Earlier", about: ava.studentId)
        mock.listError = nil
        mock.conversations = [earlier]
        await listing.load()
        mock.listError = CancellationError()
        await listing.load()
        XCTAssertEqual(listing.state, .loaded([earlier]))

        mock.listError = URLError(.timedOut)
        let unavailable = SupporterCoachListing(client: mock, studentId: ava.studentId)
        await unavailable.load()
        mock.listError = CancellationError()
        await unavailable.load()
        XCTAssertEqual(unavailable.state, .unavailable, "a cancelled retry leaves the notice up")
    }

    func testTheStarterNamesTheStudent() {
        XCTAssertEqual(SupporterThreadView.starter(for: ava), "What would Ava's list cost our family?")
    }

    /// The first session: the starter goes out as a NEW conversation about the
    /// selected student, and every later turn goes to that conversation.
    func testAThreadStartsAboutItsStudentAndContinuesTheConversation() async {
        let mock = MockSupporterConversationClient()
        let convo = Conversation(id: UUID(), name: "Costs", createdAt: Date(), updatedAt: Date(), lastActivityAt: nil, archivedAt: nil)
        mock.scripts = [
            .init(events: [
                .conversation(convo, userMessage: Message(id: "u_1", role: .user, content: "q", createdAt: Date())),
                .completed(Message(id: "c_2", role: .coach, content: "It costs this.", createdAt: Date())),
            ]),
            .init(events: [
                .userMessage(Message(id: "u_3", role: .user, content: "next", createdAt: Date())),
                .completed(Message(id: "c_4", role: .coach, content: "Do this next.", createdAt: Date())),
            ]),
        ]
        var turnsFinished = 0
        let viewModel = ConversationViewModel(
            conversationClient: SupporterThreadClient(client: mock, studentId: ava.studentId),
            onProfileRequired: {},
            onBudgetExhausted: {},
            onTurnFinished: { turnsFinished += 1 }
        )

        viewModel.messageText = SupporterThreadView.starter(for: ava)
        await viewModel.send()
        viewModel.messageText = "And what do I do next?"
        await viewModel.send()

        XCTAssertEqual(mock.startRequests.map(\.studentId), [ava.studentId])
        XCTAssertEqual(mock.startRequests.map(\.message), ["What would Ava's list cost our family?"])
        XCTAssertEqual(mock.postRequests.map(\.conversationId), [convo.id])
        XCTAssertEqual(viewModel.turns.compactMap(\.coachMessage?.content), ["It costs this.", "Do this next."])
        XCTAssertEqual(turnsFinished, 2, "each turn re-reads the parent's meter")
    }

    /// The parent's own allowance is spent: the turn is kept, marked blocked
    /// (rendered as the parent's coaching block), and the meter is re-read.
    func testASpentParentAllowanceBlocksTheTurnAndReReadsTheMeter() async {
        let mock = MockSupporterConversationClient()
        mock.scripts = [
            .init(events: [], terminalError: ErrorResponse(code: "coaching_budget_exhausted", message: "m", fieldErrors: nil, status: 402)),
        ]
        var refusals = 0
        let viewModel = ConversationViewModel(
            conversationClient: SupporterThreadClient(client: mock, studentId: ava.studentId),
            onProfileRequired: {},
            onBudgetExhausted: { refusals += 1 },
            onTurnFinished: {}
        )
        viewModel.messageText = "What will it cost?"

        await viewModel.send()

        XCTAssertEqual(viewModel.turns.first?.failure, .blocked)
        XCTAssertEqual(refusals, 1)
    }
}
