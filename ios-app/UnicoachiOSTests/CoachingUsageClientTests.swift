import XCTest
@testable import UnicoachiOS

final class CoachingUsageClientTests: XCTestCase {
    private var client: CoachingUsageClient!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        client = CoachingUsageClient(apiClient: apiClient)
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        client = nil
        super.tearDown()
    }

    private func respond(status: Int, body: String) {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }
    }

    func testFetchUsageGetsTheStudentScopedPath() async throws {
        let expectation = expectation(description: "request observed")
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students/me/coaching-usage")
            XCTAssertEqual(request.httpMethod, "GET")
            expectation.fulfill()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(#"{"usage":{"usedPercent":0,"exhausted":false,"resetsAt":null}}"#.utf8))
        }

        _ = try await client.fetch()
        await fulfillment(of: [expectation], timeout: 1)
    }

    /// The free-tier branch: a lifetime allowance that never resets, sent as an
    /// explicit `null`.
    func testFetchUsageDecodesANullResetsAt() async throws {
        respond(status: 200, body: #"{"usage":{"usedPercent":42,"exhausted":false,"resetsAt":null}}"#)

        let usage = try await client.fetch()

        XCTAssertEqual(usage.usedPercent, 42)
        XCTAssertFalse(usage.exhausted)
        XCTAssertNil(usage.resetsAt)
    }

    func testFetchUsageDecodesAnIso8601ResetsAt() async throws {
        respond(status: 200, body: """
        {"usage":{"usedPercent":68,"exhausted":false,"resetsAt":"2026-03-14T12:00:00Z"}}
        """)

        let usage = try await client.fetch()

        XCTAssertEqual(usage.resetsAt, ISO8601DateFormatter().date(from: "2026-03-14T12:00:00Z"))
    }

    func testFetchUsageDecodesTheExhaustedMeter() async throws {
        respond(status: 200, body: #"{"usage":{"usedPercent":100,"exhausted":true,"resetsAt":null}}"#)

        let usage = try await client.fetch()

        XCTAssertEqual(usage.usedPercent, 100)
        XCTAssertTrue(usage.exhausted)
    }

    func testFetchUsageSurfacesServerErrorCodes() async throws {
        let cases: [(Int, String)] = [
            (401, "unauthorized"),
            (403, "email_not_verified"),
            (409, "student_profile_required"),
            (500, "internal_error"),
        ]

        for (status, code) in cases {
            respond(status: status, body: #"{"code":"\#(code)","message":"m","fieldErrors":null}"#)
            do {
                _ = try await client.fetch()
                XCTFail("expected a throw for status=[\(status)] code=[\(code)]")
            } catch let error as ErrorResponse {
                XCTAssertEqual(error.code, code)
                XCTAssertEqual(error.status, status)
            }
        }
    }
}
