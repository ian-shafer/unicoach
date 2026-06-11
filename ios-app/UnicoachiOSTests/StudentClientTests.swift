import XCTest
@testable import UnicoachiOS

private extension URLRequest {
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

class StudentClientTests: XCTestCase {
    var studentClient: StudentClient!
    var session: URLSession!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        studentClient = StudentClient(apiClient: apiClient)
    }

    private func makeStudent() -> PublicStudent {
        PublicStudent(
            id: UUID(),
            expectedHighSchoolGraduationDate: "2028-06-15",
            version: 1,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            updatedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }

    /// Encode a `StudentResponse` with ISO-8601 string timestamps, matching the
    /// real server wire format — NOT the numeric default that masked the decode bug.
    private func encodedResponse(_ student: PublicStudent) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(StudentResponse(student: student))
    }

    func testCreateStudentSuccessReturnsPublicStudent() async throws {
        let expected = makeStudent()
        let responseData = try encodedResponse(expected)

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

            let body = try JSONDecoder().decode(CreateStudentRequest.self, from: request.resolvedBody ?? Data())
            XCTAssertEqual(body.expectedHighSchoolGraduationDate, "2028-06-15")

            let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let result = try await studentClient.createStudent(request: CreateStudentRequest(expectedHighSchoolGraduationDate: "2028-06-15"))
        XCTAssertEqual(result, expected)
    }

    func testCreateStudentValidationError() async throws {
        let errorPayload = ErrorResponse(
            code: "VALIDATION_ERROR",
            message: "Validation failed",
            fieldErrors: [FieldError(field: "expectedHighSchoolGraduationDate", message: "Invalid date")]
        )
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 400, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await studentClient.createStudent(request: CreateStudentRequest(expectedHighSchoolGraduationDate: "bad"))
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "VALIDATION_ERROR")
            XCTAssertEqual(error.fieldError(for: "expectedHighSchoolGraduationDate"), "Invalid date")
        }
    }

    func testCreateStudentAlreadyExists() async throws {
        let errorPayload = ErrorResponse(code: "STUDENT_ALREADY_EXISTS", message: "Already exists", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await studentClient.createStudent(request: CreateStudentRequest(expectedHighSchoolGraduationDate: "2028"))
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "STUDENT_ALREADY_EXISTS")
        }
    }

    func testCreateStudentUnauthorized() async throws {
        let errorPayload = ErrorResponse(code: "UNAUTHORIZED", message: "Unauthorized", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await studentClient.createStudent(request: CreateStudentRequest(expectedHighSchoolGraduationDate: "2028"))
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "UNAUTHORIZED")
        }
    }

    func testFetchProfileSuccess() async throws {
        let expected = makeStudent()
        let responseData = try encodedResponse(expected)

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students/me")
            XCTAssertEqual(request.httpMethod, "GET")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let result = try await studentClient.fetchProfile()
        XCTAssertEqual(result, expected)
    }

    func testFetchProfileNotFoundReturnsNil() async throws {
        let errorPayload = ErrorResponse(code: "STUDENT_NOT_FOUND", message: "Not found", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 404, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        let result = try await studentClient.fetchProfile()
        XCTAssertNil(result)
    }

    func testFetchProfileUnauthorizedThrows() async throws {
        let errorPayload = ErrorResponse(code: "UNAUTHORIZED", message: "Unauthorized", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await studentClient.fetchProfile()
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "UNAUTHORIZED")
        }
    }

    // MARK: - Real-wire-format decoding (boundary fidelity)
    //
    // The server serializes `createdAt`/`updatedAt` as ISO-8601 strings with
    // fractional seconds (Jackson `JavaTimeModule`), NOT the numeric timestamps
    // Swift's default `Date` coding produces. These build the response body from
    // that real shape, so they reproduce the runtime decode failures that the
    // round-tripped-fixture tests above could never catch.

    func testCreateStudentDecodesRealServerTimestamps() async throws {
        let fixture = RandomFixtures.studentResponseJSON(seed: RandomFixtures.freshSeed())

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!
            return (response, fixture.data)
        }

        let result = try await studentClient.createStudent(
            request: CreateStudentRequest(expectedHighSchoolGraduationDate: fixture.gradDate)
        )
        XCTAssertEqual(result.id, fixture.id)
        XCTAssertEqual(result.expectedHighSchoolGraduationDate, fixture.gradDate)
    }

    func testFetchProfileDecodesRealServerTimestamps() async throws {
        let fixture = RandomFixtures.studentResponseJSON(seed: RandomFixtures.freshSeed())

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, fixture.data)
        }

        let result = try await studentClient.fetchProfile()
        XCTAssertEqual(result?.id, fixture.id)
        XCTAssertEqual(result?.expectedHighSchoolGraduationDate, fixture.gradDate)
    }
}
