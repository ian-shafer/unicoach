import Foundation
import os

@MainActor
class AppViewModel: ObservableObject {
    @Published var authState: UserAuthState = .loading
    let authClient: AuthClientProtocol & SsoAuthenticating
    let studentClient: StudentClientProtocol
    let conversationClient: ConversationClientProtocol
    let collegeListClient: CollegeListClientProtocol
    let googleSignInProvider: SsoSignInProviding
    let appleSignInProvider: SsoSignInProviding
    let cookieStorage: CookieStorageProtocol
    let coachingUsageClient: CoachingUsageClientProtocol

    /// The StoreKit rail and the one `TransactionRecorder` over it.
    ///
    /// They live here, in the app's composition root, and **not** in
    /// `AuthenticatedRootView`: a `View`'s `init` runs again on every publish of
    /// this object, so a rail built there is rebuilt each time while the
    /// `@StateObject` view model keeps the first one. The listener and the view
    /// model would then hold different recorders over different stores, and the
    /// rebuilt store's registry would be empty — so `finish()` would no-op and a
    /// paid purchase would never be finished. `AppViewModel` is a `@StateObject`
    /// created once for the app's lifetime, so one store and one recorder exist
    /// for as long as the process does.
    ///
    /// The *listener* still runs at the authenticated root, where it belongs: a
    /// renewal must be recorded whether or not Settings is on screen, and must
    /// not be attempted unauthenticated, where `/verify` could only answer 401.
    let subscriptionStore: SubscriptionStoreProtocol
    let transactionRecorder: TransactionRecording

    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "AppViewModel")

    /// The store this app runs against when nobody injects one — the
    /// simulator-Debug StoreKit switch is honoured HERE, at the composition
    /// root, and nowhere else.
    ///
    /// One decision point is the whole design: a check scattered into
    /// `SubscriptionViewModel` or the `Transaction.updates` listener would have
    /// to be right in every one of them, and the first one missed re-opens the
    /// defect (see `StoreKitLaunchOverride` for what that defect is). Swapping
    /// the object itself means the disabled process holds no
    /// `StoreKitSubscriptionStore` at all, so there is no code path left that
    /// could reach StoreKit.
    ///
    /// **Disabled is the DEFAULT, and opting in is what takes an argument.** A
    /// simulator process can only ever be handed a StoreKit configuration by a
    /// scheme action, and only a scheme action can pass this argument, so "no
    /// scheme" and "no configuration" are one and the same condition. Defaulting
    /// to disabled means no launcher — a hand-typed `simctl launch`,
    /// `bin/screenshot-ios`, a future UI test — can fall through to the real App
    /// Store by forgetting a flag. Safety stops being opt-in per call site.
    ///
    /// `targetEnvironment(simulator)` is load-bearing, not decoration: a DEBUG
    /// build on a REAL DEVICE keeps real StoreKit, because a device has a real
    /// Apple Account and a device is where purchases are actually tested. Only
    /// the simulator, which cannot get the configuration outside a scheme, is
    /// defaulted off.
    ///
    /// A `static func` rather than an inline default argument because `#if`
    /// cannot appear inside a parameter list — and the guard is not optional
    /// here: Release must not be able to switch billing off.
    /// `launchArguments` is a parameter with the real process's arguments as its
    /// default so a test can drive the decision without a process to launch.
    static func defaultSubscriptionStore(
        launchArguments: [String] = ProcessInfo.processInfo.arguments
    ) -> (SubscriptionStoreProtocol & TransactionFinishing) {
        #if DEBUG && targetEnvironment(simulator)
        guard StoreKitLaunchOverride.isStoreKitEnabled(launchArguments: launchArguments) else {
            return DisabledSubscriptionStore()
        }
        #endif
        return StoreKitSubscriptionStore()
    }

    init(
        apiClient: APIClient = APIClient(),
        cookieStorage: CookieStorageProtocol = HTTPCookieStorage.shared,
        authClient: (AuthClientProtocol & SsoAuthenticating)? = nil,
        studentClient: StudentClientProtocol? = nil,
        conversationClient: ConversationClientProtocol? = nil,
        collegeListClient: CollegeListClientProtocol? = nil,
        coachingUsageClient: CoachingUsageClientProtocol? = nil,
        subscriptionClient: SubscriptionClientProtocol? = nil,
        subscriptionStore: (SubscriptionStoreProtocol & TransactionFinishing) = AppViewModel.defaultSubscriptionStore(),
        googleSignInProvider: SsoSignInProviding = GoogleSignInProvider(),
        appleSignInProvider: SsoSignInProviding = AppleSignInProvider()
    ) {
        self.authClient = authClient ?? AuthClient(apiClient: apiClient)
        self.studentClient = studentClient ?? StudentClient(apiClient: apiClient)
        self.conversationClient = conversationClient ?? ConversationClient(apiClient: apiClient)
        self.collegeListClient = collegeListClient ?? CollegeListClient(apiClient: apiClient)
        self.coachingUsageClient = coachingUsageClient ?? CoachingUsageClient(apiClient: apiClient)
        self.googleSignInProvider = googleSignInProvider
        self.appleSignInProvider = appleSignInProvider
        self.cookieStorage = cookieStorage
        self.subscriptionStore = subscriptionStore
        // The one place a `TransactionFinishing` is ever handed out.
        self.transactionRecorder = TransactionRecorder(
            client: subscriptionClient ?? SubscriptionClient(apiClient: apiClient),
            store: subscriptionStore
        )
    }

    func checkSession() async {
        authState = .loading
        do {
            let response = try await authClient.me()
            await resolveProfileState(response.user)
        } catch let error as ErrorResponse {
            logger.error("Session check failed: code=[\(error.code, privacy: .public)] message=[\(error.message, privacy: .public)]")
            if error.code == "unauthorized" {
                authState = .unauthenticated
            } else if error.code == "TIMEOUT" || error.code == "NETWORK_ERROR" {
                authState = .noConnectivity
            } else if let status = error.status, status >= 500 {
                authState = .serverError
            } else {
                authState = .unexpectedError
            }
        } catch {
            logger.error("Session check failed (unexpected): [\(error, privacy: .public)]")
            authState = .unexpectedError
        }
    }

    func onLoginSuccess(_ user: PublicUser) async {
        await resolveProfileState(user)
    }

    func onRegisterSuccess(_ user: PublicUser) async {
        await resolveProfileState(user)
    }

    func onOnboardingComplete(_ user: PublicUser) {
        authState = .authenticated(user)
    }

    /// A successful change of email address, from either of `ChangeEmailView`'s
    /// call sites. The server clears verification when the address changes, so
    /// the returned user is unverified and the app must leave `.authenticated`
    /// at once — RFC 117 made this path reachable for a *verified* student, and
    /// without this the app would keep showing a verified session for an
    /// address that is no longer verified. Same shape as `onLoginSuccess`: hand
    /// the fresh user to the router and let it decide the state.
    func onEmailChanged(_ user: PublicUser) async {
        await resolveProfileState(user)
    }

    /// Re-onboarding entry point for the abnormal `409 student_profile_required`
    /// edge: profile gating guarantees HomeView is reachable only with a profile,
    /// so a `409` from the stream endpoint means the profile was deleted
    /// server-side mid-session. The `409` itself proves no profile exists, so no
    /// confirming `fetchProfile()` round-trip is made; the root state machine
    /// simply re-enters `.onboarding`, tearing down HomeView's `NavigationStack`.
    func onStudentProfileRequired() {
        guard case .authenticated(let user) = authState else {
            return
        }
        authState = .onboarding(user)
    }

    func logout() async {
        do {
            try await authClient.logout()
        } catch {
            logger.error("Network logout failed, proceeding with local session clear: [\(error, privacy: .public)]")
        }
        if let cookies = cookieStorage.cookies {
            for cookie in cookies {
                cookieStorage.deleteCookie(cookie)
            }
        }
        authState = .unauthenticated
    }

    /// Outcome of a verification re-check, reported back to the blocked screen.
    enum VerificationRecheckOutcome: Equatable {
        case verified
        case stillUnverified
        case failed
    }

    /// Re-runs `me()` to observe an `emailVerified` flip without unwinding the
    /// blocked screen on transient failure. A verified user transitions out (via
    /// `resolveProfileState`); an unverified user or any transient error leaves
    /// the screen in place so it can render inline feedback. Only `unauthorized`
    /// tears the screen down (to `.unauthenticated`).
    func recheckVerification() async -> VerificationRecheckOutcome {
        do {
            let response = try await authClient.me()
            if response.user.emailVerified {
                await resolveProfileState(response.user)
                return .verified
            }
            return .stillUnverified
        } catch let error as ErrorResponse {
            logger.error("Verification re-check failed: code=[\(error.code, privacy: .public)] message=[\(error.message, privacy: .public)]")
            if error.code == "unauthorized" {
                authState = .unauthenticated
            }
            return .failed
        } catch {
            logger.error("Verification re-check failed (unexpected): [\(error, privacy: .public)]")
            return .failed
        }
    }

    private func resolveProfileState(_ user: PublicUser) async {
        if !user.emailVerified {
            authState = .verificationRequired(user)
            return
        }
        do {
            if try await studentClient.fetchProfile() != nil {
                authState = .authenticated(user)
            } else {
                authState = .onboarding(user)
            }
        } catch let error as ErrorResponse {
            logger.error("Profile resolve failed: code=[\(error.code, privacy: .public)] message=[\(error.message, privacy: .public)]")
            if error.code == "TIMEOUT" || error.code == "NETWORK_ERROR" {
                authState = .noConnectivity
            } else if error.code == "unauthorized" {
                authState = .unauthenticated
            } else if error.code == "email_not_verified" {
                // Defensive: client-side gating means this normally isn't called
                // while unverified, but a race (the routed user said verified, a
                // concurrent change-email reset the flag) can still yield the
                // gate's 403. Route to the blocked screen, not .unexpectedError.
                authState = .verificationRequired(user)
            } else if let status = error.status, status >= 500 {
                authState = .serverError
            } else {
                authState = .unexpectedError
            }
        } catch {
            logger.error("Profile resolve failed (unexpected): [\(error, privacy: .public)]")
            authState = .unexpectedError
        }
    }
}
