import XCTest
@testable import UnicoachiOS

@MainActor
final class SubjectPickerViewModelTests: XCTestCase {
    private var client: MockSubjectsClient!

    override func setUp() {
        super.setUp()
        client = MockSubjectsClient()
    }

    private func makeViewModel() -> SubjectPickerViewModel {
        // Short enough that the tests do not wait, long enough that a burst of
        // writes lands inside one window.
        SubjectPickerViewModel(client: client, debounce: .milliseconds(20))
    }

    func testTheFirstReadIsTheWholeListWithNoQuery() async {
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(client.searchCallCount, 1)
        XCTAssertEqual(client.lastQuery, .some(nil), "absent q serves the whole list in name order")
        XCTAssertEqual(viewModel.state, .results(PublicSubject.fixtures))
    }

    func testABurstOfKeystrokesCoalescesIntoOneRequestForTheFinalText() async {
        let viewModel = makeViewModel()
        viewModel.query = "n"
        viewModel.query = "nu"
        viewModel.query = "nur"
        await viewModel.awaitPendingSearch()
        XCTAssertEqual(client.searchCallCount, 1)
        XCTAssertEqual(client.lastQuery, .some("nur"))
    }

    func testClearingTheFieldAsksForTheWholeListRatherThanABlankQuery() async {
        let viewModel = makeViewModel()
        viewModel.query = "nursing"
        await viewModel.awaitPendingSearch()
        viewModel.query = "   "
        await viewModel.awaitPendingSearch()
        // A blank-but-present `q` is a 400 server-side; the student who cleared
        // the field is asking for the list, not making a malformed request.
        XCTAssertEqual(client.lastQuery, .some(nil))
    }

    func testZeroMatchesIsTheEmptyStateNotABlankPane() async {
        client.searchResult = .success([])
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(viewModel.state, .empty)
    }

    func testAnUnreachableListFailsRatherThanFallingBackToAShippedOne() async {
        let failure = ErrorResponse(code: "NETWORK_ERROR", message: "Offline", fieldErrors: nil)
        client.searchResult = .failure(failure)
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(viewModel.state, .failed(failure))
    }
}
