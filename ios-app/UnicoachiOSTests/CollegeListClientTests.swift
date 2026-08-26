import XCTest
@testable import UnicoachiOS

/// Drives the real `CollegeListClient` through `MockURLProtocol` so request
/// building, status handling, and DECODING are exercised on real bytes — the
/// boundary-fidelity rule in TESTING.md. Fixture timestamps are ISO-8601
/// strings with fractional seconds, exactly as Jackson emits them.
class CollegeListClientTests: XCTestCase {
    var client: CollegeListClient!
    var session: URLSession!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        client = CollegeListClient(apiClient: apiClient)
    }

    private let entryId = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000001")!
    private let collegeId = UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000001")!

    /// The server's wire shape for one entry, as `CollegeListRoutes` emits it —
    /// microsecond-fractional ISO-8601 timestamps and the RFC 137 `collegeName`.
    private func entryJSON(
        status: String = "considering",
        reasons: String = "null",
        version: Int = 1,
        observations: String = "[]"
    ) -> String {
        """
        {"id":"\(entryId.uuidString)","collegeId":"\(collegeId.uuidString)",
         "collegeName":"Columbia University","status":"\(status)","reasons":\(reasons),
         "version":\(version),"createdAt":"2025-01-07T22:16:27.092942Z",
         "updatedAt":"2025-01-07T22:16:27Z","supportingObservations":\(observations)}
        """
    }

    // MARK: - listEntries

    func testListEntriesDecodesRealServerShape() async throws {
        let body = """
        {"entries":[\(entryJSON(
            reasons: "\"Strong program\"",
            observations: "[{\"id\":7,\"quote\":\"I loved the campus\",\"utteredAt\":\"2025-01-07T22:16:27.092942Z\"}]"
        ))]}
        """
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students/me/college-list")
            XCTAssertEqual(request.httpMethod, "GET")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }

        let entries = try await client.listEntries()
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0].id, entryId)
        XCTAssertEqual(entries[0].collegeName, "Columbia University")
        XCTAssertEqual(entries[0].status, .considering)
        XCTAssertEqual(entries[0].reasons, "Strong program")
        XCTAssertEqual(entries[0].supportingObservations.count, 1)
        XCTAssertEqual(entries[0].supportingObservations[0].id, 7)
        XCTAssertEqual(entries[0].supportingObservations[0].quote, "I loved the campus")
    }

    func testListEntriesUnknownStatusFailsDecodingLoudly() async throws {
        let body = """
        {"entries":[\(entryJSON(status: "waitlisted"))]}
        """
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }

        do {
            _ = try await client.listEntries()
            XCTFail("An unknown status must fail decoding, not default")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "DECODE_ERROR")
        }
    }

    // MARK: - addEntry

    func testAddEntryPostsCollegeIdOnlyAndDecodes201() async throws {
        let body = "{\"entry\":\(entryJSON())}"
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students/me/college-list")
            XCTAssertEqual(request.httpMethod, "POST")
            let json = try XCTUnwrap(
                try JSONSerialization.jsonObject(with: request.resolvedBody ?? Data()) as? [String: Any]
            )
            XCTAssertEqual(json["collegeId"] as? String, self.collegeId.uuidString)
            // Status defaults server-side; citations are chat-only (RFC 137).
            XCTAssertNil(json["status"])
            XCTAssertNil(json["observationIds"])
            let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }

        let entry = try await client.addEntry(collegeId: collegeId)
        XCTAssertEqual(entry.collegeId, collegeId)
    }

    func testAddEntryDuplicateThrowsServerConflict() async throws {
        let errorData = try JSONEncoder().encode(
            ErrorResponse(code: "conflict", message: "College is already on the list", fieldErrors: nil)
        )
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await client.addEntry(collegeId: collegeId)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.knownCode, .conflict)
            XCTAssertEqual(error.message, "College is already on the list")
        }
    }

    // MARK: - updateEntry

    func testUpdateEntryPatchesStatusVersionAndReasons() async throws {
        let body = "{\"entry\":\(entryJSON(status: "admitted", version: 2))}"
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students/me/college-list/\(self.entryId.uuidString)")
            XCTAssertEqual(request.httpMethod, "PATCH")
            let json = try XCTUnwrap(
                try JSONSerialization.jsonObject(with: request.resolvedBody ?? Data()) as? [String: Any]
            )
            XCTAssertEqual(json["version"] as? Int, 1)
            XCTAssertEqual(json["status"] as? String, "admitted")
            XCTAssertEqual(json["reasons"] as? String, "Got in!")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }

        let entry = try await client.updateEntry(id: entryId, version: 1, status: .admitted, reasons: "Got in!")
        XCTAssertEqual(entry.status, .admitted)
        XCTAssertEqual(entry.version, 2)
    }

    func testUpdateEntryVersionConflictThrowsVersionConflict() async throws {
        let errorData = try JSONEncoder().encode(
            ErrorResponse(code: "version_conflict", message: "College list entry was modified concurrently", fieldErrors: nil)
        )
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await client.updateEntry(id: entryId, version: 1, status: .applying, reasons: nil)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.knownCode, .versionConflict)
        }
    }

    // MARK: - removeEntry

    func testRemoveEntrySendsVersionQueryAndAccepts204() async throws {
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students/me/college-list/\(self.entryId.uuidString)")
            XCTAssertEqual(request.url?.query, "version=3")
            XCTAssertEqual(request.httpMethod, "DELETE")
            let response = HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        try await client.removeEntry(id: entryId, version: 3)
    }

    // MARK: - searchColleges

    func testSearchCollegesEncodesQueryAndDecodesSummaries() async throws {
        let body = """
        {"colleges":[{"id":"CCCCCCCC-0000-0000-0000-000000000001",
         "name":"Columbia University","city":"New York","state":"NY"}]}
        """
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/colleges")
            XCTAssertEqual(request.url?.query(percentEncoded: false), "q=col umbia & co")
            XCTAssertEqual(request.httpMethod, "GET")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }

        let colleges = try await client.searchColleges(query: "col umbia & co")
        XCTAssertEqual(colleges.count, 1)
        XCTAssertEqual(colleges[0].name, "Columbia University")
        XCTAssertEqual(colleges[0].city, "New York")
        XCTAssertEqual(colleges[0].state, "NY")
    }
}
