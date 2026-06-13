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
