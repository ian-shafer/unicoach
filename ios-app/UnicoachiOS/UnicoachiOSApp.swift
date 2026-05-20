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
                case .authenticated(let user):
                    HomeView(user: user, onLogout: viewModel.logout)
                case .serverError:
                    ErrorView(
                        title: "Something Went Wrong",
                        description: "We couldn't reach the server. Please try again later.",
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
