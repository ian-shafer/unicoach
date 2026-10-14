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

    private let jordanRequest = SupporterRequest(
        requestId: UUID(uuidString: "77770000-0000-0000-0000-000000000001")!,
        name: "Jordan Lee",
        email: "jordan@example.com"
    )
    private let jordanLink = UUID(uuidString: "66660000-0000-0000-0000-000000000003")!

    private func studentClient(
        costReportLinkLive: Bool = false,
        requests: [SupporterRequest] = []
    ) -> MockSupporterInviteClient {
        let client = MockSupporterInviteClient()
        client.fetchSupportersResult = .success(StudentSupporters(
            supporters: [
                LinkedSupporter(linkId: avaLink, name: "Robin Carter"),
                LinkedSupporter(linkId: samLink, name: "Sam Lee"),
            ],
            ceiling: ceiling,
            costReportLinkLive: costReportLinkLive,
            requests: requests
        ))
        return client
    }

    private func requests(_ viewModel: FamilyConnectionsViewModel) -> [SupporterRequest] {
        guard case .loaded(let roster) = viewModel.state else { return [] }
        return roster.requests
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
        XCTAssertEqual(roster.voice, .student(ceiling: ceiling, costReportLinkLive: true, requests: []))
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

    // MARK: - Requests to connect (RFC 254)

    func testStudentListLoadsTheServedRequests() async {
        let viewModel = FamilyConnectionsViewModel(side: .student(studentClient(requests: [jordanRequest])))

        await viewModel.load()

        XCTAssertEqual(requests(viewModel), [jordanRequest])
    }

    /// The approve's own answer moves the person across; nothing is re-read.
    func testApproveMovesThePersonFromTheRequestsToTheConnectedAdults() async {
        let client = studentClient(requests: [jordanRequest])
        client.approveResult = .success(.connected(LinkedSupporter(linkId: jordanLink, name: "Jordan Lee")))
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        await viewModel.approve(jordanRequest.requestId)

        XCTAssertEqual(client.approvedRequestIds, [jordanRequest.requestId])
        XCTAssertEqual(requests(viewModel), [])
        XCTAssertEqual(names(viewModel), ["Robin Carter", "Sam Lee", "Jordan Lee"])
        XCTAssertNil(viewModel.requestStates[jordanRequest.requestId])
        XCTAssertEqual(client.fetchSupportersCallCount, 1)
    }

    /// The approve is idempotent on a live link: an adult already on the list
    /// is not listed twice.
    func testApproveOfSomeoneAlreadyConnectedDoesNotListThemTwice() async {
        let client = studentClient(requests: [jordanRequest])
        client.approveResult = .success(.connected(LinkedSupporter(linkId: samLink, name: "Sam Lee")))
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        await viewModel.approve(jordanRequest.requestId)

        XCTAssertEqual(names(viewModel), ["Robin Carter", "Sam Lee"])
        XCTAssertEqual(requests(viewModel), [])
    }

    /// The server's 404: the list is out of date — the request may have been
    /// approved elsewhere — so it is read again rather than guessed at.
    func testApproveOfARequestNoLongerPendingReReadsTheList() async {
        let client = studentClient(requests: [jordanRequest])
        client.approveResult = .success(.noLongerPending)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()
        client.fetchSupportersResult = .success(StudentSupporters(
            supporters: [LinkedSupporter(linkId: jordanLink, name: "Jordan Lee")],
            ceiling: ceiling,
            costReportLinkLive: false,
            requests: []
        ))

        await viewModel.approve(jordanRequest.requestId)

        XCTAssertEqual(client.fetchSupportersCallCount, 2)
        XCTAssertEqual(names(viewModel), ["Jordan Lee"])
        XCTAssertEqual(requests(viewModel), [])
        XCTAssertNil(viewModel.requestStates[jordanRequest.requestId])
    }

    func testApproveFailureKeepsTheRequestAndSaysTheApproveFailed() async {
        let client = studentClient(requests: [jordanRequest])
        client.approveResult = .failure(ErrorResponse.unexpected)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        await viewModel.approve(jordanRequest.requestId)

        XCTAssertEqual(requests(viewModel), [jordanRequest])
        XCTAssertEqual(names(viewModel), ["Robin Carter", "Sam Lee"])
        XCTAssertEqual(viewModel.requestStates[jordanRequest.requestId], .failed(.approve))
    }

    func testDeclineRemovesTheRequestAndConnectsNobody() async {
        let client = studentClient(requests: [jordanRequest])
        client.declineResult = .success(.declined)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        await viewModel.decline(jordanRequest.requestId)

        XCTAssertEqual(client.declinedRequestIds, [jordanRequest.requestId])
        XCTAssertEqual(requests(viewModel), [])
        XCTAssertEqual(names(viewModel), ["Robin Carter", "Sam Lee"])
        XCTAssertNil(viewModel.requestStates[jordanRequest.requestId])
    }

    func testDeclineOfARequestNoLongerPendingReReadsTheList() async {
        let client = studentClient(requests: [jordanRequest])
        client.declineResult = .success(.noLongerPending)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()
        client.fetchSupportersResult = .success(StudentSupporters(
            supporters: [],
            ceiling: ceiling,
            costReportLinkLive: false,
            requests: []
        ))

        await viewModel.decline(jordanRequest.requestId)

        XCTAssertEqual(client.fetchSupportersCallCount, 2)
        XCTAssertEqual(requests(viewModel), [])
        XCTAssertEqual(names(viewModel), [])
        XCTAssertNil(viewModel.requestStates[jordanRequest.requestId])
    }

    func testDeclineFailureKeepsTheRequestAndSaysTheDeclineFailed() async {
        let client = studentClient(requests: [jordanRequest])
        client.declineResult = .failure(ErrorResponse.unexpected)
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        await viewModel.decline(jordanRequest.requestId)

        XCTAssertEqual(requests(viewModel), [jordanRequest])
        XCTAssertEqual(viewModel.requestStates[jordanRequest.requestId], .failed(.decline))
    }

    /// A request in flight cannot be answered a second time, either way.
    func testARequestInFlightCannotBeAnsweredAgain() async {
        let client = studentClient(requests: [jordanRequest])
        client.approveResult = .success(.connected(LinkedSupporter(linkId: jordanLink, name: "Jordan Lee")))
        client.declineResult = .success(.declined)
        let gate = InFlightGate()
        client.approveGate = { await gate.hold() }
        let viewModel = FamilyConnectionsViewModel(side: .student(client))
        await viewModel.load()

        let first = Task { await viewModel.approve(jordanRequest.requestId) }
        await gate.waitUntilHeld()
        XCTAssertEqual(viewModel.requestStates[jordanRequest.requestId], .inFlight(.approve))
        await viewModel.approve(jordanRequest.requestId)
        await viewModel.decline(jordanRequest.requestId)
        await gate.release()
        await first.value

        XCTAssertEqual(client.approvedRequestIds, [jordanRequest.requestId])
        XCTAssertEqual(client.declinedRequestIds, [])
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
