import SwiftUI

struct AuthFlowView: View {
    let authClient: AuthClientProtocol
    @State private var showingRegistration = false
    let onLoginSuccess: (PublicUser) -> Void
    let onRegisterSuccess: (PublicUser) -> Void
    
    var body: some View {
        if showingRegistration {
            RegistrationView(
                authClient: authClient,
                onRegisterSuccess: onRegisterSuccess,
                onSwitchToLogin: {
                    withAnimation { showingRegistration = false }
                }
            )
            .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
        } else {
            LoginView(
                authClient: authClient,
                onLoginSuccess: onLoginSuccess,
                onSwitchToRegister: {
                    withAnimation { showingRegistration = true }
                }
            )
            .transition(.asymmetric(insertion: .move(edge: .leading), removal: .move(edge: .trailing)))
        }
    }
}
