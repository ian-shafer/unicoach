import XCTest
@testable import UnicoachiOS

/// The REAL `SharingPermissionsClient` through `MockURLProtocol`, so the paths,
/// the body and the decoding of all three wire statuses are what is exercised
/// (TESTING.md, "Boundary fidelity").
final class SharingPermissionsClientTests: XCTestCase {
    private var client: SharingPermissionsClient!

    override func setUp() {
        super.setUp()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        let apiClient = APIClient(
            baseURL: URL(string: "https://example.test")!,
            session: URLSession(configuration: configuration)
        )
        client = SharingPermissionsClient(apiClient: apiClient)
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    private func respond(_ json: String, status: Int = 200, capture: ((URLRequest) -> Void)? = nil) {
        MockURLProtocol.requestHandler = { request in
            capture?(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (response, Data(json.utf8))
        }
    }

    private func fetchedStatus(_ wire: String) async throws -> SharingPermissionStatus? {
        var request: URLRequest?
        respond(#"{"permissions":[{"permission":"intended_subjects","status":"\#(wire)"}]}"#) { request = $0 }
        let permissions = try await client.fetch()
        XCTAssertEqual(request?.httpMethod, "GET")
        XCTAssertEqual(request?.url?.path, "/api/v1/students/me/sharing-permissions")
        XCTAssertEqual(permissions.map(\.knownPermission), [.intendedSubjects])
        return permissions.first?.knownStatus
    }

    func testEachWireStatusDecodes() async throws {
        let notAsked = try await fetchedStatus("not_asked")
        XCTAssertEqual(notAsked, .notAsked)
        let granted = try await fetchedStatus("granted")
        XCTAssertEqual(granted, .granted)
        let refused = try await fetchedStatus("refused")
        XCTAssertEqual(refused, .refused)
    }

    /// A status added server-side is read as unknown, never a decode failure
    /// that would take the whole screen down.
    func testAnUnknownStatusDecodesAsUnknown() async throws {
        let status = try await fetchedStatus("paused")
        XCTAssertNil(status)
    }

    func testAWritePutsTheStatusToThePermissionsPathAndDecodesTheStoredAnswer() async throws {
        var request: URLRequest?
        respond(#"{"permission":"intended_subjects","status":"granted"}"#) { request = $0 }
        let stored = try await client.update(.intendedSubjects, to: .granted)

        XCTAssertEqual(request?.httpMethod, "PUT")
        XCTAssertEqual(request?.url?.path, "/api/v1/students/me/sharing-permissions/intended_subjects")
        let body = try XCTUnwrap(request?.resolvedBody)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json as? [String: String], ["status": "granted"])
        XCTAssertEqual(stored.knownStatus, .granted)
    }

    func testTheProfileRefusalSurfacesRatherThanBeingAbsorbed() async {
        respond(
            #"{"code":"student_profile_required","message":"Student profile required","fieldErrors":null}"#,
            status: 409
        )
        do {
            _ = try await client.update(.intendedSubjects, to: .refused)
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.knownCode, .studentProfileRequired)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }
}
