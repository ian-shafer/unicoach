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
    /// Whether the student **asked for this blank page**, and so whether the
    /// composer takes focus when it appears. Not "is this conversation fresh?":
    /// the root chat is fresh at launch too, and deriving focus from that is
    /// exactly what raised the keyboard over a thread nobody had read yet (RFC
    /// 127). Only an explicit gesture — **New conversation** in the drawer, and
    /// the conversation list's compose button and **Start a conversation** —
    /// passes `true`.
    ///
    /// It is an intent, so it is **consumed**: `hasConsumedInitialFocus` below
    /// is what limits it to one raise per view instance. The caller's value
    /// outlives the appearance that used it (it is `@State` up there, and
    /// nothing clears it), and `.onAppear` fires again on every RE-appearance —
    /// so without the consume, popping back from Settings would raise the
    /// keyboard on a page the student asked for minutes ago.
    private let focusesComposerOnAppear: Bool
    /// Whether this instance has already honoured `focusesComposerOnAppear`.
    /// The RFC's "root re-appears → CLOSED" rule in one flag: appearance is not
    /// intent, and an intent already acted on is not intent either.
    @State private var hasConsumedInitialFocus = false
    /// The close-from-outside channel, mirrored into `isComposerFocused` below.
    /// `nil` for a **pushed** conversation: the drawer that needs this only ever
    /// covers the root, so a pushed screen has no outside closer and must not
    /// answer the root's (RFC 127).
    ///
    /// **A plain `let`, and it cannot be a property wrapper**: the pushed case
    /// has no object at all, and `@ObservedObject` does not accept an optional.
    /// So this view subscribes to NOTHING — the `onChange` below fires because
    /// `AuthenticatedRootView` holds the object as a `@StateObject`, and its
    /// re-render on each publish rebuilds this view and re-evaluates the body.
    /// Both halves are load-bearing and neither is enforced by the compiler:
    /// "tidying" this to a wrapper, or the root's `@StateObject` to a `let`,
    /// breaks the keyboard silently, with no build error and no failing unit
    /// test. `ComposerFocusWiringTests` is the only thing that notices.
    private let focus: ComposerFocus?
    @FocusState private var isComposerFocused: Bool

    init(
        conversationClient: ConversationClientProtocol,
        paywallGate: PaywallGate,
        focusesComposerOnAppear: Bool,
        focus: ComposerFocus?,
        onProfileRequired: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: ConversationViewModel(
            conversationClient: conversationClient,
            onProfileRequired: onProfileRequired,
            onBudgetExhausted: paywallGate.handleBudgetExhausted,
            onTurnFinished: paywallGate.refreshBudget
        ))
        _subscriptionViewModel = ObservedObject(wrappedValue: paywallGate.subscriptions)
        self.paywallGate = paywallGate
        self.focusesComposerOnAppear = focusesComposerOnAppear
        self.focus = focus
    }

    /// An **existing** conversation, which is always a push. It never focuses on
    /// appearance — history must not open under a keyboard — and it takes **no
    /// `ComposerFocus` parameter at all**: the drawer that closes a keyboard
    /// from outside only ever covers the root, so wiring one in here is not a
    /// mistake to catch in review, it is a call that cannot be written (RFC
    /// 127).
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
            onBudgetExhausted: paywallGate.handleBudgetExhausted,
            onTurnFinished: paywallGate.refreshBudget
        ))
        _subscriptionViewModel = ObservedObject(wrappedValue: paywallGate.subscriptions)
        self.paywallGate = paywallGate
        self.focusesComposerOnAppear = false
        self.focus = nil
    }

    var body: some View {
        VStack(spacing: 0) {
            threadArea
                // Tap the thread to lower the keyboard. `contentShape` is what
                // makes the gesture cover the whole area rather than the glyphs
                // in it — and on the **empty** conversation, the screen the app
                // launches on, this is the only dismissal there is: there is
                // nothing to scroll and Return in a vertical-axis field inserts
                // a newline (RFC 127). A child's own gesture still wins, so
                // Retry and the copy menu are unaffected.
                .contentShape(Rectangle())
                .onTapGesture { isComposerFocused = false }
            validationArea
            blockedArea
            composer
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        .navigationTitle("Coaching")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.loadHistory() }
        .onAppear(perform: consumeInitialFocusIntent)
        // The close-from-outside channel: the root asks when it opens the
        // drawer, so Settings — the drawer's bottom row — is not left behind a
        // keyboard. The request's value is meaningless; that it *changed* is the
        // whole signal, which is why it is a fresh UUID each time.
        .onChange(of: focus?.closeRequest) { _, _ in
            isComposerFocused = false
        }
        // A keyboard raised over a composer that has just stopped accepting
        // turns — the paywall blocked mid-thread, or a send started streaming —
        // is an invitation to type into a dead field.
        .onChange(of: isComposerDisabled) { _, disabled in
            if disabled { isComposerFocused = false }
        }
    }

    /// The one raise in the whole view, and the only place the caller's intent
    /// is read.
    ///
    /// A blank page that was *asked for* puts the cursor in the composer, so the
    /// student can type without a tap. `isComposerDisabled` vetoes it, because
    /// raising the keyboard over a composer that cannot accept a turn (blocked
    /// by the paywall) invites typing into a dead field.
    ///
    /// It runs from `.onAppear`, but it is no longer *derived from* appearance,
    /// which is the whole point of RFC 127: the intent is CONSUMED on the first
    /// appearance, so a pop back from Settings or from a pushed conversation
    /// re-appears with the keyboard down. The consume is here rather than at the
    /// caller because this is the view that owns the focus — the caller cannot
    /// know when its intent was spent, and a flag it clears "afterwards" is a
    /// second thing to keep in step with this one.
    private func consumeInitialFocusIntent() {
        guard !hasConsumedInitialFocus else { return }
        hasConsumedInitialFocus = true
        guard focusesComposerOnAppear, !isComposerDisabled else { return }
        isComposerFocused = true
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
            ChatHistoryLoading()
        case .failed(let error):
            ChatHistoryFailed(error: error) {
                Task { await viewModel.loadHistory() }
            }
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

    // MARK: - Thread

    /// The shared thread (`ChatThread`, RFC 258) with this screen's own failure
    /// view: the paywall's "See options" is the student's, not the thread's.
    private var thread: some View {
        ChatThread(turns: viewModel.turns, isStreaming: viewModel.isStreaming) { failure, turnId in
            failureView(failure, turnId: turnId)
        }
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
                TurnInfrastructureNotice(infra: infra)
            }

            // A `.blocked` turn offers Retry again the moment the meter reports
            // the budget open — that is what keeping the student's words was
            // for. While the meter has no answer, the 402 stays the authority.
            // See `TurnAction`.
            switch TurnAction(failure: failure, budget: subscriptionViewModel.budget) {
            case .seeOptions:
                seeOptionsButton(identifier: "turnSeeOptionsButton")
            case .retry:
                ChatRetryButton {
                    Task { await viewModel.retry(turnId) }
                }
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

    /// The shared composer box (`ChatComposer`, RFC 258) with the student's
    /// coaching-budget control on the left of its control row.
    ///
    /// The field's disabling follows `isComposerDisabled` (blocked or
    /// streaming), which the budget control deliberately does not: a student
    /// who has just been blocked needs exactly that door, and the sheet is a
    /// read-only explanation the rest of the time (RFC 123).
    private var composer: some View {
        ChatComposer(
            text: $viewModel.messageText,
            isFocused: $isComposerFocused,
            isFieldDisabled: isComposerDisabled,
            isSending: viewModel.isStreaming,
            canSend: viewModel.canSend && !isBlocked,
            send: send
        ) {
            CoachingBudgetButton(
                viewModel: subscriptionViewModel,
                action: paywallGate.presentExplanation
            )
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
            focusesComposerOnAppear: false,
            focus: nil,
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
