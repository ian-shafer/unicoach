import XCTest
@testable import UnicoachiOS

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

class AuthClientTests: XCTestCase {
    var authClient: AuthClient!
    var session: URLSession!
    
    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        authClient = AuthClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
    }
    
    func testSuccessfulRegistration() async throws {
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
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
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
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
        let expectedUser = PublicUser(id: UUID(), email: "test@example.com", name: "Test")
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
}
