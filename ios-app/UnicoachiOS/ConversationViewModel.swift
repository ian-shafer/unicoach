import Foundation

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
    private let onProfileRequired: () -> Void

    /// Fresh conversation (Start Coaching / compose): no established conversation
    /// and no history to fetch, so `historyLoad` starts `.ready` and `stream()`
    /// routes the first turn to `streamConversation`.
    init(conversationClient: ConversationClientProtocol, onProfileRequired: @escaping () -> Void) {
        self.conversationClient = conversationClient
        self.onProfileRequired = onProfileRequired
    }

    /// Re-enter an established conversation: seeds `conversation` (so every turn
    /// routes to `postMessage`) and sets `historyLoad = .loading` until
    /// `loadHistory()` (driven by the view's `.task`) rebuilds the thread.
    init(conversation: Conversation,
         conversationClient: ConversationClientProtocol,
         onProfileRequired: @escaping () -> Void) {
        self.conversation = conversation
        self.conversationClient = conversationClient
        self.onProfileRequired = onProfileRequired
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

        await stream(turnId: turn.id, content: trimmed)
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

        await stream(turnId: id, content: content)
    }

    /// Consumes a stream into the turn identified by `turnId`, shared by `send`
    /// and `retry`. Dispatch is established-gated: `conversation == nil` starts a
    /// new conversation; otherwise it posts a follow-up turn.
    private func stream(turnId: ChatTurn.ID, content: String) async {
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
                    return
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
            handle(error, turnId: turnId)
        } catch {
            setFailure(.infrastructure(.serverError), turnId: turnId)
        }
    }

    /// Maps a thrown server/transport error onto the target turn. Codes are matched
    /// case-sensitively: client-synthesized codes (`TIMEOUT`/`NETWORK_ERROR`/
    /// `SERVER_ERROR`/`VALIDATION`) are UPPERCASE; this route family's server codes
    /// are lowercase. `student_profile_required` is a first-turn-only pre-stream 409.
    private func handle(_ error: ErrorResponse, turnId: ChatTurn.ID) {
        switch error.code {
        case "student_profile_required":
            // The profile was deleted server-side mid-session: the root state
            // machine replaces this screen with onboarding. Drop the optimistic
            // turn and publish nothing.
            onProfileRequired()
            turns.removeAll { $0.id == turnId }
        case "TIMEOUT":
            setFailure(.infrastructure(.timeout), turnId: turnId)
        case "NETWORK_ERROR":
            setFailure(.infrastructure(.noConnectivity), turnId: turnId)
        case "SERVER_ERROR":
            setFailure(.infrastructure(.serverError), turnId: turnId)
        default:
            setFailure(.server(error), turnId: turnId)
        }
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
