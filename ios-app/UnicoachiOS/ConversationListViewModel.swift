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
}
