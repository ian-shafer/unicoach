import XCTest
@testable import UnicoachiOS

@MainActor
final class CollegeListViewModelTests: XCTestCase {
    private var client: MockCollegeListClient!
    private var profileRequiredCount = 0

    override func setUp() {
        super.setUp()
        client = MockCollegeListClient()
        profileRequiredCount = 0
    }

    private func makeViewModel() -> CollegeListViewModel {
        CollegeListViewModel(client: client) { [weak self] in
            self?.profileRequiredCount += 1
        }
    }

    // MARK: - load

    func testLoadPopulatesLoadedState() async {
        let entries = [CollegeListEntry.fixture(), CollegeListEntry.fixture(
            id: UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000002")!,
            collegeName: "Second College",
            status: .applying
        )]
        client.listEntriesResult = .success(entries)

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(viewModel.state, .loaded(entries))
    }

    func testLoadEmptyListIsDistinctEmptyState() async {
        client.listEntriesResult = .success([])
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(viewModel.state, .empty)
    }

    func testLoadFailureIsFailedState() async {
        let error = ErrorResponse(code: "SERVER_ERROR", message: "boom", fieldErrors: nil)
        client.listEntriesResult = .failure(error)
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(viewModel.state, .failed(error))
    }

    func testLoadProfileRequiredEscalatesInsteadOfFailing() async {
        client.listEntriesResult = .failure(
            ErrorResponse(code: "student_profile_required", message: "A student profile is required", fieldErrors: nil)
        )
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(profileRequiredCount, 1)
    }

    // MARK: - refresh

    func testRefreshKeepsRowsOnFailure() async {
        let entries = [CollegeListEntry.fixture()]
        client.listEntriesResult = .success(entries)
        let viewModel = makeViewModel()
        await viewModel.load()

        client.listEntriesResult = .failure(ErrorResponse(code: "NETWORK_ERROR", message: "offline", fieldErrors: nil))
        await viewModel.refresh()

        XCTAssertEqual(viewModel.state, .loaded(entries), "a failed refresh must keep the rows on screen")
    }

    func testRefreshFallsBackToLoadWhenNothingLoaded() async {
        client.listEntriesResult = .success([CollegeListEntry.fixture()])
        let viewModel = makeViewModel()
        await viewModel.refresh()
        if case .loaded = viewModel.state {} else {
            XCTFail("refresh from .loading must perform the initial load")
        }
    }

    func testRefreshProfileRequiredEscalatesInsteadOfKeepingRows() async {
        let entries = [CollegeListEntry.fixture()]
        client.listEntriesResult = .success(entries)
        let viewModel = makeViewModel()
        await viewModel.load()

        client.listEntriesResult = .failure(
            ErrorResponse(code: "student_profile_required", message: "A student profile is required", fieldErrors: nil)
        )
        await viewModel.refresh()

        XCTAssertEqual(profileRequiredCount, 1, "a revoked profile gate must escalate from refresh exactly as from load")
    }

    // MARK: - remove

    func testRemoveDropsTheRowAndSendsTheEntryVersion() async {
        let doomed = CollegeListEntry.fixture(version: 4)
        let survivor = CollegeListEntry.fixture(
            id: UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000002")!,
            collegeName: "Survivor College"
        )
        client.listEntriesResult = .success([doomed, survivor])
        let viewModel = makeViewModel()
        await viewModel.load()

        await viewModel.remove(doomed)

        XCTAssertEqual(client.removeCalls.count, 1)
        XCTAssertEqual(client.removeCalls[0].id, doomed.id)
        XCTAssertEqual(client.removeCalls[0].version, 4)
        XCTAssertEqual(viewModel.state, .loaded([survivor]))
        XCTAssertNil(viewModel.actionError)
    }

    func testRemoveLastRowTransitionsToEmpty() async {
        let doomed = CollegeListEntry.fixture()
        client.listEntriesResult = .success([doomed])
        let viewModel = makeViewModel()
        await viewModel.load()

        await viewModel.remove(doomed)

        XCTAssertEqual(viewModel.state, .empty)
    }

