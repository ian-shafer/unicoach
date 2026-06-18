import Foundation
import os

protocol ConversationClientProtocol: Sendable {
    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error>
    func postMessage(conversationId: UUID, request: PostMessageRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error>
    func listConversations() async throws -> [Conversation]
    func fetchMessages(conversationId: UUID) async throws -> [Message]
    func deleteConversation(conversationId: UUID) async throws
    func setArchived(conversationId: UUID, archived: Bool) async throws
}

/// A thin endpoint binding over the injected `APIClient`, owning only what is
/// SSE-specific: line splitting, frame assembly, and frame→event decoding. It
/// does not construct `URLRequest`s, own a `URLSession`, or configure transport.
final class ConversationClient: ConversationClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "com.unicoachapp.UnicoachiOS", category: "ConversationClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    /// The opener frame an endpoint may legally emit. The follow-up endpoint opens
    /// with `user_message`; the start endpoint opens with `conversation`. A frame
    /// matching the other endpoint's opener is off-contract → `SERVER_ERROR`.
    private enum Opener {
        case conversation
        case userMessage
    }

    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        runStream(path: "/api/v1/conversations/stream", body: request, opener: .conversation)
    }

    func postMessage(conversationId: UUID, request: PostMessageRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        runStream(
            path: "/api/v1/conversations/\(conversationId.uuidString)/messages/stream",
            body: request,
            opener: .userMessage
        )
    }

    /// Lists the student's unarchived conversations in the server's
    /// most-recently-used order. No `status` query parameter is sent — the server
    /// defaults to the unarchived scope. Any non-`200` status routes through
    /// `decode`'s `decodeError` and throws the decoded `ErrorResponse` (no `404`
    /// short-circuit; the empty list arrives as a `200`).
    func listConversations() async throws -> [Conversation] {
        let (data, response) = try await apiClient.get("/api/v1/conversations")
        let listResponse: ConversationListResponse = try apiClient.decode(
            data: data, response: response, expectedStatus: 200
        )
        return listResponse.conversations
    }

    /// Fetches a conversation's visible turns as a flat, replay-ordered
    /// `[Message]` (strictly paired user-then-coach). A soft-deleted or foreign
    /// conversation returns `404 {"code":"not_found"}`, which `decode` routes
    /// through `decodeError` and throws as the `not_found` `ErrorResponse`.
    func fetchMessages(conversationId: UUID) async throws -> [Message] {
        let (data, response) = try await apiClient.get(
            "/api/v1/conversations/\(conversationId.uuidString)/messages"
        )
        let messageResponse: MessageListResponse = try apiClient.decode(
            data: data, response: response, expectedStatus: 200
        )
        return messageResponse.messages
    }

    /// Deletes a conversation via `DELETE /api/v1/conversations/{id}`, expecting
    /// `204`. Mirrors `fetchMessages`'s single-entity-by-id handling: any non-`204`
    /// status routes through `expect`'s `decodeError` and throws the decoded
    /// `ErrorResponse` (e.g. `not_found` on an already-deleted or foreign id).
    func deleteConversation(conversationId: UUID) async throws {
        let (data, response) = try await apiClient.delete(
            "/api/v1/conversations/\(conversationId.uuidString)"
        )
        try apiClient.expect(data: data, response: response, expectedStatus: 204)
    }

    /// Sets a conversation's archived flag via `PATCH /api/v1/conversations/{id}`
    /// with an `archived`-only body, expecting `200`. The `200`
    /// `ConversationResponse` body is intentionally discarded — the caller drops
    /// the row regardless. Any non-`200` routes through `expect`'s `decodeError`
    /// and throws the decoded `ErrorResponse`.
    func setArchived(conversationId: UUID, archived: Bool) async throws {
        let (data, response) = try await apiClient.patch(
            "/api/v1/conversations/\(conversationId.uuidString)",
            body: UpdateConversationRequest(name: nil, archived: archived)
        )
        try apiClient.expect(data: data, response: response, expectedStatus: 200)
    }

    /// Shared SSE pump for both endpoints: opens the stream, splits lines, assembles
    /// frames, decodes them (gated by the endpoint's legal `opener`), and finishes
    /// on the terminal frame. Only the path, request body, and accepted opener differ.
    private func runStream<B: Encodable & Sendable>(
        path: String,
        body: B,
        opener: Opener
    ) -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await self.pump(path: path, body: body, opener: opener, continuation: continuation)
                } catch let error as ErrorResponse {
                    continuation.finish(throwing: error)
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    // A failure thrown mid-iteration by `AsyncBytes` (idle timeout,
                    // connection loss) occurs outside `APIClient.stream()`, so map it
                    // through the shared transport vocabulary here.
                    continuation.finish(throwing: self.apiClient.transportError(error))
                }
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    private func pump<B: Encodable & Sendable>(
        path: String,
        body: B,
        opener: Opener,
        continuation: AsyncThrowingStream<ConversationStreamEvent, Error>.Continuation
    ) async throws {
        let bytes = try await apiClient.stream(
            path,
            body: body,
            accept: "text/event-stream",
            expectedStatus: 200
        )

        var assembler = SSEFrameAssembler()
        // Split on LF ourselves rather than `bytes.lines`: `AsyncLineSequence`
        // discards every empty line, but SSE uses the blank line as its frame
        // boundary, so `SSEFrameAssembler` must see those boundaries to dispatch
        // a frame. Each `\n`-delimited line (including empty ones) is fed through.
        for try await line in bytes.sseLines {
            guard let frame = assembler.consume(line: line) else { continue }
            if try yield(frame: frame, opener: opener, to: continuation) { return }
        }
        // A well-formed SSE body ends with the frame terminator (`\n\n`), so the
        // final boundary line was already delivered above. If the body was cut off
        // mid-frame (no trailing blank line) any complete buffered frame is flushed
        // here; a genuinely partial frame is dropped by the assembler.
        if let frame = assembler.flushPending() {
            if try yield(frame: frame, opener: opener, to: continuation) { return }
        }
        // Stream ended without a terminal `message` or `error` frame.
        continuation.finish()
    }

    /// Decodes one assembled frame and yields it. Returns `true` when the frame
    /// is terminal (`message`), having finished the continuation; the caller must
    /// then stop reading. An `error`/malformed frame throws (finished by the
    /// caller's `catch`).
    private func yield(
        frame: ServerSentEvent,
        opener: Opener,
        to continuation: AsyncThrowingStream<ConversationStreamEvent, Error>.Continuation
    ) throws -> Bool {
        let event = try decodeFrame(frame, opener: opener)
        continuation.yield(event)
        if case .completed = event {
            continuation.finish()
            return true
        }
        return false
    }

    /// Decodes one SSE frame's `data:` JSON into a domain event by its `type`
    /// discriminator, gated by the endpoint's legal `opener`. A `type:"error"`
    /// frame is thrown as the carried `ErrorResponse`; an unknown/off-contract
    /// `type` (or undecodable payload) is a malformed frame → thrown `SERVER_ERROR`.
    /// `conversation` is legal only on the start endpoint; `user_message` only on
    /// the follow-up endpoint; the other opener is off-contract → `SERVER_ERROR`.
    private func decodeFrame(_ frame: ServerSentEvent, opener: Opener) throws -> ConversationStreamEvent {
        guard let payload = frame.data.data(using: .utf8) else {
            throw Self.malformedFrameError
        }
        let discriminator: String
        do {
            discriminator = try apiClient.jsonDecoder.decode(FrameType.self, from: payload).type
        } catch {
            throw Self.malformedFrameError
        }

        do {
            switch discriminator {
            case "conversation" where opener == .conversation:
                let created = try apiClient.jsonDecoder.decode(ConversationCreatedFrame.self, from: payload)
                return .conversation(created.conversation, userMessage: created.userMessage)
            case "user_message" where opener == .userMessage:
                let userMessage = try apiClient.jsonDecoder.decode(UserMessageFrame.self, from: payload)
                return .userMessage(userMessage.userMessage)
            case "delta":
                let delta = try apiClient.jsonDecoder.decode(MessageDeltaFrame.self, from: payload)
                return .delta(delta.text)
            case "message":
                let completed = try apiClient.jsonDecoder.decode(MessageCompletedFrame.self, from: payload)
                return .completed(completed.message)
            case "error":
                let errorFrame = try apiClient.jsonDecoder.decode(StreamErrorFrame.self, from: payload)
                throw errorFrame.error
            default:
                // Unknown type, or an opener off-contract for this endpoint
                // (`user_message` on start, `conversation` on follow-up).
                throw Self.malformedFrameError
            }
        } catch let error as ErrorResponse {
            throw error
        } catch {
            throw Self.malformedFrameError
        }
    }

    private static let malformedFrameError = ErrorResponse(
        code: "SERVER_ERROR",
        message: String(localized: "An unexpected error occurred."),
        fieldErrors: nil
    )

    /// Minimal decoder used only to read the `type` discriminator of a frame.
    private struct FrameType: Codable {
        let type: String
    }
}

extension URLSession.AsyncBytes {
    /// LF-delimited lines that preserve empty lines, unlike `AsyncLineSequence`.
    ///
    /// SSE frames are separated by a blank line; `AsyncLineSequence` (`.lines`)
    /// discards empty lines, erasing those boundaries. `sseLines` yields every
    /// `\n`-terminated segment, including empty ones, so an SSE assembler can see
    /// the frame boundaries. A trailing CR is left intact for the assembler to
    /// normalize, so CRLF bodies parse identically to LF.
    var sseLines: AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                var buffer: [UInt8] = []
                do {
                    for try await byte in self {
                        if byte == UInt8(ascii: "\n") {
                            continuation.yield(String(decoding: buffer, as: UTF8.self))
                            buffer.removeAll(keepingCapacity: true)
                        } else {
                            buffer.append(byte)
                        }
                    }
                    // Emit any bytes after the final newline (an unterminated line);
                    // a well-formed SSE body ends with `\n` so this is usually empty.
                    if !buffer.isEmpty {
                        continuation.yield(String(decoding: buffer, as: UTF8.self))
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
