import Foundation
import SwiftUI
@testable import UnicoachiOS

// MARK: - The catalogue (RFC 122 §2)
//
// One array. Adding a screen when it ships is one entry here; a scene that is
// added but never rendered is impossible, because SnapshotTests walks this
// list.
//
// DETERMINISM. Fixture timestamps that reach a RELATIVE formatter are computed
// as offsets from now (-2h, -1d): ConversationListView renders
// `.formatted(.relative(presentation: .named))`, so an absolute fixture date
// would make the rendered string drift every day while an offset renders
// "2 hours ago" forever. Dates that are formatted ABSOLUTELY (a renewal /
// reset date) are pinned instead. Locale and time zone are forced by
// bin/snapshot-ios (`-testLanguage en -testRegion US`, `TZ=UTC`).

struct SnapshotScene {
    /// The tallest canvas a scene may ask for, and a real system constraint
    /// rather than a style rule: at `SnapshotOutput.captureScale` a taller
    /// window is a bitmap large enough for jetsam to SIGKILL the test process
    /// part way through the walk. That failure does not look like a failure —
    /// xcodebuild prints "Restarting after unexpected exit", re-runs only the
    /// remaining tests, and the corpus directory (which clears itself on every
    /// launch) is left EMPTY. It cost one full round to diagnose; it is a
    /// constant now so the next scene meets it as a number.
    static let maxHeight: CGFloat = 1500

    /// A canvas for content taller than the device. The harness window does not
    /// scroll, so a scene whose content overflows is simply clipped — and a
    /// clipped capture reviews nothing.
    static func tallCanvas(height: CGFloat) -> CGSize {
        CGSize(width: SnapshotOutput.deviceSize.width, height: height)
    }

    let name: String
    var size: CGSize = SnapshotOutput.deviceSize
    var settle: TimeInterval = 0.4
    /// `async` because most seeding is `await viewModel.load()`: every double
    /// answers synchronously, so this is deterministic and fast, but it must
    /// happen BEFORE the render rather than being left to the view's own
    /// `.task`, whose completion the render does not wait for.
    let content: @MainActor () async -> AnyView

    /// The ceiling is enforced HERE rather than in `tallCanvas`, because this
    /// is the first place that knows the scene's name — and a message naming
    /// only a height would send the next person hunting through the catalogue
    /// for it. It also catches a scene that builds its `CGSize` by hand.
    init(
        name: String,
        size: CGSize = SnapshotOutput.deviceSize,
        settle: TimeInterval = 0.4,
        content: @escaping @MainActor () async -> AnyView
    ) {
        precondition(
            size.height <= Self.maxHeight,
            "snapshot scene [\(name)] asks for [\(size.height)]pt, over the [\(Self.maxHeight)]pt ceiling: "
                + "the run would be SIGKILLed by jetsam part way through and leave an EMPTY corpus"
        )
        self.name = name
        self.size = size
        self.settle = settle
        self.content = content
    }
}

// MARK: - Fixture clock

enum SnapshotClock {
    /// Pinned instant for anything rendered as an absolute calendar date
    /// (a renewal date, a reset date). 2027-03-15 12:00:00 UTC.
    static let pinned = Date(timeIntervalSince1970: 1_805_112_000)

    /// Offsets from now, for anything rendered by a RELATIVE formatter.
    static func agoHours(_ hours: Double) -> Date { Date().addingTimeInterval(-hours * 3600) }
    static func agoDays(_ days: Double) -> Date { Date().addingTimeInterval(-days * 86400) }
}

// MARK: - Seeding helpers

@MainActor
enum SnapshotSeed {
    /// A rail whose meter has landed on `usage` and whose store sells the one
    /// fake plan. Loaded here rather than by the view's `.task`.
    static func rail(
        usage: CoachingUsage,
        store: SubscriptionStoreProtocol = PreviewSubscriptionStore(),
        recorder: TransactionRecording = PreviewTransactionRecorder()
    ) async -> SubscriptionViewModel {
        let viewModel = SubscriptionViewModel(
            usageClient: PreviewCoachingUsageClient(usage: usage),
            store: store,
            recorder: recorder
        )
        await viewModel.load()
        return viewModel
    }

    /// A rail with an ACTIVE bound subscription: the store hands back an
    /// entitlement and the recorder answers `.recorded`, which is the only way
    /// `SubscriptionViewModel.subscription` is ever populated (there is no GET
    /// for subscription state).
    static func boundRail(
        status: SubscriptionStatus,
        usage: CoachingUsage
    ) async -> SubscriptionViewModel {
        let store = MockSubscriptionStore()
        store.productResult = .success(StoreProduct(
            id: SubscriptionProduct.monthlyIdentifier,
            displayName: "Unicoach Monthly",
            displayPrice: "$10.00"
        ))
        store.entitlements = [StoreTransaction(
            id: 1,
            productID: SubscriptionProduct.monthlyIdentifier,
            jws: "snapshot-jws"
        )]
        let recorder = MockTransactionRecorder()
        recorder.outcome = .recorded(PublicSubscription(
            status: status.rawValue,
            productId: SubscriptionProduct.monthlyIdentifier,
            currentPeriodEnd: SnapshotClock.pinned
        ))
        let viewModel = SubscriptionViewModel(
            usageClient: PreviewCoachingUsageClient(usage: usage),
            store: store,
            recorder: recorder
        )
        await viewModel.load()
        return viewModel
    }

    static func boundActiveRail() async -> SubscriptionViewModel {
        await boundRail(
            status: .active,
            usage: CoachingUsage(usedPercent: 68, exhausted: false, resetsAt: SnapshotClock.pinned)
        )
    }

    /// A rail that has met `409 subscription_owned_by_other_account` (RFC 244):
    /// the Apple ID holds an entitlement for the plan, and `/verify` refuses it
    /// because that subscription belongs to a DIFFERENT unicoach account.
    /// Seeded through `load()`, which is the path a second account on a shared
    /// Apple ID meets the refusal on — before it taps anything.
    static func appleIdBoundElsewhereRail(usage: CoachingUsage) async -> SubscriptionViewModel {
        let store = MockSubscriptionStore()
        store.productResult = .success(StoreProduct(
            id: SubscriptionProduct.monthlyIdentifier,
            displayName: "Unicoach Monthly",
            displayPrice: "$10.00"
        ))
        store.entitlements = [StoreTransaction(
            id: 1,
            productID: SubscriptionProduct.monthlyIdentifier,
            jws: "snapshot-jws"
        )]
        let recorder = MockTransactionRecorder()
        recorder.outcome = .rejected(.server(ErrorResponse(
            code: ServerErrorCode.subscriptionOwnedByOtherAccount.rawValue,
            message: "snapshot",
            fieldErrors: nil,
            status: 409
        )))
        return await rail(usage: usage, store: store, recorder: recorder)
    }

