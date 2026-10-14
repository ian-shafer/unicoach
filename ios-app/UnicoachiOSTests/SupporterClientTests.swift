import XCTest
@testable import UnicoachiOS

/// The two supporter doors over a real `APIClient` (RFC 241): what
/// `POST /api/v1/supporters` accepts as a success, and what
/// `GET /api/v1/supporters/me` does with a word this build has never heard of.
class SupporterClientTests: XCTestCase {
    var supporterClient: SupporterClient!
    var session: URLSession!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        supporterClient = SupporterClient(
            apiClient: APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        )
    }

    /// One home body, with the relationship word as the caller names it — a
    /// raw `String`, so a test may send a word `SupporterRelationship` does not
    /// have.
    private func homeBody(relationship: String, pendingRequests: Int = 0) -> Data {
        Data("""
        {
          "supporter": { "id": "44440000-0000-0000-0000-000000000001" },
          "students": [
            {
              "linkId": "66660000-0000-0000-0000-000000000001",
              "studentId": "55550000-0000-0000-0000-000000000001",
              "name": "Ava Carter",
              "relationship": "\(relationship)",
              "otherSupporterNames": ["Sam Lee"]
            }
          ],
          "pendingRequests": \(pendingRequests)
        }
        """.utf8)
    }

    private func respond(status: Int, body: Data) {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (response, body)
        }
    }

    // MARK: - An idempotent write's two successes

    func testBecomeSupporterAcceptsTheMintedStatus() async throws {
        respond(status: 201, body: homeBody(relationship: "parent"))

        let home = try await supporterClient.becomeSupporter()

        XCTAssertEqual(home.students.count, 1)
    }

    /// The second call on the same account: the row is already there, so the
    /// server answers `200` with the same body. A client that accepted only the
    /// mint status would refuse every call after the first.
    func testBecomeSupporterAcceptsTheAlreadyExistedStatus() async throws {
        respond(status: 200, body: homeBody(relationship: "parent"))

        let home = try await supporterClient.becomeSupporter()

        XCTAssertEqual(home.students.count, 1)
    }

    func testBecomeSupporterThrowsTheServersErrorOnAnyOtherStatus() async throws {
        respond(
            status: 403,
            body: try JSONEncoder().encode(
                ErrorResponse(code: "email_not_verified", message: "Verify your email", fieldErrors: nil)
            )
        )

        do {
            _ = try await supporterClient.becomeSupporter()
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "email_not_verified")
        }
    }

    // MARK: - A relationship word this build does not know

    /// `family/04` adds a fifth word to a vocabulary the DB CHECK owns, and a
    /// parent holding such a link opens an app built before it. The word must
    /// not cost them their home: a `DecodingError` here is not an
    /// `ErrorResponse`, so the root state machine would fall into its catch-all
    /// and show an error screen instead of the roster — over a field no screen
    /// renders.
    func testAnUnknownRelationshipWordStillYieldsAUsableHome() async throws {
        respond(status: 200, body: homeBody(relationship: "sibling"))

        let home = try await supporterClient.fetchHome()

        let students = try XCTUnwrap(home?.students)
        XCTAssertEqual(students.count, 1)
        XCTAssertEqual(students[0].name, "Ava Carter")
        // The word is KEPT, and it is its own case: a caller can tell "the
        // server widened the vocabulary" from "the server said supporter",
        // which folding the two together made impossible.
        XCTAssertEqual(students[0].relationship, .unrecognised("sibling"))
        XCTAssertNotEqual(students[0].relationship, .supporter)
        // And it round-trips verbatim, so the client can never put a word on
        // the wire that the server did not use.
        XCTAssertEqual(students[0].relationship.wireValue, "sibling")
        // "Usable" means the home resolves a working student, which is what the
        // header names and what the selection is written against.
        XCTAssertEqual(SupporterSubject.resolve(roster: students, stored: nil), students[0])
    }

    func testAKnownRelationshipWordDecodesAsItself() async throws {
        respond(status: 200, body: homeBody(relationship: "guardian"))

        let home = try await supporterClient.fetchHome()

        XCTAssertEqual(home?.students.first?.relationship, .guardian)
    }

    /// No `supporters` row is an ANSWER, not a fault — the ordinary reply for
    /// every student.
    func testFetchHomeAnswersNilOnNotFound() async throws {
        respond(status: 404, body: Data("{}".utf8))

        let home = try await supporterClient.fetchHome()

        XCTAssertNil(home)
    }

    // MARK: - The co-supporters and the disconnect (RFC 249)

    func testTheRosterCarriesEachStudentsOtherSupporters() async throws {
        respond(status: 200, body: homeBody(relationship: "parent"))

        let home = try await supporterClient.fetchHome()

        XCTAssertEqual(home?.students.first?.otherSupporterNames, ["Sam Lee"])
    }

    // MARK: - Requests to connect (RFC 254)

    func testTheHomeCarriesTheCountOfUnansweredRequests() async throws {
        respond(status: 200, body: homeBody(relationship: "parent", pendingRequests: 2))

        let home = try await supporterClient.fetchHome()

        XCTAssertEqual(home?.pendingRequests, 2)
    }

    func testDisconnectDeletesTheCallersOwnLinkAndAcceptsNoContent() async throws {
        let linkId = UUID(uuidString: "66660000-0000-0000-0000-000000000001")!
        let seen = Locked<(method: String?, path: String?)>((nil, nil))
        MockURLProtocol.requestHandler = { request in
            seen.withLock { $0 = (request.httpMethod, request.url?.path) }
            let response = HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        let outcome = try await supporterClient.disconnect(linkId: linkId)

        XCTAssertEqual(outcome, .disconnected)
        XCTAssertEqual(seen.current.method, "DELETE")
        XCTAssertEqual(seen.current.path?.lowercased(), "/api/v1/supporters/me/students/\(linkId.uuidString.lowercased())")
    }

    func testDisconnectAnswersTheServersNotFoundAsAlreadyGone() async throws {
        respond(
            status: 404,
            body: Data(#"{"code":"supporter_link_not_found","message":"Not found"}"#.utf8)
        )

        let outcome = try await supporterClient.disconnect(linkId: UUID())

        XCTAssertEqual(outcome, .alreadyGone)
    }
}
