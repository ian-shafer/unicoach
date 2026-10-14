import StoreKit
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
    ///
    /// A supporter's case also carries its side of the family list (RFC 249):
    /// the roster the home already holds, the door it disconnects through, and
    /// the home's refresh — so a student-only screen cannot be handed a
    /// supporter's roster, and a supporter's Settings cannot forget to tell
    /// the home a student left.
    ///
    /// It also carries the home's coaching reading (RFC 252), already loaded
    /// there, so the delete confirmation can tell a parent who pays through
    /// their own subscription that Apple keeps billing — without this screen
    /// making a read of its own.
    enum Caller {
        case student(rail: SubscriptionViewModel)
        case supporter(FamilyConnectionsViewModel.SupporterFamily, coaching: SupporterCoachingViewModel)

        /// The role the shared invite control speaks in. Derived, never passed:
        /// the caller states which side it is on exactly once.
        var inviteRole: ClientRole {
            switch self {
            case .student: return .student
            case .supporter: return .supporter
            }
        }
    }

    /// The side the family list speaks for. The student's list reads its own
    /// door through the invite client, which is the student's family client.
    private var familySide: FamilyConnectionsViewModel.Side {
        switch caller {
        case .student:
            return .student(supporterInviteClient)
        case .supporter(let family, _):
            return .supporter(family)
        }
    }

    let caller: Caller
    let onEmailChanged: (PublicUser) async -> Void
    let onLogout: () async -> Void
    /// Closes the account (`AppViewModel.deleteAccount`, RFC 252). `false`
    /// means the server did not confirm, and the person is still signed in.
    let onDeleteAccount: () async -> Bool

    /// The signed-in user as this screen knows them.
    @State private var user: PublicUser
    @AppStorage(AppearancePreference.storageKey) private var appearance: AppearancePreference = .system
    @State private var isChangingEmail = false
    @State private var isLoggingOut = false

    /// The family row whose disconnect confirmation opens already expanded —
    /// `nil` in the app; the snapshot catalogue photographs it open.
    private let disconnectConfirmationOnAppear: UUID?

    /// How the delete-account confirmation opens — `.closed` in the app; the
    /// snapshot catalogue photographs it open, and open after a failed delete.
    private let deleteAccountConfirmationOnAppear: DeleteAccountConfirmation

    init(
        user: PublicUser,
        caller: Caller,
        authClient: AuthClientProtocol,
        supporterInviteClient: SupporterInviteClientProtocol,
        disconnectConfirmationOnAppear: UUID? = nil,
        deleteAccountConfirmationOnAppear: DeleteAccountConfirmation = .closed,
        onEmailChanged: @escaping (PublicUser) async -> Void,
        onLogout: @escaping () async -> Void,
        onDeleteAccount: @escaping () async -> Bool
    ) {
        _user = State(initialValue: user)
        self.caller = caller
        self.disconnectConfirmationOnAppear = disconnectConfirmationOnAppear
        self.deleteAccountConfirmationOnAppear = deleteAccountConfirmationOnAppear
        self.authClient = authClient
        self.supporterInviteClient = supporterInviteClient
        self.onEmailChanged = onEmailChanged
        self.onLogout = onLogout
        self.onDeleteAccount = onDeleteAccount
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

                    DeleteAccountControl(
                        subscription: deletionSubscription,
                        confirmingOnAppear: deleteAccountConfirmationOnAppear,
                        onDelete: onDeleteAccount
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

    /// Who is connected, with a disconnect for each (RFC 249), above the
    /// inviter's door (RFC 232). The control itself is
    /// `SupporterInviteControl`, shared with the supporter home (RFC 241) —
    /// what stays here is the section this screen puts it in. The heading is
    /// role-neutral on purpose: it names the relationship, which is the same
    /// one from either end.
    private var familySection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            Text("Family & Supporters")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextSecondary)

            FamilyConnectionsSection(
                side: familySide,
                confirmingOnAppear: disconnectConfirmationOnAppear.map { .disconnect(linkId: $0) }
            )
                .padding(.bottom, DSSpacing.sm)

            SupporterInviteControl(role: caller.inviteRole, client: supporterInviteClient)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Where the delete confirmation reads "does this person pay Apple?".
    private var deletionSubscription: DeleteAccountControl.Subscription {
        switch caller {
        case .student(let rail):
            return .student(rail)
        case .supporter(_, let coaching):
            return .supporter(coaching)
        }
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

/// The state `DeleteAccountControl` is in, and the one it opens in. `.failed`
/// is the confirmation after a delete the server did not confirm, error line
/// showing.
enum DeleteAccountConfirmation {
    case closed, open, deleting, failed
}

/// "Delete account" and — after the first tap — its inline confirmation
/// (RFC 252). An in-view disclosure rather than a stock `confirmationDialog`,
/// the `FamilyConnectionRow` reason: a system dialog renders in a window the
/// snapshot rasterizer cannot reach.
private struct DeleteAccountControl: View {
    /// The rail that says whether this person pays Apple. A student's is the
    /// StoreKit rail; a parent's is the home's coaching reading.
    enum Subscription {
        case student(SubscriptionViewModel)
        case supporter(SupporterCoachingViewModel)
    }

    let subscription: Subscription
    let onDelete: () async -> Bool

    @State private var phase: DeleteAccountConfirmation

    init(subscription: Subscription, confirmingOnAppear: DeleteAccountConfirmation, onDelete: @escaping () async -> Bool) {
        self.subscription = subscription
        self.onDelete = onDelete
        _phase = State(initialValue: confirmingOnAppear)
    }

    var body: some View {
        if phase == .closed {
            // The public privacy page (public-web/.../render/PrivacyPage.kt,
            // "Your choices") tells users "Settings › Delete account". Rename
            // or move this control and change that sentence with it.
            DSTextButton(
                "Delete account",
                role: .destructive,
                accessibilityIdentifier: "deleteAccountButton",
                accessibilityLabel: "Delete account",
                action: { withAnimation { phase = .open } }
            )
        } else {
            confirmation
        }
    }

    private var confirmation: some View {
        VStack(alignment: .leading, spacing: DSControl.stackGap) {
            prompt
            subscriptionNotice
            if phase == .failed {
                failureLine
            }
            confirmButton
            cancelButton
        }
        .padding(.horizontal, DSControl.textInset)
        .padding(.vertical, DSSpacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .dsOutlinedCard()
    }

    private var prompt: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text("Delete your account?")
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityAddTraits(.isHeader)
            Text("Your account is closed and you are signed out on every device. Anyone connected to you is told the connection has ended — not why. Their own accounts are not affected.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("deleteAccountPrompt")
        }
    }

    @ViewBuilder
    private var subscriptionNotice: some View {
        switch subscription {
        case .student(let rail):
            StudentDeletionSubscriptionNotice(rail: rail)
        case .supporter(let coaching):
            SupporterDeletionSubscriptionNotice(coaching: coaching)
        }
    }

    private var failureLine: some View {
        Text("Deleting your account didn't work. Try again.")
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .accessibilityIdentifier("deleteAccountError")
    }

    private var confirmButton: some View {
        LoadingButton(
            "Delete account",
            isLoading: phase == .deleting,
            role: .destructive,
            accessibilityIdentifier: "deleteAccountConfirmButton",
            accessibilityLabel: "Delete account",
            action: confirmDelete
        )
    }

    private var cancelButton: some View {
        DSTextButton(
            "Cancel",
            role: .secondary,
            accessibilityIdentifier: "deleteAccountCancelButton",
            accessibilityLabel: "Cancel",
            action: { withAnimation { phase = .closed } }
        )
        .disabled(phase == .deleting)
    }

    private func confirmDelete() {
        phase = .deleting
        Task {
            // On success the app has already left this screen.
            phase = await onDelete() ? .open : .failed
        }
    }
}

/// Apple's billing notice (RFC 252): deleting the account does not cancel a
/// subscription, so a person who has one is told so and given the door —
/// warned, not blocked. `conditional` is the notice for a person whose
/// coverage could not be read: said as an "if" rather than not said at all.
private struct DeletionSubscriptionNotice: View {
    let conditional: Bool
    let onManage: () async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text(conditional
                ? "If you subscribe through Apple, your subscription continues until you cancel it."
                : "Your subscription is billed by Apple and continues until you cancel it.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("deleteAccountSubscriptionNotice")
            ManageSubscriptionButton(action: onManage)
        }
    }
}

/// A student's notice, on whenever the rail has a subscription bound — the
/// condition `ManageSubscriptionLink` uses.
private struct StudentDeletionSubscriptionNotice: View {
    @ObservedObject var rail: SubscriptionViewModel

    var body: some View {
        if rail.offersManage {
            DeletionSubscriptionNotice(conditional: false) { await rail.showManagement() }
        }
    }
}

/// A parent's notice, on when their coverage is their own subscription, and
/// in its conditional form when the coverage is not known — before an
/// irreversible act, not knowing is not "does not pay". A parent's scene has
/// no StoreKit rail, so the door is StoreKit's own manage-subscriptions sheet,
/// which needs none.
private struct SupporterDeletionSubscriptionNotice: View {
    @ObservedObject var coaching: SupporterCoachingViewModel
    @State private var showingManagement = false

    var body: some View {
        switch coaching.holdsOwnSubscription {
        case .some(true):
            notice(conditional: false)
        case .some(false):
            EmptyView()
        case .none:
            notice(conditional: true)
        }
    }

    private func notice(conditional: Bool) -> some View {
        DeletionSubscriptionNotice(conditional: conditional) { showingManagement = true }
            .manageSubscriptionsSheet(isPresented: $showingManagement)
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
            onLogout: {},
            onDeleteAccount: { false }
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
