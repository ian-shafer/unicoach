import Foundation
import os

@MainActor
class AppViewModel: ObservableObject {
    @Published var authState: UserAuthState = .loading
    let authClient: AuthClientProtocol
    let studentClient: StudentClientProtocol
    let cookieStorage: CookieStorageProtocol
    private let logger = Logger(subsystem: "com.unicoach.UnicoachiOS", category: "AppViewModel")

    init(
        apiClient: APIClient = APIClient(),
        cookieStorage: CookieStorageProtocol = HTTPCookieStorage.shared,
        authClient: AuthClientProtocol? = nil,
        studentClient: StudentClientProtocol? = nil
    ) {
        self.authClient = authClient ?? AuthClient(apiClient: apiClient)
        self.studentClient = studentClient ?? StudentClient(apiClient: apiClient)
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
            } else {
                authState = .serverError
            }
        } catch {
            logger.error("Session check failed (unexpected): [\(error, privacy: .public)]")
            authState = .serverError
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
            } else if error.code == "UNAUTHORIZED" {
                authState = .unauthenticated
            } else {
                authState = .serverError
            }
        } catch {
            logger.error("Profile resolve failed (unexpected): [\(error, privacy: .public)]")
            authState = .serverError
        }
    }
}
