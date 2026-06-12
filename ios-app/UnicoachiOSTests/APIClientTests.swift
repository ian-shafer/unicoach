import XCTest
@testable import UnicoachiOS

private struct SamplePayload: Codable, Equatable {
    let value: String
}

private extension URLRequest {
    /// URLSession moves `httpBody` into `httpBodyStream` before the request reaches
    /// a custom URLProtocol, so read whichever is populated.
    var resolvedBody: Data? {
        if let body = httpBody {
            return body
        }
        guard let stream = httpBodyStream else {
            return nil
        }
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
        return data.isEmpty ? nil : data
    }
}

class APIClientTests: XCTestCase {
    var apiClient: APIClient!
    var session: URLSession!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
    }

    func testPostSuccessDecodesBody() async throws {
        let payload = SamplePayload(value: "hello")
        let responseData = try JSONEncoder().encode(payload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let (data, response) = try await apiClient.post("/api/v1/things", body: payload)
        let decoded: SamplePayload = try apiClient.decode(data: data, response: response, expectedStatus: 201)
        XCTAssertEqual(decoded, payload)
    }

    func testDecodeMismatchThrowsDecodeError() async throws {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!
            return (response, "not json".data(using: .utf8)!)
        }

        let (data, response) = try await apiClient.post("/api/v1/things")
        do {
            let _: SamplePayload = try apiClient.decode(data: data, response: response, expectedStatus: 201)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "DECODE_ERROR")
        }
    }

    func testErrorBodyIsThrownThrough() async throws {
        let errorPayload = ErrorResponse(code: "VALIDATION_ERROR", message: "Bad input", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 400, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        let (data, response) = try await apiClient.post("/api/v1/things")
        do {
            let _: SamplePayload = try apiClient.decode(data: data, response: response, expectedStatus: 201)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "VALIDATION_ERROR")
            XCTAssertEqual(error.message, "Bad input")
        }
    }

    func testUnparseableErrorBodyThrowsServerError() async throws {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (response, "Internal Server Error".data(using: .utf8)!)
        }

        let (data, response) = try await apiClient.post("/api/v1/things")
        do {
            let _: SamplePayload = try apiClient.decode(data: data, response: response, expectedStatus: 201)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "SERVER_ERROR")
        }
    }

    func testTimeoutMapsToTimeout() async throws {
        MockURLProtocol.requestHandler = { _ in
            throw NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut, userInfo: nil)
        }

        do {
            _ = try await apiClient.get("/api/v1/things")
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "TIMEOUT")
        }
    }

    func testNetworkErrorMapsToNetworkError() async throws {
        MockURLProtocol.requestHandler = { _ in
            throw NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet, userInfo: nil)
        }

        do {
            _ = try await apiClient.get("/api/v1/things")
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "NETWORK_ERROR")
        }
    }

    func testBodyPostSetsContentType() async throws {
        let payload = SamplePayload(value: "hello")

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertNotNil(request.resolvedBody)
            let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        _ = try await apiClient.post("/api/v1/things", body: payload)
    }

    func testNoBodyPostOmitsContentType() async throws {
        MockURLProtocol.requestHandler = { request in
            XCTAssertNil(request.value(forHTTPHeaderField: "Content-Type"))
            XCTAssertNil(request.resolvedBody)
            let response = HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        _ = try await apiClient.post("/api/v1/things")
    }

    func testExpectAcceptsMatchingStatus() async throws {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        let (data, response) = try await apiClient.post("/api/v1/things")
        try apiClient.expect(data: data, response: response, expectedStatus: 204)
    }

    func testExpectRejectsMismatchedStatus() async throws {
        let errorPayload = ErrorResponse(code: "CONFLICT", message: "Nope", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        let (data, response) = try await apiClient.post("/api/v1/things")
        do {
            try apiClient.expect(data: data, response: response, expectedStatus: 204)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "CONFLICT")
        }
    }

    // MARK: - stream

    private func drain(_ bytes: URLSession.AsyncBytes) async throws -> Data {
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
        }
        return data
    }

    func testStreamRequestShape() async throws {
        let payload = SamplePayload(value: "hello")
        let expectation = expectation(description: "request inspected")
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/stream")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "text/event-stream")
            let body = request.resolvedBody!
            let decoded = try JSONDecoder().decode(SamplePayload.self, from: body)
            XCTAssertEqual(decoded, payload)
            expectation.fulfill()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        let bytes = try await apiClient.stream("/api/v1/stream", body: payload, accept: "text/event-stream", expectedStatus: 200)
        _ = try await drain(bytes)
        await fulfillment(of: [expectation], timeout: 2)
    }

    func testStreamSuccessReplaysBody() async throws {
        let bodyText = "data: one\n\ndata: two\n\n"
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(bodyText.utf8))
        }

        let bytes = try await apiClient.stream("/api/v1/stream", body: SamplePayload(value: "x"), accept: "text/event-stream", expectedStatus: 200)
        let data = try await drain(bytes)
        XCTAssertEqual(String(decoding: data, as: UTF8.self), bodyText)
    }

    func testStreamStatusMismatchThrowsErrorResponse() async throws {
        let errorPayload = ErrorResponse(code: "student_profile_required", message: "no profile", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await apiClient.stream("/api/v1/stream", body: SamplePayload(value: "x"), accept: "text/event-stream", expectedStatus: 200)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "student_profile_required")
        }
    }

    func testStreamStatusMismatchNonJSONThrowsServerError() async throws {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (response, "Internal Server Error".data(using: .utf8)!)
        }

        do {
            _ = try await apiClient.stream("/api/v1/stream", body: SamplePayload(value: "x"), accept: "text/event-stream", expectedStatus: 200)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "SERVER_ERROR")
        }
    }

    func testStreamConnectionTimeoutThrowsTimeout() async throws {
        MockURLProtocol.requestHandler = { _ in
            throw NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut, userInfo: nil)
        }

        do {
            _ = try await apiClient.stream("/api/v1/stream", body: SamplePayload(value: "x"), accept: "text/event-stream", expectedStatus: 200)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "TIMEOUT")
        }
    }

    func testStreamConnectionFailureThrowsNetworkError() async throws {
        MockURLProtocol.requestHandler = { _ in
            throw NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet, userInfo: nil)
        }

        do {
            _ = try await apiClient.stream("/api/v1/stream", body: SamplePayload(value: "x"), accept: "text/event-stream", expectedStatus: 200)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "NETWORK_ERROR")
        }
    }

    // MARK: - transportError

    func testTransportErrorMapsTimedOut() {
        let nsError = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut, userInfo: nil)
        XCTAssertEqual(apiClient.transportError(nsError).code, "TIMEOUT")
    }

    func testTransportErrorMapsOtherToNetworkError() {
        let nsError = NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotConnectToHost, userInfo: nil)
        XCTAssertEqual(apiClient.transportError(nsError).code, "NETWORK_ERROR")
    }
}
