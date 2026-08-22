import SwiftUI

/// The change-email sheet. It lives in its own file because it has **two** call
/// sites: the pre-verification blocking screen (`VerificationRequiredView`) and
/// `SettingsView` — before RFC 117 it was reachable only from the former, so a
/// verified student could not change their email at all.
///
/// Presented as a sheet, so it carries its own `NavigationStack` for the Cancel
/// toolbar item; that stack is modal chrome, not part of the authenticated
/// tree's single stack (DESIGN.md §7).
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
                        .font(.dsDisplay)
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

private final class ChangeEmailPreviewAuthClient: AuthClientProtocol, @unchecked Sendable {
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

@MainActor private var changeEmailPreview: some View {
    ChangeEmailView(
        currentEmail: "preview@example.com",
        authClient: ChangeEmailPreviewAuthClient(),
        onChanged: { _ in }
    )
}

#Preview("changeEmail - Light") {
    changeEmailPreview
        .preferredColorScheme(.light)
}

#Preview("changeEmail - Dark") {
    changeEmailPreview
        .preferredColorScheme(.dark)
}
