import SwiftUI

struct RegistrationView: View {
    @StateObject private var viewModel: RegistrationViewModel
    let onSwitchToLogin: () -> Void

    enum FocusField {
        case email
        case name
        case password
    }

    @FocusState private var focusedField: FocusField?

    init(authClient: AuthClientProtocol, onRegisterSuccess: @escaping (PublicUser) -> Void, onSwitchToLogin: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: RegistrationViewModel(authClient: authClient, onRegisterSuccess: onRegisterSuccess))
        self.onSwitchToLogin = onSwitchToLogin
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                Text("Create Account")
                    .font(.dsTitleXL)
                    .foregroundStyle(Color.dsTextPrimary)

                VStack(spacing: DSSpacing.md) {
                    LabeledField(
                        "Email",
                        text: $viewModel.email,
                        error: viewModel.errorResponse?.fieldError(for: "email"),
                        focus: $focusedField,
                        equals: .email,
                        keyboardType: .emailAddress,
                        submitLabel: .next,
                        accessibilityIdentifier: "emailField",
                        accessibilityLabel: "Email",
                        onSubmit: { focusedField = .name }
                    )

                    LabeledField(
                        "Name",
                        text: $viewModel.name,
                        error: viewModel.errorResponse?.fieldError(for: "name"),
                        focus: $focusedField,
                        equals: .name,
                        autocapitalization: .words,
                        submitLabel: .next,
                        accessibilityIdentifier: "nameField",
                        accessibilityLabel: "Name",
                        onSubmit: { focusedField = .password }
                    )

                    LabeledField(
                        "Password",
                        text: $viewModel.password,
                        isSecure: true,
                        error: viewModel.errorResponse?.fieldError(for: "password"),
                        focus: $focusedField,
                        equals: .password,
                        submitLabel: .done,
                        accessibilityIdentifier: "passwordField",
                        accessibilityLabel: "Password",
                        onSubmit: {
                            focusedField = nil
                            register()
                        }
                    )
                }

                if let errorResponse = viewModel.errorResponse {
                    FormErrorBanner(errorResponse.message)
                }

                LoadingButton(
                    "Register",
                    isLoading: viewModel.isLoading,
                    role: .primary,
                    accessibilityIdentifier: "registerButton",
                    accessibilityLabel: "Register",
                    progressAccessibilityIdentifier: "loadingIndicator",
                    action: register
                )

                Button(action: onSwitchToLogin) {
                    Text("Already have an account? Log in")
                        .font(.dsLabel)
                        .frame(maxWidth: .infinity)
                }
                .foregroundStyle(Color.brandAccent)
                .accessibilityIdentifier("switchToLoginButton")
                .accessibilityLabel("Log In")
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
                    register()
                }
            )
        }
    }

    private func register() {
        Task {
            await viewModel.register()
        }
    }
}

private final class RegistrationPreviewAuthClient: AuthClientProtocol, @unchecked Sendable {
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
    RegistrationView(
        authClient: RegistrationPreviewAuthClient(),
        onRegisterSuccess: { _ in },
        onSwitchToLogin: {}
    )
}
