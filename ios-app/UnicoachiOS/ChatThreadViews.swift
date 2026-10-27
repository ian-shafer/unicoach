import SwiftUI

// The chat thread's building blocks, shared by the student's coach
// (`ConversationView`) and the parent's (`SupporterThreadView`, RFC 258). They
// moved out of `ConversationView` verbatim, so the student's screen renders
// exactly as it did; what each screen adds — the paywall, the parent's
// coaching block — stays on that screen. The history-loading and history-failed
// views, the infrastructure notice and the Retry button are shared too, so the
// two screens cannot disagree on them. Nothing here reads the student's
// billing rail.

/// Bubbles are **outlined, never filled** (DESIGN.md §8 extrapolation): a
/// saturated user bubble is exactly the large brand-coloured surface §6
/// rules out, and this design carries depth by border alone. The user's turn
/// is distinguished by border weight — a darkened `TextPrimary` hairline
/// against the coach's `FieldBorder` one — not by colour.
///
/// **The coach's bubble takes the full content width unconditionally**; the
/// user's keeps its leading spacer and stays inset (Ian's call, RFC 118).
/// Unconditional rather than "wide when the content is wide" because a
/// content-conditional width resizes the bubble *mid-stream*: the reply would
/// open as a paragraph at inset width and jump wider the moment a table's
/// delimiter row arrived three deltas later.
///
/// **Generic in its content.** Chrome is the primitive here; what goes inside
/// a turn is the caller's business, so `(isUser: true, rendersMarkdown: true)`
/// has no spelling at all.
struct ChatMessageBubble<Content: View>: View {
    let isUser: Bool
    let identifier: String
    let menu: CopyMenu
    @ViewBuilder let content: () -> Content

    var body: some View {
        // `spacing: 0` because the inset is the user's `Spacer` and nothing
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
}

/// The in-flight turn's "Coach is typing…".
struct ChatStreamingIndicator: View {
    var body: some View {
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
}

/// A re-entered conversation's history while it loads. Both screens show this
/// one view, so the caption and its VoiceOver label cannot drift apart.
struct ChatHistoryLoading: View {
    var body: some View {
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
}

/// A history read that failed: its words, and a Retry that reads it again.
struct ChatHistoryFailed: View {
    let error: ErrorResponse
    let retry: () -> Void

    var body: some View {
        VStack(spacing: DSSpacing.md) {
            FormErrorBanner(error.message)
            Button("Retry", action: retry)
                .font(.dsButton)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityIdentifier("historyRetryButton")
                .accessibilityLabel("Retry")
        }
        .padding(DSSpacing.md)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}

/// A turn that failed in transport (timeout, connectivity, server): the
/// failure's symbol, title and description, read by VoiceOver as one element.
struct TurnInfrastructureNotice: View {
    let infra: InfrastructureError

    var body: some View {
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
}

/// A failed turn's Retry, which sends the same words again.
struct ChatRetryButton: View {
    let retry: () -> Void

    var body: some View {
        Button("Retry", action: retry)
            .font(.dsButton)
            .foregroundStyle(Color.dsTextPrimary)
            .accessibilityIdentifier("retryButton")
            .accessibilityLabel("Retry")
    }
}

/// The scrolling thread of turns. What a FAILED turn shows is the caller's
/// ([failure]): the student's screen offers the paywall, the parent's shows
/// their own coaching block.
struct ChatThread<Failure: View>: View {
    let turns: [ChatTurn]
    let isStreaming: Bool
    @ViewBuilder let failure: (TurnFailure, ChatTurn.ID) -> Failure

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: DSSpacing.md) {
                    ForEach(turns) { turn in
                        turnView(turn)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(DSSpacing.md)
            }
            // The platform gesture: drag the thread and the keyboard follows
            // your finger down. Interactive rather than `.immediately` because
            // a thread you are only skimming should not lose the keyboard the
            // instant you touch it.
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: scrollAnchor) { _, _ in
                guard let lastId = turns.last?.id else { return }
                withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// A value that changes whenever new content lands at the bottom of the
    /// thread, driving auto-scroll: turn count, the active turn's streaming
    /// length, and its terminal/failure state.
    private var scrollAnchor: String {
        guard let last = turns.last else { return "" }
        return "\(turns.count)-\(last.coachStreamingText.count)-\(last.coachMessage != nil)-\(last.failure != nil)"
    }

    @ViewBuilder
    private func turnView(_ turn: ChatTurn) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            // The user's turn is an **utterance**: plain `Text`, inset.
            // Rendering it as Markdown would silently eat the `*` in "should I
            // apply *early*?" and reflow the line breaks they typed, which is a
            // bug rather than a feature (RFC 118).
            // That is also why it is an `.utterance` to copy: nothing to render,
            // so nothing to choose between.
            ChatMessageBubble(
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
                // reply is the reader's own choice.
                let source = turn.coachMessage?.content ?? turn.coachStreamingText
                ChatMessageBubble(
                    isUser: false,
                    identifier: "coachBubble",
                    menu: .document(source: source)
                ) {
                    MarkdownView(source: source)
                }
            }

            if isActiveStreamingTurn(turn) {
                ChatStreamingIndicator()
            }

            if let turnFailure = turn.failure {
                failure(turnFailure, turn.id)
            }
        }
        .id(turn.id)
    }

    /// The streaming indicator shows on the in-flight turn (the last one) while a
    /// stream is active and no coach reply has completed yet.
    private func isActiveStreamingTurn(_ turn: ChatTurn) -> Bool {
        isStreaming && turn.coachMessage == nil && turn.id == turns.last?.id
    }
}

/// One outlined box containing a text field **above a control row** (RFC
/// 123). It keeps its `DSRadius.control` corners, its 1pt `dsFieldBorder`
/// hairline and its 20pt leading inset, so it is still a `LabeledField` in
/// everything but name.
///
/// The control row's leading side is the caller's ([leading]) — the student's
/// coaching-budget control, or nothing — and the send button is always on the
/// trailing side.
struct ChatComposer<Leading: View>: View {
    @Binding var text: String
    let isFocused: FocusState<Bool>.Binding
    /// Disables the text field — streaming, blocked, or no longer connected.
    let isFieldDisabled: Bool
    /// Spins the send button.
    let isSending: Bool
    /// Enables the send button.
    let canSend: Bool
    let send: () -> Void
    @ViewBuilder let leading: () -> Leading

    var body: some View {
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

    /// The turn itself. `axis: .vertical` is what makes it grow with the message.
    private var messageField: some View {
        TextField("Message", text: $text, axis: .vertical)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextPrimary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .focused(isFocused)
            .disabled(isFieldDisabled)
            .accessibilityIdentifier("messageField")
            .accessibilityLabel("Message")
    }

    /// The caller's accessory on the left, the send button on the right.
    ///
    /// `Spacer(minLength:)` and the send button's `layoutPriority` are what make
    /// the accessory yield first under large Dynamic Type — the send control
    /// is the one thing on this row that must never be squeezed.
    private var controlRow: some View {
        HStack(spacing: DSSpacing.sm) {
            leading()

            Spacer(minLength: DSSpacing.sm)

            CircularIconButton(
                systemImage: "arrow.up",
                isLoading: isSending,
                accessibilityIdentifier: "sendButton",
                accessibilityLabel: "Send",
                action: send
            )
            .disabled(!canSend)
            .layoutPriority(1)
        }
    }
}