    func testRemoveVersionConflictReloadsAndSurfacesServerMessage() async {
        let stale = CollegeListEntry.fixture(status: .considering, version: 1)
        client.listEntriesResult = .success([stale])
        let viewModel = makeViewModel()
        await viewModel.load()

        let conflict = ErrorResponse(
            code: "version_conflict", message: "College list entry was modified concurrently", fieldErrors: nil
        )
        client.removeEntryResult = .failure(conflict)
        let fresh = CollegeListEntry.fixture(status: .admitted, version: 2)
        client.listEntriesResult = .success([fresh])

        await viewModel.remove(stale)

        XCTAssertEqual(viewModel.state, .loaded([fresh]), "a conflict must trigger a fresh read")
        XCTAssertEqual(viewModel.actionError, conflict, "the server's own message surfaces in the alert")
    }

    func testRemoveProfileRequiredEscalates() async {
        let entry = CollegeListEntry.fixture()
        client.listEntriesResult = .success([entry])
        let viewModel = makeViewModel()
        await viewModel.load()

        client.removeEntryResult = .failure(
            ErrorResponse(code: "student_profile_required", message: "A student profile is required", fieldErrors: nil)
        )
        await viewModel.remove(entry)

        XCTAssertEqual(profileRequiredCount, 1)
        XCTAssertNil(viewModel.actionError)
    }

    func testRemoveOtherFailureAlertsWithListUnchanged() async {
        let entry = CollegeListEntry.fixture()
        client.listEntriesResult = .success([entry])
        let viewModel = makeViewModel()
        await viewModel.load()

        let error = ErrorResponse(code: "SERVER_ERROR", message: "boom", fieldErrors: nil)
        client.removeEntryResult = .failure(error)
        await viewModel.remove(entry)

        XCTAssertEqual(viewModel.state, .loaded([entry]), "the row must stay on a non-conflict failure")
        XCTAssertEqual(viewModel.actionError, error)
    }

    // MARK: - update

    func testUpdateReplacesTheRowAndReturnsSaved() async {
        let entry = CollegeListEntry.fixture(status: .considering, version: 1)
        client.listEntriesResult = .success([entry])
        let viewModel = makeViewModel()
        await viewModel.load()

        let updated = CollegeListEntry.fixture(status: .admitted, reasons: "In!", version: 2)
        client.updateEntryResult = .success(updated)

        let outcome = await viewModel.update(entry: entry, status: .admitted, reasons: "In!")

        XCTAssertEqual(outcome, .saved)
        XCTAssertEqual(client.updateCalls.count, 1)
        XCTAssertEqual(client.updateCalls[0].version, 1)
        XCTAssertEqual(client.updateCalls[0].status, .admitted)
        XCTAssertEqual(client.updateCalls[0].reasons, "In!")
        XCTAssertEqual(viewModel.state, .loaded([updated]))
    }

    func testUpdateSavedForRowNotInListTriggersRefresh() async {
        let onScreen = CollegeListEntry.fixture()
        client.listEntriesResult = .success([onScreen])
        let viewModel = makeViewModel()
        await viewModel.load()

        // The server confirms a save for a row the loaded list no longer
        // holds (the state moved underneath the detail screen): the update
        // must not be silently dropped — the list re-reads the server's truth.
        let elsewhere = CollegeListEntry.fixture(
            id: UUID(uuidString: "AAAAAAAA-0000-0000-0000-00000000000F")!,
            collegeName: "Elsewhere College",
            status: .applying,
            version: 2
        )
        client.updateEntryResult = .success(elsewhere)
        let fresh = [onScreen, elsewhere]
        client.listEntriesResult = .success(fresh)

        let outcome = await viewModel.update(entry: elsewhere, status: .applying, reasons: nil)

        XCTAssertEqual(outcome, .saved)
        XCTAssertEqual(client.listEntriesCallCount, 2, "an unplaceable saved row must trigger a fresh read")
        XCTAssertEqual(viewModel.state, .loaded(fresh))
    }

