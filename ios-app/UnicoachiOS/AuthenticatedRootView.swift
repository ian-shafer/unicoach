import SwiftUI

/// The root of the authenticated state: the app opens straight into chat
/// (DESIGN.md §7). This view owns three things and nothing else — the single
/// `NavigationStack` for the authenticated tree, the conversation on screen,
/// and the slide-over menu that covers it.
///
/// `VerificationRequiredView` keeps its own stack: it is a sibling *auth state*,
/// never on screen at the same time as this one, so the two cannot share a
/// stack without hoisting it above the state switch — which §7 forbids.
struct AuthenticatedRootView: View {
    let user: PublicUser
    let authClient: AuthClientProtocol
    let conversationClient: ConversationClientProtocol
    let onProfileRequired: () -> Void
    let onEmailChanged: (PublicUser) async -> Void
    let onLogout: () async -> Void

    /// The StoreKit rail and the one `TransactionRecorder` over it, **built by
    /// `AppViewModel` and passed in**. This view's `init` runs again on every
    /// publish of that object, so anything constructed here would be rebuilt
    /// while the `@StateObject` below kept the first copy — leaving the listener
    /// and the view model on different recorders over different stores, and the
    /// rebuilt store's registry empty, so `finish()` would silently no-op and a
    /// paid purchase would never be finished.
    ///
    /// What does belong here is the *listener*: a renewal must be recorded
    /// whether or not Settings is on screen, and must not be attempted
    /// unauthenticated, where `/verify` could only answer 401.
    private let store: SubscriptionStoreProtocol
    private let recorder: TransactionRecording

    /// Destinations pushed from the menu. An enum rather than `NavigationLink`
    /// destinations because the links live in an overlay that is dismissed as it
    /// navigates.
    ///
    /// `conversation` carries the whole `Conversation` because that is what
    /// `ConversationView` needs to seed its history fetch.
    private enum Destination: Hashable {
        case conversation(Conversation)
        case conversations
        case settings
    }

    @Environment(\.scenePhase) private var scenePhase

    @State private var path: [Destination] = []
    @State private var isMenuOpen = false
    /// Re-identifies the root conversation. **New conversation** in the drawer
    /// pops back to the root, but once the root has been used it is a real
    /// conversation, so popping to it alone leaves the same thread on screen —
    /// a visible no-op. Bumping the token rebuilds the root view (and its view
    /// model) as a blank thread whose composer takes focus on appearance.
    /// Nothing is created server-side: a conversation exists only once its
    /// first message is sent.
    @State private var rootConversationToken = UUID()
    /// The paywall (RFC 121). Presented from **here**, not from the conversation
    /// screen, so one sheet serves the whole stack: a 402 on a pushed
    /// conversation and a "See options" tap on the root open the same one, over
    /// whatever is on screen.
    @State private var isPaywallPresented = false

    /// Owned here rather than by `SlideOverMenu` so the drawer's list survives
    /// open/close and the drawer can slide in already populated — see `menu`.
    @StateObject private var menuViewModel: ConversationListViewModel

    /// Owned here, not by `SettingsView`, so the subscription surface keeps its
    /// state across a push/pop of Settings. It is safe as a `@StateObject`
    /// precisely because the store and recorder it closes over are the app's,
    /// not this init's: re-running the init cannot hand it a second rail.
    @StateObject private var subscriptionViewModel: SubscriptionViewModel

