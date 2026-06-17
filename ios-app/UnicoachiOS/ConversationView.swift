import SwiftUI

private struct SendButtonWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

struct ConversationView: View {
    @StateObject private var viewModel: ConversationViewModel
    @FocusState private var isComposerFocused: Bool
    @State private var sendButtonWidth: CGFloat = 0

    init(conversationClient: ConversationClientProtocol, onProfileRequired: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: ConversationViewModel(
            conversationClient: conversationClient,
            onProfileRequired: onProfileRequired
        ))
    }

    init(conversation: Conversation, conversationClient: ConversationClientProtocol, onProfileRequired: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: ConversationViewModel(
            conversation: conversation,
            conversationClient: conversationClient,
            onProfileRequired: onProfileRequired
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            threadArea
            validationArea
            composer
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        .navigationTitle("Coaching")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.loadHistory() }
    }

    private var isComposerDisabled: Bool {
        viewModel.isStreaming || !viewModel.isReady
    }

    // MARK: - History load

    /// Renders the initial history-fetch state for a re-entered conversation: a
    /// progress indicator while loading, an inline error with Retry on failure,
    /// and the live thread when ready (also the only state a fresh VM ever shows).
    @ViewBuilder
    private var threadArea: some View {
        switch viewModel.historyLoad {
        case .loading:
            historyLoadingView
        case .failed(let error):
            historyFailedView(error)
        case .ready:
            thread
        }
    }

    private var historyLoadingView: some View {
        VStack(spacing: DSSpacing.sm) {
            ProgressView()
                .progressViewStyle(.circular)
            Text("Loading conversation…")
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("historyLoadingIndicator")
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Loading conversation")
    }

    private func historyFailedView(_ error: ErrorResponse) -> some View {
        VStack(spacing: DSSpacing.md) {
            FormErrorBanner(error.message)
            Button("Retry") {
                Task { await viewModel.loadHistory() }
            }
            .font(.dsButton)
            .foregroundStyle(Color.brandAccent)
            .accessibilityIdentifier("historyRetryButton")
            .accessibilityLabel("Retry")
        }
        .padding(DSSpacing.md)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }

    // MARK: - Thread

    private var thread: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: DSSpacing.md) {
                    ForEach(viewModel.turns) { turn in
                        turnView(turn)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(DSSpacing.md)
            }
            .onChange(of: scrollAnchor) { _, _ in
                guard let lastId = viewModel.turns.last?.id else { return }
                withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// A value that changes whenever new content lands at the bottom of the
    /// thread, driving auto-scroll: turn count, the active turn's streaming
    /// length, and its terminal/failure state.
    private var scrollAnchor: String {
        guard let last = viewModel.turns.last else { return "" }
        return "\(viewModel.turns.count)-\(last.coachStreamingText.count)-\(last.coachMessage != nil)-\(last.failure != nil)"
    }

    @ViewBuilder
    private func turnView(_ turn: ChatTurn) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            messageBubble(text: turn.userMessage.content, isUser: true, identifier: "userBubble")

            if turn.coachMessage != nil || !turn.coachStreamingText.isEmpty {
                messageBubble(
                    text: turn.coachMessage?.content ?? turn.coachStreamingText,
                    isUser: false,
                    identifier: "coachBubble"
                )
            }

            if isActiveStreamingTurn(turn) {
                streamingIndicator
            }

            if let failure = turn.failure {
                failureView(failure, turnId: turn.id)
            }
        }
        .id(turn.id)
    }

    /// The streaming indicator shows on the in-flight turn (the last one) while a
    /// stream is active and no coach reply has completed yet.
    private func isActiveStreamingTurn(_ turn: ChatTurn) -> Bool {
        viewModel.isStreaming && turn.coachMessage == nil && turn.id == viewModel.turns.last?.id
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

    // MARK: - Per-turn failure

    @ViewBuilder
    private func failureView(_ failure: TurnFailure, turnId: ChatTurn.ID) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            switch failure {
            case .server(let error):
                FormErrorBanner(error.message)
            case .infrastructure(let infra):
                HStack(alignment: .firstTextBaseline, spacing: DSSpacing.sm) {
                    Image(systemName: infra.systemImage)
                    VStack(alignment: .leading, spacing: DSSpacing.xs) {
                        Text(infra.title)
                            .font(.dsLabel)
                        Text(infra.description)
                            .font(.dsCaption)
                    }
                }
                .foregroundStyle(Color.dsError)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            }

            Button("Retry") {
                Task { await viewModel.retry(turnId) }
            }
            .font(.dsButton)
            .foregroundStyle(Color.brandAccent)
            .accessibilityIdentifier("retryButton")
            .accessibilityLabel("Retry")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Validation

    @ViewBuilder
    private var validationArea: some View {
        if let validationError = viewModel.validationError {
            FormErrorBanner(validationError.message)
                .padding(.horizontal, DSSpacing.md)
                .padding(.bottom, DSSpacing.sm)
        }
    }

    // MARK: - Composer

    private var composer: some View {
        TextField("Message", text: $viewModel.messageText, axis: .vertical)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextPrimary)
            .padding(DSSpacing.md)
            .padding(.trailing, sendButtonWidth + DSSpacing.sm)
            .background(Color.dsSurface)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.field, style: .continuous))
            .focused($isComposerFocused)
            .disabled(isComposerDisabled)
            .accessibilityIdentifier("messageField")
            .accessibilityLabel("Message")
            .overlay(alignment: .bottomTrailing) {
                CircularIconButton(
                    systemImage: "arrow.up",
                    isLoading: viewModel.isStreaming,
                    accessibilityIdentifier: "sendButton",
                    accessibilityLabel: "Send",
                    action: send
                )
                .disabled(!viewModel.canSend)
                .background(
                    GeometryReader { proxy in
                        Color.clear.preference(key: SendButtonWidthKey.self, value: proxy.size.width)
                    }
                )
                .padding(DSSpacing.sm)
            }
            .onPreferenceChange(SendButtonWidthKey.self) { width in
                sendButtonWidth = width
            }
            .padding(DSSpacing.md)
            .background(Color.dsBackground)
    }

    private func send() {
        isComposerFocused = false
        Task { await viewModel.send() }
    }
}

private final class ConversationPreviewClient: ConversationClientProtocol, @unchecked Sendable {
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

    func postMessage(conversationId: UUID, request: PostMessageRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            continuation.yield(.userMessage(
                Message(id: UUID().uuidString, role: .user, content: request.message, createdAt: Date())
            ))
            continuation.yield(.delta("Tell me more."))
            continuation.yield(.completed(
                Message(id: UUID().uuidString, role: .coach, content: "Tell me more.", createdAt: Date())
            ))
            continuation.finish()
        }
    }

    func listConversations() async throws -> [Conversation] {
        [
            Conversation(
                id: UUID(),
                name: "Essay brainstorming",
                createdAt: Date(),
                updatedAt: Date(),
                lastActivityAt: Date(),
                archivedAt: nil
            ),
        ]
    }

    func fetchMessages(conversationId: UUID) async throws -> [Message] {
        [
            Message(id: "u1", role: .user, content: "Where do I start?", createdAt: Date()),
            Message(id: "c1", role: .coach, content: "Let's begin with your goals.", createdAt: Date()),
        ]
    }

    func deleteConversation(conversationId: UUID) async throws {}

    func setArchived(conversationId: UUID, archived: Bool) async throws {}
}

#Preview {
    NavigationStack {
        ConversationView(conversationClient: ConversationPreviewClient(), onProfileRequired: {})
    }
}
