import Foundation

/// The conversation-list screen's load state. An empty list is a distinct
/// successful outcome (`empty`), not a degenerate `loaded([])`, so the view can
/// render a dedicated no-conversations affordance.
enum ConversationListState: Equatable {
    case loading
    case loaded([Conversation])
    case empty
    case failed(ErrorResponse)
}

@MainActor
final class ConversationListViewModel: ObservableObject {
    @Published private(set) var state: ConversationListState = .loading

    /// A per-action failure channel kept separate from `state`. `.failed` is
    /// reserved for initial-load failure (it replaces the whole screen); an
    /// archive/delete failure rolls the list back and surfaces here so the loaded
    /// list is never torn down. `ErrorResponse` is `Identifiable`, so this drives
    /// an item-style alert directly.
    @Published var actionError: ErrorResponse?

    private let conversationClient: ConversationClientProtocol

    init(conversationClient: ConversationClientProtocol) {
        self.conversationClient = conversationClient
    }

    /// Fetches the student's conversations in the server's most-recently-used
    /// order. Called on every appearance of the list screen so MRU order reflects
    /// a conversation just continued and popped back from. An empty response maps
    /// to `.empty`; a thrown `ErrorResponse` (transport or decoded server error)
    /// maps to `.failed`; any other error is synthesized as a `SERVER_ERROR`.
    func load() async {
        state = .loading
        do {
            let conversations = try await conversationClient.listConversations()
            state = conversations.isEmpty ? .empty : .loaded(conversations)
        } catch let error as ErrorResponse {
            state = .failed(error)
        } catch {
            state = .failed(ErrorResponse(
                code: "SERVER_ERROR",
                message: String(localized: "An unexpected error occurred."),
                fieldErrors: nil
            ))
        }
    }

    /// Archives a conversation, removing its row optimistically and rolling back
    /// on failure. The view model only ever archives (the unarchive UI is out of
    /// scope), but the client carries no archive-only assumption.
    func archive(_ conversation: Conversation) async {
        await mutate(conversation) { [conversationClient] in
            try await conversationClient.setArchived(conversationId: conversation.id, archived: true)
        }
    }

    /// Deletes a conversation, removing its row optimistically and rolling back on
    /// failure. The view's confirmation dialog gates this intent; the view model
    /// fires only after the user confirms.
    func delete(_ conversation: Conversation) async {
        await mutate(conversation) { [conversationClient] in
            try await conversationClient.deleteConversation(conversationId: conversation.id)
        }
    }

    /// The shared optimistic-with-rollback shape for both row mutations. Removes
    /// the matched row immediately (transitioning to `.empty` when it was the last
    /// row), runs `action`, and on failure reinserts the conversation into the
    /// list as it currently stands — at `min(originalIndex, currentCount)` — then
    /// surfaces the error on `actionError`. Reinserting into the current list,
    /// rather than overwriting with the pre-removal snapshot, composes correctly
    /// if the list shape changed between removal and failure. No-ops if the
    /// conversation is absent from the loaded list. `@MainActor` serializes the
    /// steps.
    private func mutate(_ conversation: Conversation, action: () async throws -> Void) async {
        guard case .loaded(var conversations) = state,
              let originalIndex = conversations.firstIndex(where: { $0.id == conversation.id }) else {
            return
        }

        conversations.remove(at: originalIndex)
        state = conversations.isEmpty ? .empty : .loaded(conversations)

        do {
            try await action()
        } catch {
            var current = currentConversations()
            current.insert(conversation, at: min(originalIndex, current.count))
            state = .loaded(current)
            actionError = (error as? ErrorResponse) ?? ErrorResponse(
                code: "SERVER_ERROR",
                message: String(localized: "An unexpected error occurred."),
                fieldErrors: nil
            )
        }
    }

    /// The currently loaded conversations, or an empty array when the list is in
    /// any non-`.loaded` state (e.g. `.empty` after removing the last row).
    private func currentConversations() -> [Conversation] {
        if case .loaded(let conversations) = state {
            return conversations
        }
        return []
    }
}
