import Foundation
import os

/// Per-turn view state: one student message and the coach reply it elicited.
/// `id` is the sole `ForEach` key — never derived from `Message.id`, which is
/// synthetic until an opener reconciles it with the server copy.
struct ChatTurn: Identifiable {
    let id: UUID
    var userMessage: Message     // synthetic on append; fully replaced by the server copy on the opener
    var coachStreamingText: String
    var coachMessage: Message?   // canonical on `.completed`
    var failure: TurnFailure?    // set on a streamed/transport failure
}

/// A turn-scoped failure, preserving the two display vocabularies the screen
/// rendered before this RFC: a coach `error` frame keeps its server message;
/// a transport-class failure keeps its `InfrastructureError` copy.
enum TurnFailure: Equatable {
    case server(ErrorResponse)               // a coach failure frame (coach_unavailable / coach_failed)
    case infrastructure(InfrastructureError) // a transport-class failure (timeout / connectivity / server)
    /// The 402: the coaching budget is spent, so this turn was refused before it
    /// reached the model. It carries no `ErrorResponse` because there is nothing
    /// in that body worth rendering — the words come from `CoachingUsage`, whose
    /// endpoint owns the meter (RFC 121). Unlike every other failure, retrying
    /// it can only reproduce it, so the screen offers "See options" instead
    /// until the block clears.
    case blocked
}

/// The action a failed turn offers, and the one rule that chooses it.
///
/// The meter's three answers are what make the rule honest (RFC 121):
///
/// - **`spent`** — no failure offers a Retry that could only 402.
/// - **`open`** — the server says the budget is open, so even a refused turn is
///   sendable again: that is the whole justification for keeping the student's
///   words rather than deleting them, and a `.blocked` turn stranded on "See
///   options" forever would keep them for nothing.
/// - **`unknown`** — no reading yet, or a refresh that failed, so the failure
///   kind decides: the 402 stays the authority for the turn it refused, and
///   every other failure keeps its ordinary Retry.
///
/// It lives here, next to `TurnFailure`, rather than inline in the view,
/// because it is a rule with a truth table and this suite has no view-test
/// harness: expressed here it is asserted directly; expressed in `body` it is
/// asserted nowhere.
enum TurnAction: Equatable {
    /// Send the words again.
    case retry
    /// Open the paywall: retrying could only reproduce the 402.
    case seeOptions

    init(failure: TurnFailure, budget: CoachingBudget) {
        switch budget {
        case .spent:
            self = .seeOptions
        case .open:
            self = .retry
        case .unknown:
            // No reading, so the failure kind decides — switched, not tested
            // for one case: a new `TurnFailure` must be given an answer here
            // rather than silently inheriting Retry, and Retry on a refusal is
            // a button that can only 402 again.
            switch failure {
            case .blocked:
                self = .seeOptions
            case .server, .infrastructure:
                self = .retry
            }
        }
    }
}

/// What a mapped failure asks the layer **above** the stream to do. `handle`
/// decides it and performs none of it: an escalation is a screen-level action,
/// and running it inside `stream` would hold `isStreaming` true across a network
/// round trip the failed turn is no longer waiting on — a disabled composer and
/// a spinning send button while nothing is in flight (RFC 121).
enum TurnEscalation: Equatable {
    case budgetExhausted
}

/// Governs only the initial history fetch when an established conversation is
/// re-entered. Distinct from the per-turn `isStreaming` / `ChatTurn.failure`
/// state, which covers sending a turn.
enum HistoryLoad: Equatable {
    case loading        // seeded VM, history fetch in flight
    case ready          // fresh VM (no fetch), or seeded VM whose fetch succeeded
    case failed(ErrorResponse)
}

@MainActor
final class ConversationViewModel: ObservableObject {
    /// Contract bounds for the message field on both endpoints.
    private static let messageMaxLength = 100_000

