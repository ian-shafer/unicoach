import XCTest
@testable import UnicoachiOS

/// The family list and its disconnect, both voices (RFC 249).
@MainActor
final class FamilyConnectionsViewModelTests: XCTestCase {
    private let avaLink = UUID(uuidString: "66660000-0000-0000-0000-000000000001")!
    private let samLink = UUID(uuidString: "66660000-0000-0000-0000-000000000002")!

    private var ceiling: SupporterCeiling {
        SupporterCeiling(
            canSee: ["Your college list and key application deadlines."],
            staysPrivate: ["Your coach conversations. Transcripts are never shared."]
        )
    }

    private func studentClient(costReportLinkLive: Bool = false) -> MockSupporterInviteClient {
        let client = MockSupporterInviteClient()
        client.fetchSupportersResult = .success(StudentSupporters(
            supporters: [
                LinkedSupporter(linkId: avaLink, name: "Robin Carter"),
                LinkedSupporter(linkId: samLink, name: "Sam Lee"),
            ],
            ceiling: ceiling,
            costReportLinkLive: costReportLinkLive
        ))
        return client
    }

    private func names(_ viewModel: FamilyConnectionsViewModel) -> [String] {
        guard case .loaded(let roster) = viewModel.state else { return [] }
        return roster.connections.map(\.name)
    }

    // MARK: - The student's list

    func testStudentListLoadsTheServedPeopleCeilingAndCostReportFlag() async {
        let client = studentClient(costReportLinkLive: true)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        XCTAssertEqual(viewModel.state, .loading)

        await viewModel.load()

        guard case .loaded(let roster) = viewModel.state else {
            return XCTFail("expected a loaded list, got \(viewModel.state)")
        }
        XCTAssertEqual(roster.connections.map(\.name), ["Robin Carter", "Sam Lee"])
        XCTAssertEqual(roster.voice, .student(ceiling: ceiling, costReportLinkLive: true))
    }

    func testStudentListLoadFailureIsItsOwnState() async {
        let client = MockSupporterInviteClient()
        client.fetchSupportersResult = .failure(ErrorResponse.unexpected)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))

        await viewModel.load()

        XCTAssertEqual(viewModel.state, .failed)
    }

    func testStudentDisconnectRemovesTheRowAndSendsThatLink() async {
        let client = studentClient()
        client.disconnectSupporterResult = .success(.disconnected)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        await viewModel.disconnect(avaLink)

        XCTAssertEqual(client.disconnectedLinkIds, [avaLink])
        XCTAssertEqual(names(viewModel), ["Sam Lee"])
        XCTAssertNil(viewModel.rowStates[avaLink])
    }

    func testStudentDisconnectFailureKeepsTheRowAndMarksIt() async {
        let client = studentClient()
        client.disconnectSupporterResult = .failure(ErrorResponse.unexpected)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        await viewModel.disconnect(avaLink)

        XCTAssertEqual(names(viewModel), ["Robin Carter", "Sam Lee"])
        XCTAssertEqual(viewModel.rowStates[avaLink], .failed)
        XCTAssertNil(viewModel.rowStates[samLink])
    }

    /// The link was on this list, so `.alreadyGone` means another device
    /// severed it first: the row leaves, it is not reported as a failure.
    func testAlreadyDisconnectedLinkLeavesTheList() async {
        let client = studentClient()
        client.disconnectSupporterResult = .success(.alreadyGone)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        await viewModel.disconnect(avaLink)

        XCTAssertEqual(names(viewModel), ["Sam Lee"])
        XCTAssertNil(viewModel.rowStates[avaLink])
    }

    // MARK: - The supporter's list

    private func roster() -> [LinkedStudent] {
        [
            LinkedStudent(
                linkId: avaLink,
                studentId: UUID(),
                name: "Ava Carter",
                relationship: .parent,
                otherSupporterNames: ["Sam Lee"]
            ),
            LinkedStudent(linkId: samLink, studentId: UUID(), name: "Noah Carter", relationship: .parent, otherSupporterNames: []),
        ]
    }

    func testSupporterListIsTheRosterWithItsOtherSupportersAndNoCeiling() {
        let loaded = FamilyConnectionsViewModel.roster(of: .init(
            roster: roster(),
            client: MockSupporterClient(),
            refreshHome: {}
        ))

        XCTAssertEqual(loaded.connections.map(\.name), ["Ava Carter", "Noah Carter"])
        XCTAssertEqual(loaded.connections.first?.alsoConnected, ["Sam Lee"])
        XCTAssertEqual(loaded.voice, .supporter)
    }

    /// The supporter's row is the home's, so the view model removes nothing:
    /// it refreshes the home, and the row leaves when the re-read roster stops
    /// carrying it.
    func testSupporterDisconnectRefreshesTheHomeAndClearsTheRowState() async {
        let client = MockSupporterClient()
        client.disconnectResult = .success(.disconnected)
        let refreshes = Locked(0)
        let viewModel = FamilyConnectionsViewModel(side: .supporter(.init(
            roster: roster(),
            client: client,
            refreshHome: { refreshes.withLock { $0 += 1 } }
        )))

        await viewModel.disconnect(avaLink)

        XCTAssertEqual(client.disconnectedLinkIds, [avaLink])
        XCTAssertEqual(refreshes.current, 1)
        XCTAssertNil(viewModel.rowStates[avaLink])
    }

    func testSupporterDisconnectFailureKeepsTheRowAndDoesNotRefresh() async {
        let client = MockSupporterClient()
        client.disconnectResult = .failure(ErrorResponse.unexpected)
        let refreshes = Locked(0)
        let viewModel = FamilyConnectionsViewModel(side: .supporter(.init(
            roster: roster(),
            client: client,
            refreshHome: { refreshes.withLock { $0 += 1 } }
        )))

        await viewModel.disconnect(avaLink)

        XCTAssertEqual(viewModel.rowStates[avaLink], .failed)
        XCTAssertEqual(refreshes.current, 0)
    }
}
