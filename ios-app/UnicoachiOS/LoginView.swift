import SwiftUI

struct LoginView: View {
    @StateObject private var viewModel: LoginViewModel
    let onSwitchToRegister: () -> Void
    @FocusState private var focusedField: Field?

    enum Field {
        case email, password
    }

    init(
        authClient: AuthClientProtocol & SsoAuthenticating,
        googleSignInProvider: SsoSignInProviding,
        appleSignInProvider: SsoSignInProviding,
        onLoginSuccess: @escaping (PublicUser) async -> Void,
        onSwitchToRegister: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: LoginViewModel(
            authClient: authClient,
            googleSignInProvider: googleSignInProvider,
            appleSignInProvider: appleSignInProvider,
            onLoginSuccess: onLoginSuccess
        ))
        self.onSwitchToRegister = onSwitchToRegister
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                logoLockup

                ssoButtons

                orDivider

                VStack(spacing: DSControl.stackGap) {
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
                    isLoading: viewModel.phase == .passwordLoading,
                    role: .primary,
                    accessibilityIdentifier: "loginButton",
                    accessibilityLabel: "Log In",
                    action: { Task { await viewModel.login() } }
                )
                .disabled(viewModel.phase != .idle)

                Button(action: onSwitchToRegister) {
                    Text("Don't have an account? Register")
                        .font(.dsLabel)
                        .frame(maxWidth: .infinity)
                }
                .foregroundStyle(Color.dsTextPrimary)
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
                    Task { await viewModel.retryLastAttempt() }
                }
            )
        }
    }

    /// The reference's login lockup: wordmark, circular brand mark, tagline —
    /// centred, and standing in for the screen heading the reference does not
    /// have. The tagline is `PRODUCT.md`'s positioning line verbatim.
    private var logoLockup: some View {
        VStack(spacing: DSSpacing.lg) {
            Text("uni.COACH")
                .font(.dsTitle)
                .foregroundStyle(Color.dsTextPrimary)

            LogoMark()

            Text("Personal college-prep coach")
                .font(.dsTitle)
                .foregroundStyle(Color.dsTextPrimary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    /// The "or" rule separating the password form from the SSO buttons.
    private var orDivider: some View {
        HStack(spacing: DSSpacing.md) {
            Rectangle()
                .fill(Color.dsFieldBorder)
                .frame(height: 1)
            Text("or")
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextSecondary)
            Rectangle()
                .fill(Color.dsFieldBorder)
                .frame(height: 1)
        }
    }

    /// The third-party sign-in block. Apple sits above Google per Apple's
    /// prominence rule, which binds Apple against other third-party sign-in
    /// rather than against our own password form. Each slot disables its
    /// control whenever any sign-in runs and overlays that provider's spinner
    /// on the stable box the control reserves.
    ///
    /// The spacing and alignment repeat the enclosing form's so the rendered
    /// gaps are exactly what they were before the block was named.
    private var ssoButtons: some View {
        VStack(alignment: .leading, spacing: DSControl.stackGap) {
            SsoButtonSlot(provider: .apple, phase: viewModel.phase) {
                AppleSignInButton {
                    Task { await viewModel.signInWithApple() }
                }
                // Google's slot needs no width modifier because
                // GoogleSignInButtonStyle stretches from the inside. The wrapped
                // ASAuthorizationAppleIDButton carries no such rule: its
                // sizeThatFits adopts whatever width it is proposed and falls
                // back to the control's intrinsic width when it is proposed
                // none. This VStack does propose one today, so the two buttons
                // would match without this; it is here so the pairing survives a
                // container that stops proposing a width.
                .frame(maxWidth: .infinity)
            }

            // GoogleSignInButton stretches full-width and reports its own
            // height via its button style (matching the Log In button above),
            // so the slot reserves real vertical space and the loading spinner
            // overlays a stable box.
            SsoButtonSlot(provider: .google, phase: viewModel.phase) {
                GoogleSignInButton {
                    Task { await viewModel.signInWithGoogle() }
                }
            }
        }
    }
}

/// One SSO button slot: the provider's own control, disabled whenever any
/// sign-in is running, with that provider's in-flight spinner overlaid on the
/// stable box the control reserves. The `LoadingButton` equivalent for buttons
/// whose chrome the provider owns and we cannot restyle.
private struct SsoButtonSlot<Content: View>: View {
    let provider: SsoProvider
    let phase: SignInPhase
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            content()
                .disabled(phase != .idle)

            if phase == .ssoLoading(provider) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .accessibilityIdentifier(provider.loadingIndicatorAccessibilityIdentifier)
            }
        }
    }
}

private final class LoginPreviewAuthClient: AuthClientProtocol, SsoAuthenticating, @unchecked Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse {
        RegisterResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: true))
    }
    func login(request: LoginRequest) async throws -> LoginResponse {
        LoginResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: true))
    }
    func signIn(with credential: SsoCredential) async throws -> LoginResponse {
        fatalError("LoginView preview never exercises SSO sign-in")
    }
    func logout() async throws {}
    func me() async throws -> MeResponse {
        MeResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: true))
    }
    func resendVerification() async throws {}
    func changeEmail(_ email: String) async throws -> PublicUser {
        PublicUser(id: UUID(), email: email, name: "Preview", emailVerified: false)
    }
}

private final class LoginPreviewSsoSignInProvider: SsoSignInProviding {
    let provider: SsoProvider
    init(provider: SsoProvider) { self.provider = provider }
    func signIn() async throws -> SsoSignInOutcome {
        // One authorization for both providers: `provider` supplies the tag,
        // and the `.google` credential drops the name on its own.
        .signedIn(SsoAuthorization(idToken: "preview-id-token", name: "Preview"))
    }
}

@MainActor private var loginPreview: some View {
    LoginView(
        authClient: LoginPreviewAuthClient(),
        googleSignInProvider: LoginPreviewSsoSignInProvider(provider: .google),
        appleSignInProvider: LoginPreviewSsoSignInProvider(provider: .apple),
        onLoginSuccess: { _ in },
        onSwitchToRegister: {}
    )
}

#Preview("login - Light") {
    loginPreview
        .preferredColorScheme(.light)
}

#Preview("login - Dark") {
    loginPreview
        .preferredColorScheme(.dark)
}
