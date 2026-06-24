import SwiftUI

@main
struct UnicoachiOSApp: App {
    @StateObject private var viewModel = AppViewModel()
    
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
        }
    }
}
