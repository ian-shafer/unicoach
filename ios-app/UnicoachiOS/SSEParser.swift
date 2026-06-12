import Foundation

/// A single Server-Sent Event assembled from one or more SSE lines.
struct ServerSentEvent: Equatable {
    let event: String?
    let data: String
}

/// A transport-agnostic SSE frame assembler. Feed it lines one at a time; it
/// returns a completed `ServerSentEvent` when it reaches a blank-line boundary,
/// otherwise `nil`. It concatenates multiple `data:` lines with "\n", records
/// the last `event:` value, and ignores comment lines (those beginning with ':').
struct SSEFrameAssembler {
    private var event: String?
    private var dataLines: [String] = []
    private var hasPayload = false

    mutating func consume(line: String) -> ServerSentEvent? {
        // Normalize a trailing CR so CRLF endings parse identically to LF.
        let normalized = line.hasSuffix("\r") ? String(line.dropLast()) : line

        if normalized.isEmpty {
            return flush()
        }

        return consumeNonBlank(normalized)
    }

    /// Emits a buffered, complete pending frame at end of stream, else `nil`.
    ///
    /// `URLSession.bytes(...).lines` (AsyncLineSequence) does not surface a
    /// trailing empty line when the body ends with the frame terminator (`\n\n`):
    /// the final blank-line boundary is consumed silently, so the last frame is
    /// never dispatched by `consume(line:)`. A well-formed SSE stream terminates
    /// every frame with that blank line, so any payload still buffered at end of
    /// stream is a complete frame whose boundary line was dropped — flush it.
    ///
    /// This is end-of-stream only and is distinct from `consume(line:)`, which
    /// never auto-flushes a frame that has not seen its blank-line boundary.
    mutating func flushPending() -> ServerSentEvent? {
        flush()
    }

    private mutating func consumeNonBlank(_ normalized: String) -> ServerSentEvent? {
        // Comment line: ignore entirely.
        if normalized.hasPrefix(":") {
            return nil
        }

        let (field, value) = parse(line: normalized)
        switch field {
        case "event":
            event = value
            hasPayload = true
        case "data":
            dataLines.append(value)
            hasPayload = true
        default:
            // Unknown field: per the SSE spec, ignore.
            break
        }
        return nil
    }

    private mutating func flush() -> ServerSentEvent? {
        guard hasPayload else { return nil }
        let frame = ServerSentEvent(event: event, data: dataLines.joined(separator: "\n"))
        event = nil
        dataLines = []
        hasPayload = false
        return frame
    }

    private func parse(line: String) -> (field: String, value: String) {
        guard let colonIndex = line.firstIndex(of: ":") else {
            // A line with no colon is a field name with an empty value.
            return (line, "")
        }
        let field = String(line[line.startIndex..<colonIndex])
        var valueStart = line.index(after: colonIndex)
        // A single leading space after the colon is stripped.
        if valueStart < line.endIndex, line[valueStart] == " " {
            valueStart = line.index(after: valueStart)
        }
        return (field, String(line[valueStart..<line.endIndex]))
    }
}
