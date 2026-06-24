import Foundation
import os

@MainActor
class AppViewModel: ObservableObject {
    @Published var authState: UserAuthState = .loading
    let authClient: AuthClientProtocol
    let studentClient: StudentClientProtocol
    let conversationClient: ConversationClientProtocol
    let cookieStorage: CookieStorageProtocol
    private let logger = Logger(subsystem: "com.unicoachapp.UnicoachiOS", category: "AppViewModel")

    init(
        apiClient: APIClient = APIClient(),
        cookieStorage: CookieStorageProtocol = HTTPCookieStorage.shared,
        authClient: AuthClientProtocol? = nil,
        studentClient: StudentClientProtocol? = nil,
        conversationClient: ConversationClientProtocol? = nil
    ) {
        self.authClient = authClient ?? AuthClient(apiClient: apiClient)
        self.studentClient = studentClient ?? StudentClient(apiClient: apiClient)
        self.conversationClient = conversationClient ?? ConversationClient(apiClient: apiClient)
        self.cookieStorage = cookieStorage
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
            logger.error("Network logout failed, proceeding with local session clear: \(error.localizedDescription)")
        }
        if let cookies = cookieStorage.cookies {
            for cookie in cookies {
                cookieStorage.deleteCookie(cookie)
            }
        }
        authState = .unauthenticated
    }

    private func resolveProfileState(_ user: PublicUser) async {
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
