import SwiftUI

/// The parent's home (RFC 241): the root scene for an account that holds a
/// `supporters` row and no `students` row.
///
/// A SIBLING of `AuthenticatedRootView`, not a conditioned version of it. That
/// view is student-shaped by construction — it mounts the chat, the college
/// list, "Your details" and an unconditional
/// `GET /api/v1/students/me/coaching-usage`, every one of which answers
/// `409 student_profile_required` for a supporter. Making a supporter's home a
/// separate scene is what stops a student-scoped call being fired for a
/// supporter AT ALL, rather than adding a flag to each of them and relying on
/// the next one remembering.
///
/// **Honest scope of the switcher.** Until `family/05/the-subject-seam` lands,
/// no screen shows a linked student's data, so the switcher selects and labels
/// — it does not yet change what any other screen reads. It ships now because
/// it is the surface the roster makes possible, and because the selection it
/// persists is what `family/05` and `family/06` will consume.
struct SupporterHomeView: View {
    let user: PublicUser
    let home: SupporterHome
    let authClient: AuthClientProtocol
    let supporterInviteClient: SupporterInviteClientProtocol
    /// The supporter doors — here for the disconnect Settings offers (RFC 249).
    let supporterClient: SupporterClientProtocol
    let collegeListClient: CollegeListClientProtocol
    let subjectStore: SupporterSubjectStore
    /// Re-reads the roster from `GET /api/v1/supporters/me` and hands it to the
    /// root state machine (`AppViewModel.refreshSupporterHome`).
    ///
    /// The read runs UNDER the screen that is already on it: this scene renders
    /// the home it was resolved with, so there is no spinner on a home that
    /// already has data, and a fresh roster simply replaces it. It exists
    /// because the result of this screen's own headline action — invite your
    /// student — is a name on this roster, and nothing else would put it there
    /// before the next relaunch.
    let refreshHome: () async -> Void
    let onEmailChanged: (PublicUser) async -> Void
    let onLogout: () async -> Void
    let onDeleteAccount: () async -> Bool

    /// Destinations pushed from the home. Two, and both are student-free:
    /// college lookup reads `GET /api/v1/colleges`, and Settings for a
    /// supporter carries no subscription rail.
    private enum Destination: Hashable {
        case collegeSearch
        case settings
    }

    @Environment(\.scenePhase) private var scenePhase

    /// The billing rail for THIS account (RFC 244). A `@StateObject` built in
    /// `init` from the client, the `AuthenticatedRootView` precedent: a view's
    /// `init` runs again on every publish of the root state machine, so a view
    /// model built in the body would be replaced mid-read.
    @StateObject private var coachingViewModel: SupporterCoachingViewModel

    @State private var path: [Destination] = []
    /// The working student, as this screen currently has it. Seeded from the
    /// store in `init` rather than in a `.task`, so the header never renders
    /// one name and then swaps to another a frame later.
    @State private var selectedStudentId: UUID?

    /// Whether the switcher opens already expanded. The `CollegeSearchView`
    /// `focusesSearchOnAppear` convention: the app always wants it closed, and
    /// the snapshot catalogue photographs it open.
    private let switcherOpenOnAppear: Bool

    init(
        user: PublicUser,
        home: SupporterHome,
        authClient: AuthClientProtocol,
        supporterInviteClient: SupporterInviteClientProtocol,
        supporterClient: SupporterClientProtocol,
        collegeListClient: CollegeListClientProtocol,
        supporterCoachingUsageClient: SupporterCoachingUsageClientProtocol,
        subjectStore: SupporterSubjectStore,
        switcherOpenOnAppear: Bool = false,
        refreshHome: @escaping () async -> Void,
        onEmailChanged: @escaping (PublicUser) async -> Void,
        onLogout: @escaping () async -> Void,
        onDeleteAccount: @escaping () async -> Bool
    ) {
        self.user = user
        self.home = home
        self.authClient = authClient
        self.supporterInviteClient = supporterInviteClient
        self.supporterClient = supporterClient
        self.collegeListClient = collegeListClient
        self.subjectStore = subjectStore
        self.switcherOpenOnAppear = switcherOpenOnAppear
        _coachingViewModel = StateObject(
            wrappedValue: SupporterCoachingViewModel(client: supporterCoachingUsageClient)
        )
        self.refreshHome = refreshHome
        self.onEmailChanged = onEmailChanged
        self.onLogout = onLogout
        self.onDeleteAccount = onDeleteAccount
        _selectedStudentId = State(initialValue: SupporterSubject.resolve(
            roster: home.students,
            stored: subjectStore.selectedStudentId(forUserId: user.id)
        )?.studentId)
    }