    @Published var messageText: String = ""
    @Published private(set) var turns: [ChatTurn] = []
    /// The established conversation: `nil` until the first turn completes; drives
    /// endpoint selection (`nil` → start; non-`nil` → follow-up).
    @Published private(set) var conversation: Conversation?
    @Published var isStreaming: Bool = false
    /// Pre-send validation banner. Turn-scoped failures live on `ChatTurn.failure`.
    @Published var validationError: ErrorResponse?
    /// The initial history-fetch state for a re-entered conversation. `.ready` on
    /// a fresh VM (no fetch happens); `.loading` until a seeded VM's fetch lands.
    @Published private(set) var historyLoad: HistoryLoad = .ready

    /// The first turn's conversation, stashed until `.completed` establishes it.
    /// A first turn that fails server-side soft-deletes the conversation, so it is
    /// committed only on the terminal success frame — never on the opener.
    private var pendingConversation: Conversation?

    private let conversationClient: ConversationClientProtocol
    private let logger = Logger.unicoach(category: "ConversationViewModel")
    private let onProfileRequired: () -> Void
    /// Reported upward on a 402, mirroring `onProfileRequired`'s shape — but it
    /// replaces no screen. `AuthenticatedRootView` answers it by refreshing the
    /// shared `SubscriptionViewModel`'s meter and presenting the paywall, so the
    /// blocked truth is the server's and every conversation in the stack sees
    /// the same one (RFC 121).
    private let onBudgetExhausted: () async -> Void

    /// Fresh conversation (Start Coaching / compose): no established conversation
    /// and no history to fetch, so `historyLoad` starts `.ready` and `stream()`
    /// routes the first turn to `streamConversation`.
    init(conversationClient: ConversationClientProtocol,
         onProfileRequired: @escaping () -> Void,
         onBudgetExhausted: @escaping () async -> Void) {
        self.conversationClient = conversationClient
        self.onProfileRequired = onProfileRequired
        self.onBudgetExhausted = onBudgetExhausted
    }

    /// Re-enter an established conversation: seeds `conversation` (so every turn
    /// routes to `postMessage`) and sets `historyLoad = .loading` until
    /// `loadHistory()` (driven by the view's `.task`) rebuilds the thread.
    init(conversation: Conversation,
         conversationClient: ConversationClientProtocol,
         onProfileRequired: @escaping () -> Void,
         onBudgetExhausted: @escaping () async -> Void) {
        self.conversation = conversation
        self.conversationClient = conversationClient
        self.onProfileRequired = onProfileRequired
        self.onBudgetExhausted = onBudgetExhausted
        self.historyLoad = .loading
    }

    /// The composer-disabled gate's readiness half: `false` only while a seeded
    /// VM's initial history fetch is in flight. The view combines it with
    /// `isStreaming` (`isStreaming || !isReady`); `canSend` is unchanged.
    var isReady: Bool {
        historyLoad == .ready
    }

