import Foundation
import os

@MainActor
class AppViewModel: ObservableObject {
    @Published var authState: UserAuthState = .loading
    let authClient: AuthClientProtocol
    let cookieStorage: CookieStorageProtocol
    private let logger = Logger(subsystem: "com.unicoach.UnicoachiOS", category: "AppViewModel")
    
    init(authClient: AuthClientProtocol = AuthClient(), cookieStorage: CookieStorageProtocol = HTTPCookieStorage.shared) {
        self.authClient = authClient
        self.cookieStorage = cookieStorage
    }
    
    func checkSession() async {
        authState = .loading
        do {
            let response = try await authClient.me()
            authState = .authenticated(response.user)
        } catch let error as ErrorResponse {
            if error.code == "unauthorized" {
                authState = .unauthenticated
            } else if error.code == "TIMEOUT" || error.code == "NETWORK_ERROR" {
                authState = .noConnectivity
            } else {
                authState = .serverError
            }
        } catch {
            authState = .serverError
        }
    }
    
    func onLoginSuccess(_ user: PublicUser) {
        authState = .authenticated(user)
    }
    
    func onRegisterSuccess(_ user: PublicUser) {
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
}
