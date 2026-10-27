import Foundation
@testable import UnicoachiOS

/// A view-level seam for the parent's coach (RFC 258): scripted list and
/// transcript reads, recorded starts and turns, and per-call stream scripts.
final class MockSupporterConversationClient: SupporterConversationClientProtocol, @unchecked Sendable {
    var conversations: [SupporterConversation] = []
    /// When set, the list read throws it instead of answering.
    var listError: Error?
    var history: [Message] = []
    /// One script per streaming call, in order; an empty queue finishes with no events.
    var scripts: [MockConversationClient.Script] = []

    private(set) var startRequests: [(studentId: UUID, message: String)] = []
    private(set) var postRequests: [(conversationId: UUID, request: PostMessageRequest)] = []

    private(set) var listCalls = 0

    func listConversations() async throws -> [SupporterConversation] {
        listCalls += 1
        if let listError { throw listError }
        return conversations
    }

    func startConversation(studentId: UUID, message: String) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        startRequests.append((studentId, message))
        return emit()
    }

    func postMessage(conversationId: UUID, request: PostMessageRequest) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        postRequests.append((conversationId, request))
        return emit()
    }

    func fetchMessages(conversationId: UUID) async throws -> [Message] { history }

    private func emit() -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        let script = scripts.isEmpty ? MockConversationClient.Script(events: []) : scripts.removeFirst()
        return AsyncThrowingStream { continuation in
            for event in script.events {
                continuation.yield(event)
            }
            if let error = script.terminalError {
                continuation.finish(throwing: error)
            } else {
                continuation.finish()
            }
        }
    }
}
