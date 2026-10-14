import Foundation
import os

/// The people on the other end of the caller's family links, and the two-tap
/// disconnect (RFC 249) — plus, on the student's side, the adults asking to
/// connect, with a one-tap approve and a two-tap decline (RFC 254). ONE view model for both voices: the student's list is
/// read from its own door and held here; the supporter's IS the roster the home
/// holds, rendered by `roster(of:)` from whatever the section is handed on each
/// render, so it is never a copy that a refreshed home could leave behind.
@MainActor
final class FamilyConnectionsViewModel: ObservableObject {
    /// A supporter's side of the family list: the roster the home already
    /// holds, the door it disconnects through, and the home's refresh — the
    /// home's `.task` does not re-run when Settings pops back, so a severed
    /// student would otherwise stay on the home until the next foreground.
    struct SupporterFamily {
        let roster: [LinkedStudent]
        let client: SupporterClientProtocol
        let refreshHome: () async -> Void
    }

    /// Which side of the link this list is for, carrying the one door each side
    /// disconnects through.
    enum Side {
        case student(SupporterInviteClientProtocol)
        case supporter(SupporterFamily)
    }

    /// One person on the list. `alsoConnected` is the supporter voice's "Also
    /// supporting" names; the student's rows have none.
    struct Connection: Identifiable, Equatable {
        let linkId: UUID
        let name: String
        let alsoConnected: [String]

        var id: UUID { linkId }
    }

    /// What the list shows once it has something to show.
    struct Roster: Equatable {
        var connections: [Connection]
        var voice: Voice

        /// The requests to answer: a student's only.
        var requests: [SupporterRequest] {
            if case .student(_, _, let requests) = voice { return requests }
            return []
        }

        mutating func removeRequest(_ requestId: UUID) {
            guard case .student(let ceiling, let costReportLinkLive, var requests) = voice else { return }
            requests.removeAll { $0.requestId == requestId }
            voice = .student(ceiling: ceiling, costReportLinkLive: costReportLinkLive, requests: requests)
        }
    }

    /// What only the student is told, and what only the student answers: the
    /// adults asking to connect (RFC 254). The supporter accepted the ceiling on
    /// the invite page and holds no Family Cost Report link.
    enum Voice: Equatable {
        case student(ceiling: SupporterCeiling, costReportLinkLive: Bool, requests: [SupporterRequest])
        case supporter
    }

    enum State: Equatable {
        case loading
        case failed
        case loaded(Roster)
    }

    /// A row's disconnect, separately from the list: a failure keeps the row
    /// and says so beside it, and a row in flight cannot be sent twice.
    enum RowState: Equatable {
        case disconnecting
        case failed
    }

    /// A request's approve or decline, the `RowState` way: a failure keeps the
    /// request and says which answer did not go through, and a request in
    /// flight cannot be answered twice.
    enum RequestState: Equatable {
        case inFlight(Answer)
        case failed(Answer)

        /// While one answer is in flight, neither can be sent.
        var isInFlight: Bool {
            if case .inFlight = self { return true }
            return false
        }
    }

    enum Answer: Equatable {
        case approve
        case decline
    }

    /// The student's list. A supporter's list is not held here — see
    /// `roster(of:)` — so on that side this stays `.loading` and is never read.
    @Published private(set) var state: State = .loading
    @Published private(set) var rowStates: [UUID: RowState] = [:]
    @Published private(set) var requestStates: [UUID: RequestState] = [:]

    let side: Side
    private let logger = Logger.unicoach(category: "FamilyConnectionsViewModel")

    init(side: Side) {
        self.side = side
    }

    /// A supporter's list IS the home's roster; this only renders it.
    static func roster(of family: SupporterFamily) -> Roster {
        Roster(
            connections: family.roster.map {
                Connection(linkId: $0.linkId, name: $0.name, alsoConnected: $0.otherSupporterNames)
            },
            voice: .supporter
        )
    }

    /// Reads the student's list. A supporter's list arrived with the roster, so
    /// there is nothing to read.
    func load() async {
        let client: SupporterInviteClientProtocol
        switch side {
        case .student(let studentClient):
            client = studentClient
        case .supporter:
            return
        }
        state = .loading
        do {
            let answer = try await client.fetchSupporters()
            state = .loaded(Roster(
                connections: answer.supporters.map { Connection(linkId: $0.linkId, name: $0.name, alsoConnected: []) },
                voice: .student(
                    ceiling: answer.ceiling,
                    costReportLinkLive: answer.costReportLinkLive,
                    requests: answer.requests
                )
            ))
        } catch {
            logger.error("Loading the student's supporters failed: [\(String(describing: error), privacy: .public)]")
            state = .failed
        }
    }

