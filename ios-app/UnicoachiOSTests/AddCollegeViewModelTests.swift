import XCTest
@testable import UnicoachiOS

@MainActor
final class AddCollegeViewModelTests: XCTestCase {
    private var client: MockCollegeListClient!
    private var profileRequiredCount = 0

    override func setUp() {
        super.setUp()
        client = MockCollegeListClient()
        profileRequiredCount = 0
    }

    /// A short but REAL debounce: coalescing is the behaviour under test, and
    /// a zero interval would let every keystroke's task fire before the next
    /// keystroke replaces it.
    private func makeViewModel(debounce: Duration = .milliseconds(50)) -> AddCollegeViewModel {
        AddCollegeViewModel(
            client: client,
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 },
            debounce: debounce
        )
    }

    // MARK: - search

    func testBlankQueryRendersPromptWithoutARequest() async {
        let viewModel = makeViewModel()
        viewModel.query = "   "
        await viewModel.awaitPendingSearch()
        XCTAssertEqual(viewModel.state, .prompt)
        XCTAssertEqual(client.searchQueries, [], "a blank query must not become a request")
    }

    func testDebounceCoalescesABurstIntoOneTrimmedRequest() async {
        client.searchCollegesResult = .success([.fixture()])
        let viewModel = makeViewModel()

        viewModel.query = "c"
        viewModel.query = "co"
        viewModel.query = " col "
        await viewModel.awaitPendingSearch()

        XCTAssertEqual(client.searchQueries, ["col"], "only the final keystroke's query may reach the wire")
        XCTAssertEqual(viewModel.state, .results([.fixture()]))
    }

    func testZeroMatchesIsEmptyStateNotBlank() async {
        client.searchCollegesResult = .success([])
        let viewModel = makeViewModel()
        viewModel.query = "zzz"
        await viewModel.awaitPendingSearch()
        XCTAssertEqual(viewModel.state, .empty)
    }

    func testSearchFailureIsFailedState() async {
        let error = ErrorResponse(code: "NETWORK_ERROR", message: "offline", fieldErrors: nil)
        client.searchCollegesResult = .failure(error)
        let viewModel = makeViewModel()
        viewModel.query = "col"
        await viewModel.awaitPendingSearch()
        XCTAssertEqual(viewModel.state, .failed(error))
    }

    func testSearchProfileRequiredEscalates() async {
        client.searchCollegesResult = .failure(
            ErrorResponse(code: "student_profile_required", message: "A student profile is required", fieldErrors: nil)
        )
        let viewModel = makeViewModel()
        viewModel.query = "col"
        await viewModel.awaitPendingSearch()
        XCTAssertEqual(profileRequiredCount, 1)
    }

    func testClearingTheQueryReturnsToPrompt() async {
        client.searchCollegesResult = .success([.fixture()])
        let viewModel = makeViewModel()
        viewModel.query = "col"
        await viewModel.awaitPendingSearch()
        viewModel.query = ""
        XCTAssertEqual(viewModel.state, .prompt)
    }

    // MARK: - add

    func testAddSuccessReturnsAddedAndSendsTheCollegeId() async {
        let college = CollegeSummary.fixture()
        client.addEntryResult = .success(.fixture(collegeId: college.id, collegeName: college.name))
        let viewModel = makeViewModel()

        let outcome = await viewModel.add(college)

        XCTAssertEqual(outcome, .added)
        XCTAssertEqual(client.addedCollegeIds, [college.id])
        XCTAssertNil(viewModel.addError)
    }

    func testAddDuplicateSurfacesServerErrorInline() async {
        let conflict = ErrorResponse(code: "conflict", message: "College is already on the list", fieldErrors: nil)
        client.addEntryResult = .failure(conflict)
        let viewModel = makeViewModel()

        let outcome = await viewModel.add(.fixture())

        // The outcome and the inline channel carry the STRUCTURED error — the
        // code distinguishes the duplicate 409 from any other refusal; the
        // view formats `.message`.
        XCTAssertEqual(outcome, .rejected(conflict))
        XCTAssertEqual(viewModel.addError, conflict)
        XCTAssertEqual(viewModel.addError?.knownCode, .conflict)
    }

    func testAddUnexpectedFailureRejectsWithTheUnexpectedError() async {
        client.addEntryResult = .failure(URLError(.notConnectedToInternet))
        let viewModel = makeViewModel()

        let outcome = await viewModel.add(.fixture())

        XCTAssertEqual(outcome, .rejected(.unexpected))
        XCTAssertEqual(viewModel.addError, .unexpected)
    }

    func testAddProfileRequiredEscalatesWithoutInlineError() async {
        client.addEntryResult = .failure(
            ErrorResponse(code: "student_profile_required", message: "A student profile is required", fieldErrors: nil)
        )
        let viewModel = makeViewModel()

        let outcome = await viewModel.add(.fixture())

        XCTAssertEqual(outcome, .profileRequired)
        XCTAssertEqual(profileRequiredCount, 1)
        XCTAssertNil(viewModel.addError)
    }
}
