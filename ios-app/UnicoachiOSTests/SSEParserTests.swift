import XCTest
@testable import UnicoachiOS

final class SSEParserTests: XCTestCase {
    private func consume(_ lines: [String]) -> [ServerSentEvent] {
        var assembler = SSEFrameAssembler()
        var frames: [ServerSentEvent] = []
        for line in lines {
            if let frame = assembler.consume(line: line) {
                frames.append(frame)
            }
        }
        return frames
    }

    func testSingleFrameWithEventAndData() {
        let frames = consume([
            "event: delta",
            "data: hello",
            "",
        ])
        XCTAssertEqual(frames, [ServerSentEvent(event: "delta", data: "hello")])
    }

    func testMultipleDataLinesJoinedWithNewline() {
        let frames = consume([
            "data: line one",
            "data: line two",
            "",
        ])
        XCTAssertEqual(frames, [ServerSentEvent(event: nil, data: "line one\nline two")])
    }

    func testDataOnlyFrameHasNilEvent() {
        let frames = consume([
            "data: payload",
            "",
        ])
        XCTAssertEqual(frames, [ServerSentEvent(event: nil, data: "payload")])
    }

    func testCommentLineIgnored() {
        let frames = consume([
            ": this is a comment",
            "data: payload",
            "",
        ])
        XCTAssertEqual(frames, [ServerSentEvent(event: nil, data: "payload")])
    }

    func testCommentOnlyEmitsNoFrame() {
        let frames = consume([
            ": keep-alive",
            "",
        ])
        XCTAssertTrue(frames.isEmpty)
    }

    func testCRLFParsedIdenticallyToLF() {
        let frames = consume([
            "event: delta\r",
            "data: hello\r",
            "\r",
        ])
        XCTAssertEqual(frames, [ServerSentEvent(event: "delta", data: "hello")])
    }

    func testIncompleteTrailingFrameEmitsNothing() {
        let frames = consume([
            "event: delta",
            "data: hello",
        ])
        XCTAssertTrue(frames.isEmpty)
    }

    func testFlushPendingReturnsBufferedFrame() {
        var assembler = SSEFrameAssembler()
        XCTAssertNil(assembler.consume(line: "event: delta"))
        XCTAssertNil(assembler.consume(line: "data: hello"))
        // No terminating blank line was seen; flushPending recovers the frame.
        XCTAssertEqual(assembler.flushPending(), ServerSentEvent(event: "delta", data: "hello"))
    }

    func testFlushPendingReturnsNilWhenNothingBuffered() {
        var assembler = SSEFrameAssembler()
        XCTAssertNil(assembler.flushPending())
    }
}
