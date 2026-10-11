import XCTest
@testable import UnicoachiOS

/// The student's family doors over a real `APIClient` (RFC 249): the list of
/// connected adults and the disconnect.
class StudentSupportersClientTests: XCTestCase {
    var client: SupporterInviteClient!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        client = SupporterInviteClient(
            apiClient: APIClient(baseURL: URL(string: "http://localhost:8080")!, session: URLSession(configuration: config))
        )
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    private func respond(status: Int, body: Data, seen: Locked<(method: String?, path: String?)>? = nil) {
        MockURLProtocol.requestHandler = { request in
            seen?.withLock { $0 = (request.httpMethod, request.url?.path) }
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (response, body)
        }
    }

    /// The body as the server's RFC 249 table states it.
    func testFetchSupportersDecodesTheServedList() async throws {
        let seen = Locked<(method: String?, path: String?)>((nil, nil))
        respond(status: 200, body: Data("""
        {
          "supporters": [
            { "linkId": "66660000-0000-0000-0000-000000000001", "name": "Robin Carter" },
            { "linkId": "66660000-0000-0000-0000-000000000002", "name": "Sam Lee" }
          ],
          "ceiling": {
            "canSee": ["Your college list and key application deadlines."],
            "staysPrivate": ["Your coach conversations. Transcripts are never shared."]
          },
          "costReportLinkLive": true
        }
        """.utf8), seen: seen)

        let answer = try await client.fetchSupporters()

        XCTAssertEqual(seen.current.method, "GET")
        XCTAssertEqual(seen.current.path, "/api/v1/students/me/supporters")
        XCTAssertEqual(answer.supporters.map(\.name), ["Robin Carter", "Sam Lee"])
        XCTAssertEqual(answer.supporters.first?.linkId, UUID(uuidString: "66660000-0000-0000-0000-000000000001"))
        XCTAssertEqual(answer.ceiling.canSee, ["Your college list and key application deadlines."])
        XCTAssertEqual(answer.ceiling.staysPrivate, ["Your coach conversations. Transcripts are never shared."])
        XCTAssertTrue(answer.costReportLinkLive)
    }

    func testDisconnectSupporterDeletesThatLinkAndAcceptsNoContent() async throws {
        let linkId = UUID(uuidString: "66660000-0000-0000-0000-000000000002")!
        let seen = Locked<(method: String?, path: String?)>((nil, nil))
        respond(status: 204, body: Data(), seen: seen)

        let outcome = try await client.disconnectSupporter(linkId: linkId)

        XCTAssertEqual(outcome, .disconnected)
        XCTAssertEqual(seen.current.method, "DELETE")
        XCTAssertEqual(seen.current.path?.lowercased(), "/api/v1/students/me/supporters/\(linkId.uuidString.lowercased())")
    }

    func testDisconnectSupporterAnswersTheServersNotFoundAsAlreadyGone() async throws {
        respond(status: 404, body: Data(#"{"code":"supporter_link_not_found","message":"Not found"}"#.utf8))

        let outcome = try await client.disconnectSupporter(linkId: UUID())

        XCTAssertEqual(outcome, .alreadyGone)
    }

    /// Keyed on the code: a 404 that is not the server's link answer — a
    /// gateway's, say — is a fault, not "already disconnected".
    func testDisconnectSupporterThrowsAnyOtherNotFound() async throws {
        respond(status: 404, body: Data(#"{"code":"not_found","message":"Not found"}"#.utf8))

        do {
            _ = try await client.disconnectSupporter(linkId: UUID())
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.status, 404)
        }
    }
}
