import XCTest
@testable import UnicoachiOS

class MockCookieStorage: CookieStorageProtocol, @unchecked Sendable {
    var cookies: [HTTPCookie]? = []
    var deletedCookies: [HTTPCookie] = []

    func deleteCookie(_ cookie: HTTPCookie) {
        deletedCookies.append(cookie)
        cookies?.removeAll { $0.name == cookie.name }
    }
}

@MainActor
class AppViewModelTests: XCTestCase {
    var viewModel: AppViewModel!
    var mockClient: MockAuthClient!
    var mockStudentClient: MockStudentClient!
    var mockSupporterClient: MockSupporterClient!
    var mockCookieStorage: MockCookieStorage!

    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        mockStudentClient = MockStudentClient()
        // Answers "no supporters row" by default, so every test written before
        // RFC 241 keeps asking the question it was written to ask — and none of
        // them reaches the network for the second read.
        mockSupporterClient = MockSupporterClient()
        mockCookieStorage = MockCookieStorage()
        viewModel = AppViewModel(
            cookieStorage: mockCookieStorage,
            authClient: mockClient,
            studentClient: mockStudentClient,
            supporterClient: mockSupporterClient
        )
    }

    private func makeSupporterHome(students: [LinkedStudent] = []) -> SupporterHome {
        SupporterHome(supporter: PublicSupporter(id: UUID()), students: students)
    }

    private func makeStudent() -> PublicStudent {
        PublicStudent(
            id: UUID(),
            expectedHighSchoolGraduationDate: "2028",
            dateOfBirth: "2009-07-10",
            version: 1,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0)
        )
    }

    func testCheckSessionAuthenticatedOnSuccess() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    /// Neither row: the app asks rather than assuming "student who has not
    /// finished onboarding" (RFC 241). This test read `.onboarding` before that
    /// slice, and the change of answer IS the slice.
    func testCheckSessionRoleChoiceWhenNeitherProfileExists() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .success(nil)

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .roleChoice(user))
    }

    func testCheckSessionSupporterHomeWhenOnlyASupporterRowExists() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let home = makeSupporterHome(students: [
            LinkedStudent(linkId: UUID(), studentId: UUID(), name: "Ava", relationship: .parent, otherSupporterNames: []),
        ])
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .success(home)

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .supporterHome(user, home))
    }

    /// Order, asserted rather than assumed: a student reaches the app on ONE
    /// read, and the supporter door is never knocked on. An account holding
    /// both rows is a student here for the same reason.
    func testAStudentIsResolvedWithoutReadingTheSupporterDoor() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(makeStudent())
        mockSupporterClient.fetchHomeResult = .success(makeSupporterHome())

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .authenticated(user))
        XCTAssertEqual(mockSupporterClient.fetchHomeCallCount, 0)
    }

    /// A failure on the SECOND read is routed by the same arms as the first:
    /// the supporter door sits behind the same session and email gates.
    func testSupporterFetchUnauthorizedRoutesToUnauthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .failure(
            ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil)
        )

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testSupporterFetchServerErrorRoutesToServerError() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .failure(
            ErrorResponse(code: "SERVER_ERROR", message: "Server error", fieldErrors: nil, status: 500)
        )

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .serverError)
    }

    func testOnStudentRoleChosenRoutesToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)

        viewModel.onStudentRoleChosen(user)

        XCTAssertEqual(viewModel.authState, .onboarding(user))
        // The row is created by the FORM, not by the choice.
        XCTAssertEqual(mockStudentClient.createStudentCallCount, 0)
    }

    func testOnSupporterHomeRoutesToTheSupporterHome() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let home = makeSupporterHome()

        viewModel.onSupporterHome(user, home)

        XCTAssertEqual(viewModel.authState, .supporterHome(user, home))
    }

    /// "I'm the student" is reversible (RFC 241): the form writes the row, so
    /// backing out of it has nothing to undo.
    func testOnRoleChoiceRequestedReturnsToTheQuestion() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .onboarding(user)

        viewModel.onRoleChoiceRequested(user)

        XCTAssertEqual(viewModel.authState, .roleChoice(user))
        XCTAssertEqual(mockStudentClient.createStudentCallCount, 0)
    }

    // MARK: - The roster refresh (RFC 241)

    /// The result of the home's own headline action: the student accepted while
    /// the parent sat on the screen, and the refresh is what names them.
    func testRefreshSupporterHomeReplacesTheRoster() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let linked = makeSupporterHome(students: [
            LinkedStudent(linkId: UUID(), studentId: UUID(), name: "Ava", relationship: .parent, otherSupporterNames: []),
        ])
        viewModel.authState = .supporterHome(user, makeSupporterHome())
        mockSupporterClient.fetchHomeResult = .success(linked)

        await viewModel.refreshSupporterHome()

        XCTAssertEqual(viewModel.authState, .supporterHome(user, linked))
    }

    /// Nobody asked for this read, so a failure has no refusal to report: the
    /// resolved home stays on screen rather than becoming an error state.
    func testRefreshSupporterHomeLeavesTheHomeStandingOnFailure() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let home = makeSupporterHome()
        viewModel.authState = .supporterHome(user, home)
        mockSupporterClient.fetchHomeResult = .failure(
            ErrorResponse(code: "SERVER_ERROR", message: "Server error", fieldErrors: nil, status: 500)
        )

        await viewModel.refreshSupporterHome()

        XCTAssertEqual(viewModel.authState, .supporterHome(user, home))
    }

    /// It refreshes a ROSTER; it does not re-decide what the account is. A
    /// `404` (no supporters row) leaves the screen alone rather than emptying
    /// it.
    func testRefreshSupporterHomeIgnoresAnAbsentSupporterRow() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let home = makeSupporterHome(students: [
            LinkedStudent(linkId: UUID(), studentId: UUID(), name: "Ava", relationship: .parent, otherSupporterNames: []),
        ])
        viewModel.authState = .supporterHome(user, home)
        mockSupporterClient.fetchHomeResult = .success(nil)

        await viewModel.refreshSupporterHome()

        XCTAssertEqual(viewModel.authState, .supporterHome(user, home))
    }

    /// Every other state ignores it, so a screen that is not a supporter home
    /// cannot be replaced by one.
    func testRefreshSupporterHomeIsANoOpElsewhere() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)

        await viewModel.refreshSupporterHome()

        XCTAssertEqual(viewModel.authState, .authenticated(user))
        XCTAssertEqual(mockSupporterClient.fetchHomeCallCount, 0)
    }

    /// The race the refresh's post-await re-read exists for: `@MainActor` stops
    /// a data race, not an interleaving. The refresh is started by the home's
    /// own `.task` AND by the scene-phase hook, and `Settings -> Log out` is two
    /// taps from that screen — so a logout completing mid-read is ordinary, and
    /// the resumed continuation must not put a signed-out parent back on a home
    /// whose session cookie is already deleted.
    func testALogoutDuringAnInFlightRefreshLeavesTheUserSignedOut() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .supporterHome(user, makeSupporterHome())
        mockClient.logoutResult = .success(())
        mockSupporterClient.fetchHomeResult = .success(makeSupporterHome(students: [
            LinkedStudent(linkId: UUID(), studentId: UUID(), name: "Ava", relationship: .parent, otherSupporterNames: []),
        ]))
        let gate = InFlightGate()
        mockSupporterClient.fetchHomeGate = { await gate.hold() }

        let refresh = Task { await viewModel.refreshSupporterHome() }
        await gate.waitUntilHeld()
        await viewModel.logout()
        await gate.release()
        await refresh.value

        XCTAssertEqual(
            viewModel.authState,
            .unauthenticated,
            "a roster read that started before the logout must not decide the screen after it"
        )
    }

    /// The same rule at the other async writer this slice added. The `409`
    /// re-resolution runs in a detached `Task` that no view teardown cancels,
    /// so the teardown that invalidates its answer — the logout — cancels it,
    /// and every arm of the resolve re-checks before it writes.
    func testALogoutDuringAnInFlightReResolutionLeavesTheUserSignedOut() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)
        mockClient.logoutResult = .success(())
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .success(makeSupporterHome())
        let gate = InFlightGate()
        mockSupporterClient.fetchHomeGate = { await gate.hold() }

        viewModel.onStudentProfileRequired()
        await gate.waitUntilHeld()
        await viewModel.logout()
        await gate.release()
        await viewModel.profileRefreshTask?.value

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testCheckSessionProfileFetchUnauthorized() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testCheckSessionProfileFetchTimeout() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .noConnectivity)
    }

    func testCheckSessionProfileFetchServerError() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server error", fieldErrors: nil, status: 500))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .serverError)
    }

    func testCheckSessionProfileFetchUnexpectedErrorOnUnhandled4xx() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "teapot", message: "I'm a teapot.", fieldErrors: nil, status: 418))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unexpectedError)
    }

    func testCheckSessionUnauthenticatedOn401() async {
        mockClient.meResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testCheckSessionNoConnectivityOnTimeout() async {
        mockClient.meResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .noConnectivity)
    }

    func testCheckSessionServerErrorOnServerFailure() async {
        mockClient.meResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Server error", fieldErrors: nil, status: 500))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .serverError)
    }

    func testCheckSessionUnexpectedErrorOnUnhandled4xx() async {
        mockClient.meResult = .failure(ErrorResponse(code: "teapot", message: "I'm a teapot.", fieldErrors: nil, status: 418))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unexpectedError)
    }

    func testCheckSessionUnexpectedErrorOnStatuslessClientError() async {
        mockClient.meResult = .failure(ErrorResponse(code: "DECODE_ERROR", message: "Failed to parse response", fieldErrors: nil))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .unexpectedError)
    }

    func testOnLoginSuccessRoutesToRoleChoice() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .success(nil)

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .roleChoice(user))
    }

    func testOnLoginSuccessRoutesToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    /// A brand-new account holds neither row, so registration lands on the role
    /// choice — the door a parent-first signup comes through (RFC 241).
    func testOnRegisterSuccessRoutesToRoleChoice() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .success(nil)

        await viewModel.onRegisterSuccess(user)

        XCTAssertEqual(viewModel.authState, .roleChoice(user))
    }

    func testOnEmailChangedRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "old@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)
        // Changing the address clears verification server-side, so the fresh
        // user comes back unverified.
        let changed = PublicUser(id: user.id, email: "new@example.com", name: "Test", emailVerified: false)

        await viewModel.onEmailChanged(changed)

        XCTAssertEqual(viewModel.authState, .verificationRequired(changed))
    }

    func testOnEmailChangedStaysAuthenticatedWhenStillVerified() async {
        let user = PublicUser(id: UUID(), email: "old@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)
        mockStudentClient.fetchProfileResult = .success(makeStudent())
        let changed = PublicUser(id: user.id, email: "new@example.com", name: "Test", emailVerified: true)

        await viewModel.onEmailChanged(changed)

        XCTAssertEqual(viewModel.authState, .authenticated(changed))
    }

    func testOnOnboardingCompleteTransitionsToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .onboarding(user)

        viewModel.onOnboardingComplete(user)

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    /// The 409 edge RE-RESOLVES now (RFC 241): the refusal proves there is no
    /// `students` row, which is no longer the whole question.
    func testOnStudentProfileRequiredRoutesAStudentBackToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .success(nil)

        viewModel.onStudentProfileRequired()
        await viewModel.profileRefreshTask?.value

        XCTAssertEqual(viewModel.authState, .roleChoice(user))
    }

    /// The defect this slice removes, at the one place a supporter could still
    /// have hit the student form: a caller who holds BOTH rows loses the
    /// student one mid-session and lands on their supporter home.
    func testOnStudentProfileRequiredRoutesASupporterHome() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let home = makeSupporterHome()
        viewModel.authState = .authenticated(user)
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .success(home)

        viewModel.onStudentProfileRequired()
        await viewModel.profileRefreshTask?.value

        XCTAssertEqual(viewModel.authState, .supporterHome(user, home))
    }

    func testOnStudentProfileRequiredNoOpFromNonAuthenticated() async {
        viewModel.authState = .unauthenticated

        viewModel.onStudentProfileRequired()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    /// A profile deleted server-side refuses every student-scoped read on the
    /// screen at once, and nine call sites can report it. One re-resolution
    /// runs; the rest are refused while it is in flight, so the last to finish
    /// cannot decide the screen.
    func testConcurrentProfileRefusalsResolveOnce() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)
        mockStudentClient.fetchProfileResult = .success(nil)
        mockSupporterClient.fetchHomeResult = .success(nil)

        viewModel.onStudentProfileRequired()
        viewModel.onStudentProfileRequired()
        viewModel.onStudentProfileRequired()
        await viewModel.profileRefreshTask?.value

        XCTAssertEqual(viewModel.authState, .roleChoice(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 1)
        XCTAssertEqual(mockSupporterClient.fetchHomeCallCount, 1)
    }

    /// The guard is not a latch: a LATER refusal, after the first resolution
    /// finished, still re-resolves.
    func testAProfileRefusalAfterTheFirstResolutionStillResolves() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        viewModel.onStudentProfileRequired()
        await viewModel.profileRefreshTask?.value
        viewModel.onStudentProfileRequired()
        await viewModel.profileRefreshTask?.value

        XCTAssertEqual(viewModel.authState, .authenticated(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 2)
    }

    func testLogoutTransitionsToUnauthenticatedOnFailure() async {
        viewModel.authState = .authenticated(PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true))
        mockClient.logoutResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Error", fieldErrors: nil))
        let cookie = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!
        mockCookieStorage.cookies = [cookie]

        await viewModel.logout()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
        XCTAssertEqual(mockCookieStorage.deletedCookies.count, 1)
        XCTAssertEqual(mockCookieStorage.deletedCookies.first?.name, "session")
    }

    func testLogoutClearsCookiesOnSuccess() async {
        viewModel.authState = .authenticated(PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true))
        mockClient.logoutResult = .success(())
        let cookie1 = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!
        let cookie2 = HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "tracking", .value: "456"])!
        mockCookieStorage.cookies = [cookie1, cookie2]

        await viewModel.logout()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
        XCTAssertEqual(mockCookieStorage.deletedCookies.count, 2)
        XCTAssertEqual(mockCookieStorage.cookies?.isEmpty, true)
    }

    // MARK: - Account deletion (RFC 252)

    private func makeDeletionViewModel(
        subjectStore: MockSupporterSubjectStore,
        appleNameStore: MockAppleNameStore
    ) -> AppViewModel {
        AppViewModel(
            cookieStorage: mockCookieStorage,
            authClient: mockClient,
            studentClient: mockStudentClient,
            supporterClient: mockSupporterClient,
            supporterSubjectStore: subjectStore,
            appleNameStore: appleNameStore
        )
    }

    /// A confirmed close runs the logout tail — cookies gone, `.unauthenticated`
    /// — WITHOUT a second call to `POST /auth/logout` (the mock would trap), and
    /// forgets what this device held for the account.
    func testDeleteAccountEndsTheSessionAndClearsPerUserDefaults() async {
        let user = PublicUser(id: UUID(), email: "parent@example.com", name: "Robin", emailVerified: true)
        let studentId = UUID()
        let subjectStore = MockSupporterSubjectStore(selections: [user.id: studentId])
        let appleNameStore = MockAppleNameStore()
        appleNameStore.store(name: "Robin Carter", forUserId: "apple-sub")
        let viewModel = makeDeletionViewModel(subjectStore: subjectStore, appleNameStore: appleNameStore)
        viewModel.authState = .supporterHome(user, makeSupporterHome())
        mockClient.deleteAccountResult = .success(())
        mockCookieStorage.cookies = [HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!]

        let deleted = await viewModel.deleteAccount()

        XCTAssertTrue(deleted)
        XCTAssertEqual(mockClient.deleteAccountCallCount, 1)
        XCTAssertEqual(viewModel.authState, .unauthenticated)
        XCTAssertTrue(viewModel.accountClosed, "the login screen says the account was closed")
        XCTAssertEqual(mockCookieStorage.deletedCookies.map(\.name), ["session"])
        XCTAssertNil(subjectStore.selectedStudentId(forUserId: user.id))
        XCTAssertNil(appleNameStore.name(forUserId: "apple-sub"))
    }

    /// A close the server did not confirm changes nothing local: the account
    /// still exists, so the person stays signed in and Settings shows the
    /// failure.
    func testDeleteAccountFailureKeepsTheUserSignedIn() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let studentId = UUID()
        let subjectStore = MockSupporterSubjectStore(selections: [user.id: studentId])
        let appleNameStore = MockAppleNameStore()
        appleNameStore.store(name: "Test Student", forUserId: "apple-sub")
        let viewModel = makeDeletionViewModel(subjectStore: subjectStore, appleNameStore: appleNameStore)
        viewModel.authState = .authenticated(user)
        mockClient.deleteAccountResult = .failure(ErrorResponse(code: "SERVER_ERROR", message: "Error", fieldErrors: nil, status: 500))
        mockCookieStorage.cookies = [HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!]

        let deleted = await viewModel.deleteAccount()

        XCTAssertFalse(deleted)
        XCTAssertEqual(viewModel.authState, .authenticated(user))
        XCTAssertFalse(viewModel.accountClosed)
        XCTAssertTrue(mockCookieStorage.deletedCookies.isEmpty)
        XCTAssertEqual(subjectStore.selectedStudentId(forUserId: user.id), studentId)
        XCTAssertEqual(appleNameStore.name(forUserId: "apple-sub"), "Test Student")
    }

    /// A `401` means the session is already dead — the account closed from
    /// another device, or the session was revoked — so the app ends it locally
    /// rather than leaving the person "signed in" to retry forever. Whether the
    /// account is closed is unknown, so nothing per-user is cleared and the
    /// closed line is not shown.
    func testDeleteAccountUnauthorizedEndsTheLocalSession() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        let studentId = UUID()
        let subjectStore = MockSupporterSubjectStore(selections: [user.id: studentId])
        let appleNameStore = MockAppleNameStore()
        appleNameStore.store(name: "Test Student", forUserId: "apple-sub")
        let viewModel = makeDeletionViewModel(subjectStore: subjectStore, appleNameStore: appleNameStore)
        viewModel.authState = .authenticated(user)
        mockClient.deleteAccountResult = .failure(ErrorResponse(code: "unauthorized", message: "Not authenticated", fieldErrors: nil, status: 401))
        mockCookieStorage.cookies = [HTTPCookie(properties: [.domain: "example.com", .path: "/", .name: "session", .value: "123"])!]

        let deleted = await viewModel.deleteAccount()

        XCTAssertFalse(deleted)
        XCTAssertEqual(viewModel.authState, .unauthenticated)
        XCTAssertFalse(viewModel.accountClosed)
        XCTAssertEqual(mockCookieStorage.deletedCookies.map(\.name), ["session"])
        XCTAssertEqual(subjectStore.selectedStudentId(forUserId: user.id), studentId)
        XCTAssertEqual(appleNameStore.name(forUserId: "apple-sub"), "Test Student")
    }

    /// The closed line is for the screen the deletion lands on, not for the
    /// next account that signs in on this device.
    func testTheNextSignInClearsTheAccountClosedLine() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)
        mockClient.deleteAccountResult = .success(())
        _ = await viewModel.deleteAccount()
        XCTAssertTrue(viewModel.accountClosed)

        await viewModel.onLoginSuccess(PublicUser(id: UUID(), email: "next@example.com", name: "Next", emailVerified: false))

        XCTAssertFalse(viewModel.accountClosed)
    }

    // MARK: - Email verification routing

    func testOnLoginSuccessUnverifiedRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testOnRegisterSuccessUnverifiedRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)

        await viewModel.onRegisterSuccess(user)

        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testCheckSessionUnverifiedRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)
        mockClient.meResult = .success(MeResponse(user: user))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testResolveProfileStateEmailNotVerifiedRaceRoutesToVerificationRequired() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .failure(ErrorResponse(code: "email_not_verified", message: "Email verification required.", fieldErrors: nil, status: 403))

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
    }

    func testRecheckVerificationVerifiedTransitionsToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .verificationRequired(user)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        let outcome = await viewModel.recheckVerification()

        XCTAssertEqual(outcome, .verified)
        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testRecheckVerificationStillUnverifiedLeavesStateUnchanged() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)
        viewModel.authState = .verificationRequired(user)
        mockClient.meResult = .success(MeResponse(user: user))

        let outcome = await viewModel.recheckVerification()

        XCTAssertEqual(outcome, .stillUnverified)
        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testRecheckVerificationUnauthorizedTearsDownToUnauthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)
        viewModel.authState = .verificationRequired(user)
        mockClient.meResult = .failure(ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil, status: 401))

        let outcome = await viewModel.recheckVerification()

        XCTAssertEqual(outcome, .failed)
        XCTAssertEqual(viewModel.authState, .unauthenticated)
    }

    func testRecheckVerificationTimeoutLeavesStateUnchanged() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: false)
        viewModel.authState = .verificationRequired(user)
        mockClient.meResult = .failure(ErrorResponse(code: "TIMEOUT", message: "Timeout", fieldErrors: nil))

        let outcome = await viewModel.recheckVerification()

        XCTAssertEqual(outcome, .failed)
        XCTAssertEqual(viewModel.authState, .verificationRequired(user))
    }

    // MARK: - The subscription rail (RFC 119)

    /// The rail is composed **here**, once, and not inside
    /// `AuthenticatedRootView`, whose `init` re-runs on every publish of this
    /// object. Reading it twice must yield the same objects — turn either into a
    /// computed property that rebuilds and this goes red.
    func testTheSubscriptionRailIsBuiltOnceForTheAppsLifetime() {
        let store = MockSubscriptionStore()
        let viewModel = AppViewModel(
            authClient: mockClient,
            studentClient: mockStudentClient,
            subscriptionStore: store
        )

        XCTAssertTrue(viewModel.subscriptionStore as AnyObject === store)
        XCTAssertTrue(viewModel.subscriptionStore as AnyObject === viewModel.subscriptionStore as AnyObject)
        XCTAssertTrue(viewModel.transactionRecorder as AnyObject === viewModel.transactionRecorder as AnyObject)
    }

    /// The property that actually loses money: the recorder must finish through
    /// the *same* store the rest of the app holds. When the rail was built in a
    /// SwiftUI `init`, the listener's recorder and the view model's store were
    /// different instances and the surviving store's registry was empty, so
    /// `finish()` silently no-opped and a paid purchase was never finished.
    func testTheRecorderFinishesThroughTheStoreTheAppComposed() async {
        let store = MockSubscriptionStore()
        let client = MockSubscriptionClient()
        client.result = .success(PublicSubscription(
            status: "active",
            productId: SubscriptionProduct.monthlyIdentifier,
            currentPeriodEnd: Date(timeIntervalSince1970: 1_773_000_000)
        ))
        let viewModel = AppViewModel(
            authClient: mockClient,
            studentClient: mockStudentClient,
            subscriptionClient: client,
            subscriptionStore: store
        )
        let transaction = StoreTransaction(id: 11, productID: SubscriptionProduct.monthlyIdentifier, jws: "a.b.c")

        let outcome = await viewModel.transactionRecorder.record(transaction)

        guard case .recorded = outcome else {
            return XCTFail("expected the transaction to be recorded, got [\(outcome)]")
        }
        XCTAssertEqual(store.finished, [transaction], "finished through the app's own store")
    }

    // MARK: - The simulator-Debug StoreKit switch (opt-in)

    /// The predicate behind `-UnicoachEnableStoreKit`. A StoreKit
    /// *configuration* is bound to the LAUNCH, not the artifact — it is not in
    /// the built `.app` — so on a simulator only a scheme action has one, and
    /// only a scheme action passes this argument. Everything else must stay
    /// disabled or it falls through to the REAL App Store and the simulator
    /// throws "Sign in to your Apple Account" alerts over the UI. These cases
    /// are the only mechanical authority the switch has: `bin/test` never
    /// compiles ios-app.
    func testTheEnableArgumentIsRecognisedOnlyWhenItIsExactlyPresent() {
        XCTAssertTrue(StoreKitLaunchOverride.isStoreKitEnabled(
            launchArguments: ["/path/to/UnicoachiOS", StoreKitLaunchOverride.enableArgument]
        ))
        XCTAssertTrue(StoreKitLaunchOverride.isStoreKitEnabled(
            launchArguments: [StoreKitLaunchOverride.enableArgument, "-UnicoachUITestScreen", "profile"]
        ))
        XCTAssertFalse(StoreKitLaunchOverride.isStoreKitEnabled(launchArguments: []))
        XCTAssertFalse(StoreKitLaunchOverride.isStoreKitEnabled(launchArguments: ["/path/to/UnicoachiOS"]))
        // Near-misses stay OFF: a prefix or a case-folded match would let an
        // unrelated argument switch real billing on by accident.
        XCTAssertFalse(StoreKitLaunchOverride.isStoreKitEnabled(launchArguments: ["-UnicoachEnableStoreKitFoo"]))
        XCTAssertFalse(StoreKitLaunchOverride.isStoreKitEnabled(launchArguments: ["-unicoachenablestorekit"]))
        XCTAssertFalse(StoreKitLaunchOverride.isStoreKitEnabled(launchArguments: ["UnicoachEnableStoreKit"]))
    }

    /// The argument is spelled exactly once in the app, and the scheme's
    /// LaunchAction passes that literal string. Pinning it here is what makes a
    /// rename go red instead of silently leaving Xcode's own Run action —
    /// the one launch that DOES carry the local `.storekit` catalogue — with an
    /// inert store and no way to exercise a purchase.
    func testTheEnableArgumentSpelling() {
        XCTAssertEqual(StoreKitLaunchOverride.enableArgument, "-UnicoachEnableStoreKit")
    }

    /// The inert store is inert: no product on offer (so a capture shows the
    /// honest "no purchase path" state rather than a fabricated price), no
    /// entitlements, and a stream that ends at once so the authenticated root's
    /// listener task does not hang on a stream that will never yield.
    func testTheDisabledStoreOffersNothingAndEndsItsUpdateStream() async throws {
        let store = DisabledSubscriptionStore()

        let product = try await store.product(id: SubscriptionProduct.monthlyIdentifier)
        XCTAssertNil(product, "a disabled store must not invent a price")
        let entitlements = await store.currentEntitlements()
        XCTAssertTrue(entitlements.isEmpty)
        let purchase = try await store.purchase(productID: SubscriptionProduct.monthlyIdentifier)
        XCTAssertEqual(purchase, .unavailable, "nothing was cancelled; the plan is not on offer")
        let restore = await store.sync()
        XCTAssertEqual(restore, .synced)

        var delivered: [StoreTransaction] = []
        for await transaction in store.updates() {
            delivered.append(transaction)
        }
        XCTAssertTrue(delivered.isEmpty)
    }

    /// The property that makes this complete rather than per-call-site: a
    /// simulator Debug launch that says NOTHING gets a `DisabledSubscriptionStore`,
    /// so no code path capable of calling StoreKit exists in the process at all.
    /// A forgetful `xcrun simctl launch` — or any launcher nobody has written
    /// yet — cannot reach the real App Store. This test running at all proves
    /// the point: these cases are compiled and run on a simulator.
    func testTheCompositionRootDefaultsToTheInertStoreOnASimulator() {
        let store = AppViewModel.defaultSubscriptionStore(launchArguments: ["/path/to/UnicoachiOS"])

        XCTAssertTrue(store is DisabledSubscriptionStore, "a bare simulator launch must not reach StoreKit")
    }

    /// And opting in works, because Xcode's Run action does exactly this: the
    /// scheme's LaunchAction carries the argument alongside the
    /// `StoreKitConfigurationFileReference`, which is the launch that has a
    /// local catalogue to talk to. A regression here would leave a developer
    /// unable to exercise a purchase from Xcode.
    func testTheCompositionRootUsesRealStoreKitWhenTheLaunchOptsIn() {
        let store = AppViewModel.defaultSubscriptionStore(
            launchArguments: ["/path/to/UnicoachiOS", StoreKitLaunchOverride.enableArgument]
        )

        XCTAssertTrue(store is StoreKitSubscriptionStore)
    }
}

/// A read held open, so a test can act on the view model while the request is
/// genuinely in flight rather than guessing at a sleep. The `WriteGate`
/// precedent in `YourDetailsViewModelTests`.
private actor InFlightGate {
    private var isReleased = false
    private var isHeld = false
    private var releaseWaiters: [CheckedContinuation<Void, Never>] = []
    private var heldWaiters: [CheckedContinuation<Void, Never>] = []

    /// Called from inside the client double. Only the FIRST read is held; a
    /// later one passes straight through, so a transition that makes its own
    /// read cannot deadlock the test.
    func hold() async {
        guard !isHeld else { return }
        isHeld = true
        for waiter in heldWaiters { waiter.resume() }
        heldWaiters = []
        guard !isReleased else { return }
        await withCheckedContinuation { releaseWaiters.append($0) }
    }

    /// Returns once a read has actually reached the client.
    func waitUntilHeld() async {
        guard !isHeld else { return }
        await withCheckedContinuation { heldWaiters.append($0) }
    }

    func release() {
        isReleased = true
        for waiter in releaseWaiters { waiter.resume() }
        releaseWaiters = []
    }
}
