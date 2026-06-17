import XCTest
@testable import UnicoachiOS

@MainActor
final class ConversationListViewModelTests: XCTestCase {
    private func makeConversation(name: String) -> Conversation {
        Conversation(
            id: UUID(),
            name: name,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            lastActivityAt: Date(timeIntervalSince1970: 0),
            archivedAt: nil
        )
    }

    func testLoadSuccessTransitionsToLoadedPreservingOrder() async {
        let mock = MockConversationClient()
        mock.listConversationsResult = [
            makeConversation(name: "Newest"),
            makeConversation(name: "Middle"),
            makeConversation(name: "Oldest"),
        ]
        let vm = ConversationListViewModel(conversationClient: mock)

        await vm.load()

        guard case .loaded(let conversations) = vm.state else {
            return XCTFail("expected .loaded, got [\(vm.state)]")
        }
        XCTAssertEqual(conversations.map(\.name), ["Newest", "Middle", "Oldest"])
        XCTAssertEqual(mock.listConversationsCallCount, 1)
    }

    func testLoadEmptyTransitionsToEmpty() async {
        let mock = MockConversationClient()
        mock.listConversationsResult = []
        let vm = ConversationListViewModel(conversationClient: mock)

        await vm.load()

        XCTAssertEqual(vm.state, .empty)
    }

    func testLoadFailureCarriesError() async {
        let mock = MockConversationClient()
        let error = ErrorResponse(code: "unauthorized", message: "Not authenticated", fieldErrors: nil)
        mock.listConversationsError = error
        let vm = ConversationListViewModel(conversationClient: mock)

        await vm.load()

        XCTAssertEqual(vm.state, .failed(error))
    }

    func testNonErrorResponseFailureSynthesizesServerError() async {
        struct OpaqueError: Error {}
        let mock = MockConversationClient()
        mock.listConversationsError = OpaqueError()
        let vm = ConversationListViewModel(conversationClient: mock)

        await vm.load()

        guard case .failed(let error) = vm.state else {
            return XCTFail("expected .failed, got [\(vm.state)]")
        }
        XCTAssertEqual(error.code, "SERVER_ERROR")
    }

    func testSecondAppearanceReFetchesAndReplacesState() async {
        let mock = MockConversationClient()
        mock.listConversationsResult = [makeConversation(name: "First")]
        let vm = ConversationListViewModel(conversationClient: mock)

        await vm.load()
        guard case .loaded(let first) = vm.state else {
            return XCTFail("expected .loaded after first load")
        }
        XCTAssertEqual(first.map(\.name), ["First"])
        XCTAssertEqual(mock.listConversationsCallCount, 1)

        // A second appearance re-fetches (MRU freshness) and replaces the state.
        mock.listConversationsResult = [
            makeConversation(name: "Just continued"),
            makeConversation(name: "First"),
        ]
        await vm.load()

        guard case .loaded(let second) = vm.state else {
            return XCTFail("expected .loaded after second load")
        }
        XCTAssertEqual(second.map(\.name), ["Just continued", "First"])
        XCTAssertEqual(mock.listConversationsCallCount, 2)
    }

    // MARK: - archive / delete intents

    /// Loads the view model with the given conversations and asserts it reached
    /// `.loaded`, returning the conversations for the test to act on.
    private func loadedViewModel(
        _ conversations: [Conversation],
        configure: (MockConversationClient) -> Void = { _ in }
    ) async -> (ConversationListViewModel, MockConversationClient) {
        let mock = MockConversationClient()
        mock.listConversationsResult = conversations
        configure(mock)
        let vm = ConversationListViewModel(conversationClient: mock)
        await vm.load()
        return (vm, mock)
    }

    func testArchiveRemovesRowOptimistically() async {
        let a = makeConversation(name: "A")
        let b = makeConversation(name: "B")
        let c = makeConversation(name: "C")
        let (vm, mock) = await loadedViewModel([a, b, c])

        await vm.archive(b)

        guard case .loaded(let conversations) = vm.state else {
            return XCTFail("expected .loaded, got [\(vm.state)]")
        }
        XCTAssertEqual(conversations.map(\.id), [a.id, c.id])
        XCTAssertEqual(mock.setArchivedRequests.count, 1)
        XCTAssertEqual(mock.setArchivedRequests.first?.conversationId, b.id)
        XCTAssertEqual(mock.setArchivedRequests.first?.archived, true)
        XCTAssertNil(vm.actionError)
    }

