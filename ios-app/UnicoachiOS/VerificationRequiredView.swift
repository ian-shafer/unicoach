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
        VStack(spacing: 0) {
            BrandTopBar()

            ScrollView {
                VStack(alignment: .leading, spacing: DSSpacing.lg) {
                    Text("Verify Your Email")
                        .font(.dsDisplay)
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

                    VStack(spacing: DSControl.stackGap) {
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
                        // Not brandAccent: `#EE732F` on white is 2.95:1 (DESIGN.md §6).
                        .foregroundStyle(Color.dsTextPrimary)
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
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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

@MainActor private var verificationPreview: some View {
    VerificationRequiredView(
        user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: false),
        authClient: VerificationPreviewAuthClient(),
        onRecheck: { .stillUnverified },
        onLogout: {}
    )
}

#Preview("verification - Light") {
    verificationPreview
        .preferredColorScheme(.light)
}

#Preview("verification - Dark") {
    verificationPreview
        .preferredColorScheme(.dark)
}
