import XCTest
@testable import UnicoachiOS

final class ConversationClientTests: XCTestCase {
    var client: ConversationClient!
    var session: URLSession!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        client = ConversationClient(apiClient: apiClient)
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    // MARK: - SSE body helpers

    private func sseFrame(_ json: String) -> String {
        "data: \(json)\n\n"
    }

    private func respond(statusCode: Int, body: String, contentType: String = "text/event-stream") {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: statusCode,
                httpVersion: nil,
                headerFields: ["Content-Type": contentType]
            )!
            return (response, Data(body.utf8))
        }
    }

    private func respond(statusCode: Int, data: Data, contentType: String) {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: statusCode,
                httpVersion: nil,
                headerFields: ["Content-Type": contentType]
            )!
            return (response, data)
        }
    }

    private func collect(
        request: CreateConversationRequest = CreateConversationRequest(message: "Hi", name: nil)
    ) async -> Result<[ConversationStreamEvent], Error> {
        do {
            var events: [ConversationStreamEvent] = []
            for try await event in client.streamConversation(request: request) {
                events.append(event)
            }
            return .success(events)
        } catch {
            return .failure(error)
        }
    }

    private func collectPost(
        conversationId: UUID = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!,
        request: PostMessageRequest = PostMessageRequest(message: "Hi")
    ) async -> Result<[ConversationStreamEvent], Error> {
        do {
            var events: [ConversationStreamEvent] = []
            for try await event in client.postMessage(conversationId: conversationId, request: request) {
                events.append(event)
            }
            return .success(events)
        } catch {
            return .failure(error)
        }
    }

    // Sample frame JSON.
    private let conversationFrameJSON = """
    {"type":"conversation","conversation":{"id":"550E8400-E29B-41D4-A716-446655440000","name":"My chat","createdAt":"2026-06-10T12:00:00.123Z","updatedAt":"2026-06-10T12:00:00.123Z","lastActivityAt":null,"archivedAt":null},"userMessage":{"id":"m1","role":"user","content":"Hi","createdAt":"2026-06-10T12:00:00.123Z"}}
    """

    // MARK: - Tests

    func testRequestShape() async throws {
        let expectation = expectation(description: "request inspected")
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/conversations/stream")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "text/event-stream")
            // URLSession.bytes(for:) moves the body to httpBodyStream.
            let body = request.bodyData()
            let decoded = try JSONDecoder().decode(CreateConversationRequest.self, from: body)
            XCTAssertEqual(decoded.message, "Hello coach")
            expectation.fulfill()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        _ = await collect(request: CreateConversationRequest(message: "Hello coach", name: nil))
        await fulfillment(of: [expectation], timeout: 2)
    }

    func testHappyPath() async throws {
        let body =
            sseFrame(conversationFrameJSON)
            + sseFrame("""
            {"type":"delta","text":"Hello"}
            """)
            + sseFrame("""
            {"type":"delta","text":" there"}
            """)
            + sseFrame("""
            {"type":"message","message":{"id":"m2","role":"coach","content":"Hello there","createdAt":"2026-06-10T12:00:01.000Z"}}
            """)
        respond(statusCode: 200, body: body)

        let result = await collect()
        let events = try result.get()
        XCTAssertEqual(events.count, 4)

        guard case .conversation(let conversation, let userMessage) = events[0] else {
            return XCTFail("expected conversation event")
        }
        XCTAssertEqual(conversation.name, "My chat")
        XCTAssertEqual(userMessage.role, .user)

        guard case .delta(let d1) = events[1], case .delta(let d2) = events[2] else {
            return XCTFail("expected delta events")
        }
        XCTAssertEqual(d1, "Hello")
        XCTAssertEqual(d2, " there")

        guard case .completed(let message) = events[3] else {
            return XCTFail("expected completed event")
        }
        XCTAssertEqual(message.content, d1 + d2)
        XCTAssertEqual(message.role, .coach)
    }

    func testDateDecodingFractionalSeconds() async throws {
        respond(statusCode: 200, body: sseFrame(conversationFrameJSON))
        let events = try await collect().get()
        guard case .conversation(let conversation, _) = events[0] else {
            return XCTFail("expected conversation event")
        }
        let expected = ISO8601DateFormatter.fractional.date(from: "2026-06-10T12:00:00.123Z")!
        XCTAssertEqual(conversation.createdAt, expected)
    }

    func testDateDecodingNonFractionalSeconds() async throws {
        let json = """
        {"type":"conversation","conversation":{"id":"550E8400-E29B-41D4-A716-446655440000","name":"My chat","createdAt":"2026-06-10T12:00:00Z","updatedAt":"2026-06-10T12:00:00Z","lastActivityAt":null,"archivedAt":null},"userMessage":{"id":"m1","role":"user","content":"Hi","createdAt":"2026-06-10T12:00:00Z"}}
        """
        respond(statusCode: 200, body: sseFrame(json))
        let events = try await collect().get()
        guard case .conversation(let conversation, _) = events[0] else {
            return XCTFail("expected conversation event")
        }
        let expected = ISO8601DateFormatter.plain.date(from: "2026-06-10T12:00:00Z")!
        XCTAssertEqual(conversation.createdAt, expected)
    }

    func testTerminalErrorFrameThrows() async {
        let body =
            sseFrame(conversationFrameJSON)
            + sseFrame("""
            {"type":"error","error":{"code":"INTERNAL","message":"boom"}}
            """)
        respond(statusCode: 200, body: body)

        let result = await collect()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "INTERNAL")
        XCTAssertEqual(errorResponse.message, "boom")
    }

    func testPreStream400Throws() async {
        respond(
            statusCode: 400,
            body: #"{"code":"VALIDATION","message":"bad request"}"#,
            contentType: "application/json"
        )
        let result = await collect()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "VALIDATION")
    }

    func testPreStream401Throws() async {
        respond(
            statusCode: 401,
            body: #"{"code":"unauthorized","message":"nope"}"#,
            contentType: "application/json"
        )
        let result = await collect()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "unauthorized")
    }

    func testPreStream409StudentProfileRequiredThrows() async {
        respond(
            statusCode: 409,
            body: #"{"code":"student_profile_required","message":"no profile"}"#,
            contentType: "application/json"
        )
        let result = await collect()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "student_profile_required")
    }

    func testPreStreamNonJSONThrowsServerError() async {
        respond(statusCode: 500, body: "Internal Server Error", contentType: "text/plain")
        let result = await collect()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "SERVER_ERROR")
    }

    func testInStreamUnknownTypeThrowsServerError() async {
        let body =
            sseFrame(conversationFrameJSON)
            + sseFrame("""
            {"type":"mystery","text":"???"}
            """)
        respond(statusCode: 200, body: body)
        let result = await collect()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "SERVER_ERROR")
    }

    func testInStreamUserMessageFrameOffContractThrowsServerError() async {
        let body =
            sseFrame(conversationFrameJSON)
            + sseFrame("""
            {"type":"user_message","userMessage":{"id":"m1","role":"user","content":"Hi","createdAt":"2026-06-10T12:00:00.123Z"}}
            """)
        respond(statusCode: 200, body: body)
        let result = await collect()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "SERVER_ERROR")
    }

    // MARK: - postMessage (follow-up endpoint)

    private let userMessageFrameJSON = """
    {"type":"user_message","userMessage":{"id":"m1","role":"user","content":"Hi","createdAt":"2026-06-10T12:00:00.123Z"}}
    """

    func testPostMessageRequestShape() async throws {
        let conversationId = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        let expectation = expectation(description: "request inspected")
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/conversations/\(conversationId.uuidString)/messages/stream")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "text/event-stream")
            let body = request.bodyData()
            let decoded = try JSONDecoder().decode(PostMessageRequest.self, from: body)
            XCTAssertEqual(decoded.message, "Tell me more")
            expectation.fulfill()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        _ = await collectPost(conversationId: conversationId, request: PostMessageRequest(message: "Tell me more"))
        await fulfillment(of: [expectation], timeout: 2)
    }

    func testPostMessageHappyPath() async throws {
        let body =
            sseFrame(userMessageFrameJSON)
            + sseFrame("""
            {"type":"delta","text":"Hello"}
            """)
            + sseFrame("""
            {"type":"delta","text":" there"}
            """)
            + sseFrame("""
            {"type":"message","message":{"id":"m2","role":"coach","content":"Hello there","createdAt":"2026-06-10T12:00:01.000Z"}}
            """)
        respond(statusCode: 200, body: body)

        let events = try await collectPost().get()
        XCTAssertEqual(events.count, 4)

        guard case .userMessage(let userMessage) = events[0] else {
            return XCTFail("expected userMessage event")
        }
        XCTAssertEqual(userMessage.role, .user)
        XCTAssertEqual(userMessage.id, "m1")

        guard case .delta(let d1) = events[1], case .delta(let d2) = events[2] else {
            return XCTFail("expected delta events")
        }
        XCTAssertEqual(d1, "Hello")
        XCTAssertEqual(d2, " there")

        guard case .completed(let message) = events[3] else {
            return XCTFail("expected completed event")
        }
        XCTAssertEqual(message.content, "Hello there")
        XCTAssertEqual(message.role, .coach)
    }

    func testPostMessageDateDecodingFractionalSeconds() async throws {
        respond(statusCode: 200, body: sseFrame(userMessageFrameJSON))
        let events = try await collectPost().get()
        guard case .userMessage(let userMessage) = events[0] else {
            return XCTFail("expected userMessage event")
        }
        let expected = ISO8601DateFormatter.fractional.date(from: "2026-06-10T12:00:00.123Z")!
        XCTAssertEqual(userMessage.createdAt, expected)
    }

    func testPostMessageDateDecodingNonFractionalSeconds() async throws {
        let json = """
        {"type":"user_message","userMessage":{"id":"m1","role":"user","content":"Hi","createdAt":"2026-06-10T12:00:00Z"}}
        """
        respond(statusCode: 200, body: sseFrame(json))
        let events = try await collectPost().get()
        guard case .userMessage(let userMessage) = events[0] else {
            return XCTFail("expected userMessage event")
        }
        let expected = ISO8601DateFormatter.plain.date(from: "2026-06-10T12:00:00Z")!
        XCTAssertEqual(userMessage.createdAt, expected)
    }

    func testPostMessageTerminalErrorFrameThrows() async {
        let body =
            sseFrame(userMessageFrameJSON)
            + sseFrame("""
            {"type":"error","error":{"code":"coach_unavailable","message":"unavailable"}}
            """)
        respond(statusCode: 200, body: body)

        let result = await collectPost()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "coach_unavailable")
        XCTAssertEqual(errorResponse.message, "unavailable")
    }

    func testPostMessagePreStream400Throws() async {
        respond(
            statusCode: 400,
            body: #"{"code":"VALIDATION","message":"bad request"}"#,
            contentType: "application/json"
        )
        let result = await collectPost()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "VALIDATION")
    }

    func testPostMessagePreStream401Throws() async {
        respond(
            statusCode: 401,
            body: #"{"code":"unauthorized","message":"nope"}"#,
            contentType: "application/json"
        )
        let result = await collectPost()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "unauthorized")
    }

    func testPostMessagePreStream404NotFoundThrows() async {
        respond(
            statusCode: 404,
            body: #"{"code":"not_found","message":"no conversation"}"#,
            contentType: "application/json"
        )
        let result = await collectPost()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "not_found")
    }

    func testPostMessagePreStream500Throws() async {
        respond(
            statusCode: 500,
            body: #"{"code":"internal","message":"boom"}"#,
            contentType: "application/json"
        )
        let result = await collectPost()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "internal")
    }

    func testPostMessagePreStreamNonJSONThrowsServerError() async {
        respond(statusCode: 500, body: "Internal Server Error", contentType: "text/plain")
        let result = await collectPost()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "SERVER_ERROR")
    }

    func testPostMessageOffContractConversationOpenerThrowsServerError() async {
        respond(statusCode: 200, body: sseFrame(conversationFrameJSON))
        let result = await collectPost()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "SERVER_ERROR")
    }

    func testPostMessageUnknownTypeThrowsServerError() async {
        let body =
            sseFrame(userMessageFrameJSON)
            + sseFrame("""
            {"type":"mystery","text":"???"}
            """)
        respond(statusCode: 200, body: body)
        let result = await collectPost()
        guard case .failure(let error) = result, let errorResponse = error as? ErrorResponse else {
            return XCTFail("expected thrown ErrorResponse")
        }
        XCTAssertEqual(errorResponse.code, "SERVER_ERROR")
    }
}

// MARK: - Test helpers

private extension ISO8601DateFormatter {
    static var fractional: ISO8601DateFormatter {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }

    static var plain: ISO8601DateFormatter {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }
}

private extension URLRequest {
    /// Reads the body from either `httpBody` or the `httpBodyStream`
    /// (URLSession.bytes/data moves the body into a stream).
    func bodyData() -> Data {
        if let body = httpBody {
            return body
        }
        guard let stream = httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }
}
