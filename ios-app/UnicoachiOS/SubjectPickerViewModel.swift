import Foundation
import os

/// The searchable subject list behind `DSSubjectsRow` (RFC 205 §5/§7): a
/// debounced query over `GET /api/v1/subjects`, in the `AddCollegeViewModel`
/// idiom — debounce by replacement, one request for the final text.
///
/// **There is no shipped fallback list and no `.prompt` state.** A blank query
/// is not "nothing to ask": the route serves the whole taxonomy in name order
/// when `q` is absent, so an empty field shows a browsable list rather than an
/// instruction to type. A failed fetch is `.failed`, and the row that presents
/// this list says the list is unavailable — the app carries no copy of the
/// taxonomy to degrade to, by design.
@MainActor
final class SubjectPickerViewModel: ObservableObject {
    enum SearchPhase: Equatable {
        case searching
        case results([PublicSubject])
        /// Zero matches — never a blank pane; `SubjectPickerSheet` owns the
        /// sentence. A term that is not a subject is refused with a reason
        /// rather than mapped to something near it: no synonym is stored and
        /// none is invented (RFC 205 §5).
        case empty
        case failed(ErrorResponse)

        init(results: [PublicSubject]) {
            self = results.isEmpty ? .empty : .results(results)
        }
    }

    @Published var query: String = "" {
        didSet { handleQueryChange() }
    }
    @Published private(set) var state: SearchPhase = .searching

    private let client: SubjectsClientProtocol
    private let logger = Logger.unicoach(category: "SubjectPickerViewModel")
    private let debounce: Duration
    private var searchTask: Task<Void, Never>?

    /// `debounce` is injectable so tests drive coalescing without wall-clock
    /// sleeps at the production interval — the `AddCollegeViewModel` seam.
    init(client: SubjectsClientProtocol, debounce: Duration = .milliseconds(300)) {
        self.client = client
        self.debounce = debounce
    }

    /// The first, unqueried read: the whole list, in the server's name order.
    /// Not debounced — nobody typed anything, so there is nothing to coalesce.
    func load() async {
        searchTask?.cancel()
        searchTask = nil
        await search(nil)
    }

    /// Debounce by replacement: every keystroke cancels the pending task and
    /// schedules a new one, so a burst of edits coalesces into one request for
    /// the final text. Clearing the field is a request too — for the whole
    /// list — so it takes the same path rather than short-circuiting.
    private func handleQueryChange() {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        searchTask = Task { [debounce] in
            do {
                try await Task.sleep(for: debounce)
            } catch is CancellationError {
                return // superseded by a newer keystroke
            } catch {
                logger.error("Debounce sleep failed unexpectedly: [\(error, privacy: .public)]")
                return
            }
            await search(trimmed.isEmpty ? nil : trimmed)
        }
    }

    private func search(_ trimmed: String?) async {
        state = .searching
        do {
            let subjects = try await client.search(query: trimmed)
            guard !Task.isCancelled else { return }
            state = SearchPhase(results: subjects)
        } catch is CancellationError {
            // superseded
        } catch let error as ErrorResponse {
            guard !Task.isCancelled else { return }
            state = .failed(error)
        } catch {
            guard !Task.isCancelled else { return }
            logger.error("Subject search failed (unexpected): [\(error, privacy: .public)]")
            state = .failed(.unexpected)
        }
    }

    /// Waits out any pending debounce — the test hook that makes "the debounce
    /// coalesced" observable without racing the interval.
    func awaitPendingSearch() async {
        await searchTask?.value
    }
}
