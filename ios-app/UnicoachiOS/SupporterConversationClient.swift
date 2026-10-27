import Foundation
import os

/// The parent's coach doors (RFC 258): their own conversations, about the
/// students they support. Four routes, all under the supporter home.
///
/// Its own client rather than more methods on `ConversationClient`, for the
/// reason `SupporterClient` is its own: these are the SUPPORTER side's routes,
/// and a parent's conversations have no archive and no delete.
protocol SupporterConversationClientProtocol: Sendable {
    /// `GET /api/v1/supporters/me/conversations`: every conversation of the
    /// caller's, about every student they support. The caller filters.
    func listConversations() async throws -> [SupporterConversation]

    /// `POST /api/v1/supporters/me/conversations/stream`.
    func startConversation(studentId: UUID, message: String) -> AsyncThrowingStream<ConversationStreamEvent, Error>

    /// `POST /api/v1/supporters/me/conversations/{id}/messages/stream`.
    func postMessage(conversationId: UUID, request: PostMessageRequest) -> AsyncThrowingStream<ConversationStreamEvent, Error>

    /// `GET /api/v1/supporters/me/conversations/{id}/messages`. Readable after
    /// the connection to the student ends: the conversation is the parent's own.
    func fetchMessages(conversationId: UUID) async throws -> [Message]
}

final class SupporterConversationClient: SupporterConversationClientProtocol, @unchecked Sendable {
    private static let basePath = "/api/v1/supporters/me/conversations"

    private let apiClient: APIClient
    /// The SAME decoder the student's client streams through: the server sends
    /// both coaches' frames through one writer.
    private let streams: ConversationStreams
    private let logger = Logger.unicoach(category: "SupporterConversationClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
        self.streams = ConversationStreams(apiClient: apiClient)
    }

    func listConversations() async throws -> [SupporterConversation] {
        logger.debug("Listing the parent's coach conversations")
        let (data, response) = try await apiClient.get(Self.basePath)
        let list: SupporterConversationListResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return list.conversations
    }

    func startConversation(studentId: UUID, message: String) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        streams.run(
            path: "\(Self.basePath)/stream",
            body: CreateSupporterConversationRequest(studentId: studentId, message: message),
            opener: .conversation
        )
    }

    func postMessage(conversationId: UUID, request: PostMessageRequest) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        streams.run(
            path: "\(Self.basePath)/\(conversationId.uuidString)/messages/stream",
            body: request,
            opener: .userMessage
        )
    }

    func fetchMessages(conversationId: UUID) async throws -> [Message] {
        let (data, response) = try await apiClient.get("\(Self.basePath)/\(conversationId.uuidString)/messages")
        let list: MessageListResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return list.messages
    }
}

/// One thread of the parent's coach, as `ConversationViewModel` drives it: the
/// parent's client bound to the student the conversation is about, so the view
/// model's "start" names that student and its "continue" names the
/// conversation, exactly as the routes do.
struct SupporterThreadClient: ConversationThreadClient {
    let client: SupporterConversationClientProtocol
    let studentId: UUID

    func streamConversation(request: CreateConversationRequest) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        client.startConversation(studentId: studentId, message: request.message)
    }

    func postMessage(conversationId: UUID, request: PostMessageRequest) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        client.postMessage(conversationId: conversationId, request: request)
    }

    func fetchMessages(conversationId: UUID) async throws -> [Message] {
        try await client.fetchMessages(conversationId: conversationId)
    }
}

/// The parent coach's canvas double: one conversation, a short reply, no
/// network. Declared next to the seam, the `PreviewSupporterClient` precedent.
struct PreviewSupporterConversationClient: SupporterConversationClientProtocol {
    var conversations: [SupporterConversation] = []
    var history: [Message] = []

    func listConversations() async throws -> [SupporterConversation] { conversations }

    func startConversation(studentId: UUID, message: String) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let conversation = Conversation(id: UUID(), name: message, createdAt: Date(), updatedAt: Date(), lastActivityAt: nil, archivedAt: nil)
            continuation.yield(.conversation(conversation, userMessage: Message(id: "u1", role: .user, content: message, createdAt: Date())))
            continuation.yield(.completed(Message(id: "c1", role: .coach, content: "Here is what the list would cost.", createdAt: Date())))
            continuation.finish()
        }
    }

    func postMessage(conversationId: UUID, request: PostMessageRequest) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            continuation.yield(.userMessage(Message(id: UUID().uuidString, role: .user, content: request.message, createdAt: Date())))
            continuation.yield(.completed(Message(id: UUID().uuidString, role: .coach, content: "Tell me more.", createdAt: Date())))
            continuation.finish()
        }
    }

    func fetchMessages(conversationId: UUID) async throws -> [Message] { history }
}
