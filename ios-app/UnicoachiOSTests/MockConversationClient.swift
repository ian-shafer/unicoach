import Foundation
@testable import UnicoachiOS

/// A view-model-level test seam: scripts a sequence of stream events, optionally
/// terminating with a thrown error after emitting them. Records each request.
final class MockConversationClient: ConversationClientProtocol, @unchecked Sendable {
    /// Events emitted in order before the stream finishes.
    var scriptedEvents: [ConversationStreamEvent] = []
    /// If set, the stream finishes by throwing this after emitting `scriptedEvents`.
    var terminalError: Error?
    /// Optional per-yield delay so a consumer can be cancelled mid-stream.
    var perEventDelay: Duration?

    private(set) var requests: [CreateConversationRequest] = []
    var invocationCount: Int { requests.count }

    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        requests.append(request)
        let events = scriptedEvents
        let error = terminalError
        let delay = perEventDelay
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for event in events {
                        if let delay {
                            try await Task.sleep(for: delay)
                        }
                        try Task.checkCancellation()
                        continuation.yield(event)
                    }
                    if let error {
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
