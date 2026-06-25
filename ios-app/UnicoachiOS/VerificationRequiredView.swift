import SwiftUI

struct VerificationRequiredView: View {
    @StateObject private var viewModel: VerificationViewModel
    @State private var isLoggingOut = false
    @State private var isChangingEmail = false

    init(
        user: PublicUser,
        authClient: AuthClientProtocol,
        onRecheck: @escaping () async -> AppViewModel.VerificationRecheckOutcome,
        onLogout: @escaping () async -> Void
    ) {
        _viewModel = StateObject(
            wrappedValue: VerificationViewModel(
                user: user,
                authClient: authClient,
                onRecheck: onRecheck,
                onLogout: onLogout
            )
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                Text("Verify Your Email")
                    .font(.dsTitleXL)
                    .foregroundStyle(Color.dsTextPrimary)

                Text("We sent a verification link to \(viewModel.email). Tap it to finish setting up your account, then check again.")
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextSecondary)

                if let confirmation = viewModel.changeConfirmation {
                    Text(confirmation)
                        .font(.dsLabel)
                        .foregroundStyle(Color.dsTextSecondary)
                        .accessibilityIdentifier("changeEmailConfirmation")
                }

                if let confirmation = viewModel.resendConfirmation {
                    Text(confirmation)
                        .font(.dsLabel)
                        .foregroundStyle(Color.dsTextSecondary)
                        .accessibilityIdentifier("resendConfirmation")
                }

                if let resendError = viewModel.resendError {
                    FormErrorBanner(resendError.message)
                }

                if let recheckMessage = viewModel.recheckMessage {
                    Text(recheckMessage)
                        .font(.dsLabel)
                        .foregroundStyle(Color.dsTextSecondary)
                        .accessibilityIdentifier("recheckMessage")
                }

                VStack(spacing: DSSpacing.md) {
                    LoadingButton(
                        "Check Again",
                        isLoading: viewModel.isChecking,
                        role: .primary,
                        accessibilityIdentifier: "checkAgainButton",
                        accessibilityLabel: "Check Again",
                        action: { Task { await viewModel.checkAgain() } }
                    )

                    LoadingButton(
                        "Resend Email",
                        isLoading: viewModel.isResending,
                        role: .primary,
                        accessibilityIdentifier: "resendVerificationButton",
                        accessibilityLabel: "Resend Email",
                        action: { Task { await viewModel.resend() } }
                    )

                    Button(action: { isChangingEmail = true }) {
                        Text("Change Email")
                            .font(.dsLabel)
                            .frame(maxWidth: .infinity)
                    }
                    .foregroundStyle(Color.brandAccent)
                    .accessibilityIdentifier("changeEmailButton")
                    .accessibilityLabel("Change Email")

                    LoadingButton(
                        "Log Out",
                        isLoading: isLoggingOut,
                        role: .destructive,
                        accessibilityIdentifier: "logoutButton",
                        accessibilityLabel: "Log Out",
                        action: {
                            isLoggingOut = true
                            Task {
                                await viewModel.onLogout()
                                isLoggingOut = false
                            }
                        }
                    )
                }
            }
            .padding(DSSpacing.lg)
        }
        .background(Color.dsBackground)
        .sheet(isPresented: $isChangingEmail) {
            ChangeEmailView(
                currentEmail: viewModel.email,
                authClient: viewModel.authClient,
                onChanged: { user in
                    viewModel.onEmailChanged(user)
                    isChangingEmail = false
                }
            )
        }
    }
}

struct ChangeEmailView: View {
    @StateObject private var viewModel: ChangeEmailViewModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var emailFocused: Bool?

    init(currentEmail: String, authClient: AuthClientProtocol, onChanged: @escaping (PublicUser) -> Void) {
        _viewModel = StateObject(
            wrappedValue: ChangeEmailViewModel(
                email: currentEmail,
                authClient: authClient,
                onChanged: onChanged
            )
        )
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: DSSpacing.lg) {
                    Text("Change Email")
                        .font(.dsTitleXL)
                        .foregroundStyle(Color.dsTextPrimary)

                    Text("Enter a new email address. We'll send a fresh verification link there.")
                        .font(.dsBody)
                        .foregroundStyle(Color.dsTextSecondary)

                    LabeledField(
                        "Email",
                        text: $viewModel.email,
                        error: viewModel.errorResponse?.fieldError(for: "email"),
                        focus: $emailFocused,
                        equals: true,
                        keyboardType: .emailAddress,
                        submitLabel: .go,
                        accessibilityIdentifier: "changeEmailField",
                        accessibilityLabel: "Email",
                        onSubmit: { Task { await viewModel.submit() } }
                    )

                    if let errorResponse = viewModel.errorResponse, errorResponse.fieldError(for: "email") == nil {
                        FormErrorBanner(errorResponse.message)
                    }

                    LoadingButton(
                        "Send Verification",
                        isLoading: viewModel.isLoading,
                        role: .primary,
                        accessibilityIdentifier: "submitChangeEmailButton",
                        accessibilityLabel: "Send Verification",
                        action: { Task { await viewModel.submit() } }
                    )
                }
                .padding(DSSpacing.lg)
            }
            .background(Color.dsBackground)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("cancelChangeEmailButton")
                }
            }
        }
    }
}

private final class VerificationPreviewAuthClient: AuthClientProtocol, @unchecked Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse {
        RegisterResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: false))
    }
    func login(request: LoginRequest) async throws -> LoginResponse {
        LoginResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: false))
    }
    func logout() async throws {}
    func me() async throws -> MeResponse {
        MeResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: false))
    }
    func resendVerification() async throws {}
    func changeEmail(_ email: String) async throws -> PublicUser {
        PublicUser(id: UUID(), email: email, name: "Preview", emailVerified: false)
    }
}

#Preview {
    VerificationRequiredView(
        user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: false),
        authClient: VerificationPreviewAuthClient(),
        onRecheck: { .stillUnverified },
        onLogout: {}
    )
}
