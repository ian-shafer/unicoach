import SwiftUI

struct AuthFlowView: View {
    let authClient: AuthClientProtocol & GoogleAuthenticating
    let googleSignInProvider: GoogleSignInProviding
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
                onLoginSuccess: onLoginSuccess,
                onSwitchToRegister: {
                    withAnimation { showingRegistration = true }
                }
            )
            .transition(.move(edge: .leading))
        }
    }
}