    init(
        user: PublicUser,
        authClient: AuthClientProtocol,
        conversationClient: ConversationClientProtocol,
        coachingUsageClient: CoachingUsageClientProtocol,
        subscriptionStore: SubscriptionStoreProtocol,
        transactionRecorder: TransactionRecording,
        onProfileRequired: @escaping () -> Void,
        onEmailChanged: @escaping (PublicUser) async -> Void,
        onLogout: @escaping () async -> Void
    ) {
        self.user = user
        self.authClient = authClient
        self.conversationClient = conversationClient
        self.onProfileRequired = onProfileRequired
        self.onEmailChanged = onEmailChanged
        self.onLogout = onLogout
        _menuViewModel = StateObject(wrappedValue: ConversationListViewModel(conversationClient: conversationClient))

        self.store = subscriptionStore
        self.recorder = transactionRecorder
        _subscriptionViewModel = StateObject(wrappedValue: SubscriptionViewModel(
            usageClient: coachingUsageClient,
            store: subscriptionStore,
            recorder: transactionRecorder
        ))
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
        // The transaction listener, for the whole authenticated session: Apple
        // redelivers an unfinished transaction and pushes renewals and Ask to
        // Buy approvals here. Every one goes to the same recorder, which is the
        // only thing that decides whether it may be finished, and its outcome is
        // handed to the surface that shows it. SwiftUI cancels the task when
        // this view goes away, i.e. on logout.
        .task { await recordTransactionUpdates() }
        // The initial meter read, moved up from `SubscriptionSection` (RFC
        // 121): the composer must be able to block for a student who never
        // opens Settings, and the paywall must have a number to show the moment
        // it appears rather than a beat later.
        //
        // **Usage only** — not `load()`, which also fetches the StoreKit product
        // and re-posts the newest entitlement to `/verify`. RFC 119 scoped that
        // to the subscription surface deliberately, and taking the whole of it
        // here would put a `/verify` POST on every launch. Settings still calls
        // `load()`, as does the paywall: they are the screens with a price to
        // show.
        .task { await subscriptionViewModel.refreshUsage() }
        // The proactive block's only expiry, mirroring RFC 72's `scenePhase`
        // re-check. A period rolls over (or a subscription is bought elsewhere)
        // while the app is resident, and nothing else would ever ask: the
        // composer would stay disabled past the very reset date the paywall
        // named. One GET on returning to the foreground, which also keeps the
        // meter on the two screens that render it honest.
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await subscriptionViewModel.refreshUsage() }
        }
        .sheet(isPresented: $isPaywallPresented) {
            PaywallView(viewModel: subscriptionViewModel)
        }
    }

    /// The gate, built here because this is the only place that can build it
    /// correctly: the rail below is the one the sheet renders, and the sheet's
    /// flag is this view's. Everything under the root takes this one value
    /// rather than three arguments that are only correct together (RFC 121).
    private var paywallGate: PaywallGate {
        PaywallGate(
            subscriptions: subscriptionViewModel,
            isPresented: $isPaywallPresented
        )
    }

    /// The listener pump itself, named rather than inlined in `body`: a
    /// `for await` loop over a session-long stream is not layout.
    private func recordTransactionUpdates() async {
        for await transaction in store.updates() {
            await subscriptionViewModel.apply(await recorder.record(transaction))
        }
    }

    /// The brand chrome stays **above** the drawer rather than under it: the
    /// gradient bar is the app's identity, the button that opened the drawer
    /// stays where the user left it (and closes it again), and the drawer clips
    /// to the content area by construction rather than by fighting the safe
    /// area.
    private var root: some View {
        VStack(spacing: 0) {
            BrandTopBar {
                BrandTopBarButton(
                    systemImage: "line.3.horizontal",
                    accessibilityIdentifier: "menuButton",
                    accessibilityLabel: isMenuOpen ? "Close menu" : "Menu",
                    action: { setMenu(open: !isMenuOpen) }
                )
            }

            // The drawer is measured against the content area, and stays in the
            // hierarchy at all times — hidden by an offset rather than by
            // conditional insertion, so it and its contents are one animation.
            GeometryReader { proxy in
                let menuWidth = proxy.size.width * DSMenu.widthFraction

                ZStack(alignment: .topLeading) {
                    chat
                    scrim
                    menu(width: menuWidth)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        // Fetched once when the authenticated tree appears, so the first open
        // is already populated rather than empty-then-populated.
        .task { await menuViewModel.load() }
        .onChange(of: isMenuOpen) { _, isOpen in
            // Refresh, not reload: `refresh()` keeps the rows on screen while it
            // runs, so MRU order updates without the list blinking out.
            if isOpen { Task { await menuViewModel.refresh() } }
        }
    }

    /// The root is **always a fresh conversation** — chat-first, with nothing
    /// behind it to go back to. Every existing conversation is a pushed
    /// destination instead, from the menu and from the full list alike, so a
    /// push gives it its own `ConversationView` (and its own `@StateObject`
    /// view model) by construction. That is what retired the earlier
    /// `.id(selectedConversation)` re-identification trick: there is no longer
    /// a shared root view model to swap underneath.
    private var chat: some View {
        ConversationView(
            conversationClient: conversationClient,
            paywallGate: paywallGate,
            onProfileRequired: onProfileRequired
        )
        .id(rootConversationToken)
    }

    // MARK: - Menu

    /// A dim, not a shadow: this design has no elevation (DESIGN.md §3).
    private var scrim: some View {
        Color.dsScrim
            .ignoresSafeArea(edges: .bottom)
            .opacity(isMenuOpen ? 1 : 0)
            // A transparent scrim would still swallow every tap meant for the
            // chat underneath it.
            .allowsHitTesting(isMenuOpen)
            .onTapGesture { setMenu(open: false) }
            .accessibilityIdentifier("menuScrim")
            .accessibilityLabel("Close menu")
            .accessibilityAddTraits(.isButton)
    }

    private func menu(width: CGFloat) -> some View {
        SlideOverMenu(
            viewModel: menuViewModel,
            // Pop anything pushed on top of the root, blank the root itself,
            // and close the menu.
            onNewConversation: {
                path.removeAll()
                rootConversationToken = UUID()
                setMenu(open: false)
            },
            onSelect: { conversation in push(.conversation(conversation)) },
            onAllConversations: { push(.conversations) },
            onSettings: { push(.settings) }
        )
        .frame(width: width)
        // The drawer resolves its geometry as ONE unit, before the offset is
        // applied. Without this, `.offset` is interpolated per leaf: a row
        // inserted by `refresh()` *during* the slide has no in-flight geometry
        // to interpolate from, so it resolves against the offset's target value
        // and is painted at its final x — outside the still-sliding drawer,
        // over the chat — snapping into place only when the animation ends.
        // This must sit ABOVE `.offset`; below it, it is a no-op.
        .geometryGroup()
        .offset(x: isMenuOpen ? 0 : -width)
        // Swiping back closes the drawer, as tapping the scrim does.
        .gesture(
            DragGesture().onEnded { value in
                if value.translation.width < 0 { setMenu(open: false) }
            }
        )
    }

    @ViewBuilder
    private func destination(_ destination: Destination) -> some View {
        switch destination {
        case .conversation(let conversation):
            ConversationView(
                conversation: conversation,
                conversationClient: conversationClient,
                paywallGate: paywallGate,
                onProfileRequired: onProfileRequired
            )
        case .conversations:
            ConversationListView(
                conversationClient: conversationClient,
                paywallGate: paywallGate,
                onProfileRequired: onProfileRequired
            )
        case .settings:
            SettingsView(
                user: user,
                authClient: authClient,
                subscriptionViewModel: subscriptionViewModel,
                onEmailChanged: onEmailChanged,
                onLogout: onLogout
            )
        }
    }

    private func push(_ destination: Destination) {
        setMenu(open: false)
        path.append(destination)
    }

    private func setMenu(open: Bool) {
        withAnimation { isMenuOpen = open }
    }
}

// MARK: - Previews

private final class AuthenticatedRootPreviewClient: ConversationClientProtocol, @unchecked Sendable {
    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { $0.finish() }
    }

    func postMessage(conversationId: UUID, request: PostMessageRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { $0.finish() }
    }

    func listConversations() async throws -> [Conversation] {
        [
            Conversation(id: UUID(), name: "Essay brainstorming", createdAt: Date(), updatedAt: Date(), lastActivityAt: Date(), archivedAt: nil),
            Conversation(id: UUID(), name: "Application timeline", createdAt: Date(), updatedAt: Date(), lastActivityAt: Date().addingTimeInterval(-3600), archivedAt: nil),
        ]
    }

    func fetchMessages(conversationId: UUID) async throws -> [Message] { [] }

    func deleteConversation(conversationId: UUID) async throws {}

    func setArchived(conversationId: UUID, archived: Bool) async throws {}
}

private final class AuthenticatedRootPreviewAuthClient: AuthClientProtocol, @unchecked Sendable {
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

@MainActor private var authenticatedRootPreview: some View {
    AuthenticatedRootView(
        user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview User", emailVerified: true),
        authClient: AuthenticatedRootPreviewAuthClient(),
        conversationClient: AuthenticatedRootPreviewClient(),
        coachingUsageClient: PreviewCoachingUsageClient(),
        subscriptionStore: PreviewSubscriptionStore(),
        transactionRecorder: PreviewTransactionRecorder(),
        onProfileRequired: {},
        onEmailChanged: { _ in },
        onLogout: {}
    )
}

#Preview("authenticatedRoot - Light") {
    authenticatedRootPreview
        .preferredColorScheme(.light)
}

#Preview("authenticatedRoot - Dark") {
    authenticatedRootPreview
        .preferredColorScheme(.dark)
}