    /// Presentational gate for the send button. Multi-turn: the only blocks are
    /// an in-flight stream and an empty message — a completed turn does NOT
    /// disable sending (follow-ups are allowed). `send()`'s own isStreaming and
    /// empty/length guards remain the authoritative defense.
    var canSend: Bool {
        !isStreaming
            && !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func send() async {
        // One turn in flight at a time.
        if isStreaming {
            return
        }

        validationError = nil

        let trimmed = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.count > Self.messageMaxLength {
            validationError = ErrorResponse(
                code: "VALIDATION",
                message: String(localized: "Validation error"),
                fieldErrors: [FieldError(field: "message", message: String(localized: "Enter a message to start coaching."))]
            )
            return
        }

        let turn = ChatTurn(
            id: UUID(),
            userMessage: Message(id: UUID().uuidString, role: .user, content: trimmed, createdAt: Date()),
            coachStreamingText: "",
            coachMessage: nil,
            failure: nil
        )
        turns.append(turn)
        messageText = ""

        await streamAndEscalate(turnId: turn.id, content: trimmed)
    }

    /// Re-dispatches a failed turn in place: clears its failure and partial text,
    /// then re-streams its user message. Routing follows the same establishment
    /// rule as `send` — an unestablished first turn re-creates via
    /// `streamConversation` (the soft-deleted conversation is never reused); an
    /// established follow-up retries via `postMessage` against the same id.
    func retry(_ id: ChatTurn.ID) async {
        if isStreaming {
            return
        }
        guard let idx = turns.firstIndex(where: { $0.id == id }) else {
            return
        }
        turns[idx].failure = nil
        turns[idx].coachStreamingText = ""
        turns[idx].coachMessage = nil
        let content = turns[idx].userMessage.content

        await streamAndEscalate(turnId: id, content: content)
    }

    /// Runs the turn, then performs whatever its failure asked of the layer
    /// above — **after** `isStreaming` has been cleared, so a screen-level
    /// escalation's network round trip cannot hold the composer disabled on a
    /// turn that has already finished failing.
    private func streamAndEscalate(turnId: ChatTurn.ID, content: String) async {
        switch await stream(turnId: turnId, content: content) {
        case .budgetExhausted:
            await onBudgetExhausted()
        case nil:
            break
        }
    }

    /// Consumes a stream into the turn identified by `turnId`, shared by `send`
    /// and `retry`. Dispatch is established-gated: `conversation == nil` starts a
    /// new conversation; otherwise it posts a follow-up turn. Returns what the
    /// failure (if any) asks of the caller, and performs none of it itself.
    private func stream(turnId: ChatTurn.ID, content: String) async -> TurnEscalation? {
        isStreaming = true
        defer { isStreaming = false }

        let events: AsyncThrowingStream<ConversationStreamEvent, Error>
        if let conversation {
            events = conversationClient.postMessage(
                conversationId: conversation.id,
                request: PostMessageRequest(message: content)
            )
        } else {
            events = conversationClient.streamConversation(
                request: CreateConversationRequest(message: content, name: nil)
            )
        }

        do {
            for try await event in events {
                guard let idx = turns.firstIndex(where: { $0.id == turnId }) else {
                    return nil
                }
                switch event {
                case .conversation(let convo, let userMessage):
                    // First turn only: hold the conversation until `.completed`
                    // establishes it (a failed first turn is soft-deleted server-side).
                    pendingConversation = convo
                    turns[idx].userMessage = userMessage
                case .userMessage(let userMessage):
                    turns[idx].userMessage = userMessage
                case .delta(let text):
                    turns[idx].coachStreamingText += text
                case .completed(let message):
                    turns[idx].coachMessage = message
                    if conversation == nil, let established = pendingConversation {
                        conversation = established
                        pendingConversation = nil
                    }
                }
            }
        } catch let error as ErrorResponse {
            return handle(error, turnId: turnId)
        } catch {
            setFailure(.infrastructure(.serverError), turnId: turnId)
        }
        return nil
    }

    /// Maps a thrown server/transport error onto the target turn, and **only**
    /// that: a failure that needs something of the screen above returns a
    /// `TurnEscalation` for `streamAndEscalate` to perform once the stream has
    /// torn down. Writing a failure onto a turn and awaiting a cross-screen
    /// refresh are two jobs, and doing both here cost `isStreaming` a network
    /// round trip.
    ///
    /// It switches on `knownCode`, not the raw string it used to compare against
    /// literals: the codes this screen must *act* on are now enum cases, so a
    /// typo is a build failure rather than a silently unhandled refusal, and
    /// there is no `default:` — a new `ServerErrorCode` has to be given an arm
    /// here before this compiles. The last arm is every code with no chat-screen
    /// behaviour of its own, plus `nil` for a code this client has never heard
    /// of (a newer server code, or the client-synthesized `VALIDATION`): all of
    /// them keep the turn and show the server's own message.
    private func handle(_ error: ErrorResponse, turnId: ChatTurn.ID) -> TurnEscalation? {
        switch error.knownCode {
        case .studentProfileRequired:
            // The profile was deleted server-side mid-session: the root state
            // machine replaces this screen with onboarding. Drop the optimistic
            // turn and publish nothing.
            onProfileRequired()
            turns.removeAll { $0.id == turnId }
        case .coachingBudgetExhausted:
            // The deliberate inverse of the arm above: the turn is **kept**. It
            // never reached the model, and the student's words are theirs — so
            // they stay in the transcript, marked blocked, ready to send again
            // the moment the block clears.
            //
            // Logged with the identifiers that make it reconcilable against the
            // server's gate: this one refusal disables every composer in the
            // stack, and "blocked while subscribed" is otherwise a report with
            // nothing to look up.
            logger.error(
                """
                Coaching budget gate refused a turn                 [conversation=\(self.conversation?.id.uuidString ?? "new", privacy: .public)]                 [turn=\(turnId.uuidString, privacy: .public)]                 [code=\(error.code, privacy: .public)]                 [message=\(error.message, privacy: .public)]
                """
            )
            setFailure(.blocked, turnId: turnId)
            return .budgetExhausted
        case .timeout:
            setFailure(.infrastructure(.timeout), turnId: turnId)
        case .networkError:
            setFailure(.infrastructure(.noConnectivity), turnId: turnId)
        case .serverError:
            setFailure(.infrastructure(.serverError), turnId: turnId)
        case .unauthorized, .emailNotVerified, .accountEmailNotVerified,
             .accountDisabled, .serviceUnavailable, .studentAlreadyExists,
             .subscriptionNotFound, .subscriptionOwnedByOtherAccount,
             .validationFailed, .payloadTooLarge, .decodeError, nil:
            setFailure(.server(error), turnId: turnId)
        }
        return nil
    }

    private func setFailure(_ failure: TurnFailure, turnId: ChatTurn.ID) {
        guard let idx = turns.firstIndex(where: { $0.id == turnId }) else {
            return
        }
        turns[idx].failure = failure
    }

    /// Loads a re-entered conversation's history once, rebuilding `turns` from the
    /// server's flat `[Message]`. Called from the view's `.task` and the retry
    /// action. A no-op on the fresh path (no `conversation`) and idempotent on
    /// re-appearance (a loaded thread is non-empty); after a failure the guard
    /// still holds because `turns` stayed empty, so retry re-runs the fetch.
    func loadHistory() async {
        guard let conversation, turns.isEmpty else {
            return
        }

        historyLoad = .loading
        do {
            let messages = try await conversationClient.fetchMessages(conversationId: conversation.id)
            turns = Self.turns(from: messages)
            historyLoad = .ready
        } catch let error as ErrorResponse {
            historyLoad = .failed(error)
        } catch {
            historyLoad = .failed(ErrorResponse(
                code: "SERVER_ERROR",
                message: String(localized: "An unexpected error occurred."),
                fieldErrors: nil
            ))
        }
    }

    /// Rebuilds the `[ChatTurn]` thread from a flat, replay-ordered `[Message]`.
    /// The server emits strictly paired `user`-then-`coach` messages (a turn is
    /// visible only with a non-null coach response). A `.user` opens a new turn;
    /// the next `.coach` attaches as its `coachMessage`. The non-paired shapes
    /// (trailing `.user`, leading orphan `.coach`) are crash-guards against a
    /// contract violation, never expected output: a trailing `.user` yields a
    /// turn with `coachMessage == nil`; an orphan `.coach` with no open turn is
    /// dropped.
    private static func turns(from messages: [Message]) -> [ChatTurn] {
        var rebuilt: [ChatTurn] = []
        for message in messages {
            switch message.role {
            case .user:
                rebuilt.append(ChatTurn(
                    id: UUID(),
                    userMessage: message,
                    coachStreamingText: "",
                    coachMessage: nil,
                    failure: nil
                ))
            case .coach:
                guard !rebuilt.isEmpty, rebuilt[rebuilt.count - 1].coachMessage == nil else {
                    continue
                }
                rebuilt[rebuilt.count - 1].coachMessage = message
            }
        }
        return rebuilt
    }
}
