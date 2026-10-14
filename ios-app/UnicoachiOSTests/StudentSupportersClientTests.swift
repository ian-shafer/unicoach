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
          "costReportLinkLive": true,
          "requests": [
            {
              "requestId": "77770000-0000-0000-0000-000000000001",
              "name": "Jordan Lee",
              "email": "jordan@example.com",
              "requestedAt": "2026-10-11T09:30:00Z"
            }
          ]
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
        XCTAssertEqual(answer.requests, [
            SupporterRequest(
                requestId: UUID(uuidString: "77770000-0000-0000-0000-000000000001")!,
                name: "Jordan Lee",
                email: "jordan@example.com"
            ),
        ])
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

    // MARK: - Requests to connect (RFC 254)

    private let requestId = UUID(uuidString: "77770000-0000-0000-0000-000000000001")!

    func testApprovePostsThatRequestAndAnswersTheNewConnection() async throws {
        let seen = Locked<(method: String?, path: String?)>((nil, nil))
        respond(
            status: 200,
            body: Data(#"{"linkId":"66660000-0000-0000-0000-000000000003","name":"Jordan Lee"}"#.utf8),
            seen: seen
        )

        let outcome = try await client.approveSupporterRequest(requestId: requestId)

        XCTAssertEqual(outcome, .connected(LinkedSupporter(
            linkId: UUID(uuidString: "66660000-0000-0000-0000-000000000003")!,
            name: "Jordan Lee"
        )))
        XCTAssertEqual(seen.current.method, "POST")
        XCTAssertEqual(
            seen.current.path?.lowercased(),
            "/api/v1/students/me/supporter-requests/\(requestId.uuidString.lowercased())/approve"
        )
    }

    func testApproveAnswersTheServersNotFoundAsNoLongerPending() async throws {
        respond(status: 404, body: Data(#"{"code":"supporter_request_not_found","message":"Not found"}"#.utf8))

        let outcome = try await client.approveSupporterRequest(requestId: requestId)

        XCTAssertEqual(outcome, .noLongerPending)
    }

    /// Keyed on the request's own code: the LINK's not-found from the
    /// neighbouring door is a fault here, not "no longer pending".
    func testApproveThrowsAnyOtherNotFound() async throws {
        respond(status: 404, body: Data(#"{"code":"supporter_link_not_found","message":"Not found"}"#.utf8))

        do {
            _ = try await client.approveSupporterRequest(requestId: requestId)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.status, 404)
        }
    }

    func testDeclinePostsThatRequestAndAcceptsNoContent() async throws {
        let seen = Locked<(method: String?, path: String?)>((nil, nil))
        respond(status: 204, body: Data(), seen: seen)

        let outcome = try await client.declineSupporterRequest(requestId: requestId)

        XCTAssertEqual(outcome, .declined)
        XCTAssertEqual(seen.current.method, "POST")
        XCTAssertEqual(
            seen.current.path?.lowercased(),
            "/api/v1/students/me/supporter-requests/\(requestId.uuidString.lowercased())/decline"
        )
    }

    func testDeclineAnswersTheServersNotFoundAsNoLongerPending() async throws {
        respond(status: 404, body: Data(#"{"code":"supporter_request_not_found","message":"Not found"}"#.utf8))

        let outcome = try await client.declineSupporterRequest(requestId: requestId)

        XCTAssertEqual(outcome, .noLongerPending)
    }

    func testDeclineThrowsAnyOtherNotFound() async throws {
        respond(status: 404, body: Data(#"{"code":"not_found","message":"Not found"}"#.utf8))

        do {
            _ = try await client.declineSupporterRequest(requestId: requestId)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.status, 404)
        }
    }
}
