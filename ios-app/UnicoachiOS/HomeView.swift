import SwiftUI

struct HomeView: View {
    let user: PublicUser
    let conversationClient: ConversationClientProtocol
    let onProfileRequired: () -> Void
    let onLogout: () async -> Void
    @State private var isLoggingOut = false

    var body: some View {
        NavigationStack {
            VStack(spacing: DSSpacing.md) {
                Text("Welcome, \(user.name)!")
                    .font(.dsTitleXL)
                    .foregroundStyle(Color.dsTextPrimary)
                    .padding(.top, DSSpacing.xl)

                Text(user.email)
                    .font(.dsLabel)
                    .foregroundStyle(Color.dsTextSecondary)

                Spacer()

                NavigationLink {
                    ConversationView(conversationClient: conversationClient, onProfileRequired: onProfileRequired)
                } label: {
                    Text("Start Coaching")
                        .font(.dsButton)
                        .foregroundStyle(Color.brandOnAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, DSSpacing.md)
                        .background(Color.brandAccent)
                        .clipShape(RoundedRectangle(cornerRadius: DSRadius.button, style: .continuous))
                }
                .accessibilityIdentifier("startCoachingButton")
                .accessibilityLabel("Start Coaching")

                NavigationLink {
                    ConversationListView(conversationClient: conversationClient, onProfileRequired: onProfileRequired)
                } label: {
                    Text("Your Conversations")
                        .font(.dsButton)
                        .foregroundStyle(Color.brandAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, DSSpacing.md)
                }
                .accessibilityIdentifier("yourConversationsButton")
                .accessibilityLabel("Your Conversations")

                LoadingButton(
                    "Log Out",
                    isLoading: isLoggingOut,
                    role: .destructive,
                    action: {
                        isLoggingOut = true
                        Task {
                            await onLogout()
                            isLoggingOut = false
                        }
                    }
                )
                .padding(.bottom, DSSpacing.xl)
            }
            .padding(.horizontal, DSSpacing.lg)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.dsBackground)
        }
    }
}

private final class HomePreviewConversationClient: ConversationClientProtocol, @unchecked Sendable {
    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { $0.finish() }
    }

    func postMessage(conversationId: UUID, request: PostMessageRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { $0.finish() }
    }

    func listConversations() async throws -> [Conversation] { [] }

    func fetchMessages(conversationId: UUID) async throws -> [Message] { [] }

    func deleteConversation(conversationId: UUID) async throws {}

    func setArchived(conversationId: UUID, archived: Bool) async throws {}
}

#Preview {
    HomeView(
        user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview User"),
        conversationClient: HomePreviewConversationClient(),
        onProfileRequired: {},
        onLogout: {}
    )
}
