import XCTest
@testable import UnicoachiOS

/// `AppleLoginRequest` is `Encodable`-only (the app never decodes its own
/// outgoing request), so this test-local mirror decodes the wire body instead.
private struct DecodedAppleLoginRequest: Decodable {
    let idToken: String
    let name: String?
}

class MockURLProtocol: URLProtocol {
    nonisolated(unsafe) static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    
    override class func canInit(with request: URLRequest) -> Bool {
        return true
    }
    
    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }
    
    override func startLoading() {
        guard let handler = MockURLProtocol.requestHandler else {
            fatalError("Handler is unavailable.")
        }
        
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }
    
    override func stopLoading() {}
}

/// Resolves a request's body whether URLSession carried it as `httpBody` or
/// re-materialized it as an `httpBodyStream` (which is what a custom
/// `URLProtocol` like `MockURLProtocol` sees). Shared by every client test
/// that asserts on outgoing bytes.
extension URLRequest {
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

class AuthClientTests: XCTestCase {
    var authClient: AuthClient!
    var session: URLSession!
    
    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        authClient = AuthClient(apiClient: apiClient)
    }
    
    func testSuccessfulRegistration() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let responsePayload = RegisterResponse(user: expectedUser)
        let responseData = try JSONEncoder().encode(responsePayload)
        
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/register")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            
            let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }
        
        let request = RegisterRequest(email: "test@example.com", password: "password123", name: "Test")
        let response = try await authClient.register(request: request)
        
        XCTAssertEqual(response.user.email, expectedUser.email)
    }
    
    func testServerError() async throws {
        let errorPayload = ErrorResponse(code: "CONFLICT", message: "Email in use", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)
        
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }
        
        let request = RegisterRequest(email: "test@example.com", password: "password123", name: "Test")
        do {
            _ = try await authClient.register(request: request)
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "CONFLICT")
            XCTAssertEqual(error.message, "Email in use")
        }
    }

    func testLoginSuccess() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let responsePayload = LoginResponse(user: expectedUser)
        let responseData = try JSONEncoder().encode(responsePayload)
        
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/login")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }
        
        let request = LoginRequest(email: "test@example.com", password: "password123")
        let response = try await authClient.login(request: request)
        
        XCTAssertEqual(response.user.email, expectedUser.email)
    }

    func testLoginUnauthorized() async throws {
        let errorPayload = ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)
        
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }
        
        let request = LoginRequest(email: "test@example.com", password: "password123")
        do {
            _ = try await authClient.login(request: request)
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "unauthorized")
        }
    }

    func testLoginServerError() async throws {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (response, "Internal Server Error".data(using: .utf8)!)
        }
        
        let request = LoginRequest(email: "test@example.com", password: "password123")
        do {
            _ = try await authClient.login(request: request)
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "SERVER_ERROR")
        }
    }

    func testLogoutSuccess() async throws {
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/logout")
            XCTAssertEqual(request.httpMethod, "POST")
            
            let response = HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }
        
        try await authClient.logout()
    }

    func testMeSuccess() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let responsePayload = MeResponse(user: expectedUser)
        let responseData = try JSONEncoder().encode(responsePayload)
        
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/me")
            XCTAssertEqual(request.httpMethod, "GET")
            
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }
        
        let response = try await authClient.me()
        XCTAssertEqual(response.user.email, expectedUser.email)
    }

    func testMeUnauthorized() async throws {
        let errorPayload = ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)
        
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }
        
        do {
            _ = try await authClient.me()
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "unauthorized")
        }
    }

    func testResendVerificationSuccess() async throws {
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/resend-verification")
            XCTAssertEqual(request.httpMethod, "POST")

            let response = HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }

        try await authClient.resendVerification()
    }

    func testResendVerificationUnauthorized() async throws {
        let errorPayload = ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            try await authClient.resendVerification()
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "unauthorized")
        }
    }

    func testChangeEmailSuccess() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "new@example.com", name: "Test", emailVerified: false)
        let responsePayload = ChangeEmailResponse(user: expectedUser)
        let responseData = try JSONEncoder().encode(responsePayload)

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/change-email")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let user = try await authClient.changeEmail("new@example.com")
        XCTAssertEqual(user.email, "new@example.com")
        XCTAssertFalse(user.emailVerified)
    }

    func testChangeEmailValidationFailed() async throws {
        let errorPayload = ErrorResponse(
            code: "validation_failed",
            message: "Validation failed.",
            fieldErrors: [FieldError(field: "email", message: "Invalid email address.")]
        )
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 400, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.changeEmail("not-an-email")
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "validation_failed")
            XCTAssertEqual(error.status, 400)
            XCTAssertEqual(error.fieldError(for: "email"), "Invalid email address.")
        }
    }

    func testChangeEmailConflict() async throws {
        let errorPayload = ErrorResponse(code: "conflict", message: "Email already in use.", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.changeEmail("taken@example.com")
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "conflict")
            XCTAssertEqual(error.status, 409)
        }
    }

    /// Reads the request body under `MockURLProtocol`, where `URLRequest.httpBody`
    /// is always nil and the body must be read from the stream instead. A read
    /// error fails the test where it happens: returning the bytes gathered so
    /// far would make a truncated body indistinguishable from a wrongly encoded
    /// one at every call site.
    private func readBody(_ request: URLRequest, file: StaticString = #filePath, line: UInt = #line) -> Data {
        request.httpBody ?? request.httpBodyStream.map { stream -> Data in
            stream.open()
            defer { stream.close() }
            var data = Data()
            let bufferSize = 1024
            let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
            defer { buffer.deallocate() }
            while stream.hasBytesAvailable {
                let read = stream.read(buffer, maxLength: bufferSize)
                if read == 0 { break }
                if read < 0 {
                    XCTFail("Request body stream failed after [\(data.count)] bytes: [\(String(describing: stream.streamError))]", file: file, line: line)
                    return Data()
                }
                data.append(buffer, count: read)
            }
            return data
        } ?? Data()
    }

    func testGoogleSignInSuccess() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "google@example.com", name: "Google", emailVerified: true)
        let responsePayload = LoginResponse(user: expectedUser)
        let responseData = try JSONEncoder().encode(responsePayload)
        let sentToken = "sent-id-token"

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/google")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

            let decoded = try? JSONDecoder().decode(GoogleLoginRequest.self, from: self.readBody(request))
            XCTAssertEqual(decoded?.idToken, sentToken)

            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let response = try await authClient.signIn(with: .google(idToken: sentToken))
        XCTAssertEqual(response.user.email, expectedUser.email)
    }

    func testGoogleSignInUnauthorized() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .google(idToken: "t"))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "unauthorized")
            XCTAssertEqual(error.status, 401)
        }
    }

    func testGoogleSignInEmailNotVerified() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(code: "email_not_verified", message: "Email not verified", fieldErrors: nil))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 403, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .google(idToken: "t"))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "email_not_verified")
            XCTAssertEqual(error.status, 403)
        }
    }

    func testGoogleSignInAccountDisabled() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(code: "account_disabled", message: "Account disabled", fieldErrors: nil))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 403, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .google(idToken: "t"))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "account_disabled")
            XCTAssertEqual(error.status, 403)
        }
    }

    func testGoogleSignInServiceUnavailable() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(code: "service_unavailable", message: "Service unavailable", fieldErrors: nil))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 503, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .google(idToken: "t"))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "service_unavailable")
            XCTAssertEqual(error.status, 503)
        }
    }

    func testGoogleSignInServerError() async throws {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (response, "Internal Server Error".data(using: .utf8)!)
        }

        do {
            _ = try await authClient.signIn(with: .google(idToken: "t"))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "SERVER_ERROR")
        }
    }

    // MARK: - Apple sign-in

    func testAppleSignInSuccess() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "apple@example.com", name: "Apple User", emailVerified: true)
        let responsePayload = LoginResponse(user: expectedUser)
        let responseData = try JSONEncoder().encode(responsePayload)
        let sentToken = "sent-apple-id-token"
        let sentName = "Apple User"

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/apple")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

            let decoded = try? JSONDecoder().decode(DecodedAppleLoginRequest.self, from: self.readBody(request))
            XCTAssertEqual(decoded?.idToken, sentToken)
            XCTAssertEqual(decoded?.name, sentName)

            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let response = try await authClient.signIn(with: .apple(idToken: sentToken, name: sentName))
        XCTAssertEqual(response.user.email, expectedUser.email)
    }

    func testAppleSignInOmitsNameWhenNil() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "apple2@example.com", name: "apple2", emailVerified: true)
        let responsePayload = LoginResponse(user: expectedUser)
        let responseData = try JSONEncoder().encode(responsePayload)
        let sentToken = "sent-apple-id-token"

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            guard let json = try? JSONSerialization.jsonObject(with: self.readBody(request)) as? [String: Any] else {
                XCTFail("Apple sign-in body was not a JSON object")
                return (response, responseData)
            }
            // The token pins the body that was actually encoded, so the missing
            // name below is a proven omission rather than an unread body.
            XCTAssertEqual(json["idToken"] as? String, sentToken)
            // Absent, never an empty string — an empty string is a value
            // PersonName.create would reject, blocking the backend's own
            // email-local-part fallback.
            XCTAssertNil(json["name"], "name key must be omitted, not present as an empty string")

            return (response, responseData)
        }

        _ = try await authClient.signIn(with: .apple(idToken: sentToken, name: nil))
    }

    func testAppleSignInUnauthorized() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .apple(idToken: "t", name: nil))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "unauthorized")
            XCTAssertEqual(error.status, 401)
        }
    }

    func testAppleSignInAccountEmailNotVerified() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(
            code: "account_email_not_verified",
            message: "The matched account's email is not verified",
            fieldErrors: nil
        ))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 403, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .apple(idToken: "t", name: nil))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "account_email_not_verified")
            XCTAssertEqual(error.status, 403)
        }
    }

    func testAppleSignInEmailNotVerified() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(code: "email_not_verified", message: "Email not verified", fieldErrors: nil))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 403, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .apple(idToken: "t", name: nil))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "email_not_verified")
            XCTAssertEqual(error.status, 403)
        }
    }

    func testAppleSignInAccountDisabled() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(code: "account_disabled", message: "Account disabled", fieldErrors: nil))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 403, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .apple(idToken: "t", name: nil))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "account_disabled")
            XCTAssertEqual(error.status, 403)
        }
    }

    func testAppleSignInServiceUnavailable() async throws {
        let errorData = try JSONEncoder().encode(ErrorResponse(code: "service_unavailable", message: "Service unavailable", fieldErrors: nil))

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 503, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await authClient.signIn(with: .apple(idToken: "t", name: nil))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "service_unavailable")
            XCTAssertEqual(error.status, 503)
        }
    }

    func testAppleSignInServerError() async throws {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (response, "Internal Server Error".data(using: .utf8)!)
        }

        do {
            _ = try await authClient.signIn(with: .apple(idToken: "t", name: nil))
            XCTFail("Should have thrown an error")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.code, "SERVER_ERROR")
        }
    }
}
