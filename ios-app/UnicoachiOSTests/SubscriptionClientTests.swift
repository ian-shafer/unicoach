import XCTest
@testable import UnicoachiOS

/// Drives the real `SubscriptionClient` through `MockURLProtocol`, so request
/// building, status handling and decoding are all exercised for real.
final class SubscriptionClientTests: XCTestCase {
    private var client: SubscriptionClient!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        client = SubscriptionClient(apiClient: apiClient)
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

    private func errorBody(_ code: String) -> String {
        """
        {"code":"\(code)","message":"something","fieldErrors":null}
        """
    }

    func testVerifyPostsTheSignedTransaction() async throws {
        let expectation = expectation(description: "request observed")
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/subscriptions/verify")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

            // URLProtocol strips httpBody into httpBodyStream, so read it back.
            let body = request.httpBody ?? request.httpBodyStream.map { stream -> Data in
                stream.open()
                defer { stream.close() }
                var data = Data()
                let size = 4096
                let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: size)
                defer { buffer.deallocate() }
                while stream.hasBytesAvailable {
                    let read = stream.read(buffer, maxLength: size)
                    if read <= 0 { break }
                    data.append(buffer, count: read)
                }
                return data
            } ?? Data()
            let decoded = try JSONDecoder().decode(SubscriptionVerifyRequest.self, from: body)
            XCTAssertEqual(decoded.signedTransaction, "the.signed.jws")
            expectation.fulfill()

            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let payload = """
            {"subscription":{"status":"active","productId":"coach.uni.UnicoachiOS.monthly10","currentPeriodEnd":"2026-03-14T12:00:00Z"}}
            """
            return (response, Data(payload.utf8))
        }

        _ = try await client.verify(signedTransaction: "the.signed.jws")
        await fulfillment(of: [expectation], timeout: 1)
    }

    func testVerifyDecodesTheSubscription() async throws {
        respond(status: 200, body: """
        {"subscription":{"status":"active","productId":"coach.uni.UnicoachiOS.monthly10","currentPeriodEnd":"2026-03-14T12:00:00Z"}}
        """)

        let subscription = try await client.verify(signedTransaction: "jws")

        XCTAssertEqual(subscription.status, "active")
        XCTAssertEqual(subscription.knownStatus, .active)
        XCTAssertEqual(subscription.productId, "coach.uni.UnicoachiOS.monthly10")
        XCTAssertEqual(
            subscription.currentPeriodEnd,
            ISO8601DateFormatter().date(from: "2026-03-14T12:00:00Z")
        )
    }

    func testVerifyDecodesEveryKnownStatus() async throws {
        for (wire, expected) in [
            ("active", SubscriptionStatus.active),
            ("expired", .expired),
            ("grace", .grace),
            ("revoked", .revoked),
            ("billing_retry", .billingRetry),
        ] {
            respond(status: 200, body: """
            {"subscription":{"status":"\(wire)","productId":"p","currentPeriodEnd":"2026-03-14T12:00:00Z"}}
            """)
            let subscription = try await client.verify(signedTransaction: "jws")
            XCTAssertEqual(subscription.knownStatus, expected, "for wire status [\(wire)]")
        }
    }

    /// The tolerance that keeps a display concern from becoming a hard failure
    /// of the whole response: an unknown status decodes, it just has no case.
    func testVerifyDecodesAnUnknownStatusWithoutFailing() async throws {
        respond(status: 200, body: """
        {"subscription":{"status":"paused_by_apple","productId":"p","currentPeriodEnd":"2026-03-14T12:00:00Z"}}
        """)

        let subscription = try await client.verify(signedTransaction: "jws")

        XCTAssertEqual(subscription.status, "paused_by_apple")
        XCTAssertNil(subscription.knownStatus)
    }

    func testVerifyDecodesFractionalSecondTimestamps() async throws {
        respond(status: 200, body: """
        {"subscription":{"status":"active","productId":"p","currentPeriodEnd":"2026-03-14T12:00:00.123456Z"}}
        """)

        let subscription = try await client.verify(signedTransaction: "jws")

        XCTAssertNotNil(subscription.currentPeriodEnd)
    }

    func testVerifySurfacesEveryErrorCode() async throws {
        let cases: [(Int, String)] = [
            (400, "validation_failed"),
            (401, "unauthorized"),
            (403, "email_not_verified"),
            (404, "subscription_not_found"),
            (409, "subscription_owned_by_other_account"),
            (409, "student_profile_required"),
            (413, "payload_too_large"),
            (500, "internal_error"),
            (503, "service_unavailable"),
        ]

        for (status, code) in cases {
            respond(status: status, body: errorBody(code))
            do {
                _ = try await client.verify(signedTransaction: "jws")
                XCTFail("expected a throw for status=[\(status)] code=[\(code)]")
            } catch let error as ErrorResponse {
                XCTAssertEqual(error.code, code)
                XCTAssertEqual(error.status, status)
            }
        }
    }

    /// The server emits `fieldErrors` explicitly as `null`; a non-optional
    /// decode of it would break every error path.
    func testVerifyDecodesAnErrorWithNullFieldErrors() async throws {
        respond(status: 401, body: #"{"code":"unauthorized","message":"Not authenticated","fieldErrors":null}"#)

        do {
            _ = try await client.verify(signedTransaction: "jws")
            XCTFail("expected a throw")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "unauthorized")
            XCTAssertNil(error.fieldErrors)
        }
    }

    func testVerifyCarriesFieldErrors() async throws {
        respond(status: 400, body: """
        {"code":"validation_failed","message":"Invalid signed transaction","fieldErrors":[{"field":"signedTransaction","message":"not a decodable JWS"}]}
        """)

        do {
            _ = try await client.verify(signedTransaction: "jws")
            XCTFail("expected a throw")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.knownCode, .validationFailed)
            XCTAssertEqual(error.fieldError(for: "signedTransaction"), "not a decodable JWS")
        }
    }
}