    /// The state the answered onboarding scene shows. `ResidencyState` cannot
    /// be constructed outside its own file, so a scene names an offered one.
    static let california = ResidencyStates.offered.first { $0.code == "CA" }!

    /// An `OnboardingView` over a seeded view model (RFC 163). The clients
    /// answer, but nothing here submits: the scene is the form at rest.
    ///
    /// The date of birth is left EMPTY by default, which is the state the form
    /// really opens in (RFC 222); `dateOfBirthAnswered` fills it, and
    /// `dateOfBirthError` shows the inline refusal a submit with nothing chosen
    /// produces.
    static func onboarding(
        precision: OnboardingViewModel.Precision = .year,
        residencyState: ResidencyState? = nil,
        incomeBand: IncomeBand? = nil,
        dateOfBirthAnswered: Bool = false,
        dateOfBirthError: Bool = false
    ) -> some View {
        let viewModel = OnboardingViewModel(
            studentClient: MockStudentClient(),
            moneyProfileClient: MockMoneyProfileClient(),
            onComplete: {},
            // Pinned, not `Calendar.current` — the year window is rendered, so a
            // clock-derived one would move the capture every January.
            year: 2028
        )
        viewModel.precision = precision
        viewModel.month = 6
        viewModel.day = 15
        viewModel.residencyState = residencyState
        viewModel.incomeBand = incomeBand
        if dateOfBirthAnswered {
            // Pinned like the year above, and for the same reason.
            viewModel.birthYear = 2009
            viewModel.birthMonth = 7
            viewModel.birthDay = 10
        }
        if dateOfBirthError {
            // The message the view model itself would set — quoted from its one
            // home rather than retyped here.
            viewModel.dateOfBirthError = OnboardingViewModel.dateOfBirthRequiredMessage
        }
        return OnboardingView(viewModel: viewModel, userName: "Kendall")
    }

    // MARK: - Your details (RFC 171)

    /// The profile the mixed scene shows: one field answered, one declined —
    /// the two states that read differently, with the declined field's picker
    /// still fully live beneath its "Remove my answer" control.
    static let mixedMoneyProfile = PublicMoneyProfile(
        incomeBandStatus: AnswerStatus.answered.rawValue,
        incomeBand: IncomeBand.k48To75k.rawValue,
        residencyStatus: AnswerStatus.declined.rawValue,
        residencyState: nil,
        livingPlanStatus: AnswerStatus.unanswered.rawValue,
        livingPlan: nil,
        version: 3,
        createdAt: SnapshotClock.agoDays(7),
        updatedAt: SnapshotClock.agoHours(2)
    )

    /// A `YourDetailsView` over seeded mocks, loading through its own `.task`
    /// exactly as the college-list scenes do. The vocabulary mock serves the
    /// real fixture document, so the scene photographs the SERVED menu rather
    /// than the fallback.
    static func yourDetails(
        profile: PublicMoneyProfile? = nil,
        fetchResult: Result<PublicMoneyProfile?, Error>? = nil,
        intendedSubjects: PublicIntendedSubjects = .unanswered,
        sharing: SharingPermissionStatus = .notAsked
    ) -> some View {
        let moneyProfileClient = MockMoneyProfileClient()
        moneyProfileClient.fetchResult = fetchResult ?? .success(profile)
        let intendedSubjectsClient = MockIntendedSubjectsClient()
        intendedSubjectsClient.fetchResult = .success(intendedSubjects)
        let sharingPermissionsClient = MockSharingPermissionsClient()
        sharingPermissionsClient.fetchResult = .success([.intendedSubjects(sharing)])
        return NavigationStack {
            YourDetailsView(
                moneyProfileClient: moneyProfileClient,
                vocabularyClient: MockVocabularyClient(),
                subjectsClient: MockSubjectsClient(),
                intendedSubjectsClient: intendedSubjectsClient,
                sharingPermissionsClient: sharingPermissionsClient,
                onProfileRequired: {},
                onMyColleges: {}
            )
        }
    }

    static func gate(_ rail: SubscriptionViewModel) -> PaywallGate {
        // One `SubscriptionSheet?` binding, not two `Bool`s (RFC 123). Nothing
        // here presents a sheet -- each sheet is its own scene, hosted
        // directly -- so the binding is pinned at "no sheet on screen".
        PaywallGate(subscriptions: rail, presentedSheet: .constant(nil))
    }

    static func conversationClient(history: [Message], list: [Conversation] = []) -> MockConversationClient {
        let client = MockConversationClient()
        client.fetchMessagesResult = history
        client.listConversationsResult = list
        return client
    }

    static func conversation(name: String, hoursAgo: Double) -> Conversation {
        Conversation(
            id: UUID(uuidString: "00000000-0000-0000-0000-0000000000\(String(format: "%02d", Int(hoursAgo)))")
                ?? UUID(),
            name: name,
            createdAt: SnapshotClock.agoDays(7),
            updatedAt: SnapshotClock.agoHours(hoursAgo),
            lastActivityAt: SnapshotClock.agoHours(hoursAgo),
            archivedAt: nil
        )
    }

    static let recents: [Conversation] = [
        conversation(name: "Essay brainstorming", hoursAgo: 2),
        conversation(name: "Choosing between two offers", hoursAgo: 24),
        conversation(name: "Personal statement structure", hoursAgo: 72),
    ]

    static let user = PublicUser(
        id: UUID(uuidString: "11111111-2222-3333-4444-555555555555") ?? UUID(),
        email: "student@example.com",
        name: "Alex Student",
        emailVerified: true
    )

    // MARK: - The supporter surfaces (RFC 241)

    /// The parent this slice's scenes are photographed as. A second user rather
    /// than `user` above, because the two screens are for two different people
    /// and a capture that showed "Alex Student" on a parent's home would read
    /// as a bug.
    static let supporterUser = PublicUser(
        id: UUID(uuidString: "22222222-3333-4444-5555-666666666666") ?? UUID(),
        email: "parent@example.com",
        name: "Robin Carter",
        emailVerified: true
    )

    /// Pinned ids: nothing renders them, but a scene that minted a fresh UUID
    /// per run would make the selection store answer differently each time.
    static func linkedStudent(
        _ index: Int,
        name: String,
        relationship: SupporterRelationship = .parent,
        otherSupporterNames: [String] = []
    ) -> LinkedStudent {
        LinkedStudent(
            linkId: UUID(uuidString: "66660000-0000-0000-0000-00000000000\(index)") ?? UUID(),
            studentId: UUID(uuidString: "55550000-0000-0000-0000-00000000000\(index)") ?? UUID(),
            name: name,
            relationship: relationship,
            otherSupporterNames: otherSupporterNames
        )
    }

