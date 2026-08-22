import SwiftUI

struct HomeView: View {
    let user: PublicUser
    let conversationClient: ConversationClientProtocol
    let onProfileRequired: () -> Void
    let onLogout: () async -> Void
    @State private var isLoggingOut = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                BrandTopBar()

                VStack(alignment: .leading, spacing: DSControl.stackGap) {
                    Text("Welcome, \(user.name)")
                        .dsOverlineStyle()
                        .foregroundStyle(Color.dsTextPrimary)

                    Text("What should we work on?")
                        .font(.dsDisplay)
                        .foregroundStyle(Color.dsTextPrimary)

                    Text(user.email)
                        .font(.dsLabel)
                        .foregroundStyle(Color.dsTextSecondary)

                    Spacer()

                    NavigationLink {
                        ConversationView(conversationClient: conversationClient, onProfileRequired: onProfileRequired)
                    } label: {
                        Text("Start Coaching")
                            .font(.dsButton)
                            .foregroundStyle(Color.dsControlOnFill)
                            .frame(maxWidth: .infinity, minHeight: DSControl.height)
                            .background(Color.dsControlFill)
                            .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
                    }
                    .accessibilityIdentifier("startCoachingButton")
                    .accessibilityLabel("Start Coaching")

                    // The secondary of the pair: same 64pt/16pt box, outlined
                    // rather than filled. Depth is carried by the hairline, and
                    // there is exactly one filled control per screen.
                    NavigationLink {
                        ConversationListView(conversationClient: conversationClient, onProfileRequired: onProfileRequired)
                    } label: {
                        Text("Your Conversations")
                            .font(.dsButton)
                            .foregroundStyle(Color.dsTextPrimary)
                            .frame(maxWidth: .infinity, minHeight: DSControl.height)
                            .background(Color.dsSurface)
                            .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                                    .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
                            )
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
                .padding(.top, DSSpacing.lg)
                .padding(.horizontal, DSSpacing.lg)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.dsBackground)
            // BrandTopBar IS this screen's chrome; the stock bar would sit above
            // it as a second, empty one.
            .toolbar(.hidden, for: .navigationBar)
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

@MainActor private var homePreview: some View {
    HomeView(
        user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview User", emailVerified: true),
        conversationClient: HomePreviewConversationClient(),
        onProfileRequired: {},
        onLogout: {}
    )
}

#Preview("home - Light") {
    homePreview
        .preferredColorScheme(.light)
}

#Preview("home - Dark") {
    homePreview
        .preferredColorScheme(.dark)
}
