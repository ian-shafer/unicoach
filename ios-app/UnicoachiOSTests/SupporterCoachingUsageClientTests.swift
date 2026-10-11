import SwiftUI
import XCTest
@testable import UnicoachiOS

/// `GET /api/v1/supporters/me/coaching-usage` over a real `APIClient`
/// (RFC 244): the path it asks for, the two coverage kinds, a kind this build
/// has never heard of, and the `404` that is an answer.
final class SupporterCoachingUsageClientTests: XCTestCase {
    private var client: SupporterCoachingUsageClient!

    /// The server's own 200 body for a fresh account, verbatim from RFC 244 §6.
    private let freeAllowanceBody = #"{"usage":{"usedPercent":0,"exhausted":false,"resetsAt":null},"coverage":{"kind":"free_allowance"}}"#

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: config)
        client = SupporterCoachingUsageClient(
            apiClient: APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        )
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        client = nil
        super.tearDown()
    }

    /// Records every path the session was asked for, so a test can assert on
    /// what was NOT called as well as on what was.
    private func respond(status: Int, body: String, paths: Locked<[String]>? = nil) {
        MockURLProtocol.requestHandler = { request in
            paths?.withLock { $0.append(request.url?.path ?? "") }
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }
    }

    func testFetchGetsTheSupporterScopedPath() async throws {
        let expectation = expectation(description: "request observed")
        let body = freeAllowanceBody
        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/supporters/me/coaching-usage")
            XCTAssertEqual(request.httpMethod, "GET")
            expectation.fulfill()
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(body.utf8))
        }

        _ = try await client.fetch()
        await fulfillment(of: [expectation], timeout: 1)
    }

    func testFetchDecodesTheFreeAllowanceBody() async throws {
        respond(status: 200, body: freeAllowanceBody)

        let fetched = try await client.fetch()
        let reading = try XCTUnwrap(fetched)

        XCTAssertEqual(reading.usage, CoachingUsage(usedPercent: 0, exhausted: false, resetsAt: nil))
        XCTAssertEqual(reading.coverage.kind, .freeAllowance)
    }

    func testFetchDecodesTheOwnSubscriptionBodyWithItsPeriodEnd() async throws {
        respond(status: 200, body: """
        {"usage":{"usedPercent":68,"exhausted":false,"resetsAt":"2026-03-14T12:00:00Z"},
         "coverage":{"kind":"own_subscription"}}
        """)

        let fetched = try await client.fetch()
        let reading = try XCTUnwrap(fetched)

        XCTAssertEqual(reading.coverage.kind, .ownSubscription)
        XCTAssertEqual(reading.usage.resetsAt, ISO8601DateFormatter().date(from: "2026-03-14T12:00:00Z"))
    }

    /// A kind the server publishes after this build shipped. It is KEPT
    /// verbatim rather than throwing: the meter in the same body is perfectly
    /// readable, and a `DecodingError` here is not an `ErrorResponse`, so it
    /// would reach the screen as an unexplained failure instead of a named one.
    /// What it must never do is decode as one of the two known kinds — see
    /// `SupporterCoachingViewModelTests` for what the section then renders.
    func testAnUnknownCoverageKindDecodesVerbatimRatherThanThrowing() async throws {
        respond(status: 200, body: #"{"usage":{"usedPercent":42,"exhausted":false,"resetsAt":null},"coverage":{"kind":"family_plan"}}"#)

        let fetched = try await client.fetch()
        let reading = try XCTUnwrap(fetched)

        XCTAssertEqual(reading.coverage.kind, .unrecognised("family_plan"))
        XCTAssertNotEqual(reading.coverage.kind, .freeAllowance)
        XCTAssertEqual(reading.usage.usedPercent, 42, "the readable half of the body survives the unreadable word")
    }

    /// No `supporters` row is an ANSWER, not a fault — the same absence
    /// `GET /api/v1/supporters/me` reports with the same status.
    func testFetchAnswersNilOnNotFound() async throws {
        respond(status: 404, body: #"{"code":"supporter_not_found","message":"m","fieldErrors":null}"#)

        let reading = try await client.fetch()

        XCTAssertNil(reading)
    }

    func testFetchSurfacesServerErrorCodes() async throws {
        let cases: [(Int, String)] = [
            (401, "unauthorized"),
            (403, "email_not_verified"),
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

    /// **The parent's rail makes no student-scoped call.** Asserted on the wire
    /// rather than argued from the type: the supporter home's billing rail is
    /// driven end to end over a real `APIClient`, and every path the session was
    /// asked for is read back. `/api/v1/students/me/coaching-usage` is the one
    /// that would be refused `409 student_profile_required` for a parent — and
    /// that refusal is swallowed into a blank meter, so a screen that fired it
    /// would look plausible and be permanently wrong.
    @MainActor
    func testTheParentsRailCallsOnlyTheSupporterRoute() async throws {
        let paths = Locked<[String]>([])
        respond(status: 200, body: freeAllowanceBody, paths: paths)
        let viewModel = SupporterCoachingViewModel(client: client)

        await viewModel.load()
        await viewModel.load()

        XCTAssertEqual(
            paths.current,
            ["/api/v1/supporters/me/coaching-usage", "/api/v1/supporters/me/coaching-usage"]
        )
        XCTAssertFalse(paths.current.contains { $0.contains("/students/") })
    }
}

/// **A parent's whole home makes no student-scoped call.** The rail test above
/// covers the one read this slice adds; this covers the SCENE, because the
/// claim `SupporterHomeView`'s doc comment makes is about the screen and not
/// about one client — a separate scene is what stops a student-scoped call
/// being fired for a supporter AT ALL.
///
/// Built with REAL clients over the mock transport, so what is asserted is the
/// wire and not a double's call count, and hosted in a real window (the
/// `ComposerFocusTests` recipe) so every `.task` on the scene actually fires.
@MainActor
final class SupporterHomeWireTests: XCTestCase {
    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    func testAParentsHomeAsksForNoStudentScopedPath() {
        let paths = Locked<[String]>([])
        MockURLProtocol.requestHandler = { request in
            paths.withLock { $0.append(request.url?.path ?? "") }
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            let body = #"{"usage":{"usedPercent":0,"exhausted":false,"resetsAt":null},"coverage":{"kind":"free_allowance"}}"#
            return (response, Data(body.utf8))
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let apiClient = APIClient(
            baseURL: URL(string: "http://localhost:8080")!,
            session: URLSession(configuration: config)
        )
        let user = PublicUser(id: UUID(), email: "parent@example.com", name: "Robin", emailVerified: true)

        let window = SnapshotHost.mount(AnyView(SupporterHomeView(
            user: user,
            home: SupporterHome(supporter: PublicSupporter(id: UUID()), students: []),
            authClient: AuthClient(apiClient: apiClient),
            supporterInviteClient: SupporterInviteClient(apiClient: apiClient),
            supporterClient: SupporterClient(apiClient: apiClient),
            collegeListClient: CollegeListClient(apiClient: apiClient),
            supporterCoachingUsageClient: SupporterCoachingUsageClient(apiClient: apiClient),
            subjectStore: MockSupporterSubjectStore(),
            // The roster refresh is `AppViewModel`'s and is tested there; what
            // this test is about is what the SCENE itself asks for.
            refreshHome: {},
            onEmailChanged: { _ in },
            onLogout: {},
            onDeleteAccount: { false }
        )))
        addTeardownBlock { @MainActor in SnapshotHost.dismiss(window) }
        SnapshotHost.settle(1.0)

        XCTAssertEqual(paths.current, ["/api/v1/supporters/me/coaching-usage"])
        XCTAssertFalse(
            paths.current.contains { $0.contains("/students/") },
            "a supporter session fires no student-scoped read"
        )
    }

    /// A parent's Settings now carries the family list (RFC 249), and the
    /// student's version of that list reads a student-scoped route. The
    /// parent's version must read NOTHING: its roster came with the home.
    func testAParentsSettingsAsksForNoStudentScopedPath() {
        let paths = Locked<[String]>([])
        MockURLProtocol.requestHandler = { request in
            paths.withLock { $0.append(request.url?.path ?? "") }
            let response = HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!
            return (response, Data("{}".utf8))
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let apiClient = APIClient(
            baseURL: URL(string: "http://localhost:8080")!,
            session: URLSession(configuration: config)
        )
        let user = PublicUser(id: UUID(), email: "parent@example.com", name: "Robin", emailVerified: true)
        let roster = [
            LinkedStudent(
                linkId: UUID(),
                studentId: UUID(),
                name: "Ava",
                relationship: .parent,
                otherSupporterNames: ["Sam Lee"]
            ),
        ]

        let window = SnapshotHost.mount(AnyView(NavigationStack {
            SettingsView(
                user: user,
                caller: .supporter(
                    .init(roster: roster, client: SupporterClient(apiClient: apiClient), refreshHome: {}),
                    coaching: SupporterCoachingViewModel(client: SupporterCoachingUsageClient(apiClient: apiClient))
                ),
                authClient: AuthClient(apiClient: apiClient),
                supporterInviteClient: SupporterInviteClient(apiClient: apiClient),
                onEmailChanged: { _ in },
                onLogout: {},
                onDeleteAccount: { false }
            )
        }))
        addTeardownBlock { @MainActor in SnapshotHost.dismiss(window) }
        SnapshotHost.settle(1.0)

        XCTAssertEqual(paths.current, [], "a parent's Settings reads nothing on appear")
    }
}