    /// The second tap. On success the row leaves; on failure it stays and its
    /// state says so.
    func disconnect(_ linkId: UUID) async {
        guard rowStates[linkId] != .disconnecting else { return }
        rowStates[linkId] = .disconnecting
        do {
            let outcome = try await send(linkId)
            await didDisconnect(linkId, outcome)
        } catch {
            logger.error("Disconnect failed: side=[\(self.sideName, privacy: .public)] linkId=[\(linkId, privacy: .public)] error=[\(String(describing: error), privacy: .public)]")
            rowStates[linkId] = .failed
        }
    }

    /// `.alreadyGone` is NOT a failure from here: the link was on this list, so
    /// it means another device already severed it, and the outcome the caller
    /// asked for is the one that holds.
    ///
    /// The student's row leaves this list; a supporter's leaves when the re-read
    /// home stops carrying it, and shows "Disconnecting…" until then.
    private func didDisconnect(_ linkId: UUID, _ outcome: DisconnectOutcome) async {
        switch outcome {
        case .disconnected:
            break
        case .alreadyGone:
            logger.info("Link [\(linkId, privacy: .public)] was already disconnected")
        }
        switch side {
        case .student:
            removeConnection(linkId)
        case .supporter(let family):
            await family.refreshHome()
        }
        rowStates[linkId] = nil
    }

    /// The one tap. On success the person moves from the requests to the
    /// connected adults, from the approve's own answer, without a re-read.
    func approve(_ requestId: UUID) async {
        guard case .student(let client) = side, requestStates[requestId]?.isInFlight != true else { return }
        requestStates[requestId] = .inFlight(.approve)
        do {
            switch try await client.approveSupporterRequest(requestId: requestId) {
            case .connected(let supporter):
                requestStates[requestId] = nil
                connect(requestId, supporter)
            case .noLongerPending:
                await requestWasAnsweredElsewhere(requestId)
            }
        } catch {
            logger.error("Approve failed: requestId=[\(requestId, privacy: .public)] error=[\(String(describing: error), privacy: .public)]")
            requestStates[requestId] = .failed(.approve)
        }
    }

    /// The second tap of a decline. On success the request leaves the list.
    func decline(_ requestId: UUID) async {
        guard case .student(let client) = side, requestStates[requestId]?.isInFlight != true else { return }
        requestStates[requestId] = .inFlight(.decline)
        do {
            switch try await client.declineSupporterRequest(requestId: requestId) {
            case .declined:
                requestStates[requestId] = nil
                removeRequest(requestId)
            case .noLongerPending:
                await requestWasAnsweredElsewhere(requestId)
            }
        } catch {
            logger.error("Decline failed: requestId=[\(requestId, privacy: .public)] error=[\(String(describing: error), privacy: .public)]")
            requestStates[requestId] = .failed(.decline)
        }
    }

    /// `noLongerPending` says only that THIS list is out of date: the request
    /// may have been approved on another device, so the person may now be
    /// connected. The list is read again rather than guessed at.
    private func requestWasAnsweredElsewhere(_ requestId: UUID) async {
        logger.info("Request [\(requestId, privacy: .public)] was no longer pending; re-reading the list")
        requestStates[requestId] = nil
        await load()
    }

    private func connect(_ requestId: UUID, _ supporter: LinkedSupporter) {
        guard case .loaded(var roster) = state else { return }
        roster.removeRequest(requestId)
        // The approve is idempotent on a live link, so the person may already
        // be on the list.
        if !roster.connections.contains(where: { $0.linkId == supporter.linkId }) {
            roster.connections.append(Connection(linkId: supporter.linkId, name: supporter.name, alsoConnected: []))
        }
        state = .loaded(roster)
    }

    private func removeRequest(_ requestId: UUID) {
        guard case .loaded(var roster) = state else { return }
        roster.removeRequest(requestId)
        state = .loaded(roster)
    }

    private func removeConnection(_ linkId: UUID) {
        guard case .loaded(var roster) = state else { return }
        roster.connections.removeAll { $0.linkId == linkId }
        state = .loaded(roster)
    }

    /// The side as a log field: which door a disconnect went through.
    private var sideName: String {
        switch side {
        case .student: return "student"
        case .supporter: return "supporter"
        }
    }

    private func send(_ linkId: UUID) async throws -> DisconnectOutcome {
        switch side {
        case .student(let client):
            return try await client.disconnectSupporter(linkId: linkId)
        case .supporter(let family):
            return try await family.client.disconnect(linkId: linkId)
        }
    }
}
