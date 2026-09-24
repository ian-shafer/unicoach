import SwiftUI

/// The authenticated tree's secondary surface, pushed from the menu footer
/// (DESIGN.md §7): who you are signed in as, changing that email, and signing
/// out. Before RFC 117 none of the three had a home once `HomeView` was gone,
/// and Change Email had none at all for a verified student.
///
/// §7 also asks this screen to absorb subscription status and coaching usage,
/// and RFC 119 lands both as the `SubscriptionSection` below: the meter comes
/// from `GET /api/v1/students/me/coaching-usage`, which has existed since
/// RFC 109. What remains true of the older deferral is only this — the server
/// exposes **no GET for subscription state**, so subscription status is read by
/// re-posting the current entitlement's JWS to the idempotent
/// `POST /api/v1/subscriptions/verify`, and a student with no StoreKit
/// entitlement simply has no status to show.
struct SettingsView: View {
    let authClient: AuthClientProtocol
    let supporterInviteClient: SupporterInviteClientProtocol
    let onEmailChanged: (PublicUser) async -> Void
    let onLogout: () async -> Void

    /// Owned by `AuthenticatedRootView` — it shares the one
    /// `TransactionRecorder` with the session-long transaction listener — so it
    /// arrives as an `@ObservedObject` rather than being built here.
    @ObservedObject var subscriptionViewModel: SubscriptionViewModel

    /// The signed-in user as this screen knows them.
    @State private var user: PublicUser
    @AppStorage(AppearancePreference.storageKey) private var appearance: AppearancePreference = .system
    @State private var isChangingEmail = false
    @State private var isLoggingOut = false

    /// What the family section is showing (RFC 232). ONE value, so a stale link
    /// beside a fresh error — or a spinner beside either — is not representable.
    /// Three independent flags made those combinations legal and left their
    /// assignment order the only thing preventing them.
    @State private var inviteState: InviteState = .idle

