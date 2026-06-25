import SwiftUI

@main
struct UnicoachiOSApp: App {
    @StateObject private var viewModel = AppViewModel()
    @Environment(\.scenePhase) private var scenePhase

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
                        onLoginSuccess: viewModel.onLoginSuccess,
                        onRegisterSuccess: viewModel.onRegisterSuccess
                    )
                case .onboarding(let user):
                    OnboardingView(
                        studentClient: viewModel.studentClient,
                        onComplete: { viewModel.onOnboardingComplete(user) }
                    )
                case .authenticated(let user):
                    HomeView(
                        user: user,
                        conversationClient: viewModel.conversationClient,
                        onProfileRequired: viewModel.onStudentProfileRequired,
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
            .onChange(of: scenePhase) { _, newPhase in
                // Returning to the foreground is the primary detection path for an
                // emailVerified flip: the user must leave the app to open the
                // verification link (deep-linking is out of scope). Every .active
                // transition silently re-checks while blocked; the outcome is ignored.
                if newPhase == .active, case .verificationRequired = viewModel.authState {
                    Task { _ = await viewModel.recheckVerification() }
                }
            }
        }
    }
}
