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

    @State private var path: [Destination] = []
    @State private var isMenuOpen = false

    /// Owned here rather than by `SlideOverMenu` so the drawer's list survives
    /// open/close and the drawer can slide in already populated — see `menu`.
    @StateObject private var menuViewModel: ConversationListViewModel

    init(
        user: PublicUser,
        authClient: AuthClientProtocol,
        conversationClient: ConversationClientProtocol,
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
            onProfileRequired: onProfileRequired
        )
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
            // Already at a fresh conversation: closing the menu is the whole
            // action, and popping anything pushed on top of it.
            onNewConversation: {
                path.removeAll()
                setMenu(open: false)
            },
            onSelect: { conversation in push(.conversation(conversation)) },
            onAllConversations: { push(.conversations) },
            onSettings: { push(.settings) }
        )
        .frame(width: width)
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
                onProfileRequired: onProfileRequired
            )
        case .conversations:
            ConversationListView(conversationClient: conversationClient, onProfileRequired: onProfileRequired)
        case .settings:
            SettingsView(user: user, authClient: authClient, onEmailChanged: onEmailChanged, onLogout: onLogout)
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
