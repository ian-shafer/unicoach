import Foundation
import os

/// The add-college search flow (RFC 137): a debounced name query over the
/// picker endpoint, and the add action itself, which writes through the SAME
/// client instance the list screen reads from.
@MainActor
final class AddCollegeViewModel: ObservableObject {
    /// The search pane's state. A blank query renders the prompt, not a
    /// request; zero matches are `empty` ("No colleges match."), never a blank
    /// pane.
    enum SearchState: Equatable {
        case prompt
        case searching
        case results([CollegeSummary])
        case empty
        case failed(ErrorResponse)

        /// The one place the empty/results split is decided: zero matches are
        /// `.empty` ("No colleges match."), never a zero-row `.results`.
        init(results: [CollegeSummary]) {
            self = results.isEmpty ? .empty : .results(results)
        }
    }

    /// The add action's outcomes, named rather than erased into a `Bool`:
    /// `.added` pops back to the list; `.rejected` (the duplicate 409, or any
    /// other server refusal) carries the structured server error the view
    /// formats inline; `.profileRequired` has already escalated to the root
    /// state machine.
    enum AddOutcome: Equatable {
        case added
        case rejected(ErrorResponse)
        case profileRequired
    }

    @Published var query: String = "" {
        didSet { handleQueryChange() }
    }
    @Published private(set) var state: SearchState = .prompt
    /// The inline failure under the field — the duplicate-add 409's
    /// structured server error, per the RFC ("Already on the list" surfaces
    /// inline). The view formats its `message`; the code stays available.
    @Published private(set) var addError: ErrorResponse?
    @Published private(set) var isAdding = false

    private let client: CollegeListClientProtocol
    private let onProfileRequired: () -> Void
    private let logger = Logger.unicoach(category: "AddCollegeViewModel")
    private let debounce: Duration
    private var searchTask: Task<Void, Never>?

    /// `debounce` is injectable so tests drive coalescing without wall-clock
    /// sleeps at the production interval.
    init(
        client: CollegeListClientProtocol,
        onProfileRequired: @escaping () -> Void,
        debounce: Duration = .milliseconds(300)
    ) {
        self.client = client
        self.onProfileRequired = onProfileRequired
        self.debounce = debounce
    }

    /// Debounce by replacement: every keystroke cancels the pending task and
    /// schedules a new one, so a burst of edits coalesces into one request for
    /// the final text.
    private func handleQueryChange() {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            state = .prompt
            searchTask = nil
            return
        }
        searchTask = Task { [debounce] in
            do {
                try await Task.sleep(for: debounce)
            } catch is CancellationError {
                return // superseded by a newer keystroke
            } catch {
                logger.error("Debounce sleep failed unexpectedly: [\(error, privacy: .public)]")
                return
            }
            await search(trimmed)
        }
    }

    private func search(_ trimmed: String) async {
        state = .searching
        do {
            let colleges = try await client.searchColleges(query: trimmed)
            guard !Task.isCancelled else { return }
            state = SearchState(results: colleges)
        } catch is CancellationError {
            // superseded
        } catch let error as ErrorResponse {
            guard !Task.isCancelled else { return }
            if error.knownCode == .studentProfileRequired {
                onProfileRequired()
                return
            }
            state = .failed(error)
        } catch {
            guard !Task.isCancelled else { return }
            state = .failed(.unexpected)
        }
    }

    /// Waits out any pending debounce — the test hook that makes "the debounce
    /// coalesced" observable without racing the interval.
    func awaitPendingSearch() async {
        await searchTask?.value
    }

    /// Adds `college` with the server-default status (`considering`). On
    /// `.added` the view pops back to the list, which refreshes on return. A
    /// 409 (already on the list) is `.rejected` with the server's message,
    /// surfaced inline; the profile refusal escalates like every other screen.
    func add(_ college: CollegeSummary) async -> AddOutcome {
        addError = nil
        isAdding = true
        defer { isAdding = false }
        do {
            _ = try await client.addEntry(collegeId: college.id)
            return .added
        } catch let error as ErrorResponse {
            if error.knownCode == .studentProfileRequired {
                onProfileRequired()
                return .profileRequired
            }
            addError = error
            return .rejected(error)
        } catch {
            addError = .unexpected
            return .rejected(.unexpected)
        }
    }

}
