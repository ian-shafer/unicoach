import Foundation
import os

@MainActor
class AppViewModel: ObservableObject {
    @Published var authState: UserAuthState = .loading
    let authClient: AuthClientProtocol
    let studentClient: StudentClientProtocol
    let conversationClient: ConversationClientProtocol
    let cookieStorage: CookieStorageProtocol
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "AppViewModel")

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
