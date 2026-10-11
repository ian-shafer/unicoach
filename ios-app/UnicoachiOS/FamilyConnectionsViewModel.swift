import Foundation
import os

/// The people on the other end of the caller's family links, and the two-tap
/// disconnect (RFC 249). ONE view model for both voices: the student's list is
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
        let voice: Voice
    }

    /// What only the student is told. The supporter accepted the ceiling on the
    /// invite page and holds no Family Cost Report link.
    enum Voice: Equatable {
        case student(ceiling: SupporterCeiling, costReportLinkLive: Bool)
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

    /// The student's list. A supporter's list is not held here — see
    /// `roster(of:)` — so on that side this stays `.loading` and is never read.
    @Published private(set) var state: State = .loading
    @Published private(set) var rowStates: [UUID: RowState] = [:]

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
                voice: .student(ceiling: answer.ceiling, costReportLinkLive: answer.costReportLinkLive)
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
