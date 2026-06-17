import Foundation
@testable import UnicoachiOS

/// A view-model-level test seam. Scripts a queue of per-call outcomes spanning
/// both endpoints, so multi-turn and retry tests can drive a different outcome
/// per turn. Records `streamConversation` and `postMessage` invocations in
/// separate per-method logs so a test can assert which endpoint a turn dispatched
/// to. Each script may optionally throw a terminal error after emitting its
/// events, and a per-event delay supports cancellation tests.
final class MockConversationClient: ConversationClientProtocol, @unchecked Sendable {
    /// One scripted outcome consumed per client call, in FIFO order across both
    /// methods. When the queue is exhausted a call finishes with no events.
    struct Script {
        var events: [ConversationStreamEvent]
        var terminalError: Error?
        var perEventDelay: Duration?

        init(events: [ConversationStreamEvent], terminalError: Error? = nil, perEventDelay: Duration? = nil) {
            self.events = events
            self.terminalError = terminalError
            self.perEventDelay = perEventDelay
        }
    }

    /// FIFO queue of outcomes, one dequeued per call across both methods.
    var scripts: [Script] = []

    private(set) var streamConversationRequests: [CreateConversationRequest] = []
    private(set) var postMessageRequests: [(conversationId: UUID, request: PostMessageRequest)] = []

    /// Scripted outcome for `listConversations`: a thrown error takes precedence,
    /// otherwise the stored list is returned. `listConversationsCallCount` records
    /// every invocation (incremented even when the call throws).
    var listConversationsResult: [Conversation] = []
    var listConversationsError: Error?
    private(set) var listConversationsCallCount = 0

    /// Scripted outcome for `fetchMessages`, mirroring the list scripting. The
    /// requested conversation ids are recorded in `fetchMessagesRequests`.
    var fetchMessagesResult: [Message] = []
    var fetchMessagesError: Error?
    private(set) var fetchMessagesRequests: [UUID] = []
    var fetchMessagesCallCount: Int { fetchMessagesRequests.count }

    /// Scripted outcome for `setArchived`: every call is recorded in
    /// `setArchivedRequests` (id + flag), then the scripted error is thrown if set.
    var setArchivedError: Error?
    private(set) var setArchivedRequests: [(conversationId: UUID, archived: Bool)] = []

    /// Scripted outcome for `deleteConversation`: every call records its id in
    /// `deleteConversationRequests`, then the scripted error is thrown if set.
    var deleteConversationError: Error?
    private(set) var deleteConversationRequests: [UUID] = []

    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        streamConversationRequests.append(request)
        return emit(nextScript())
    }

    func postMessage(conversationId: UUID, request: PostMessageRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        postMessageRequests.append((conversationId, request))
        return emit(nextScript())
    }

    func listConversations() async throws -> [Conversation] {
        listConversationsCallCount += 1
        if let error = listConversationsError {
            throw error
        }
        return listConversationsResult
    }

    func fetchMessages(conversationId: UUID) async throws -> [Message] {
        fetchMessagesRequests.append(conversationId)
        if let error = fetchMessagesError {
            throw error
        }
        return fetchMessagesResult
    }

    func deleteConversation(conversationId: UUID) async throws {
        deleteConversationRequests.append(conversationId)
        if let error = deleteConversationError {
            throw error
        }
    }

    func setArchived(conversationId: UUID, archived: Bool) async throws {
        setArchivedRequests.append((conversationId, archived))
        if let error = setArchivedError {
            throw error
        }
    }

    private func nextScript() -> Script {
        if scripts.isEmpty {
            return Script(events: [])
        }
        return scripts.removeFirst()
    }

    private func emit(_ script: Script) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for event in script.events {
                        if let delay = script.perEventDelay {
                            try await Task.sleep(for: delay)
                        }
                        try Task.checkCancellation()
                        continuation.yield(event)
                    }
                    if let error = script.terminalError {
                        continuation.finish(throwing: error)
                    } else {
                        continuation.finish()
                    }
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
