import XCTest
@testable import UnicoachiOS

/// The parent's coach doors (RFC 258) over a real `APIClient`: the four paths,
/// the body that names the student, the frames — decoded by the SAME decoder the
/// student's stream uses — and the refusals, decoded as the server sends them.
final class SupporterConversationClientTests: XCTestCase {
    private var client: SupporterConversationClient!

    private let studentId = UUID(uuidString: "55550000-0000-0000-0000-000000000001")!
    private let convoId = UUID(uuidString: "01A17500-0000-7000-8000-000000000001")!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: URLSession(configuration: config))
        client = SupporterConversationClient(apiClient: apiClient)
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        client = nil
        super.tearDown()
    }

    private func respond(status: Int, body: String, contentType: String) {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: status,
                httpVersion: nil,
                headerFields: ["Content-Type": contentType]
            )!
            return (response, Data(body.utf8))
        }
    }

    private func collect(_ stream: AsyncThrowingStream<ConversationStreamEvent, Error>) async -> Result<[ConversationStreamEvent], Error> {
        do {
            var events: [ConversationStreamEvent] = []
            for try await event in stream {
                events.append(event)
            }
            return .success(events)
        } catch {
            return .failure(error)
        }
    }

    /// The server's own frames for a parent's start, as `SupporterConvoRoutes`
    /// writes them through the shared writer.
    private var startBody: String {
        """
        event: conversation
        data: {"conversation":{"id":"\(convoId.uuidString.lowercased())","name":"What would Ava's list cost our family?","createdAt":"2026-10-26T01:00:00.123456Z","updatedAt":"2026-10-26T01:00:00.123456Z","lastActivityAt":"2026-10-26T01:00:00.2Z","archivedAt":null},"userMessage":{"id":"u_1","role":"user","content":"What would Ava's list cost our family?","createdAt":"2026-10-26T01:00:00.2Z"},"type":"conversation"}

        event: delta
        data: {"text":"Here","type":"delta"}

        event: message
        data: {"message":{"id":"c_2","role":"coach","content":"Here is what it costs.","createdAt":"2026-10-26T01:00:01Z"},"type":"message"}


        """
    }

    func testListGetsTheSupporterPathAndDecodesTheServedShape() async throws {
        let expectation = expectation(description: "request observed")
        let body = """
        {"conversations":[{"id":"\(convoId.uuidString.lowercased())","studentId":"\(studentId.uuidString.lowercased())","name":"Costs","createdAt":"2026-10-26T01:00:00.123456Z","updatedAt":"2026-10-26T01:00:00Z"}]}
        """
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/supporters/me/conversations")
            XCTAssertEqual(request.httpMethod, "GET")
            expectation.fulfill()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }

        let conversations = try await client.listConversations()

        await fulfillment(of: [expectation], timeout: 1)
        XCTAssertEqual(conversations.map(\.id), [convoId])
        XCTAssertEqual(conversations.first?.studentId, studentId)
        XCTAssertEqual(conversations.first?.name, "Costs")
    }

    func testStartPostsTheStudentAndMessageToTheStreamPath() async throws {
        let expectation = expectation(description: "request inspected")
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/supporters/me/conversations/stream")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "text/event-stream, application/json")
            let decoded = try JSONDecoder().decode(CreateSupporterConversationRequest.self, from: try XCTUnwrap(request.resolvedBody))
            XCTAssertEqual(decoded.studentId, self.studentId)
            XCTAssertEqual(decoded.message, "What would Ava's list cost our family?")
            expectation.fulfill()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        _ = await collect(client.startConversation(studentId: studentId, message: "What would Ava's list cost our family?"))
        await fulfillment(of: [expectation], timeout: 2)
    }

    func testStartDecodesConversationDeltaMessageInOrder() async throws {
        respond(status: 200, body: startBody, contentType: "text/event-stream")

        let events = try await collect(client.startConversation(studentId: studentId, message: "m")).get()

        XCTAssertEqual(events.count, 3)
        guard case .conversation(let conversation, let userMessage) = events[0] else { return XCTFail("expected conversation, got \(events[0])") }
        XCTAssertEqual(conversation.id, convoId)
        XCTAssertEqual(userMessage.role, .user)
        guard case .delta(let text) = events[1] else { return XCTFail("expected delta") }
        XCTAssertEqual(text, "Here")
        guard case .completed(let message) = events[2] else { return XCTFail("expected message") }
        XCTAssertEqual(message.content, "Here is what it costs.")
    }

    func testPostGoesToTheConversationsStreamPathAndRefusesAConversationOpener() async throws {
        let paths = Locked<[String]>([])
        MockURLProtocol.requestHandler = { request in
            paths.withLock { $0.append(request.url?.path ?? "") }
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "text/event-stream"])!
            return (response, Data(self.startBody.utf8))
        }

        let result = await collect(client.postMessage(conversationId: convoId, request: PostMessageRequest(message: "And then?")))

        XCTAssertEqual(paths.current, ["/api/v1/supporters/me/conversations/\(convoId.uuidString)/messages/stream"])
        guard case .failure(let error as ErrorResponse) = result else { return XCTFail("a conversation frame is off-contract on a turn") }
        XCTAssertEqual(error.code, ErrorResponse.unexpected.code)
    }

    func testFetchMessagesReadsTheTranscriptPath() async throws {
        let expectation = expectation(description: "request observed")
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/supporters/me/conversations/\(self.convoId.uuidString)/messages")
            expectation.fulfill()
            let body = #"{"messages":[{"id":"u_1","role":"user","content":"Hi","createdAt":"2026-10-26T01:00:00Z"},{"id":"c_2","role":"coach","content":"Hello","createdAt":"2026-10-26T01:00:01.5Z"}]}"#
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }

        let messages = try await client.fetchMessages(conversationId: convoId)

        await fulfillment(of: [expectation], timeout: 1)
        XCTAssertEqual(messages.map(\.role), [.user, .coach])
    }

    /// Every refusal is plain JSON before any SSE byte; each decodes to the code
    /// `ConversationViewModel` acts on.
    func testPreStreamRefusalsDecodeToTheirCodes() async {
        let cases: [(Int, String, ServerErrorCode)] = [
            (404, "student_not_found", .studentNotFound),
            (404, "supporter_not_found", .supporterNotFound),
            (404, "not_found", .notFound),
            (402, "coaching_budget_exhausted", .coachingBudgetExhausted),
        ]
        for (status, code, known) in cases {
            respond(status: status, body: #"{"code":"\#(code)","message":"m","fieldErrors":null}"#, contentType: "application/json")

            let result = await collect(client.startConversation(studentId: studentId, message: "m"))

            guard case .failure(let error as ErrorResponse) = result else {
                XCTFail("expected a refusal for [\(code)]")
                continue
            }
            XCTAssertEqual(error.knownCode, known, "code [\(code)]")
            XCTAssertEqual(error.status, status)
        }
    }

    func testTheThreadClientNamesItsStudentOnStartAndTheConversationOnATurn() async {
        let mock = MockSupporterConversationClient()
        let thread = SupporterThreadClient(client: mock, studentId: studentId)

        _ = await collect(thread.streamConversation(request: CreateConversationRequest(message: "Start", name: nil)))
        _ = await collect(thread.postMessage(conversationId: convoId, request: PostMessageRequest(message: "Next")))

        XCTAssertEqual(mock.startRequests.map(\.studentId), [studentId])
        XCTAssertEqual(mock.startRequests.map(\.message), ["Start"])
        XCTAssertEqual(mock.postRequests.map(\.conversationId), [convoId])
    }
}
