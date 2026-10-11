import GoogleSignIn
import SwiftUI

@main
struct UnicoachiOSApp: App {
    @StateObject private var viewModel = AppViewModel()
    @Environment(\.scenePhase) private var scenePhase
    /// Applied at the scene, not inside the authenticated tree, so the login and
    /// verification screens honour it too.
    @AppStorage(AppearancePreference.storageKey) private var appearance: AppearancePreference = .system

    var body: some Scene {
        WindowGroup {
            Group {
                switch viewModel.authState {
                case .loading:
                    ProgressView()
                        .task { await viewModel.checkSession() }
                case .unauthenticated:
                    AuthFlowView(
                        authClient: viewModel.authClient,
                        googleSignInProvider: viewModel.googleSignInProvider,
                        appleSignInProvider: viewModel.appleSignInProvider,
                        onLoginSuccess: viewModel.onLoginSuccess,
                        onRegisterSuccess: viewModel.onRegisterSuccess
                    )
                case .onboarding(let user):
                    OnboardingView(
                        studentClient: viewModel.studentClient,
                        moneyProfileClient: viewModel.moneyProfileClient,
                        userName: user.name,
                        onComplete: { viewModel.onOnboardingComplete(user) },
                        onBack: { viewModel.onRoleChoiceRequested(user) }
                    )
                case .roleChoice(let user):
                    RoleChoiceView(
                        userName: user.name,
                        supporterClient: viewModel.supporterClient,
                        onStudent: { viewModel.onStudentRoleChosen(user) },
                        onSupporter: { home in viewModel.onSupporterHome(user, home) }
                    )
                case .supporterHome(let user, let home):
                    SupporterHomeView(
                        user: user,
                        home: home,
                        authClient: viewModel.authClient,
                        supporterInviteClient: viewModel.supporterInviteClient,
                        supporterClient: viewModel.supporterClient,
                        collegeListClient: viewModel.collegeListClient,
                        supporterCoachingUsageClient: viewModel.supporterCoachingUsageClient,
                        subjectStore: viewModel.supporterSubjectStore,
                        refreshHome: viewModel.refreshSupporterHome,
                        onEmailChanged: viewModel.onEmailChanged,
                        onLogout: viewModel.logout
                    )
                case .authenticated(let user):
                    AuthenticatedRootView(
                        user: user,
                        authClient: viewModel.authClient,
                        conversationClient: viewModel.conversationClient,
                        collegeListClient: viewModel.collegeListClient,
                        moneyProfileClient: viewModel.moneyProfileClient,
                        vocabularyClient: viewModel.vocabularyClient,
                        subjectsClient: viewModel.subjectsClient,
                        intendedSubjectsClient: viewModel.intendedSubjectsClient,
                        coachingUsageClient: viewModel.coachingUsageClient,
                        supporterInviteClient: viewModel.supporterInviteClient,
                        subscriptionStore: viewModel.subscriptionStore,
                        transactionRecorder: viewModel.transactionRecorder,
                        onProfileRequired: viewModel.onStudentProfileRequired,
                        onEmailChanged: viewModel.onEmailChanged,
                        onLogout: viewModel.logout
                    )
                case .verificationRequired(let user):
                    VerificationRequiredView(
                        user: user,
                        authClient: viewModel.authClient,
                        onRecheck: viewModel.recheckVerification,
                        onLogout: viewModel.logout
                    )
                case .serverError:
                    ErrorView(
                        title: "Server Problem",
                        description: "Something went wrong on our end. Please try again in a moment.",
                        systemImage: "exclamationmark.triangle",
                        retryAction: { Task { await viewModel.checkSession() } }
                    )
                case .unexpectedError:
                    ErrorView(
                        title: "Something Went Wrong",
                        description: "The app ran into a problem it didn't expect. Please try again.",
                        systemImage: "exclamationmark.triangle",
                        retryAction: { Task { await viewModel.checkSession() } }
                    )
                case .noConnectivity:
                    ErrorView(
                        title: "No Connection",
                        description: "Check your internet connection and try again.",
                        systemImage: "wifi.slash",
                        retryAction: { Task { await viewModel.checkSession() } }
                    )
                }
            }
            // One app-wide accent. Without it every stock control SwiftUI still
            // owns — the navigation back button, toolbar glyphs, alert actions,
            // text-selection handles — renders in the system blue, which is the
            // exact colour this design removed.
            .tint(Color.dsTextPrimary)
            // nil for `.system`, which is SwiftUI's "follow the device".
            .preferredColorScheme(appearance.colorScheme)
            .onOpenURL { url in
                // Receive the Google OAuth callback under the SwiftUI App
                // lifecycle (no UIApplicationDelegate exists).
                GIDSignIn.sharedInstance.handle(url)
            }
            .onChange(of: scenePhase) { _, newPhase in
                // Returning to the foreground is the primary detection path for an
                // emailVerified flip: the user must leave the app to open the
                // verification link (deep-linking is out of scope). Every .active
                // transition silently re-checks while blocked; the outcome is ignored.
                if newPhase == .active, case .verificationRequired = viewModel.authState {
                    Task { _ = await viewModel.recheckVerification() }
                }
                // The same hook, for the same reason, on a parent's home
                // (RFC 241): the invite is shared through the system share
                // sheet, so coming BACK is when a parent expects the student
                // who accepted to be named. The home also reads on appearance;
                // this covers the return that is not a fresh appearance.
                if newPhase == .active, case .supporterHome = viewModel.authState {
                    Task { await viewModel.refreshSupporterHome() }
                }
            }
        }
    }
}
