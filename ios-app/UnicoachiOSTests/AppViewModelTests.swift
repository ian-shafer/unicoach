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
    var mockCookieStorage: MockCookieStorage!

    override func setUp() async throws {
        try await super.setUp()
        mockClient = MockAuthClient()
        mockStudentClient = MockStudentClient()
        mockCookieStorage = MockCookieStorage()
        viewModel = AppViewModel(
            cookieStorage: mockCookieStorage,
            authClient: mockClient,
            studentClient: mockStudentClient
        )
    }

    private func makeStudent() -> PublicStudent {
        PublicStudent(
            id: UUID(),
            expectedHighSchoolGraduationDate: "2028",
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

    func testCheckSessionOnboardingWhenNoProfile() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockClient.meResult = .success(MeResponse(user: user))
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.checkSession()

        XCTAssertEqual(viewModel.authState, .onboarding(user))
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

    func testOnLoginSuccessRoutesToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .onboarding(user))
    }

    func testOnLoginSuccessRoutesToAuthenticated() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(makeStudent())

        await viewModel.onLoginSuccess(user)

        XCTAssertEqual(viewModel.authState, .authenticated(user))
    }

    func testOnRegisterSuccessRoutesToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        mockStudentClient.fetchProfileResult = .success(nil)

        await viewModel.onRegisterSuccess(user)

        XCTAssertEqual(viewModel.authState, .onboarding(user))
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

    func testOnStudentProfileRequiredRoutesAuthenticatedToOnboarding() async {
        let user = PublicUser(id: UUID(), email: "test@example.com", name: "Test", emailVerified: true)
        viewModel.authState = .authenticated(user)

        viewModel.onStudentProfileRequired()

        XCTAssertEqual(viewModel.authState, .onboarding(user))
        XCTAssertEqual(mockStudentClient.fetchProfileCallCount, 0)
    }

    func testOnStudentProfileRequiredNoOpFromNonAuthenticated() async {
        viewModel.authState = .unauthenticated

        viewModel.onStudentProfileRequired()

        XCTAssertEqual(viewModel.authState, .unauthenticated)
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
