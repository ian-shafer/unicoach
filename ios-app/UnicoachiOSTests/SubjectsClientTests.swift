import XCTest
@testable import UnicoachiOS

/// Client tests drive the REAL clients through `MockURLProtocol`, so the
/// request building and the DECODING are what is exercised (TESTING.md).
final class SubjectsClientTests: XCTestCase {
    private var subjectsClient: SubjectsClient!
    private var intendedSubjectsClient: IntendedSubjectsClient!

    override func setUp() {
        super.setUp()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        let apiClient = APIClient(
            baseURL: URL(string: "https://example.test")!,
            session: URLSession(configuration: configuration)
        )
        subjectsClient = SubjectsClient(apiClient: apiClient)
        intendedSubjectsClient = IntendedSubjectsClient(apiClient: apiClient)
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    private func respond(_ json: String, status: Int = 200, capture: ((URLRequest) -> Void)? = nil) {
        MockURLProtocol.requestHandler = { request in
            capture?(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (response, Data(json.utf8))
        }
    }

    // MARK: - The picker's list

    func testSearchSendsTheQueryAndDecodesSlugAndName() async throws {
        var url: URL?
        respond(#"{"subjects":[{"slug":"marine-biology","name":"Marine biology"}]}"#) { url = $0.url }
        let subjects = try await subjectsClient.search(query: "marine")
        XCTAssertEqual(url?.query, "q=marine")
        XCTAssertEqual(subjects, [PublicSubject(slug: "marine-biology", name: "Marine biology")])
    }

    /// A blank-but-present `q` is a 400 server-side, so a blank query is sent
    /// as no `q` at all — which is the request for the whole list.
    func testABlankQueryIsSentAsNoQueryAtAll() async throws {
        var url: URL?
        respond(#"{"subjects":[]}"#) { url = $0.url }
        _ = try await subjectsClient.search(query: "   ")
        XCTAssertNil(url?.query)
        XCTAssertEqual(url?.path, "/api/v1/subjects")
    }

    func testAFailedListSurfacesTheServerError() async {
        respond(#"{"code":"validation_failed","message":"q must not be blank","fieldErrors":null}"#, status: 400)
        do {
            _ = try await subjectsClient.search(query: "x")
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.knownCode, .validationFailed)
            XCTAssertEqual(error.status, 400)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    // MARK: - The student's answer

    /// The route has no 404: before any write it answers `unanswered` with an
    /// empty list, and the client carries that through as a real answer.
    func testAnUnansweredStudentDecodesAsAnAnswerNotAnAbsence() async throws {
        respond(#"{"intendedSubjects":{"status":"unanswered","subjects":[]}}"#)
        let answer = try await intendedSubjectsClient.fetch()
        XCTAssertEqual(answer.knownStatus, .unanswered)
        XCTAssertTrue(answer.subjects.isEmpty)
    }

    func testAnAnsweredStudentDecodesItsSubjects() async throws {
        respond(#"{"intendedSubjects":{"status":"answered","subjects":[{"slug":"nursing","name":"Nursing"}]}}"#)
        let answer = try await intendedSubjectsClient.fetch()
        XCTAssertEqual(answer.knownStatus, .answered)
        XCTAssertEqual(answer.subjects.map(\.slug), ["nursing"])
    }

    /// The fourth status is a value this client must be able to READ, not only
    /// to write: it is what the chat tool stores for "I don't know yet".
    func testUndecidedIsAReadableStatus() async throws {
        respond(#"{"intendedSubjects":{"status":"undecided","subjects":[]}}"#)
        let answer = try await intendedSubjectsClient.fetch()
        XCTAssertEqual(answer.knownStatus, .undecided)
    }

    // MARK: - The write carries exactly one key

    private func sentBody(_ answer: UpdateIntendedSubjectsRequest.Answer) async throws -> [String: Any] {
        var body: Data?
        // `resolvedBody` (AuthClientTests) rather than `httpBody`: URLSession
        // re-materializes the body as a stream by the time a custom
        // `URLProtocol` sees it, so `httpBody` alone is nil here.
        respond(#"{"intendedSubjects":{"status":"unanswered","subjects":[]}}"#) { body = $0.resolvedBody }
        _ = try await intendedSubjectsClient.update(UpdateIntendedSubjectsRequest(answer))
        let data = try XCTUnwrap(body)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testASubjectSetSendsOnlyTheSubjectsKey() async throws {
        let body = try await sentBody(.subjects(["nursing", "neuroscience"]))
        XCTAssertEqual(Set(body.keys), ["subjects"], "a second key present is a pooled 400")
        XCTAssertEqual(body["subjects"] as? [String], ["nursing", "neuroscience"])
    }

    func testTheEmptySetIsSentAsAnEmptyListNotAsAMissingKey() async throws {
        let body = try await sentBody(.subjects([]))
        XCTAssertEqual(Set(body.keys), ["subjects"])
        XCTAssertEqual(body["subjects"] as? [String], [])
    }

    func testDeclinedSendsOnlyDeclined() async throws {
        let body = try await sentBody(.declined)
        XCTAssertEqual(Set(body.keys), ["declined"])
        XCTAssertEqual(body["declined"] as? Bool, true)
    }

    func testUndecidedSendsOnlyUndecided() async throws {
        let body = try await sentBody(.undecided)
        XCTAssertEqual(Set(body.keys), ["undecided"])
        XCTAssertEqual(body["undecided"] as? Bool, true)
    }
}