    func testArchiveFailureRollsBackAtOriginalIndex() async {
        let a = makeConversation(name: "A")
        let b = makeConversation(name: "B")
        let c = makeConversation(name: "C")
        let (vm, _) = await loadedViewModel([a, b, c]) { mock in
            mock.setArchivedError = ErrorResponse(code: "not_found", message: "gone", fieldErrors: nil)
        }

        await vm.archive(b)

        guard case .loaded(let conversations) = vm.state else {
            return XCTFail("expected .loaded, got [\(vm.state)]")
        }
        XCTAssertEqual(conversations.map(\.id), [a.id, b.id, c.id])
        XCTAssertEqual(conversations[1].id, b.id)
        XCTAssertEqual(vm.actionError?.code, "not_found")
    }

    func testDeleteRemovesRowOptimistically() async {
        let a = makeConversation(name: "A")
        let b = makeConversation(name: "B")
        let c = makeConversation(name: "C")
        let (vm, mock) = await loadedViewModel([a, b, c])

        await vm.delete(c)

        guard case .loaded(let conversations) = vm.state else {
            return XCTFail("expected .loaded, got [\(vm.state)]")
        }
        XCTAssertEqual(conversations.map(\.id), [a.id, b.id])
        XCTAssertEqual(mock.deleteConversationRequests, [c.id])
        XCTAssertNil(vm.actionError)
    }

    func testDeleteFailureRollsBackAndSurfacesError() async {
        let a = makeConversation(name: "A")
        let b = makeConversation(name: "B")
        let c = makeConversation(name: "C")
        let error = ErrorResponse(code: "unauthorized", message: "nope", fieldErrors: nil)
        let (vm, _) = await loadedViewModel([a, b, c]) { mock in
            mock.deleteConversationError = error
        }

        await vm.delete(a)

        guard case .loaded(let conversations) = vm.state else {
            return XCTFail("expected .loaded, got [\(vm.state)]")
        }
        XCTAssertEqual(conversations.map(\.id), [a.id, b.id, c.id])
        XCTAssertEqual(vm.actionError, error)
    }

    func testArchiveLastConversationTransitionsToEmpty() async {
        let a = makeConversation(name: "A")
        let (vm, _) = await loadedViewModel([a])

        await vm.archive(a)

        XCTAssertEqual(vm.state, .empty)
        XCTAssertNil(vm.actionError)
    }

    func testActionFailureFromEmptyRestoresLoaded() async {
        let a = makeConversation(name: "A")
        let (vm, _) = await loadedViewModel([a]) { mock in
            mock.setArchivedError = ErrorResponse(code: "not_found", message: "gone", fieldErrors: nil)
        }

        await vm.archive(a)

        guard case .loaded(let conversations) = vm.state else {
            return XCTFail("expected .loaded, got [\(vm.state)]")
        }
        XCTAssertEqual(conversations.map(\.id), [a.id])
        XCTAssertNotNil(vm.actionError)
    }

    func testNonErrorResponseActionFailureSynthesizesServerError() async {
        struct OpaqueError: Error {}
        let a = makeConversation(name: "A")
        let b = makeConversation(name: "B")
        let (vm, _) = await loadedViewModel([a, b]) { mock in
            mock.deleteConversationError = OpaqueError()
        }

        await vm.delete(a)

        guard case .loaded(let conversations) = vm.state else {
            return XCTFail("expected .loaded, got [\(vm.state)]")
        }
        XCTAssertEqual(conversations.map(\.id), [a.id, b.id])
        XCTAssertEqual(vm.actionError?.code, "SERVER_ERROR")
    }

    func testActionOnUnknownConversationNoOps() async {
        let a = makeConversation(name: "A")
        let b = makeConversation(name: "B")
        let stranger = makeConversation(name: "Stranger")
        let (vm, mock) = await loadedViewModel([a, b])

        await vm.delete(stranger)

        guard case .loaded(let conversations) = vm.state else {
            return XCTFail("expected .loaded, got [\(vm.state)]")
        }
        XCTAssertEqual(conversations.map(\.id), [a.id, b.id])
        XCTAssertTrue(mock.deleteConversationRequests.isEmpty)
        XCTAssertNil(vm.actionError)
    }

    func testActionFailureDoesNotEnterFailedState() async {
        let a = makeConversation(name: "A")
        let b = makeConversation(name: "B")
        let (vm, _) = await loadedViewModel([a, b]) { mock in
            mock.deleteConversationError = ErrorResponse(code: "not_found", message: "gone", fieldErrors: nil)
        }

        await vm.delete(a)

        if case .failed = vm.state {
            XCTFail("action failure must not enter .failed state")
        }
        guard case .loaded = vm.state else {
            return XCTFail("expected .loaded after action failure, got [\(vm.state)]")
        }
    }
}
