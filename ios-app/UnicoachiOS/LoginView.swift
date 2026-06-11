import SwiftUI

struct LoginView: View {
    @StateObject private var viewModel: LoginViewModel
    let onSwitchToRegister: () -> Void
    @FocusState private var focusedField: Field?

    enum Field {
        case email, password
    }

    init(authClient: AuthClientProtocol, onLoginSuccess: @escaping (PublicUser) async -> Void, onSwitchToRegister: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: LoginViewModel(authClient: authClient, onLoginSuccess: onLoginSuccess))
        self.onSwitchToRegister = onSwitchToRegister
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                Text("Welcome Back")
                    .font(.dsTitleXL)
                    .foregroundStyle(Color.dsTextPrimary)

                VStack(spacing: DSSpacing.md) {
                    LabeledField(
                        "Email",
                        text: $viewModel.email,
                        focus: $focusedField,
                        equals: .email,
                        keyboardType: .emailAddress,
                        submitLabel: .next,
                        accessibilityIdentifier: "loginEmailField",
                        accessibilityLabel: "Email",
                        onSubmit: { focusedField = .password }
                    )

                    LabeledField(
                        "Password",
                        text: $viewModel.password,
                        isSecure: true,
                        focus: $focusedField,
                        equals: .password,
                        submitLabel: .go,
                        accessibilityIdentifier: "loginPasswordField",
                        accessibilityLabel: "Password",
                        onSubmit: { Task { await viewModel.login() } }
                    )
                }

                if let errorResponse = viewModel.errorResponse {
                    FormErrorBanner(errorResponse.message)
                }

                LoadingButton(
                    "Log In",
                    isLoading: viewModel.isLoading,
                    role: .primary,
                    accessibilityIdentifier: "loginButton",
                    accessibilityLabel: "Log In",
                    action: { Task { await viewModel.login() } }
                )

                Button(action: onSwitchToRegister) {
                    Text("Don't have an account? Register")
                        .font(.dsLabel)
                        .frame(maxWidth: .infinity)
                }
                .foregroundStyle(Color.brandAccent)
                .accessibilityIdentifier("switchToRegisterButton")
                .accessibilityLabel("Register")
            }
            .padding(DSSpacing.lg)
        }
        .background(Color.dsBackground)
        .fullScreenCover(item: $viewModel.infrastructureError) { error in
            ErrorView(
                title: error.title,
                description: error.description,
                systemImage: error.systemImage,
                retryAction: {
                    viewModel.infrastructureError = nil
                    Task { await viewModel.login() }
                }
            )
        }
    }
}

private final class LoginPreviewAuthClient: AuthClientProtocol, @unchecked Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse {
        RegisterResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview"))
    }
    func login(request: LoginRequest) async throws -> LoginResponse {
        LoginResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview"))
    }
    func logout() async throws {}
    func me() async throws -> MeResponse {
        MeResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview"))
    }
}

#Preview {
    LoginView(
        authClient: LoginPreviewAuthClient(),
        onLoginSuccess: { _ in },
        onSwitchToRegister: {}
    )
}