    init(
        user: PublicUser,
        authClient: AuthClientProtocol,
        supporterInviteClient: SupporterInviteClientProtocol,
        subscriptionViewModel: SubscriptionViewModel,
        onEmailChanged: @escaping (PublicUser) async -> Void,
        onLogout: @escaping () async -> Void
    ) {
        _user = State(initialValue: user)
        self.authClient = authClient
        self.supporterInviteClient = supporterInviteClient
        self.subscriptionViewModel = subscriptionViewModel
        self.onEmailChanged = onEmailChanged
        self.onLogout = onLogout
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                identity

                appearanceSection

                familySection

                SubscriptionSection(viewModel: subscriptionViewModel)

                VStack(spacing: DSControl.stackGap) {
                    LoadingButton(
                        "Change Email",
                        isLoading: false,
                        role: .primary,
                        accessibilityIdentifier: "changeEmailButton",
                        accessibilityLabel: "Change Email",
                        action: { isChangingEmail = true }
                    )

                    LoadingButton(
                        "Log Out",
                        isLoading: isLoggingOut,
                        role: .destructive,
                        accessibilityIdentifier: "logoutButton",
                        accessibilityLabel: "Log Out",
                        action: {
                            isLoggingOut = true
                            Task {
                                await onLogout()
                                isLoggingOut = false
                            }
                        }
                    )
                }
            }
            .padding(DSSpacing.lg)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $isChangingEmail) {
            ChangeEmailView(
                currentEmail: user.email,
                authClient: authClient,
                // The server clears verification when the address changes, so
                // the app cannot stay in `.authenticated`: hand the fresh user
                // up to the root state machine, which routes to the
                // verification screen. Dismissing first keeps the sheet from
                // outliving the screen underneath it.
                onChanged: { changed in
                    user = changed
                    isChangingEmail = false
                    Task { await onEmailChanged(changed) }
                }
            )
        }
    }

    /// The four states the invite control can be in (RFC 232).
    enum InviteState: Equatable {
        case idle
        case minting
        case minted(URL)
        case failed(String)
    }

    /// The state that FOLLOWS minting a link (RFC 232).
    ///
    /// It answers with a value rather than assigning three fields, so the
    /// caller has one assignment point and no intermediate combination exists.
    /// The previous link is gone the moment minting starts: a mint that fails
    /// must not leave the last token on screen, where it may already have been
    /// consumed.
    ///
    /// No relationship is sent. The server owns the product default, and the
    /// response reports the word it used — a literal here is the client
    /// asserting a vocabulary it does not own.
    ///
    /// A link the app cannot parse is a FAILURE, not silence: `URL(string:)`
    /// returning nil used to leave no share control, no message and no sign the
    /// tap had done anything at all.
    private func mintedInviteState() async -> InviteState {
        do {
            let invite = try await supporterInviteClient.createInvite(relationship: nil)
            guard let url = URL(string: invite.inviteUrl) else {
                return .failed(ErrorResponse.unexpected.message)
            }
            return .minted(url)
        } catch {
            return .failed((error as? ErrorResponse)?.message ?? ErrorResponse.unexpected.message)
        }
    }

    private func mintInvite() async {
        inviteState = .minting
        inviteState = await mintedInviteState()
    }

    /// The inviter's door (RFC 232): mint a shareable link and hand it to the
    /// system share sheet, so the act of sharing IS the inviter's consent.
    private var familySection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            Text("Family & Supporters")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextSecondary)

            LoadingButton(
                inviteState == .minting ? "Preparing link…" : "Invite a parent or supporter",
                isLoading: inviteState == .minting,
                role: .primary,
                accessibilityIdentifier: "inviteSupporterButton",
                accessibilityLabel: "Invite a parent or supporter",
                action: { Task { await mintInvite() } }
            )

            switch inviteState {
            case .idle, .minting:
                EmptyView()
            case let .minted(url):
                shareInvite(url)
            case let .failed(message):
                inviteFailure(message)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The share sheet for a minted link.
    ///
    /// The URL travels as the ITEM so the share sheet still renders a tappable,
    /// previewable link, and RFC 232's sentence rides with it as the message. A
    /// bare URL from an unknown sender is the exact context-free link a parent
    /// is told never to tap.
    private func shareInvite(_ url: URL) -> some View {
        ShareLink(
            item: url,
            subject: Text("Join me on unicoach"),
            message: Text("Join me on unicoach to support my college journey: \(url.absoluteString)")
        ) {
            Label("Share invite link", systemImage: "square.and.arrow.up")
        }
        .accessibilityIdentifier("shareInviteLink")
    }

    private func inviteFailure(_ message: String) -> some View {
        Text(message)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .accessibilityIdentifier("inviteError")
    }

    /// Dark mode is no longer only a device setting (DESIGN.md §2.1): the
    /// student can pin it either way, or follow the device. `SegmentedSelector`
    /// is the motif's own three-way control — a stock `.pickerStyle(.segmented)`
    /// would put a grey capsule on the screen, which §2 and §3 both rule out.
    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            Text("Appearance")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextSecondary)

            SegmentedSelector(
                options: AppearancePreference.allCases.map { (tag: $0, title: $0.title) },
                selection: $appearance,
                accessibilityIdentifier: "appearanceSelector"
            )
        }
    }

    private var identity: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text("Signed in as")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextSecondary)

            Text(user.name)
                .font(.dsTitle)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityIdentifier("settingsUserName")

            Text(user.email)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
                .accessibilityIdentifier("settingsUserEmail")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private final class SettingsPreviewAuthClient: AuthClientProtocol, @unchecked Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse {
        RegisterResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: true))
    }
    func login(request: LoginRequest) async throws -> LoginResponse {
        LoginResponse(user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview", emailVerified: true))
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

@MainActor private var settingsPreview: some View {
    NavigationStack {
        SettingsView(
            user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview User", emailVerified: true),
            authClient: SettingsPreviewAuthClient(),
            supporterInviteClient: PreviewSupporterInviteClient(),
            subscriptionViewModel: SubscriptionViewModel(
                usageClient: PreviewCoachingUsageClient(),
                store: PreviewSubscriptionStore(),
                recorder: PreviewTransactionRecorder()
            ),
            onEmailChanged: { _ in },
            onLogout: {}
        )
    }
}

#Preview("settings - Light") {
    settingsPreview
        .preferredColorScheme(.light)
}

#Preview("settings - Dark") {
    settingsPreview
        .preferredColorScheme(.dark)
}
