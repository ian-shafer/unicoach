import SwiftUI

struct AuthFlowView: View {
    let authClient: AuthClientProtocol & SsoAuthenticating
    let googleSignInProvider: SsoSignInProviding
    let appleSignInProvider: SsoSignInProviding
    /// This device just deleted its account (RFC 252): the login screen says so.
    let accountClosed: Bool
    @State private var showingRegistration = false
    let onLoginSuccess: (PublicUser) async -> Void
    let onRegisterSuccess: (PublicUser) async -> Void

    var body: some View {
        if showingRegistration {
            RegistrationView(
                authClient: authClient,
                onRegisterSuccess: onRegisterSuccess,
                onSwitchToLogin: {
                    withAnimation { showingRegistration = false }
                }
            )
            .transition(.move(edge: .trailing))
        } else {
            LoginView(
                authClient: authClient,
                googleSignInProvider: googleSignInProvider,
                appleSignInProvider: appleSignInProvider,
                accountClosed: accountClosed,
                onLoginSuccess: onLoginSuccess,
                onSwitchToRegister: {
                    withAnimation { showingRegistration = true }
                }
            )
            .transition(.move(edge: .leading))
        }
    }
}
