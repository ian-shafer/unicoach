import Foundation
import os

/// The college-list screen's load state — the `ConversationListState` shape.
/// An empty list is a distinct successful outcome so the view renders the
/// dedicated add-affordance state rather than a zero-row list.
enum CollegeListState: Equatable {
    case loading
    case loaded([CollegeListEntry])
    case empty
    case failed(ErrorResponse)
}

extension CollegeListState {
    /// The one place the empty/loaded split is decided: an empty fetch is
    /// `.empty`, never a zero-row `.loaded`.
    init(entries: [CollegeListEntry]) {
        self = entries.isEmpty ? .empty : .loaded(entries)
    }
}

@MainActor
final class CollegeListViewModel: ObservableObject {
    /// How an entry mutation resolved: `.saved` pops the detail screen having
    /// applied the change; `.staleEntry` pops it too — the entry it was pushed
    /// with no longer exists at that version, and the reloaded list underneath
    /// is the fresh truth; `.failed` keeps the screen up for a retry.
    enum MutationOutcome: Equatable {
        case saved
        case staleEntry
        case failed
    }

    @Published private(set) var state: CollegeListState = .loading

    /// Per-action failure channel, separate from `state` exactly as in
    /// `ConversationListViewModel`: `.failed` replaces the whole screen and is
    /// reserved for initial load; a remove/update failure surfaces here while
    /// the list stays up.
    @Published var actionError: ErrorResponse?

    private let client: CollegeListClientProtocol
    private let onProfileRequired: () -> Void
    private let logger = Logger.unicoach(category: "CollegeListViewModel")

    init(client: CollegeListClientProtocol, onProfileRequired: @escaping () -> Void) {
        self.client = client
        self.onProfileRequired = onProfileRequired
    }

    func load() async {
        state = .loading
        do {
            let entries = try await client.listEntries()
            state = CollegeListState(entries: entries)
        } catch let error as ErrorResponse {
            if error.knownCode == .studentProfileRequired {
                onProfileRequired()
                return
            }
            state = .failed(error)
        } catch {
            state = .failed(.unexpected)
        }
    }

    /// A reload without the `.loading` blink (the RFC 117 rationale): the rows
    /// on screen survive while the fetch runs, and a failure keeps them — but
    /// the failure is retained, not erased: the profile refusal escalates
    /// exactly as `load()` does, and anything else is logged.
    func refresh() async {
        guard case .loaded = state else {
            await load()
            return
        }
        do {
            let entries = try await client.listEntries()
            state = CollegeListState(entries: entries)
        } catch let error as ErrorResponse where error.knownCode == .studentProfileRequired {
            onProfileRequired()
        } catch {
            // Keep the rows already showing.
            logger.error("College-list refresh failed, keeping stale rows: [\(error, privacy: .public)]")
        }
    }

    /// Removes an entry (the view's confirmation dialog gates this call). A
    /// version conflict — or a 404 from the entry already being removed —
    /// means the chat tool or another device moved the list, so the honest
    /// recovery is a fresh read plus the server's own message.
    func remove(_ entry: CollegeListEntry) async {
        do {
            try await client.removeEntry(id: entry.id, version: entry.version)
            await removeRow(entry)
        } catch let error as ErrorResponse {
            await handleMutationFailure(error)
        } catch {
            actionError = .unexpected
        }
    }

    /// Replaces an entry's status and reasons. `.staleEntry` names the lost
    /// race (conflict or concurrent removal): the list has been reloaded and
    /// the server's message surfaces in the standard alert, so the detail
    /// screen pops rather than retrying a version that can only re-fail.
    func update(entry: CollegeListEntry, status: CollegeListStatus, reasons: String?) async -> MutationOutcome {
        do {
            let updated = try await client.updateEntry(
                id: entry.id, version: entry.version, status: status, reasons: reasons
            )
            await replaceRow(updated)
            return .saved
        } catch let error as ErrorResponse {
            await handleMutationFailure(error)
            switch error.knownCode {
            case .versionConflict, .conflict, .notFound: return .staleEntry
            default: return .failed
            }
        } catch {
            actionError = .unexpected
            return .failed
        }
    }

    // MARK: - Failure handling

    /// The `ConversationViewModel.handle` discipline, for list mutations:
    /// `student_profile_required` escalates to the root state machine;
    /// `version_conflict` / `conflict` / `not_found` reload the list — all
    /// three are faces of "someone else moved it", the 404 being the entry
    /// another device already removed — and surface the server's message;
    /// anything else alerts with the list unchanged.
    private func handleMutationFailure(_ error: ErrorResponse) async {
        switch error.knownCode {
        case .studentProfileRequired:
            onProfileRequired()
        case .versionConflict, .conflict, .notFound:
            await refresh()
            actionError = error
        default:
            actionError = error
        }
    }

    // MARK: - Row bookkeeping

    private func removeRow(_ entry: CollegeListEntry) async {
        guard case .loaded(var entries) = state else {
            await refresh()
            return
        }
        entries.removeAll { $0.id == entry.id }
        state = CollegeListState(entries: entries)
    }

    /// Mirrors `removeRow`: a confirmed server-side save that cannot be
    /// patched in place — the state moved on, or the row is gone after a
    /// conflict-triggered reload — re-reads the list so the server's truth
    /// lands on screen instead of being silently dropped.
    private func replaceRow(_ updated: CollegeListEntry) async {
        guard case .loaded(var entries) = state,
              let idx = entries.firstIndex(where: { $0.id == updated.id }) else {
            await refresh()
            return
        }
        entries[idx] = updated
        state = .loaded(entries)
    }
}