    func testUpdateVersionConflictReloadsSurfacesMessageAndReturnsStaleEntry() async {
        let stale = CollegeListEntry.fixture(version: 1)
        client.listEntriesResult = .success([stale])
        let viewModel = makeViewModel()
        await viewModel.load()

        let conflict = ErrorResponse(
            code: "version_conflict", message: "College list entry was modified concurrently", fieldErrors: nil
        )
        client.updateEntryResult = .failure(conflict)
        let fresh = CollegeListEntry.fixture(status: .rejected, version: 3)
        client.listEntriesResult = .success([fresh])

        let outcome = await viewModel.update(entry: stale, status: .applying, reasons: nil)

        XCTAssertEqual(outcome, .staleEntry, "a lost race pops the detail screen — retrying the stale version can only re-fail")
        XCTAssertEqual(viewModel.state, .loaded([fresh]))
        XCTAssertEqual(viewModel.actionError, conflict)
    }

    func testUpdateProfileRequiredEscalates() async {
        let entry = CollegeListEntry.fixture()
        client.listEntriesResult = .success([entry])
        let viewModel = makeViewModel()
        await viewModel.load()

        client.updateEntryResult = .failure(
            ErrorResponse(code: "student_profile_required", message: "A student profile is required", fieldErrors: nil)
        )
        let outcome = await viewModel.update(entry: entry, status: .applying, reasons: nil)

        XCTAssertEqual(outcome, .failed)
        XCTAssertEqual(profileRequiredCount, 1)
    }

    func testUpdateNotFoundReloadsAndReturnsStaleEntry() async {
        let ghost = CollegeListEntry.fixture(version: 1)
        client.listEntriesResult = .success([ghost])
        let viewModel = makeViewModel()
        await viewModel.load()

        // The other face of the same race: another device already removed the
        // entry, so the mutation 404s. Recovery is identical to a conflict —
        // fresh read, server message, pop.
        let notFound = ErrorResponse(code: "not_found", message: "No such college list entry", fieldErrors: nil)
        client.updateEntryResult = .failure(notFound)
        client.listEntriesResult = .success([])

        let outcome = await viewModel.update(entry: ghost, status: .applying, reasons: nil)

        XCTAssertEqual(outcome, .staleEntry)
        XCTAssertEqual(viewModel.state, .empty, "the ghost row must not survive the reload")
        XCTAssertEqual(viewModel.actionError, notFound)
    }

    func testUpdateOtherFailureKeepsScreenAndReturnsFailed() async {
        let entry = CollegeListEntry.fixture()
        client.listEntriesResult = .success([entry])
        let viewModel = makeViewModel()
        await viewModel.load()

        let error = ErrorResponse(code: "SERVER_ERROR", message: "boom", fieldErrors: nil)
        client.updateEntryResult = .failure(error)

        let outcome = await viewModel.update(entry: entry, status: .applying, reasons: nil)

        XCTAssertEqual(outcome, .failed, "a refusal the list did not move for keeps the detail screen up")
        XCTAssertEqual(viewModel.state, .loaded([entry]))
        XCTAssertEqual(viewModel.actionError, error)
    }

    func testRemoveNotFoundReloadsAndSurfacesServerMessage() async {
        let ghost = CollegeListEntry.fixture(version: 1)
        client.listEntriesResult = .success([ghost])
        let viewModel = makeViewModel()
        await viewModel.load()

        let notFound = ErrorResponse(code: "not_found", message: "No such college list entry", fieldErrors: nil)
        client.removeEntryResult = .failure(notFound)
        client.listEntriesResult = .success([])

        await viewModel.remove(ghost)

        XCTAssertEqual(viewModel.state, .empty, "the 404 from a concurrent removal must trigger a fresh read")
        XCTAssertEqual(viewModel.actionError, notFound)
    }
}
