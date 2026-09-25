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

    /// Which side of a family link this Settings screen is for, and — for a
    /// student — the subscription rail it shows (RFC 241).
    ///
    /// ONE value, because the two facts are one fact. A supporter's Settings
    /// has no rail: `SubscriptionSection` loads
    /// `GET /api/v1/students/me/coaching-usage` from its own `.task`, and that
    /// route is caller-scoped to a STUDENT, so a supporter reading it could
    /// only ever be refused. A role beside an optional rail left "supporter,
    /// with a rail" one call-site line away; this makes it not compile.
    ///
    /// The rail is built by `AuthenticatedRootView` — it shares the one
    /// `TransactionRecorder` with the session-long transaction listener — so it
    /// arrives from outside rather than being built here.
    enum Caller {
        case student(rail: SubscriptionViewModel)
        case supporter

        /// The role the shared invite control speaks in. Derived, never passed:
        /// the caller states which side it is on exactly once.
        var inviteRole: ClientRole {
            switch self {
            case .student: return .student
            case .supporter: return .supporter
            }
        }
    }

    let caller: Caller
    let onEmailChanged: (PublicUser) async -> Void
    let onLogout: () async -> Void

    /// The signed-in user as this screen knows them.
    @State private var user: PublicUser
    @AppStorage(AppearancePreference.storageKey) private var appearance: AppearancePreference = .system
    @State private var isChangingEmail = false
    @State private var isLoggingOut = false

    init(
        user: PublicUser,
        caller: Caller,
        authClient: AuthClientProtocol,
        supporterInviteClient: SupporterInviteClientProtocol,
        onEmailChanged: @escaping (PublicUser) async -> Void,
        onLogout: @escaping () async -> Void
    ) {
        _user = State(initialValue: user)
        self.caller = caller
        self.authClient = authClient
        self.supporterInviteClient = supporterInviteClient
        self.onEmailChanged = onEmailChanged
        self.onLogout = onLogout
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                identity

                appearanceSection

                familySection

                if case .student(let rail) = caller {
                    SubscriptionSection(viewModel: rail)
                }

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

    /// The inviter's door (RFC 232). The control itself is
    /// `SupporterInviteControl`, shared with the supporter home (RFC 241) —
    /// what stays here is the section this screen puts it in. The heading is
    /// role-neutral on purpose: it names the relationship, which is the same
    /// one from either end.
    private var familySection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            Text("Family & Supporters")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextSecondary)

            SupporterInviteControl(role: caller.inviteRole, client: supporterInviteClient)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
            caller: .student(rail: SubscriptionViewModel(
                usageClient: PreviewCoachingUsageClient(),
                store: PreviewSubscriptionStore(),
                recorder: PreviewTransactionRecorder()
            )),
            authClient: SettingsPreviewAuthClient(),
            supporterInviteClient: PreviewSupporterInviteClient(),
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
