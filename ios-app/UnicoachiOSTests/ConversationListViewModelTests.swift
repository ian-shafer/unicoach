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
}