    static let oneLinkedStudent: [LinkedStudent] = [
        linkedStudent(1, name: "Ava Carter"),
    ]

    static let threeLinkedStudents: [LinkedStudent] = [
        linkedStudent(1, name: "Ava Carter"),
        linkedStudent(2, name: "Noah Carter"),
        linkedStudent(3, name: "Mia Okafor", relationship: .guardian),
    ]

    /// A parent's billing rail whose read has landed (RFC 244), loaded here
    /// rather than left to the section's own `.task`.
    static func supporterCoaching(
        kind: CoachingCoverageKind,
        usage: CoachingUsage
    ) async -> SupporterCoachingViewModel {
        let viewModel = SupporterCoachingViewModel(client: PreviewSupporterCoachingUsageClient(
            reading: SupporterCoachingUsage(usage: usage, coverage: CoachingCoverage(kind: kind))
        ))
        await viewModel.load()
        return viewModel
    }

    /// The same rail after a read that produced nothing — the state the section
    /// must say out loud instead of drawing a 0% bar.
    static func supporterCoachingUnavailable() async -> SupporterCoachingViewModel {
        let client = MockSupporterCoachingUsageClient()
        client.results = [.failure(ErrorResponse.unexpected)]
        let viewModel = SupporterCoachingViewModel(client: client)
        await viewModel.load()
        return viewModel
    }

    static func supporterHome(
        students: [LinkedStudent],
        switcherOpen: Bool = false
    ) -> some View {
        SupporterHomeView(
            user: supporterUser,
            home: SupporterHome(
                supporter: PublicSupporter(id: UUID(uuidString: "44440000-0000-0000-0000-000000000001") ?? UUID()),
                students: students
            ),
            authClient: MockAuthClient(),
            supporterInviteClient: PreviewSupporterInviteClient(),
            supporterClient: PreviewSupporterClient(),
            collegeListClient: MockCollegeListClient(),
            // The section reads on its own `.task`, which the harness's settle
            // lets through — the `ConversationView` history-load precedent.
            supporterCoachingUsageClient: PreviewSupporterCoachingUsageClient(),
            // An in-memory store, so a scene never inherits a selection a
            // previous run of the host app left in `UserDefaults` — the same
            // hazard the appearance segment is pinned against below.
            subjectStore: MockSupporterSubjectStore(),
            switcherOpenOnAppear: switcherOpen,
            // No read: a scene photographs the home it was handed, and the
            // roster refresh is `AppViewModel`'s, tested there.
            refreshHome: {},
            onEmailChanged: { _ in },
            onLogout: {},
            onDeleteAccount: { false }
        )
    }

    // MARK: - The family list (RFC 249)

    /// The ceiling in the student's voice, as `SupporterCeiling` in `:service`
    /// serves it. Copied here because a scene photographs what the server
    /// says; the app itself carries none of these words.
    static let studentCeiling = SupporterCeiling(
        canSee: [
            "Your college list and key application deadlines.",
            "Estimated college costs and financial-aid calculations.",
            "The names of the other people connected to you.",
        ],
        staysPrivate: [
            "Your coach conversations. Transcripts are never shared.",
            "They cannot change your college list, profile or tasks.",
        ]
    )

    static let robinLink = UUID(uuidString: "99990000-0000-0000-0000-000000000001") ?? UUID()

    /// The invite double answering the student's list. The section reads it on
    /// its own `.task`, which the settle lets through — the
    /// `supporterHome` coaching-section precedent.
    static func studentFamilyClient(
        _ supporters: [LinkedSupporter],
        costReportLinkLive: Bool = false
    ) -> PreviewSupporterInviteClient {
        PreviewSupporterInviteClient(supporters: StudentSupporters(
            supporters: supporters,
            ceiling: studentCeiling,
            costReportLinkLive: costReportLinkLive
        ))
    }

    static let twoAdults: [LinkedSupporter] = [
        LinkedSupporter(linkId: robinLink, name: "Robin Carter"),
        LinkedSupporter(linkId: UUID(uuidString: "99990000-0000-0000-0000-000000000002") ?? UUID(), name: "Sam Lee"),
    ]

    /// The student's family list on its own, at the screen margin — for the
    /// states whose difference is inside the section, where a whole Settings
    /// capture would spend its height on the rail.
    static func studentFamilyStrip(
        _ client: PreviewSupporterInviteClient,
        confirmingOnAppear: UUID? = nil
    ) -> AnyView {
        AnyView(
            FamilyConnectionsSection(side: .student(client), confirmingOnAppear: confirmingOnAppear)
                .padding(DSSpacing.lg)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(Color.dsBackground)
        )
    }

    /// A parent's Settings with a roster: one student another adult also
    /// supports, and one they support alone.
    ///
    /// `coaching` is the home's reading the delete confirmation consults
    /// (RFC 252); unloaded by default, which shows the notice's conditional
    /// form (coverage not known is not "does not pay").
    static func supporterSettings(
        confirmingOnAppear: UUID? = nil,
        coaching: SupporterCoachingViewModel? = nil,
        deleteConfirmation: DeleteAccountConfirmation = .closed
    ) -> AnyView {
        UserDefaults.standard.set(AppearancePreference.system.rawValue, forKey: AppearancePreference.storageKey)
        return AnyView(
            NavigationStack {
                SettingsView(
                    user: supporterUser,
                    caller: .supporter(
                        .init(
                            roster: [
                                linkedStudent(1, name: "Ava Carter", otherSupporterNames: ["Sam Lee", "Jordan Lee"]),
                                linkedStudent(2, name: "Noah Carter"),
                            ],
                            client: PreviewSupporterClient(),
                            refreshHome: {}
                        ),
                        coaching: coaching ?? SupporterCoachingViewModel(client: PreviewSupporterCoachingUsageClient())
                    ),
                    authClient: MockAuthClient(),
                    supporterInviteClient: PreviewSupporterInviteClient(),
                    disconnectConfirmationOnAppear: confirmingOnAppear,
                    deleteAccountConfirmationOnAppear: deleteConfirmation,
                    onEmailChanged: { _ in },
                    onLogout: {},
                    onDeleteAccount: { false }
                )
            }
        )
    }

    // MARK: - College-list fixtures (RFC 137)

