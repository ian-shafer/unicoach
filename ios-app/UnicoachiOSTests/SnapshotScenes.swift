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
    let name: String
    var size: CGSize = SnapshotOutput.deviceSize
    var settle: TimeInterval = 0.4
    /// `async` because most seeding is `await viewModel.load()`: every double
    /// answers synchronously, so this is deterministic and fast, but it must
    /// happen BEFORE the render rather than being left to the view's own
    /// `.task`, whose completion the render does not wait for.
    let content: @MainActor () async -> AnyView
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
            SnapshotScene(name: "settings-populated", size: CGSize(width: 402, height: 1000)) {
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
                            authClient: MockAuthClient(),
                            subscriptionViewModel: rail,
                            onEmailChanged: { _ in },
                            onLogout: {}
                        )
                    }
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
                size: CGSize(width: 402, height: 1400),
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

            // --- The unauthenticated entry point: Apple/Google button parity is
            // a PNG question, not a test question.
            SnapshotScene(name: "login-idle") {
                AnyView(
                    LoginView(
                        authClient: MockAuthClient(),
                        googleSignInProvider: MockSsoSignInProvider(provider: .google),
                        appleSignInProvider: MockSsoSignInProvider(provider: .apple),
                        onLoginSuccess: { _ in },
                        onSwitchToRegister: {}
                    )
                )
            },

            // --- Every design-system control in one tall scene.
            SnapshotScene(name: "design-system-catalogue", size: CGSize(width: 402, height: 1500)) {
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
