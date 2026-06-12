import Foundation
import os

protocol ConversationClientProtocol: Sendable {
    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error>
}

/// A thin endpoint binding over the injected `APIClient`, owning only what is
/// SSE-specific: line splitting, frame assembly, and frame→event decoding. It
/// does not construct `URLRequest`s, own a `URLSession`, or configure transport.
final class ConversationClient: ConversationClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "com.unicoach.UnicoachiOS", category: "ConversationClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await self.runStream(request: request, continuation: continuation)
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

    private func runStream(
        request: CreateConversationRequest,
        continuation: AsyncThrowingStream<ConversationStreamEvent, Error>.Continuation
    ) async throws {
        let bytes = try await apiClient.stream(
            "/api/v1/conversations/stream",
            body: request,
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
            if try yield(frame: frame, to: continuation) { return }
        }
        // A well-formed SSE body ends with the frame terminator (`\n\n`), so the
        // final boundary line was already delivered above. If the body was cut off
        // mid-frame (no trailing blank line) any complete buffered frame is flushed
        // here; a genuinely partial frame is dropped by the assembler.
        if let frame = assembler.flushPending() {
            if try yield(frame: frame, to: continuation) { return }
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
        to continuation: AsyncThrowingStream<ConversationStreamEvent, Error>.Continuation
    ) throws -> Bool {
        let event = try decodeFrame(frame)
        continuation.yield(event)
        if case .completed = event {
            continuation.finish()
            return true
        }
        return false
    }

    /// Decodes one SSE frame's `data:` JSON into a domain event by its `type`
    /// discriminator. A `type:"error"` frame is thrown as the carried
    /// `ErrorResponse`; an unknown/off-contract `type` (or undecodable payload)
    /// is a malformed frame → thrown `SERVER_ERROR`.
    private func decodeFrame(_ frame: ServerSentEvent) throws -> ConversationStreamEvent {
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
            case "conversation":
                let created = try apiClient.jsonDecoder.decode(ConversationCreatedFrame.self, from: payload)
                return .conversation(created.conversation, userMessage: created.userMessage)
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
                // Unknown/off-contract type (including `user_message` on this endpoint).
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
