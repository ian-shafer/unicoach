import SwiftUI

/// The conversation-history surface: the student's unarchived conversations in
/// the server's most-recently-used order, each row tapping into the chat screen
/// seeded with that conversation's history. A toolbar compose button starts a
/// fresh conversation. Re-fetches on every appearance so MRU order reflects a
/// conversation just continued and popped back from.
struct ConversationListView: View {
    @StateObject private var viewModel: ConversationListViewModel
    private let conversationClient: ConversationClientProtocol
    private let onProfileRequired: () -> Void
    /// Carried, not used: every `ConversationView` this screen pushes needs the
    /// authenticated root's paywall gate (RFC 121), and this list is on the path
    /// between the two. **One** opaque value, so a list that has no paywall
    /// concern of its own does not have to keep three correlated arguments in
    /// step.
    private let paywallGate: PaywallGate

    /// Set by a Delete tap (swipe or context menu) to stage a conversation for the
    /// confirmation dialog. The actual `viewModel.delete(_:)` fires only when the
    /// dialog's confirm button resolves.
    @State private var pendingDeletion: Conversation?

    init(
        conversationClient: ConversationClientProtocol,
        paywallGate: PaywallGate,
        onProfileRequired: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: ConversationListViewModel(conversationClient: conversationClient))
        self.conversationClient = conversationClient
        self.paywallGate = paywallGate
        self.onProfileRequired = onProfileRequired
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.dsBackground)
            .navigationTitle("Your Conversations")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink {
                        freshConversation
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                    .accessibilityIdentifier("composeButton")
                    .accessibilityLabel("New conversation")
                }
            }
            .task { await viewModel.load() }
            .confirmationDialog(
                "Delete this conversation?",
                isPresented: deletionDialogBinding,
                titleVisibility: .visible,
                presenting: pendingDeletion
            ) { conversation in
                Button("Delete", role: .destructive) {
                    Task { await viewModel.delete(conversation) }
                }
                .accessibilityIdentifier("deleteConfirmButton")
                Button("Cancel", role: .cancel) {}
                    .accessibilityIdentifier("deleteCancelButton")
            } message: { _ in
                Text("This can't be undone.")
            }
            .alert(item: $viewModel.actionError) { error in
                Alert(
                    title: Text("Something went wrong"),
                    message: Text(error.message),
                    dismissButton: .default(Text("OK"))
                )
            }
    }

    /// Bridges the optional `pendingDeletion` to the dialog's `isPresented` flag:
    /// presenting when a conversation is staged, and clearing the staged value
    /// when the dialog dismisses.
    private var deletionDialogBinding: Binding<Bool> {
        Binding(
            get: { pendingDeletion != nil },
            set: { presented in
                if !presented { pendingDeletion = nil }
            }
        )
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .loading:
            loadingView
        case .loaded(let conversations):
            conversationList(conversations)
        case .empty:
            emptyView
        case .failed(let error):
            failedView(error)
        }
    }

    // MARK: - States

    private var loadingView: some View {
        ProgressView()
            .progressViewStyle(.circular)
            .tint(Color.dsTextPrimary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("conversationListLoading")
            .accessibilityLabel("Loading conversations")
    }

    private func conversationList(_ conversations: [Conversation]) -> some View {
        List(conversations) { conversation in
            NavigationLink {
                ConversationView(
                    conversation: conversation,
                    conversationClient: conversationClient,
                    paywallGate: paywallGate,
                    onProfileRequired: onProfileRequired
                )
            } label: {
                conversationRow(conversation)
            }
            // Rows are outlined cards on white, not stock inset-grouped rows:
            // the separator and the row background are removed so the card's own
            // hairline is the only separation (DESIGN.md §8 extrapolation).
            .listRowSeparator(.hidden)
            .listRowBackground(Color.dsBackground)
            .listRowInsets(EdgeInsets(top: DSControl.stackGap / 2, leading: DSSpacing.lg, bottom: DSControl.stackGap / 2, trailing: DSSpacing.lg))
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) {
                    pendingDeletion = conversation
                } label: {
                    Label("Delete", systemImage: "trash")
                }
                .accessibilityIdentifier("deleteButton")

                Button {
                    Task { await viewModel.archive(conversation) }
                } label: {
                    Label("Archive", systemImage: "archivebox")
                }
                .accessibilityIdentifier("archiveButton")
            }
            .contextMenu {
                Button {
                    Task { await viewModel.archive(conversation) }
                } label: {
                    Label("Archive", systemImage: "archivebox")
                }
                .accessibilityIdentifier("archiveButton")

                Button(role: .destructive) {
                    pendingDeletion = conversation
                } label: {
                    Label("Delete", systemImage: "trash")
                }
                .accessibilityIdentifier("deleteButton")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.dsBackground)
    }

    private func conversationRow(_ conversation: Conversation) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text(conversation.name)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
            Text(relativeTimestamp(conversation))
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
        }
        .padding(DSSpacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.dsSurface)
        .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
        )
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("conversationRow")
    }

    /// A relative timestamp from `lastActivityAt`, falling back to `updatedAt`
    /// to unwrap the `Date?` (a listed conversation always has a visible turn, so
    /// `lastActivityAt` is set in practice).
    private func relativeTimestamp(_ conversation: Conversation) -> String {
        let date = conversation.lastActivityAt ?? conversation.updatedAt
        return date.formatted(.relative(presentation: .named))
    }

    private var emptyView: some View {
        VStack(spacing: DSSpacing.md) {
            Text("No conversations yet")
                .font(.dsDisplay)
                .foregroundStyle(Color.dsTextPrimary)
            Text("Start a conversation to get coaching.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
                .multilineTextAlignment(.center)
            startConversationLink
        }
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("conversationListEmpty")
    }

    private func failedView(_ error: ErrorResponse) -> some View {
        VStack(spacing: DSSpacing.md) {
            FormErrorBanner(error.message)
            Button("Retry") {
                Task { await viewModel.load() }
            }
            .font(.dsButton)
            .foregroundStyle(Color.dsTextPrimary)
            .accessibilityIdentifier("conversationListRetryButton")
            .accessibilityLabel("Retry")
        }
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("conversationListFailed")
    }

    // MARK: - Compose / start

    private var startConversationLink: some View {
        NavigationLink {
            freshConversation
        } label: {
            Text("Start a conversation")
                .font(.dsButton)
                .foregroundStyle(Color.dsControlOnFill)
                .frame(maxWidth: .infinity, minHeight: DSControl.height)
                .background(Color.dsControlFill)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        }
        .accessibilityIdentifier("startConversationButton")
        .accessibilityLabel("Start a conversation")
    }

    private var freshConversation: some View {
        ConversationView(
            conversationClient: conversationClient,
            paywallGate: paywallGate,
            onProfileRequired: onProfileRequired
        )
    }
}

private final class ConversationListPreviewClient: ConversationClientProtocol, @unchecked Sendable {
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

@MainActor private var conversationListPreview: some View {
    NavigationStack {
        ConversationListView(
            conversationClient: ConversationListPreviewClient(),
            paywallGate: PaywallGate(
                subscriptions: SubscriptionViewModel(
                    usageClient: PreviewCoachingUsageClient(),
                    store: PreviewSubscriptionStore(),
                    recorder: PreviewTransactionRecorder()
                ),
                isPresented: .constant(false)
            ),
            onProfileRequired: {}
        )
    }
}

#Preview("conversationList - Light") {
    conversationListPreview
        .preferredColorScheme(.light)
}

#Preview("conversationList - Dark") {
    conversationListPreview
        .preferredColorScheme(.dark)
}
