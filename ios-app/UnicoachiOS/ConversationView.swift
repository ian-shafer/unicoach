import SwiftUI

struct ConversationView: View {
    @StateObject private var viewModel: ConversationViewModel
    /// The **shared** blocked truth, owned by `AuthenticatedRootView` and handed
    /// down as it already is to Settings. Every pushed conversation builds its
    /// own `ConversationViewModel`, so a per-view-model flag would leave one
    /// screen blocked and the next one cheerfully offering a composer — this one
    /// object is what makes the block the same everywhere (RFC 121).
    @ObservedObject private var subscriptionViewModel: SubscriptionViewModel
    /// The gate itself, whose `present()` opens the paywall living above this
    /// view: the sheet is the authenticated root's, so it survives a push and
    /// covers the whole stack.
    private let paywallGate: PaywallGate
    /// Whether this view was opened as a *new* conversation rather than an
    /// existing one. A new conversation has nothing to read, so the composer
    /// takes focus on appearance and the student can type immediately; an
    /// existing one must not, or the keyboard covers the history they just
    /// opened. Note this creates nothing server-side — a conversation is only
    /// created when the first message is sent.
    private let startsFresh: Bool
    @FocusState private var isComposerFocused: Bool

    init(
        conversationClient: ConversationClientProtocol,
        paywallGate: PaywallGate,
        onProfileRequired: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: ConversationViewModel(
            conversationClient: conversationClient,
            onProfileRequired: onProfileRequired,
            onBudgetExhausted: paywallGate.handleBudgetExhausted
        ))
        _subscriptionViewModel = ObservedObject(wrappedValue: paywallGate.subscriptions)
        self.paywallGate = paywallGate
        self.startsFresh = true
    }

    init(
        conversation: Conversation,
        conversationClient: ConversationClientProtocol,
        paywallGate: PaywallGate,
        onProfileRequired: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: ConversationViewModel(
            conversation: conversation,
            conversationClient: conversationClient,
            onProfileRequired: onProfileRequired,
            onBudgetExhausted: paywallGate.handleBudgetExhausted
        ))
        _subscriptionViewModel = ObservedObject(wrappedValue: paywallGate.subscriptions)
        self.paywallGate = paywallGate
        self.startsFresh = false
    }

    var body: some View {
        VStack(spacing: 0) {
            threadArea
            validationArea
            blockedArea
            composer
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        .navigationTitle("Coaching")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.loadHistory() }
        // A new conversation is a blank page: put the cursor in the composer so
        // the student can type without a tap. Guarded by `isComposerDisabled`
        // because raising the keyboard over a composer that cannot accept a turn
        // (blocked by the paywall) invites typing into a dead field.
        .onAppear {
            guard startsFresh, !isComposerDisabled else { return }
            isComposerFocused = true
        }
    }

    private var isComposerDisabled: Bool {
        viewModel.isStreaming || !viewModel.isReady || isBlocked
    }

    /// The proactive half of the gate, and a **courtesy only**: it disables the
    /// composer before the student types into something that cannot send. Usage
    /// can be stale, so an enabled composer never promises a turn will be
    /// accepted — the 402 is the authority, and works on its own.
    ///
    /// Only a meter that says `spent` blocks: `unknown` is a reading that has
    /// not arrived or a refresh that failed, and a failed read must never
    /// disable a composer.
    private var isBlocked: Bool {
        subscriptionViewModel.budget == .spent
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
            // That is also why it is an `.utterance` to copy: nothing to render,
            // so nothing to choose between.
            messageBubble(
                isUser: true,
                identifier: "userBubble",
                menu: .utterance(text: turn.userMessage.content)
            ) {
                Text(turn.userMessage.content)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextPrimary)
            }

            if turn.coachMessage != nil || !turn.coachStreamingText.isEmpty {
                // The coach's turn is a **document** — it may carry a heading, a
                // table, a code block — and is rendered Markdown at full width.
                // The same string feeds the renderer and both copy actions, so
                // "copy as Markdown" is by construction the exact source the
                // bubble drew — including mid-stream, where copying half a
                // reply is the student's own choice.
                let source = turn.coachMessage?.content ?? turn.coachStreamingText
                messageBubble(
                    isUser: false,
                    identifier: "coachBubble",
                    menu: .document(source: source)
                ) {
                    MarkdownView(source: source)
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
        menu: CopyMenu,
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
                .copyMenu(menu)
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

    /// The failure's words, then its action. The action is not always Retry:
    /// while blocked, a refused turn's only honest offer is "See options",
    /// because retrying can do nothing but reproduce the 402. The turn itself is
    /// kept either way — the student's words are theirs — so once the block
    /// clears this same turn offers Retry again and sends what they wrote.
    @ViewBuilder
    private func failureView(_ failure: TurnFailure, turnId: ChatTurn.ID) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            switch failure {
            case .server(let error):
                FormErrorBanner(error.message)
            case .blocked:
                Text(PaywallCopy.refusedTurnDetail(basis: subscriptionViewModel.coachingBasis))
                    .font(.dsCaption)
                    .foregroundStyle(Color.dsTextSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("turnBlocked")
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

            // A `.blocked` turn offers Retry again the moment the meter reports
            // the budget open — that is what keeping the student's words was
            // for. While the meter has no answer, the 402 stays the authority.
            // See `TurnAction`.
            switch TurnAction(failure: failure, budget: subscriptionViewModel.budget) {
            case .seeOptions:
                seeOptionsButton(identifier: "turnSeeOptionsButton")
            case .retry:
                Button("Retry") {
                    Task { await viewModel.retry(turnId) }
                }
                .font(.dsButton)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityIdentifier("retryButton")
                .accessibilityLabel("Retry")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The paywall's one affordance, said once — beside a refused turn and above
    /// the blocked composer. The identifier is a parameter because both can be
    /// on screen at the same time, and a duplicated identifier names neither
    /// element for VoiceOver or a UI test.
    private func seeOptionsButton(identifier: String) -> some View {
        Button("See options", action: paywallGate.present)
            .font(.dsButton)
            .foregroundStyle(Color.dsTextPrimary)
            .accessibilityIdentifier(identifier)
            .accessibilityLabel("See options")
    }

    // MARK: - Blocked

    /// The block state, in the shape `validationArea` already established: one
    /// optional published value, rendered just above the composer. It is not a
    /// `FormErrorBanner` — being out of coaching is not an error the student
    /// made, and the only thing to do about it is on the sheet.
    @ViewBuilder
    private var blockedArea: some View {
        if isBlocked {
            VStack(alignment: .leading, spacing: DSSpacing.xs) {
                // The title, not the detail: the sentence naming the basis (and
                // the reset date) belongs to the refused turn and to the sheet.
                // Saying it a third time, one line above a button that opens the
                // screen it is written on, is noise.
                Text(PaywallCopy.pausedTitle)
                    .font(.dsCaption)
                    .foregroundStyle(Color.dsTextSecondary)

                seeOptionsButton(identifier: "composerSeeOptionsButton")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, DSSpacing.md)
            .padding(.bottom, DSSpacing.sm)
            .accessibilityIdentifier("composerBlocked")
        }
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

    /// One outlined box containing a text field **above a control row** (RFC
    /// 123) — the shape the composer was already imitating. It keeps its
    /// `DSRadius.control` corners, its 1pt `dsFieldBorder` hairline and its 20pt
    /// leading inset, so it is still a `LabeledField` in everything but name.
    ///
    /// The box replaces a `TextField` with the send button `.overlay`-ed at
    /// `.bottomTrailing` and the text inset out of its way by a
    /// `SendButtonWidthKey` preference measured at runtime. That geometry hack
    /// existed only to fake the row this now actually has, so it — the
    /// preference key, the `@State` width, the trailing padding and the
    /// `onPreferenceChange` — is gone. The composer is **taller**, which is the
    /// accepted cost of the row.
    private var composer: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            messageField

            controlRow
        }
        .padding(.horizontal, DSControl.textInset)
        .padding(.vertical, DSSpacing.md)
        .frame(minHeight: DSControl.height)
        .background(Color.dsSurface)
        .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
        )
        .padding(DSSpacing.md)
        .background(Color.dsBackground)
    }

    /// The turn itself. Named rather than inlined so the box's two children —
    /// this and `controlRow` — read at the same altitude: one named child
    /// beside seven stacked modifiers hides the shape the box exists to state.
    ///
    /// `axis: .vertical` is what makes it grow with the message; disabling
    /// follows `isComposerDisabled` (blocked or streaming), which the budget
    /// control deliberately does not.
    private var messageField: some View {
        TextField("Message", text: $viewModel.messageText, axis: .vertical)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextPrimary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .focused($isComposerFocused)
            .disabled(isComposerDisabled)
            .accessibilityIdentifier("messageField")
            .accessibilityLabel("Message")
    }

    /// The budget on the left, the send button on the right.
    ///
    /// The budget control is **not** disabled with the composer: a student who
    /// has just been blocked needs exactly that door, and the sheet is a
    /// read-only explanation the rest of the time (RFC 123).
    ///
    /// `Spacer(minLength:)` and the send button's `layoutPriority` are what make
    /// the budget label yield first under large Dynamic Type — the send control
    /// is the one thing on this row that must never be squeezed.
    private var controlRow: some View {
        HStack(spacing: DSSpacing.sm) {
            CoachingBudgetButton(
                viewModel: subscriptionViewModel,
                action: paywallGate.presentExplanation
            )

            Spacer(minLength: DSSpacing.sm)

            CircularIconButton(
                systemImage: "arrow.up",
                isLoading: viewModel.isStreaming,
                accessibilityIdentifier: "sendButton",
                accessibilityLabel: "Send",
                action: send
            )
            .disabled(!viewModel.canSend || isBlocked)
            .layoutPriority(1)
        }
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

/// The canvas's gate over a shared subscription rail, built once per preview:
/// nothing is loaded into it, so the meter reads `unknown` and the composer
/// previews in its ordinary state. The blocked composer is judged on the
/// paywall's canvas and in the simulator, where a real reading can be injected.
@MainActor private func conversationPreviewGate(usage: CoachingUsage? = nil) -> PaywallGate {
    PaywallGate(
        subscriptions: SubscriptionViewModel(
            usageClient: PreviewCoachingUsageClient(usage: usage ?? CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)),
            store: PreviewSubscriptionStore(),
            recorder: PreviewTransactionRecorder()
        ),
        presentedSheet: .constant(nil)
    )
}

@MainActor private var conversationPreview: some View {
    NavigationStack {
        ConversationView(
            conversationClient: ConversationPreviewClient(),
            paywallGate: conversationPreviewGate(),
            onProfileRequired: {}
        )
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
            paywallGate: conversationPreviewGate(),
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