    static let collegeEntries: [CollegeListEntry] = [
        CollegeListEntry(
            id: UUID(uuidString: "77770000-0000-0000-0000-000000000001")!,
            collegeId: UUID(uuidString: "88880000-0000-0000-0000-000000000001")!,
            collegeName: "University of Michigan",
            status: .admitted,
            reasons: "Great engineering program and the aid offer works.",
            version: 3,
            supportingObservations: []
        ),
        CollegeListEntry(
            id: UUID(uuidString: "77770000-0000-0000-0000-000000000002")!,
            collegeId: UUID(uuidString: "88880000-0000-0000-0000-000000000002")!,
            collegeName: "Columbia University",
            status: .applying,
            reasons: nil,
            version: 1,
            supportingObservations: []
        ),
        CollegeListEntry(
            id: UUID(uuidString: "77770000-0000-0000-0000-000000000003")!,
            collegeId: UUID(uuidString: "88880000-0000-0000-0000-000000000003")!,
            collegeName: "Reed College",
            status: .considering,
            reasons: nil,
            version: 1,
            supportingObservations: []
        ),
    ]

    static let detailEntry = CollegeListEntry(
        id: UUID(uuidString: "77770000-0000-0000-0000-000000000001")!,
        collegeId: UUID(uuidString: "88880000-0000-0000-0000-000000000001")!,
        collegeName: "University of Michigan",
        status: .admitted,
        reasons: "Great engineering program and the aid offer works.",
        version: 3,
        supportingObservations: [
            SupportingObservation(
                id: 1,
                quote: "I really clicked with the campus when we visited.",
                utteredAt: SnapshotClock.pinned
            ),
            SupportingObservation(
                id: 2,
                quote: "Engineering is the one subject I never get tired of.",
                utteredAt: SnapshotClock.pinned
            ),
        ]
    )

    static let collegeSearchResults: [CollegeSummary] = [
        CollegeSummary(
            id: UUID(uuidString: "99990000-0000-0000-0000-000000000001")!,
            name: "Columbia University",
            city: "New York",
            state: "NY"
        ),
        CollegeSummary(
            id: UUID(uuidString: "99990000-0000-0000-0000-000000000002")!,
            name: "Columbia College",
            city: "Columbia",
            state: "SC"
        ),
        CollegeSummary(
            id: UUID(uuidString: "99990000-0000-0000-0000-000000000003")!,
            name: "College of Charleston",
            city: "Charleston",
            state: "SC"
        ),
    ]

    static let thread: [Message] = [
        Message(id: "u1", role: .user, content: "Where do I start with my personal statement?",
                createdAt: SnapshotClock.agoHours(2)),
        Message(id: "c1", role: .coach,
                content: "Start with the moment that made you pick the subject. Two or three sentences, "
                    + "concrete, no adjectives you would not say out loud.",
                createdAt: SnapshotClock.agoHours(2)),
        Message(id: "u2", role: .user, content: "It was a summer project I nearly quit.",
                createdAt: SnapshotClock.agoHours(1)),
        Message(id: "c2", role: .coach,
                content: "Good — nearly quitting is the interesting half. What made you stay?",
                createdAt: SnapshotClock.agoHours(1)),
    ]
}

/// `LabeledField` needs a `FocusState` binding and the app's own host for it is
/// `private`, so the catalogue owns eight lines of its own rather than widening
/// anything in `ios-app/UnicoachiOS/`.
private struct LabeledFieldSnapshotHost: View {
    @State var text: String
    var error: String?
    @FocusState private var focus: Bool?

    var body: some View {
        LabeledField(
            "Email",
            text: $text,
            error: error,
            focus: $focus,
            equals: true,
            keyboardType: .emailAddress
        )
    }
}

private struct SegmentedSelectorSnapshotHost: View {
    @State var selection: Int = 0

    var body: some View {
        SegmentedSelector(
            options: [(tag: 0, title: "System"), (tag: 1, title: "Light"), (tag: 2, title: "Dark")],
            selection: $selection
        )
    }
}

private struct OptionCardSnapshotHost: View {
    var body: some View {
        VStack(spacing: DSControl.stackGap) {
            OptionCard("Undergraduate", isSelected: true, action: {})
            OptionCard("Postgraduate", isSelected: false, action: {})
        }
    }
}

// MARK: - The scenes

