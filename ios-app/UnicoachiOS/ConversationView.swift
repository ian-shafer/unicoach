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
            if viewModel.turns.isEmpty {
                emptyThread
            } else {
                thread
            }
        }
    }

    /// The root of the authenticated app opens on a fresh conversation, so this
    /// is the first thing a signed-in student sees — a blank void otherwise.
    /// Deliberately ONE line of token-driven copy: a designed empty state is
    /// still open work (DESIGN.md §8.2), and inventing an illustration here
    /// would be inventing visual language.
    private var emptyThread: some View {
        Text("What's on your mind about college?")
            .font(.dsDisplay)
            .foregroundStyle(Color.dsTextSecondary)
            .multilineTextAlignment(.center)
            .padding(DSSpacing.lg)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("conversationEmpty")
    }

    private var historyLoadingView: some View {
        VStack(spacing: DSSpacing.sm) {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Color.dsTextPrimary)
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
            .foregroundStyle(Color.dsTextPrimary)
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
            // The student's turn is an **utterance**: plain `Text`, inset.
            // Rendering it as Markdown would silently eat the `*` in "should I
            // apply *early*?" and reflow the line breaks they typed, which is a
            // bug rather than a feature (RFC 118).
            messageBubble(isUser: true, identifier: "userBubble") {
                Text(turn.userMessage.content)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextPrimary)
            }

            if turn.coachMessage != nil || !turn.coachStreamingText.isEmpty {
                // The coach's turn is a **document** — it may carry a heading, a
                // table, a code block — and is rendered Markdown at full width.
                messageBubble(isUser: false, identifier: "coachBubble") {
                    MarkdownView(source: turn.coachMessage?.content ?? turn.coachStreamingText)
                }
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

    /// Bubbles are **outlined, never filled** (DESIGN.md §8 extrapolation): a
    /// saturated user bubble is exactly the large brand-coloured surface §6
    /// rules out, and this design carries depth by border alone. The user's turn
    /// is distinguished by border weight — a darkened `TextPrimary` hairline
    /// against the coach's `FieldBorder` one — not by colour.
    ///
    /// **The coach's bubble takes the full content width unconditionally**; the
    /// student's keeps its leading spacer and stays inset (Ian's call, RFC
    /// 118). Unconditional rather than "wide when the content is wide" because
    /// a content-conditional width resizes the bubble *mid-stream*: the reply
    /// would open as a paragraph at inset width and jump wider the moment a
    /// table's delimiter row arrived three deltas later.
    ///
    /// **Generic in its content.** The bubble used to take `isUser` *and*
    /// `rendersMarkdown` — two booleans that are always exact inverses — and
    /// switch on the second internally, which let a caller write the meaningless
    /// `(isUser: true, rendersMarkdown: true)`. Chrome is the primitive here;
    /// what goes inside a turn is the caller's business, so that combination now
    /// has no spelling at all.
    private func messageBubble(
        isUser: Bool,
        identifier: String,
        @ViewBuilder content: () -> some View
    ) -> some View {
        // `spacing: 0` because the inset is the student's `Spacer` and nothing
        // else: the default HStack gap would sit outside the coach's
        // `.infinity` frame and quietly make "full width" full width minus 8pt.
        HStack(spacing: 0) {
            if isUser { Spacer(minLength: DSSpacing.xl) }
            content()
                .padding(DSSpacing.md)
                .frame(maxWidth: isUser ? nil : .infinity, alignment: .leading)
                .background(Color.dsSurface)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                        .stroke(isUser ? Color.dsTextPrimary : Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
                )
                .accessibilityIdentifier(identifier)
        }
    }


    private var streamingIndicator: some View {
        HStack(spacing: DSSpacing.sm) {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Color.dsTextPrimary)
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
            .foregroundStyle(Color.dsTextPrimary)
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
            .padding(.horizontal, DSControl.textInset)
            .padding(.vertical, DSSpacing.md)
            .padding(.trailing, sendButtonWidth + DSSpacing.sm)
            .frame(minHeight: DSControl.height)
            .background(Color.dsSurface)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
            // The composer is a LabeledField in everything but name: same
            // radius, same hairline, same 20pt leading inset.
            .overlay(
                RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                    .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
            )
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
    /// History the preview replays. Parameterised so one client can back both
    /// the plain preview and the Markdown one below.
    private let history: [Message]

    init(history: [Message] = [
        Message(id: "u1", role: .user, content: "Where do I start?", createdAt: Date()),
        Message(id: "c1", role: .coach, content: "Let's begin with your goals.", createdAt: Date()),
    ]) {
        self.history = history
    }

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
        history
    }

    func deleteConversation(conversationId: UUID) async throws {}

    func setArchived(conversationId: UUID, archived: Bool) async throws {}
}

@MainActor private var conversationPreview: some View {
    NavigationStack {
        ConversationView(conversationClient: ConversationPreviewClient(), onProfileRequired: {})
    }
}

#Preview("conversation - Light") {
    conversationPreview
        .preferredColorScheme(.light)
}

#Preview("conversation - Dark") {
    conversationPreview
        .preferredColorScheme(.dark)
}

/// The worst-case reply in the real bubble, which is the only place the
/// full-width coach turn, the bubble's radius and the table's own scrolling can
/// be judged together (RFC 118).
@MainActor private var markdownConversationPreview: some View {
    NavigationStack {
        ConversationView(
            conversation: Conversation(
                id: UUID(),
                name: "Deadlines",
                createdAt: Date(),
                updatedAt: Date(),
                lastActivityAt: Date(),
                archivedAt: nil
            ),
            conversationClient: ConversationPreviewClient(history: [
                Message(id: "u1", role: .user, content: "What should I do next?", createdAt: Date()),
                Message(id: "c1", role: .coach, content: MarkdownFixture.worstCaseReply, createdAt: Date()),
            ]),
            onProfileRequired: {}
        )
    }
}

#Preview("conversation markdown - Light") {
    markdownConversationPreview
        .preferredColorScheme(.light)
}

#Preview("conversation markdown - Dark") {
    markdownConversationPreview
        .preferredColorScheme(.dark)
}
