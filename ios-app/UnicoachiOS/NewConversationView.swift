import SwiftUI

struct NewConversationView: View {
    @StateObject private var viewModel: NewConversationViewModel
    @FocusState private var isComposerFocused: Bool

    init(conversationClient: ConversationClientProtocol, onProfileRequired: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: NewConversationViewModel(
            conversationClient: conversationClient,
            onProfileRequired: onProfileRequired
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            thread
            errorArea
            composer
        }
        .background(Color.dsBackground)
        .navigationTitle("New Conversation")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var isComposerDisabled: Bool {
        viewModel.isStreaming || viewModel.coachMessage != nil
    }

    // MARK: - Thread

    private var thread: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.md) {
                if let userMessage = viewModel.userMessage {
                    messageBubble(
                        text: userMessage.content,
                        isUser: true,
                        identifier: "userBubble"
                    )
                }

                if viewModel.coachMessage != nil || !viewModel.coachStreamingText.isEmpty {
                    messageBubble(
                        text: viewModel.coachMessage?.content ?? viewModel.coachStreamingText,
                        isUser: false,
                        identifier: "coachBubble"
                    )
                }

                if viewModel.isStreaming && viewModel.coachMessage == nil {
                    streamingIndicator
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(DSSpacing.md)
        }
    }

    private func messageBubble(text: String, isUser: Bool, identifier: String) -> some View {
        HStack {
            if isUser { Spacer(minLength: DSSpacing.xl) }
            Text(text)
                .font(.dsBody)
                .foregroundStyle(isUser ? Color.brandOnAccent : Color.dsTextPrimary)
                .padding(DSSpacing.md)
                .background(isUser ? Color.brandAccent : Color.dsSurface)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.field, style: .continuous))
                .accessibilityIdentifier(identifier)
            if !isUser { Spacer(minLength: DSSpacing.xl) }
        }
    }

    private var streamingIndicator: some View {
        HStack(spacing: DSSpacing.sm) {
            ProgressView()
                .progressViewStyle(.circular)
            Text("Coach is typing…")
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
        }
        .accessibilityIdentifier("streamingIndicator")
        .accessibilityLabel("Coach is responding")
    }

    // MARK: - Errors

    @ViewBuilder
    private var errorArea: some View {
        if let errorResponse = viewModel.errorResponse {
            FormErrorBanner(errorResponse.message)
                .padding(.horizontal, DSSpacing.md)
                .padding(.bottom, DSSpacing.sm)
        }

        if let infrastructureError = viewModel.infrastructureError {
            HStack(alignment: .firstTextBaseline, spacing: DSSpacing.sm) {
                Image(systemName: infrastructureError.systemImage)
                VStack(alignment: .leading, spacing: DSSpacing.xs) {
                    Text(infrastructureError.title)
                        .font(.dsLabel)
                    Text(infrastructureError.description)
                        .font(.dsCaption)
                }
            }
            .foregroundStyle(Color.dsError)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(DSSpacing.md)
            .accessibilityElement(children: .combine)
            .padding(.horizontal, DSSpacing.md)
            .padding(.bottom, DSSpacing.sm)
        }
    }

    // MARK: - Composer

    private var composer: some View {
        HStack(spacing: DSSpacing.sm) {
            TextField("Message", text: $viewModel.messageText, axis: .vertical)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .padding(DSSpacing.md)
                .background(Color.dsSurface)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.field, style: .continuous))
                .focused($isComposerFocused)
                .disabled(isComposerDisabled)
                .accessibilityIdentifier("messageField")
                .accessibilityLabel("Message")

            LoadingButton(
                "Send",
                isLoading: viewModel.isStreaming,
                role: .primary,
                accessibilityIdentifier: "sendButton",
                accessibilityLabel: "Send",
                action: send
            )
            .frame(width: 96)
            .disabled(isComposerDisabled)
        }
        .padding(DSSpacing.md)
        .background(Color.dsBackground)
    }

    private func send() {
        isComposerFocused = false
        Task { await viewModel.send() }
    }
}

private final class NewConversationPreviewClient: ConversationClientProtocol, @unchecked Sendable {
    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let conversation = Conversation(
                id: UUID(),
                name: "Preview",
                createdAt: Date(),
                updatedAt: Date(),
                lastActivityAt: nil,
                archivedAt: nil
            )
            continuation.yield(.conversation(
                conversation,
                userMessage: Message(id: "u1", role: .user, content: request.message, createdAt: Date())
            ))
            continuation.yield(.delta("Let's get started."))
            continuation.yield(.completed(
                Message(id: "c1", role: .coach, content: "Let's get started.", createdAt: Date())
            ))
            continuation.finish()
        }
    }
}

#Preview {
    NavigationStack {
        NewConversationView(conversationClient: NewConversationPreviewClient(), onProfileRequired: {})
    }
}
