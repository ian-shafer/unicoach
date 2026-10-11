import Foundation
import os

@MainActor
class AppViewModel: ObservableObject {
    @Published var authState: UserAuthState = .loading
    /// Set when this device just closed its account (RFC 252), so the login
    /// screen the app lands on can say so. Cleared by the next sign-in.
    @Published private(set) var accountClosed = false
    let authClient: AuthClientProtocol & SsoAuthenticating & AccountDeleting
    let studentClient: StudentClientProtocol
    let moneyProfileClient: MoneyProfileClientProtocol
    let conversationClient: ConversationClientProtocol
    let collegeListClient: CollegeListClientProtocol
    let vocabularyClient: VocabularyClientProtocol
    /// The two RFC 205 surfaces: the subject taxonomy the picker searches, and
    /// the student's own answer. Two clients because they are two resources —
    /// one is reference data every student sees, the other is one student's
    /// answer.
    let subjectsClient: SubjectsClientProtocol
    let intendedSubjectsClient: IntendedSubjectsClientProtocol
    let googleSignInProvider: SsoSignInProviding
    let appleSignInProvider: SsoSignInProviding
    let cookieStorage: CookieStorageProtocol
    let coachingUsageClient: CoachingUsageClientProtocol
    let supporterInviteClient: SupporterInviteClientProtocol
    let supporterClient: SupporterClientProtocol
    /// The parent's own coaching meter (RFC 244). A second client beside
    /// `coachingUsageClient` and not a replacement for it: the two routes are
    /// caller-scoped to the two roles, and a supporter reading the student one
    /// could only be refused.
    let supporterCoachingUsageClient: SupporterCoachingUsageClientProtocol
    /// The working-student selection (RFC 241). Held at the composition root
    /// like every other dependency, so a test substitutes it by conforming to
    /// the protocol rather than by reaching into `UserDefaults`.
    let supporterSubjectStore: SupporterSubjectStore
    /// The name Apple disclosed at first sign-in, cleared when an account is
    /// deleted (RFC 252). The default `AppleSignInProvider` is built over this
    /// same instance, so the sweep clears what sign-in wrote.
    let appleNameStore: AppleNameStore

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
        authClient: (AuthClientProtocol & SsoAuthenticating & AccountDeleting)? = nil,
        studentClient: StudentClientProtocol? = nil,
        moneyProfileClient: MoneyProfileClientProtocol? = nil,
        conversationClient: ConversationClientProtocol? = nil,
        collegeListClient: CollegeListClientProtocol? = nil,
        vocabularyClient: VocabularyClientProtocol? = nil,
        subjectsClient: SubjectsClientProtocol? = nil,
        intendedSubjectsClient: IntendedSubjectsClientProtocol? = nil,
        coachingUsageClient: CoachingUsageClientProtocol? = nil,
        supporterInviteClient: SupporterInviteClientProtocol? = nil,
        supporterClient: SupporterClientProtocol? = nil,
        supporterCoachingUsageClient: SupporterCoachingUsageClientProtocol? = nil,
        supporterSubjectStore: SupporterSubjectStore? = nil,
        appleNameStore: AppleNameStore = UserDefaultsAppleNameStore(),
        subscriptionClient: SubscriptionClientProtocol? = nil,
        subscriptionStore: (SubscriptionStoreProtocol & TransactionFinishing) = AppViewModel.defaultSubscriptionStore(),
        googleSignInProvider: SsoSignInProviding = GoogleSignInProvider(),
        appleSignInProvider: SsoSignInProviding? = nil
    ) {
        self.authClient = authClient ?? AuthClient(apiClient: apiClient)
        self.studentClient = studentClient ?? StudentClient(apiClient: apiClient)
        self.moneyProfileClient = moneyProfileClient ?? MoneyProfileClient(apiClient: apiClient)
        self.conversationClient = conversationClient ?? ConversationClient(apiClient: apiClient)
        self.collegeListClient = collegeListClient ?? CollegeListClient(apiClient: apiClient)
        self.vocabularyClient = vocabularyClient ?? VocabularyClient(apiClient: apiClient)
        self.subjectsClient = subjectsClient ?? SubjectsClient(apiClient: apiClient)
        self.intendedSubjectsClient = intendedSubjectsClient ?? IntendedSubjectsClient(apiClient: apiClient)
        self.coachingUsageClient = coachingUsageClient ?? CoachingUsageClient(apiClient: apiClient)
        self.supporterInviteClient = supporterInviteClient ?? SupporterInviteClient(apiClient: apiClient)
        self.supporterClient = supporterClient ?? SupporterClient(apiClient: apiClient)
        self.supporterCoachingUsageClient = supporterCoachingUsageClient
            ?? SupporterCoachingUsageClient(apiClient: apiClient)
        self.supporterSubjectStore = supporterSubjectStore ?? UserDefaultsSupporterSubjectStore()
        self.appleNameStore = appleNameStore
        self.googleSignInProvider = googleSignInProvider
        self.appleSignInProvider = appleSignInProvider ?? AppleSignInProvider(nameStore: appleNameStore)
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
        accountClosed = false
        await resolveProfileState(user)
    }

    func onRegisterSuccess(_ user: PublicUser) async {
        accountClosed = false
        await resolveProfileState(user)
    }

    func onOnboardingComplete(_ user: PublicUser) {
        authState = .authenticated(user)
    }

    /// "I'm the student" on the role choice (RFC 241): the existing onboarding
    /// form, unchanged. No server call — creating the `students` row is what
    /// that form does, and doing it here would write a row from a tap that has
    /// not been asked its questions yet.
    func onStudentRoleChosen(_ user: PublicUser) {
        authState = .onboarding(user)
    }

    /// Back out of "I'm the student" (RFC 241).
    ///
    /// The tap is reversible because the question has two answers and neither
    /// of them has written a row yet: the `students` row is written by the
    /// FORM, and the `supporters` row by the `POST`. Without this, a parent who
    /// mistaps is on a form asking for THEIR date of birth with no exit but
    /// logging out — and once that form is submitted the supporter home is
    /// unreachable, because `resolveProfileState` reads the student row first.
    func onRoleChoiceRequested(_ user: PublicUser) {
        authState = .roleChoice(user)
    }

    /// "I'm supporting a student" succeeded (RFC 241). The home comes from the
    /// `POST`'s own response — one read, not a second confirming `GET`.
    func onSupporterHome(_ user: PublicUser, _ home: SupporterHome) {
        authState = .supporterHome(user, home)
    }

    /// Re-reads a supporter's roster and hands it back to the state machine
    /// (RFC 241), from the home's own appearance and from every return to the
    /// foreground.
    ///
    /// The home's headline action is the invite, it is shared through the
    /// system share sheet — so the parent LEAVES the app to send it — and its
    /// result is a name on this roster. Without this read the roster is frozen
    /// at the resolve that built the screen, and a parent who watches their
    /// student accept sees "No student linked yet" until the process is
    /// relaunched. At one query per second a `GET /api/v1/supporters/me` per
    /// appearance is not the load this app has to save.
    ///
    /// A failure leaves the resolved home standing and is logged, not shown:
    /// nobody asked for this read, so it has no refusal to report — and it
    /// refreshes a ROSTER, it does not re-decide what the account is.
    func refreshSupporterHome() async {
        guard case .supporterHome(let user, _) = authState else {
            return
        }
        do {
            // `nil` is the server reporting that this caller holds no
            // `supporters` row — a 404, not a transport failure. The resolved
            // home stays up, because this refreshes a ROSTER and does not
            // re-decide what the account is, so this line is the only evidence
            // that the row went away mid-session.
            guard let home = try await supporterClient.fetchHome() else {
                logger.error("Supporter home refresh found no supporter row; leaving the resolved home standing")
                return
            }
            // Re-read AFTER the await, not assumed from before it. `@MainActor`
            // rules out a data race, not an interleaving: a logout — or any
            // re-resolution — that lands while `fetchHome()` is in flight has
            // already decided what the screen is, and this read reports a
            // ROSTER. Without this guard the resumed continuation puts the
            // parent back on a home whose session cookie is already deleted.
            guard case .supporterHome(let currentUser, _) = authState, currentUser.id == user.id else {
                return
            }
            authState = .supporterHome(currentUser, home)
        } catch {
            logger.error("Supporter home refresh failed: [\(String(describing: error), privacy: .public)]")
        }
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

    /// The in-flight re-resolution started by [onStudentProfileRequired].
    ///
    /// Exposed so a test can await the work a SYNCHRONOUS entry point starts.
    /// `onProfileRequired` is a `() -> Void` threaded through nine call sites
    /// and two view trees; widening it to an async closure to make one re-read
    /// awaitable would change every one of them for no gain at any of them.
    private(set) var profileRefreshTask: Task<Void, Never>?

    /// Set for as long as a re-resolution is in flight.
    ///
    /// `authState` stays `.authenticated` across the round trip — deliberately,
    /// see below — so it cannot do this job. Without it every concurrent `409`
    /// starts its own pair of reads, and a profile deleted server-side refuses
    /// every student-scoped call on the screen at once: the stream, the college
    /// list and the usage meter would each start a re-resolution, only the last
    /// handle would be kept, and whichever finished last would decide the
    /// screen.
    private var isResolvingProfile = false

    /// Re-entry point for the abnormal `409 student_profile_required` edge:
    /// profile gating guarantees the authenticated tree is reachable only with
    /// a profile, so a `409` from a student-scoped endpoint means the profile
    /// was deleted server-side mid-session.
    ///
    /// It now RE-RESOLVES rather than asserting `.onboarding` (RFC 241). The
    /// `409` proves there is no `students` row, which used to be the whole
    /// question; it does not prove there is no `supporters` row, and a caller
    /// may legitimately hold both. Sending a supporter into the student
    /// onboarding form is the exact defect this slice removes, so the answer
    /// comes from the one function that knows all three states.
    ///
    /// The screen stays up for the round trip rather than blanking: it is
    /// already showing a refusal, and `.loading` would re-enter `checkSession`
    /// and read `auth/me` a second time.
    func onStudentProfileRequired() {
        guard case .authenticated(let user) = authState, !isResolvingProfile else {
            return
        }
        isResolvingProfile = true
        profileRefreshTask = Task {
            await resolveProfileState(user)
            // Reached on every path `resolveProfileState` has today: it
            // swallows its own failures into a state assignment and returns
            // normally on cancellation, so it never throws past here. A
            // `resolveProfileState` that gains a `throw` or an early `return`
            // must clear this in a `defer` instead — a latch left set refuses
            // every LATER 409 for the life of the session.
            isResolvingProfile = false
        }
    }

    func logout() async {
        // A re-resolution started under THIS session must not assign a state
        // under the next one. `onStudentProfileRequired` runs its reads in a
        // detached `Task` that no view teardown cancels, so the teardown that
        // invalidates its answer has to say so itself; `resolveProfileState`
        // checks cancellation before every write.
        profileRefreshTask?.cancel()
        do {
            try await authClient.logout()
        } catch {
            logger.error("Network logout failed, proceeding with local session clear: [\(error, privacy: .public)]")
        }
        endLocalSession()
    }

    /// Closes the signed-in account (RFC 252): `DELETE /api/v1/auth/me`, then
    /// the logout tail, plus the per-user `UserDefaults` this device holds.
    ///
    /// Returns `false` when the server did not confirm the close. Nothing local
    /// is touched then: the account still exists, so the person stays signed
    /// in and Settings says the attempt failed — unless the refusal is
    /// `unauthorized`. That session is already dead (the account closed from
    /// another device, or the session was revoked), so the app ends it locally
    /// and lands on the login screen, as `checkSession` would.
    func deleteAccount() async -> Bool {
        do {
            try await authClient.deleteAccount()
        } catch {
            logger.error("Account deletion failed: [\(String(describing: error), privacy: .public)]")
            if let refusal = error as? ErrorResponse, refusal.code == "unauthorized" {
                profileRefreshTask?.cancel()
                endLocalSession()
            }
            return false
        }
        if let user = signedInUser {
            supporterSubjectStore.clear(forUserId: user.id)
        }
        appleNameStore.removeAll()
        profileRefreshTask?.cancel()
        accountClosed = true
        endLocalSession()
        return true
    }

    /// The local half of ending a session, shared by logout and deletion.
    private func endLocalSession() {
        if let cookies = cookieStorage.cookies {
            for cookie in cookies {
                cookieStorage.deleteCookie(cookie)
            }
        }
        authState = .unauthenticated
    }

    /// The user the current state is about, when it is about one.
    private var signedInUser: PublicUser? {
        switch authState {
        case .authenticated(let user),
             .onboarding(let user),
             .roleChoice(let user),
             .supporterHome(let user, _),
             .verificationRequired(let user):
            return user
        case .loading, .unauthenticated, .serverError, .unexpectedError, .noConnectivity:
            return nil
        }
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
            // THREE answers, resolved in this order (RFC 241).
            //
            // The student read comes FIRST and is unchanged, so every student
            // reaches the app on exactly the reads they did before — one — and
            // the supporter read is paid only by an account that has no
            // `students` row. An account holding both rows is a student here,
            // which is what the student app being the richer surface means.
            //
            // Every arm re-checks cancellation before it writes. This function
            // is reached from a detached `Task` (`onStudentProfileRequired`)
            // that outlives the screen that started it, and `logout()` cancels
            // that task: a resolve begun under the old session must not decide
            // the state under the next one.
            if try await studentClient.fetchProfile() != nil {
                guard !Task.isCancelled else { return }
                authState = .authenticated(user)
            } else if let home = try await supporterClient.fetchHome() {
                guard !Task.isCancelled else { return }
                authState = .supporterHome(user, home)
            } else {
                guard !Task.isCancelled else { return }
                // Neither row. Nothing yet says what this account is for, so
                // the app asks rather than assuming "student who has not
                // finished onboarding".
                authState = .roleChoice(user)
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