    /// The student this screen is about, or `nil` when nobody is linked.
    /// Derived on every read rather than stored a second time: the stored id
    /// and the roster are the two facts, and the rule that combines them has
    /// one home.
    private var subject: LinkedStudent? {
        SupporterSubject.resolve(roster: home.students, stored: selectedStudentId)
    }

    var body: some View {
        NavigationStack(path: $path) {
            root
                // BrandTopBar IS this screen's chrome; the stock bar would sit
                // above it as a second, empty one. Pushed destinations keep
                // theirs, which is where the back button comes from.
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: Destination.self, destination: destination)
        }
        .task { await refreshHome() }
        // The coaching read, on the same two occasions as the roster: on
        // appearance, and on every return to the foreground. It is hooked HERE
        // rather than beside the roster's own foreground hook
        // (`UnicoachiOSApp`), because that one re-resolves state this scene is
        // handed from above while this reading is this scene's own —
        // `AuthenticatedRootView` keeps its student meter's `scenePhase` hook
        // for exactly that reason.
        .task { await coachingViewModel.load() }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await coachingViewModel.load() }
        }
    }

    private var root: some View {
        VStack(spacing: 0) {
            BrandTopBar()

            // Outside the scroll, so the subject stays named while the rest of
            // the screen moves under it.
            subjectHeader

            DSSheetScroll {
                if home.students.isEmpty {
                    whatLinkingAdds
                }
                actions

                // Below the actions and outside the empty-roster branch: how
                // coaching is paid for is true of this ACCOUNT, so it is said
                // whether or not anybody is linked (RFC 244).
                SupporterCoachingSection(viewModel: coachingViewModel)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }

    // MARK: - The subject header

    /// Three shapes, one per roster size, and the sizes are the whole design:
    /// nobody linked says nothing, one student is a quiet line that does not
    /// pretend to be a control, and two or more is a visible switcher — because
    /// only then is there something to switch.
    @ViewBuilder
    private var subjectHeader: some View {
        if let subject {
            Group {
                if home.students.count == 1 {
                    Text("Supporting \(subject.name)")
                        .font(.dsLabel)
                        .foregroundStyle(Color.dsTextSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("supporterSubjectLine")
                } else {
                    SupporterSubjectSwitcher(
                        roster: home.students,
                        subject: subject,
                        openOnAppear: switcherOpenOnAppear,
                        onSelect: select
                    )
                }
            }
            .padding(.horizontal, DSSpacing.lg)
            .padding(.top, DSSpacing.lg)
        }
    }

    /// Writing through the store on selection, not on leaving the screen: this
    /// scene is torn down and rebuilt by the root state machine, and a
    /// selection that only survived a graceful exit would be the one a crash
    /// or a re-resolve lost.
    private func select(_ student: LinkedStudent) {
        selectedStudentId = student.studentId
        subjectStore.select(studentId: student.studentId, forUserId: user.id)
    }

    // MARK: - Body

    /// What linking adds, for a supporter who has linked nobody. Every line is
    /// true of THIS slice — the invite, the acceptance, the name in the header,
    /// the switcher — because copy that promised what `family/05` will show
    /// would be a claim the screen cannot keep today.
    private var whatLinkingAdds: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            Text("No student linked yet")
                .font(.dsTitle)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityIdentifier("supporterEmptyTitle")

            VStack(alignment: .leading, spacing: DSSpacing.sm) {
                Text("Send your student an invite. Nothing is shared until they accept it.")
                Text("Once they do, they're named here — and if you support more than one, you can switch between them.")
                Text("You can look up any college right now, without linking anyone.")
            }
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The three things this home always offers, linked or not.
    private var actions: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            SupporterInviteControl(role: .supporter, client: supporterInviteClient)

            // The control reads the destination's OWN name rather than
            // transcribing it, so the button and the screen it pushes cannot
            // drift apart.
            DSTextButton(
                CollegeSearchView.Tap.browseOnly.title,
                accessibilityIdentifier: "supporterCollegeSearchButton",
                accessibilityLabel: CollegeSearchView.Tap.browseOnly.title,
                action: { path.append(.collegeSearch) }
            )

            DSTextButton(
                "Settings",
                role: .secondary,
                accessibilityIdentifier: "supporterSettingsButton",
                accessibilityLabel: "Settings",
                action: { path.append(.settings) }
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func destination(_ destination: Destination) -> some View {
        switch destination {
        case .collegeSearch:
            // The SAME search the add sheet runs, browse-only: a supporter has
            // no college list of their own to add to, and nothing here sends a
            // student id anywhere.
            CollegeSearchView(
                client: collegeListClient,
                tap: .browseOnly,
                onProfileRequired: {}
            )
        case .settings:
            SettingsView(
                user: user,
                // A supporter, and there is still no rail to pass: the rail's
                // meter is student-scoped, and `family/03.5` answered whose
                // budget a parent sees by giving the parent their OWN account's
                // meter on their OWN scene — `SupporterCoachingSection` above,
                // reading a supporter-scoped route. Settings gains nothing, so
                // the `Caller` enum's claim stays true (RFC 244).
                //
                // The roster and the refresh are for the family list
                // (RFC 249): popping back from Settings does not re-run this
                // scene's `.task`, so a disconnect re-reads the home itself.
                // The coaching reading is this scene's own, already loaded:
                // the delete confirmation reads it rather than asking again.
                caller: .supporter(
                    .init(roster: home.students, client: supporterClient, refreshHome: refreshHome),
                    coaching: coachingViewModel
                ),
                authClient: authClient,
                supporterInviteClient: supporterInviteClient,
                onEmailChanged: onEmailChanged,
                onLogout: onLogout,
                onDeleteAccount: onDeleteAccount
            )
        }
    }
}

// MARK: - Previews

private final class SupporterHomePreviewAuthClient: AuthClientProtocol, @unchecked Sendable {
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
        PublicUser(id: UUID(), email: email, name: "Preview", emailVerified: true)
    }
}

private final class SupporterHomePreviewCollegeListClient: CollegeListClientProtocol, @unchecked Sendable {
    func listEntries() async throws -> [CollegeListEntry] { [] }
    func addEntry(collegeId: UUID) async throws -> CollegeListEntry {
        throw ErrorResponse(code: "SERVER_ERROR", message: "Preview", fieldErrors: nil)
    }
    func updateEntry(
        id: UUID,
        version: Int,
        status: CollegeListStatus,
        reasons: String?,
        livingPlan: LivingPlanUpdate
    ) async throws -> CollegeListEntry {
        throw ErrorResponse(code: "SERVER_ERROR", message: "Preview", fieldErrors: nil)
    }
    func removeEntry(id: UUID, version: Int) async throws {}
    func searchColleges(query: String) async throws -> [CollegeSummary] { [] }
}

/// A store with no selection, so a preview never depends on what a previous
/// run left in `UserDefaults`.
private struct SupporterHomePreviewSubjectStore: SupporterSubjectStore {
    func selectedStudentId(forUserId userId: UUID) -> UUID? { nil }
    func select(studentId: UUID, forUserId userId: UUID) {}
    func clear(forUserId userId: UUID) {}
}

@MainActor private func supporterHomePreview(_ students: [LinkedStudent]) -> some View {
    SupporterHomeView(
        user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview Parent", emailVerified: true),
        home: SupporterHome(supporter: PublicSupporter(id: UUID()), students: students),
        authClient: SupporterHomePreviewAuthClient(),
        supporterInviteClient: PreviewSupporterInviteClient(),
        supporterClient: PreviewSupporterClient(),
        collegeListClient: SupporterHomePreviewCollegeListClient(),
        supporterCoachingUsageClient: PreviewSupporterCoachingUsageClient(),
        subjectStore: SupporterHomePreviewSubjectStore(),
        refreshHome: {},
        onEmailChanged: { _ in },
        onLogout: {},
        onDeleteAccount: { false }
    )
}

private let supporterHomePreviewRoster: [LinkedStudent] = [
    LinkedStudent(linkId: UUID(), studentId: UUID(), name: "Ava", relationship: .parent, otherSupporterNames: []),
    LinkedStudent(linkId: UUID(), studentId: UUID(), name: "Noah", relationship: .parent, otherSupporterNames: []),
]

#Preview("supporterHome empty - Light") {
    supporterHomePreview([])
        .preferredColorScheme(.light)
}

#Preview("supporterHome roster - Dark") {
    supporterHomePreview(supporterHomePreviewRoster)
        .preferredColorScheme(.dark)
}