enum SnapshotCatalogue {
    @MainActor
    static var scenes: [SnapshotScene] {
        [
            // --- Billing surfaces (RFC 119 / RFC 121), never photographed before.
            SnapshotScene(name: "paywall-free-exhausted") {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)
                )
                return AnyView(PaywallView(viewModel: rail))
            },
            SnapshotScene(name: "paywall-period-exhausted") {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: SnapshotClock.pinned)
                )
                return AnyView(PaywallView(viewModel: rail))
            },
            // DisabledSubscriptionStore answers product(id:) with nil, which is
            // the honest "no purchase path offered" arm of `Offer`.
            SnapshotScene(name: "paywall-offer-unavailable") {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil),
                    store: DisabledSubscriptionStore()
                )
                return AnyView(PaywallView(viewModel: rail))
            },
            SnapshotScene(name: "subscription-section-free", size: CGSize(width: 402, height: 560)) {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
                )
                return AnyView(
                    SubscriptionSection(viewModel: rail)
                        .padding(DSSpacing.lg)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .background(Color.dsBackground)
                )
            },
            SnapshotScene(name: "subscription-section-bound-active", size: CGSize(width: 402, height: 560)) {
                let rail = await SnapshotSeed.boundActiveRail()
                return AnyView(
                    SubscriptionSection(viewModel: rail)
                        .padding(DSSpacing.lg)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .background(Color.dsBackground)
                )
            },
            // The Settings section with a FAILING card (RFC 128). Subscribe is
            // withheld here, so this scene is what pins the composition that
            // keeps the student from being stranded: the status line naming the
            // problem, Restore, and `ManageSubscriptionLink` -- the control that
            // fixes it -- below its hairline.
            SnapshotScene(name: "subscription-section-billing-retry", size: CGSize(width: 402, height: 560)) {
                let rail = await SnapshotSeed.boundRail(
                    status: .billingRetry,
                    usage: CoachingUsage(usedPercent: 12, exhausted: false, resetsAt: SnapshotClock.pinned)
                )
                return AnyView(
                    SubscriptionSection(viewModel: rail)
                        .padding(DSSpacing.lg)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .background(Color.dsBackground)
                )
            },

            // --- The RFC 123 subscription sheet, in the four situations its
            // words are decided by: no subscription, an active one running,
            // an active one whose period is spent, and a failing payment.
            // `SubscriptionExplanation` is a pure value with its own tests;
            // what these scenes add is the rendered sheet -- the meter, both
            // hairlines, the offer and `ManageSubscriptionLink` -- around it.
            SnapshotScene(name: "subscription-sheet-free") {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
                )
                return AnyView(SubscriptionView(viewModel: rail))
            },
            SnapshotScene(name: "subscription-sheet-active-open") {
                let rail = await SnapshotSeed.boundRail(
                    status: .active,
                    usage: CoachingUsage(usedPercent: 31, exhausted: false, resetsAt: SnapshotClock.pinned)
                )
                return AnyView(SubscriptionView(viewModel: rail))
            },
            SnapshotScene(name: "subscription-sheet-period-spent") {
                let rail = await SnapshotSeed.boundRail(
                    status: .active,
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: SnapshotClock.pinned)
                )
                return AnyView(SubscriptionView(viewModel: rail))
            },
            SnapshotScene(name: "subscription-sheet-billing-retry") {
                let rail = await SnapshotSeed.boundRail(
                    status: .billingRetry,
                    usage: CoachingUsage(usedPercent: 12, exhausted: false, resetsAt: SnapshotClock.pinned)
                )
                return AnyView(SubscriptionView(viewModel: rail))
            },

            // The paywall over a BOUND subscription: the three paywall scenes
            // above are all unbound, so `ManageSubscriptionLink` -- and the
            // hairline it brings with it -- is absent from every one of them.
            // `offersManage` is `subscription != nil`, so this is the only way
            // that block is photographed on this sheet (RFC 123).
            SnapshotScene(name: "paywall-bound-subscriber") {
                let rail = await SnapshotSeed.boundRail(
                    status: .active,
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: SnapshotClock.pinned)
                )
                return AnyView(PaywallView(viewModel: rail))
            },

            // The Apple-ID collision (RFC 244), reached through the app-open
            // path rather than a purchase. The only state where a withdrawn
            // Subscribe explains ITSELF, so the capture is what shows the
            // explanation standing without a banner: `load()` has cleared the
            // notice, and `ManageSubscriptionLink` cannot stand in for it here
            // because this account holds no subscription to manage.
            SnapshotScene(name: "paywall-apple-id-bound-elsewhere") {
                let rail = await SnapshotSeed.appleIdBoundElsewhereRail(
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)
                )
                return AnyView(PaywallView(viewModel: rail))
            },

            // --- The composer's budget control, at every state
            // `CoachingBudgetGlance` has (RFC 123). One strip rather than four
            // conversation scenes, following `usage-meter-strip`: the control
            // is a primitive whose four readings are the question, and the
            // composer row it sits on is already photographed by
            // `conversation-thread` and `conversation-blocked-composer`.
            // On `dsSurface`, which is the composer's own background -- the
            // ring's colour choice is argued against that surface, not
            // against `dsBackground`.
            SnapshotScene(name: "coaching-budget-strip", size: CGSize(width: 402, height: 400)) {
                let healthy = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 38, exhausted: false, resetsAt: nil)
                )
                let nearlySpent = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 92, exhausted: false, resetsAt: SnapshotClock.pinned)
                )
                let spent = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: SnapshotClock.pinned)
                )
                // Deliberately NOT loaded: an unread meter is the `.noReading`
                // glance, the state that must draw a bare track and say
                // nothing rather than invent a number.
                let unread = SubscriptionViewModel(
                    usageClient: PreviewCoachingUsageClient(),
                    store: PreviewSubscriptionStore(),
                    recorder: PreviewTransactionRecorder()
                )
                return AnyView(
                    VStack(alignment: .leading, spacing: DSSpacing.md) {
                        CoachingBudgetButton(viewModel: healthy, action: {})
                        CoachingBudgetButton(viewModel: nearlySpent, action: {})
                        CoachingBudgetButton(viewModel: spent, action: {})
                        CoachingBudgetButton(viewModel: unread, action: {})
                    }
                    .padding(DSSpacing.lg)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .background(Color.dsSurface)
                )
            },

            // --- The authenticated Settings screen, whole.
            SnapshotScene(name: "settings-populated", size: SnapshotScene.tallCanvas(height: 1000)) {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
                )
                // @AppStorage reads the real UserDefaults, so the appearance
                // segment is pinned rather than inheriting whatever a previous
                // run of the host app left behind.
                UserDefaults.standard.set(
                    AppearancePreference.system.rawValue,
                    forKey: AppearancePreference.storageKey
                )
                return AnyView(
                    NavigationStack {
                        SettingsView(
                            user: SnapshotSeed.user,
                            caller: .student(rail: rail),
                            authClient: MockAuthClient(),
                            supporterInviteClient: PreviewSupporterInviteClient(),
                            onEmailChanged: { _ in },
                            onLogout: {},
                            onDeleteAccount: { false }
                        )
                    }
                )
            },
            // The SAME screen for a supporter (RFC 241): a supporter-voiced
            // invite control, and no subscription section at all — the meter
            // behind it is student-scoped. Both differences are the point of
            // the capture, and neither is visible in a `settings-populated`
            // diff.
            SnapshotScene(name: "settings-supporter", size: SnapshotScene.tallCanvas(height: 800)) {
                UserDefaults.standard.set(
                    AppearancePreference.system.rawValue,
                    forKey: AppearancePreference.storageKey
                )
                return AnyView(
                    NavigationStack {
                        SettingsView(
                            user: SnapshotSeed.supporterUser,
                            caller: .supporter(
                                .init(roster: [], client: PreviewSupporterClient(), refreshHome: {}),
                                coaching: SupporterCoachingViewModel(client: PreviewSupporterCoachingUsageClient())
                            ),
                            authClient: MockAuthClient(),
                            supporterInviteClient: PreviewSupporterInviteClient(),
                            onEmailChanged: { _ in },
                            onLogout: {},
                            onDeleteAccount: { false }
                        )
                    }
                )
            },

            // --- The family list (RFC 249): who is connected, what they can
            // see, and the two-tap disconnect, from both ends. The confirmation
            // is an in-view disclosure precisely so it can be photographed open.
            SnapshotScene(name: "settings-family-two-adults", size: SnapshotScene.tallCanvas(height: 1500)) {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
                )
                UserDefaults.standard.set(
                    AppearancePreference.system.rawValue,
                    forKey: AppearancePreference.storageKey
                )
                return AnyView(
                    NavigationStack {
                        SettingsView(
                            user: SnapshotSeed.user,
                            caller: .student(rail: rail),
                            authClient: MockAuthClient(),
                            supporterInviteClient: SnapshotSeed.studentFamilyClient(SnapshotSeed.twoAdults),
                            onEmailChanged: { _ in },
                            onLogout: {},
                            onDeleteAccount: { false }
                        )
                    }
                )
            },
            SnapshotScene(name: "family-student-confirm-open", size: SnapshotScene.tallCanvas(height: 900)) {
                SnapshotSeed.studentFamilyStrip(
                    SnapshotSeed.studentFamilyClient(SnapshotSeed.twoAdults),
                    confirmingOnAppear: SnapshotSeed.robinLink
                )
            },
            SnapshotScene(name: "family-student-cost-report-live", size: SnapshotScene.tallCanvas(height: 900)) {
                SnapshotSeed.studentFamilyStrip(
                    SnapshotSeed.studentFamilyClient(SnapshotSeed.twoAdults, costReportLinkLive: true)
                )
            },
            SnapshotScene(name: "family-student-empty", size: SnapshotScene.tallCanvas(height: 400)) {
                SnapshotSeed.studentFamilyStrip(SnapshotSeed.studentFamilyClient([]))
            },
            SnapshotScene(name: "settings-supporter-family", size: SnapshotScene.tallCanvas(height: 1000)) {
                SnapshotSeed.supporterSettings()
            },
            SnapshotScene(name: "settings-supporter-family-confirm-open", size: SnapshotScene.tallCanvas(height: 1100)) {
                SnapshotSeed.supporterSettings(confirmingOnAppear: SnapshotSeed.linkedStudent(1, name: "Ava Carter").linkId)
            },

            // --- Account deletion (RFC 252): the inline confirmation, open,
            // from both ends, each with the Apple billing notice — a student's
            // driven by the bound subscription on the StoreKit rail, a
            // parent's by coverage `own_subscription` on the home's reading.
            SnapshotScene(name: "settings-delete-confirm-open", size: SnapshotScene.tallCanvas(height: 1500)) {
                let rail = await SnapshotSeed.boundActiveRail()
                UserDefaults.standard.set(
                    AppearancePreference.system.rawValue,
                    forKey: AppearancePreference.storageKey
                )
                return AnyView(
                    NavigationStack {
                        SettingsView(
                            user: SnapshotSeed.user,
                            caller: .student(rail: rail),
                            authClient: MockAuthClient(),
                            supporterInviteClient: PreviewSupporterInviteClient(),
                            deleteAccountConfirmationOnAppear: .open,
                            onEmailChanged: { _ in },
                            onLogout: {},
                            onDeleteAccount: { false }
                        )
                    }
                )
            },
            SnapshotScene(name: "settings-supporter-delete-confirm-open", size: SnapshotScene.tallCanvas(height: 1400)) {
                let coaching = await SnapshotSeed.supporterCoaching(
                    kind: .ownSubscription,
                    usage: CoachingUsage(usedPercent: 64, exhausted: false, resetsAt: SnapshotClock.pinned)
                )
                return SnapshotSeed.supporterSettings(coaching: coaching, deleteConfirmation: .open)
            },
            // The confirmation after a delete the server did not confirm: the
            // person is still signed in, and the error line sits above the
            // buttons that let them try again.
            SnapshotScene(name: "settings-delete-confirm-failed", size: SnapshotScene.tallCanvas(height: 1400)) {
                SnapshotSeed.supporterSettings(deleteConfirmation: .failed)
            },

            // --- The parent account (RFC 241). Four screens the gate has never
            // seen, because none of them existed: the question an account with
            // neither row is asked, and the home it lands on at each of the
            // three roster sizes that read differently.
            SnapshotScene(name: "role-choice") {
                AnyView(
                    RoleChoiceView(
                        userName: "Robin",
                        supporterClient: PreviewSupporterClient(),
                        onStudent: {},
                        onSupporter: { _ in }
                    )
                )
            },
            SnapshotScene(name: "supporter-home-empty") {
                AnyView(SnapshotSeed.supporterHome(students: []))
            },
            SnapshotScene(name: "supporter-home-one-student") {
                AnyView(SnapshotSeed.supporterHome(students: SnapshotSeed.oneLinkedStudent))
            },
            // Open, because closed it is one line of chrome. The switcher is an
            // in-view disclosure rather than a `Menu` precisely so this capture
            // is possible: a system menu renders in its own window, which the
            // rasterizer cannot reach.
            SnapshotScene(name: "supporter-home-switcher-open") {
                AnyView(SnapshotSeed.supporterHome(
                    students: SnapshotSeed.threeLinkedStudents,
                    switcherOpen: true
                ))
            },
            // How a parent's coaching is paid for (RFC 244), at all four of the
            // section's states in one canvas. One scene rather than four homes:
            // the three home scenes above photograph it IN PLACE, in its
            // commonest state, and what no home capture can show is the three
            // other sets of words — including the one that must never be a
            // silent 0% bar.
            SnapshotScene(name: "supporter-coaching-states", size: SnapshotScene.tallCanvas(height: 700)) {
                let freeAllowance = await SnapshotSeed.supporterCoaching(
                    kind: .freeAllowance,
                    usage: CoachingUsage(usedPercent: 18, exhausted: false, resetsAt: nil)
                )
                let spent = await SnapshotSeed.supporterCoaching(
                    kind: .freeAllowance,
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)
                )
                let ownSubscription = await SnapshotSeed.supporterCoaching(
                    kind: .ownSubscription,
                    usage: CoachingUsage(usedPercent: 64, exhausted: false, resetsAt: SnapshotClock.pinned)
                )
                let unavailable = await SnapshotSeed.supporterCoachingUnavailable()
                return AnyView(
                    VStack(alignment: .leading, spacing: DSSpacing.xl) {
                        SupporterCoachingSection(viewModel: freeAllowance)
                        SupporterCoachingSection(viewModel: spent)
                        SupporterCoachingSection(viewModel: ownSubscription)
                        SupporterCoachingSection(viewModel: unavailable)
                    }
                    .padding(DSSpacing.lg)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .background(Color.dsBackground)
                )
            },

            // --- The one invented primitive, at its three interesting readings.
            SnapshotScene(name: "usage-meter-strip", size: CGSize(width: 402, height: 480)) {
                AnyView(
                    VStack(alignment: .leading, spacing: DSSpacing.xl) {
                        UsageMeter(usedPercent: 42, exhausted: false, resetsAt: nil)
                        UsageMeter(usedPercent: 68, exhausted: false, resetsAt: SnapshotClock.pinned)
                        UsageMeter(usedPercent: 100, exhausted: true, resetsAt: SnapshotClock.pinned)
                    }
                    .padding(DSSpacing.lg)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .background(Color.dsBackground)
                )
            },

            // --- The slide-over drawer, only ever seen mid-animation until now.
            SnapshotScene(name: "menu-recents") {
                let client = SnapshotSeed.conversationClient(history: [], list: SnapshotSeed.recents)
                let listViewModel = ConversationListViewModel(conversationClient: client)
                await listViewModel.load()
                return AnyView(menu(listViewModel))
            },
            SnapshotScene(name: "menu-empty") {
                let client = SnapshotSeed.conversationClient(history: [], list: [])
                let listViewModel = ConversationListViewModel(conversationClient: client)
                await listViewModel.load()
                return AnyView(menu(listViewModel))
            },

            // --- The primary screen.
            SnapshotScene(name: "conversation-thread") {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
                )
                let client = SnapshotSeed.conversationClient(history: SnapshotSeed.thread)
                return AnyView(
                    NavigationStack {
                        ConversationView(
                            conversation: SnapshotSeed.conversation(name: "Personal statement", hoursAgo: 2),
                            conversationClient: client,
                            paywallGate: SnapshotSeed.gate(rail),
                            onProfileRequired: {}
                        )
                    }
                )
            },
            // The RFC 118/120 defect ground: the worst-case reply in the real
            // bubble. A longer settle because the MarkdownView width probe's
            // preference-key round trip is the async measurement the whole
            // RunLoop spin exists for.
            // A TALLER canvas than the device: at 874pt the reply is cut off at
            // the composer, half way down the table, and the fenced block --
            // the wrapped-code case RFC 120 was written about -- never appears
            // at all. A scene that exists to review table and code-block
            // rendering has to show them, and the frame is not a claim about
            // what fits on an iPhone; the device-height scenes are.
            SnapshotScene(
                name: "conversation-markdown-worstcase",
                size: SnapshotScene.tallCanvas(height: 1400),
                settle: 0.8
            ) {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
                )
                let client = SnapshotSeed.conversationClient(history: [
                    Message(id: "u1", role: .user, content: "What should I do next?",
                            createdAt: SnapshotClock.agoHours(2)),
                    Message(id: "c1", role: .coach, content: MarkdownFixture.worstCaseReply,
                            createdAt: SnapshotClock.agoHours(2)),
                ])
                return AnyView(
                    NavigationStack {
                        ConversationView(
                            conversation: SnapshotSeed.conversation(name: "Deadlines", hoursAgo: 2),
                            conversationClient: client,
                            paywallGate: SnapshotSeed.gate(rail),
                            onProfileRequired: {}
                        )
                    }
                )
            },
            // RFC 121's paused composer: the rail's meter says `spent`, which
            // is the shared blocked truth every conversation observes.
            SnapshotScene(name: "conversation-blocked-composer") {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)
                )
                let client = SnapshotSeed.conversationClient(history: SnapshotSeed.thread)
                return AnyView(
                    NavigationStack {
                        ConversationView(
                            conversation: SnapshotSeed.conversation(name: "Personal statement", hoursAgo: 2),
                            conversationClient: client,
                            paywallGate: SnapshotSeed.gate(rail),
                            onProfileRequired: {}
                        )
                    }
                )
            },

            // --- The history surface.
            SnapshotScene(name: "conversation-list-populated") {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
                )
                let client = SnapshotSeed.conversationClient(history: [], list: SnapshotSeed.recents)
                return AnyView(
                    NavigationStack {
                        ConversationListView(
                            conversationClient: client,
                            paywallGate: SnapshotSeed.gate(rail),
                            onProfileRequired: {}
                        )
                    }
                )
            },
            SnapshotScene(name: "conversation-list-empty") {
                let rail = await SnapshotSeed.rail(
                    usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
                )
                let client = SnapshotSeed.conversationClient(history: [], list: [])
                return AnyView(
                    NavigationStack {
                        ConversationListView(
                            conversationClient: client,
                            paywallGate: SnapshotSeed.gate(rail),
                            onProfileRequired: {}
                        )
                    }
                )
            },

            // --- The college list (RFC 137): the drawer's "My colleges"
            // destination, its empty state, the entry detail, and the add
            // picker's results. The list scenes hand the view a seeded mock
            // client and let its own `.task` load — the conversation-list
            // precedent — while the add scene pre-seeds its view model, because
            // typing into a debounced field is not a render.
            SnapshotScene(name: "college-list-populated") {
                let client = MockCollegeListClient()
                client.listEntriesResult = .success(SnapshotSeed.collegeEntries)
                return AnyView(
                    NavigationStack {
                        CollegeListView(client: client, onProfileRequired: {})
                    }
                )
            },
            SnapshotScene(name: "college-list-empty") {
                let client = MockCollegeListClient()
                client.listEntriesResult = .success([])
                return AnyView(
                    NavigationStack {
                        CollegeListView(client: client, onProfileRequired: {})
                    }
                )
            },
            SnapshotScene(name: "college-entry-detail") {
                let client = MockCollegeListClient()
                let entry = SnapshotSeed.detailEntry
                client.listEntriesResult = .success([entry])
                let listViewModel = CollegeListViewModel(client: client, onProfileRequired: {})
                await listViewModel.load()
                return AnyView(
                    NavigationStack {
                        CollegeEntryDetailView(viewModel: listViewModel, entry: entry)
                    }
                )
            },
            SnapshotScene(name: "add-college-results") {
                let client = MockCollegeListClient()
                client.searchCollegesResult = .success(SnapshotSeed.collegeSearchResults)
                let viewModel = AddCollegeViewModel(
                    client: client, onProfileRequired: {}, debounce: .zero
                )
                viewModel.query = "col"
                await viewModel.awaitPendingSearch()
                return AnyView(
                    NavigationStack {
                        CollegeSearchView(
                            viewModel: viewModel,
                            tap: .addsToMyList,
                            focusesSearchOnAppear: false
                        )
                    }
                )
            },

            // --- Your details (RFC 171): the drawer's "Your details"
            // destination. Each scene hands the view seeded mocks and lets its
            // own `.task` load, the college-list precedent. Three states,
            // because they are three different screens to read: nothing
            // answered (both undo controls resolve to one), a mixed profile
            // (an answer and a decline, with the decline's picker still fully
            // live), and the whole-screen load failure. A taller canvas than
            // the device: the form does not scroll for a reviewer, and a
            // clipped screen cannot be reviewed.
            SnapshotScene(name: "your-details-unanswered", size: SnapshotScene.tallCanvas(height: SnapshotScene.maxHeight)) {
                AnyView(SnapshotSeed.yourDetails())
            },
            SnapshotScene(name: "your-details-answered-and-declined", size: SnapshotScene.tallCanvas(height: SnapshotScene.maxHeight)) {
                AnyView(SnapshotSeed.yourDetails(
                    profile: SnapshotSeed.mixedMoneyProfile,
                    // The subjects row answered as well (RFC 205 §7), so the
                    // chips and the two first-class choices are in the corpus
                    // beside the money block rather than only as an empty row.
                    intendedSubjects: .answered(["nursing", "marine-biology"])
                ))
            },
            // The decline, which is the state this screen exists to make
            // undoable: a decline set in CHAT is visible here, with the search
            // still offered beneath it.
            SnapshotScene(name: "your-details-subjects-declined", size: SnapshotScene.tallCanvas(height: SnapshotScene.maxHeight)) {
                AnyView(SnapshotSeed.yourDetails(intendedSubjects: .declined))
            },
            // The sharing control answered yes (RFC 251 §5): "Show them"
            // selected and no never-asked line. The three scenes above carry
            // the default, never asked, with neither segment selected.
            SnapshotScene(name: "your-details-sharing-granted", size: SnapshotScene.tallCanvas(height: SnapshotScene.maxHeight)) {
                AnyView(SnapshotSeed.yourDetails(
                    profile: SnapshotSeed.mixedMoneyProfile,
                    intendedSubjects: .answered(["nursing", "marine-biology"]),
                    sharing: .granted
                ))
            },
            SnapshotScene(name: "your-details-failed") {
                AnyView(SnapshotSeed.yourDetails(
                    fetchResult: .failure(ErrorResponse(
                        code: "SERVER_ERROR", message: "An unexpected error occurred.", fieldErrors: nil
                    ))
                ))
            },

            // --- Onboarding (RFC 163). This screen is entirely visual and had
            // no scene at all, which is how a form grew to 1.9 viewports without
            // anyone seeing it. Three states: the default first paint, the
            // tallest date shape, and both optional answers given.
            // `onboarding-year` keeps the DEVICE canvas on purpose: this scene
            // is the answer to "does the first paint fit without scrolling?",
            // and a taller window would answer a question nobody asked. The
            // other two are given a taller canvas because the window does not
            // scroll for you, and a clipped form cannot be reviewed.
            SnapshotScene(name: "onboarding-year") {
                AnyView(SnapshotSeed.onboarding())
            },
            SnapshotScene(name: "onboarding-full-date", size: SnapshotScene.tallCanvas(height: 1400)) {
                AnyView(SnapshotSeed.onboarding(precision: .full))
            },
            SnapshotScene(name: "onboarding-answered", size: SnapshotScene.tallCanvas(height: 1400)) {
                AnyView(SnapshotSeed.onboarding(
                    residencyState: SnapshotSeed.california,
                    incomeBand: .k48To75k,
                    dateOfBirthAnswered: true
                ))
            },
            // The age field's two states, which are the whole of RFC 222 on
            // this screen: three EMPTY menus at first paint — no pre-selected
            // birth year, because a form must not nudge the answer it is asking
            // for — and the inline error a submit with nothing chosen produces,
            // which is the form's own refusal and never reaches the server.
            SnapshotScene(name: "onboarding-date-of-birth-empty", size: SnapshotScene.tallCanvas(height: 1400)) {
                AnyView(SnapshotSeed.onboarding())
            },
            SnapshotScene(name: "onboarding-date-of-birth-required", size: SnapshotScene.tallCanvas(height: 1400)) {
                AnyView(SnapshotSeed.onboarding(dateOfBirthError: true))
            },
            // The same screen at an accessibility text size. Every other scene
            // renders at default type, so nothing in the corpus observed the
            // claim `DSPickerRow` is built on — that its `@ScaledMetric` height
            // makes the row GROW rather than clip its label. Dynamic Type is a
            // SwiftUI environment value, so the harness needs nothing new: the
            // scene sets it on the hosted view.
            //
            // At `SnapshotScene.maxHeight`, which is a ceiling rather than a
            // fit to the content — see that constant for the crash it encodes.
            // The form measured ~1420pt at `.accessibility1` and fit with
            // nothing clipped; RFC 222's three date-of-birth rows push it past
            // the ceiling, so the capture is now clipped at the foot. The
            // scene's question is unharmed: it asks whether a `DSPickerRow`
            // GROWS rather than clips its own label, and the rows in frame
            // answer it.
            SnapshotScene(name: "onboarding-accessibility-type", size: SnapshotScene.tallCanvas(height: SnapshotScene.maxHeight)) {
                AnyView(SnapshotSeed.onboarding().dynamicTypeSize(.accessibility1))
            },

            // --- The unauthenticated entry point: Apple/Google button parity is
            // a PNG question, not a test question.
            SnapshotScene(name: "login-idle") {
                AnyView(
                    LoginView(
                        authClient: MockAuthClient(),
                        googleSignInProvider: MockSsoSignInProvider(provider: .google),
                        appleSignInProvider: MockSsoSignInProvider(provider: .apple),
                        accountClosed: false,
                        onLoginSuccess: { _ in },
                        onSwitchToRegister: {}
                    )
                )
            },
            // Where a deletion lands (RFC 252): the closed line and the Apple
            // ID directions above the sign-in buttons.
            SnapshotScene(name: "login-account-closed") {
                AnyView(
                    LoginView(
                        authClient: MockAuthClient(),
                        googleSignInProvider: MockSsoSignInProvider(provider: .google),
                        appleSignInProvider: MockSsoSignInProvider(provider: .apple),
                        accountClosed: true,
                        onLoginSuccess: { _ in },
                        onSwitchToRegister: {}
                    )
                )
            },

            // --- Every design-system control in one tall scene.
            SnapshotScene(name: "design-system-catalogue", size: SnapshotScene.tallCanvas(height: 1500)) {
                AnyView(designSystemCatalogue)
            },
        ]
    }

    /// The drawer is a fraction of the screen width and is never full-bleed, so
    /// it is composed here exactly as `AuthenticatedRootView` composes it.
    @MainActor
    private static func menu(_ viewModel: ConversationListViewModel) -> some View {
        HStack(spacing: 0) {
            SlideOverMenu(
                viewModel: viewModel,
                onNewConversation: {},
                onSelect: { _ in },
                onYourDetails: {},
                onMyColleges: {},
                onAllConversations: {},
                onSettings: {}
            )
            .frame(width: SnapshotOutput.deviceSize.width * DSMenu.widthFraction)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground.opacity(0.6))
    }

    @MainActor
    private static var designSystemCatalogue: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                BrandTopBar {
                    BrandTopBarButton(
                        systemImage: "line.3.horizontal",
                        accessibilityIdentifier: "menuButton",
                        accessibilityLabel: "Menu",
                        action: {}
                    )
                }

                LogoMark(widthFraction: 0.2)

                LoadingButton("Primary", isLoading: false, role: .primary, action: {})
                LoadingButton("Destructive", isLoading: false, role: .destructive, action: {})
                LoadingButton("Loading", isLoading: true, role: .primary, action: {})

                HStack(spacing: DSSpacing.md) {
                    CircularIconButton(systemImage: "arrow.up", isLoading: false, action: {})
                    CircularIconButton(systemImage: "arrow.up", isLoading: true, action: {})
                }

                LabeledFieldSnapshotHost(text: "student@example.com")
                LabeledFieldSnapshotHost(text: "wrong", error: "That email is already registered.")

                OptionCardSnapshotHost()

                SegmentedSelectorSnapshotHost()

                StepIndicator(count: 3, current: 1)

                DSHairline()

                FormErrorBanner("We couldn't sign you in. Please try again.")
                FieldErrorText("Enter a valid email address.")

                GoogleSignInButton(action: {})
                AppleSignInButton(action: {})
            }
            .padding(DSSpacing.lg)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color.dsBackground)
    }
}
