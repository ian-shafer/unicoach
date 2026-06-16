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

    init(conversationClient: ConversationClientProtocol, onProfileRequired: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: ConversationListViewModel(conversationClient: conversationClient))
        self.conversationClient = conversationClient
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
                    onProfileRequired: onProfileRequired
                )
            } label: {
                conversationRow(conversation)
            }
        }
        .listStyle(.plain)
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
        .frame(maxWidth: .infinity, alignment: .leading)
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
                .font(.dsTitleXL)
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
            .foregroundStyle(Color.brandAccent)
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
                .foregroundStyle(Color.brandOnAccent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, DSSpacing.md)
                .background(Color.brandAccent)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.button, style: .continuous))
        }
        .accessibilityIdentifier("startConversationButton")
        .accessibilityLabel("Start a conversation")
    }

    private var freshConversation: some View {
        ConversationView(conversationClient: conversationClient, onProfileRequired: onProfileRequired)
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
}

#Preview {
    NavigationStack {
        ConversationListView(conversationClient: ConversationListPreviewClient(), onProfileRequired: {})
    }
}
