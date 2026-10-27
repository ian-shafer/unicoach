import SwiftUI
import os

/// The parent's coach (RFC 258, product slice family/06), pushed from the
/// parent home's "Talk to your coach about ⟨name⟩".
///
/// It is about ONE student, the one the home has selected: the parent's
/// conversations are listed for that student only, filtered here on the client
/// from the one list read, and a new conversation names that student — the
/// subject travels with each conversation and is never server-side session
/// state (brief 0014 D14).
///
/// **Value before ask.** A parent with no conversation about this student
/// lands straight on an empty thread whose one offer is the starter, so the
/// first session is two taps from the home to the cost answer; nothing is
/// asked of the parent first. A first list read that fails still opens the
/// same thread — starting a conversation never waits on reading old ones — but
/// says above it that earlier conversations could not be loaded, with a Retry,
/// so a failure is never shown as "you have no conversations".
struct SupporterCoachView: View {
    let student: LinkedStudent
    let client: SupporterConversationClientProtocol
    /// The parent's own coaching meter — the home's, so a turn's spend moves
    /// the bar the parent sees there too.
    @ObservedObject var coaching: SupporterCoachingViewModel

    /// A thread opened from the list: a fresh one, or an existing conversation.
    private enum ThreadTarget: Hashable, Identifiable {
        case new(UUID)
        case existing(SupporterConversation)

        var id: Self { self }
    }

    @StateObject private var listing: SupporterCoachListing
    @State private var openThread: ThreadTarget?

    init(student: LinkedStudent, client: SupporterConversationClientProtocol, coaching: SupporterCoachingViewModel) {
        self.student = student
        self.client = client
        self.coaching = coaching
        _listing = StateObject(wrappedValue: SupporterCoachListing(client: client, studentId: student.studentId))
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.dsBackground)
            .navigationTitle("Coach")
            .navigationBarTitleDisplayMode(.inline)
            // Read on arrival, and again each time a pushed thread returns
            // (`openThread` back to nil): a conversation that thread started is
            // then on the list the parent comes back to.
            .task(id: openThread) {
                guard openThread == nil else { return }
                await listing.load()
            }
            .navigationDestination(item: $openThread) { target in
                switch target {
                case .new:
                    thread(conversation: nil)
                case .existing(let conversation):
                    thread(conversation: conversation)
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch listing.state {
        case .loading:
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Color.dsTextPrimary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier("supporterCoachLoading")
        case .loaded(let conversations) where conversations.isEmpty:
            // No conversation about this student yet: the thread itself, with
            // its starter, rather than an empty list with a button to reach it.
            starterThread(listUnavailable: false)
        case .loaded(let conversations):
            list(conversations)
        case .unavailable:
            // The list could not be read: the starter thread anyway (value
            // before ask), under a notice that any earlier conversations
            // could not be shown.
            starterThread(listUnavailable: true)
        }
    }

    /// The empty thread, optionally under the list-unavailable notice. One
    /// structure for both, so a Retry that finds no conversations keeps the
    /// thread the parent may already be typing in.
    private func starterThread(listUnavailable: Bool) -> some View {
        VStack(spacing: 0) {
            if listUnavailable {
                listUnavailableNotice
            }
            thread(conversation: nil)
        }
    }

    private var listUnavailableNotice: some View {
        HStack(alignment: .firstTextBaseline, spacing: DSSpacing.sm) {
            FormErrorBanner(String(localized: "Earlier conversations couldn't be loaded"))
                .accessibilityIdentifier("supporterCoachListUnavailable")
            Button("Retry") {
                Task { await listing.load() }
            }
            .font(.dsButton)
            .foregroundStyle(Color.dsTextPrimary)
            .frame(minHeight: DSControl.tapTarget)
            .accessibilityIdentifier("supporterCoachListRetryButton")
            .accessibilityLabel("Retry loading earlier conversations")
        }
        .padding(.horizontal, DSSpacing.md)
        .padding(.top, DSSpacing.sm)
    }

    private func thread(conversation: SupporterConversation?) -> some View {
        SupporterThreadView(student: student, client: client, coaching: coaching, conversation: conversation)
    }

    private func list(_ conversations: [SupporterConversation]) -> some View {
        DSSheetScroll {
            DSTextButton(
                "New conversation",
                accessibilityIdentifier: "supporterCoachNewConversationButton",
                accessibilityLabel: "New conversation",
                action: { openThread = .new(UUID()) }
            )

            VStack(alignment: .leading, spacing: DSSpacing.sm) {
                ForEach(conversations) { conversation in
                    Button { openThread = .existing(conversation) } label: {
                        Text(conversation.name)
                            .font(.dsBody)
                            .foregroundStyle(Color.dsTextPrimary)
                            .lineLimit(2)
                            .frame(maxWidth: .infinity, minHeight: DSControl.tapTarget, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("supporterCoachConversationRow")
                }
            }
        }
    }
}

/// The parent's conversations about ONE student, as the coach screen lists
/// them: most recent first as the server ordered them, filtered here from the
/// one list read. Re-read on every call, so a conversation started since the
/// last read appears. A failed read is logged; with no list yet it is
/// [State.unavailable] — distinct from "no conversations", though the screen
/// still offers the starter — and with a list already on screen it keeps that
/// list. A cancelled read (the screen went away) records nothing.
@MainActor
final class SupporterCoachListing: ObservableObject {
    /// What the list read has said so far.
    enum State: Equatable {
        case loading
        case loaded([SupporterConversation])
        /// No read has succeeded yet and the last one failed: what the parent
        /// has is unknown, which is not the same as having nothing.
        case unavailable
    }

    @Published private(set) var state: State = .loading

    private let client: SupporterConversationClientProtocol
    private let studentId: UUID
    private let logger = Logger.unicoach(category: "SupporterCoachListing")

    init(client: SupporterConversationClientProtocol, studentId: UUID) {
        self.client = client
        self.studentId = studentId
    }

    func load() async {
        do {
            let all = try await client.listConversations()
            state = .loaded(all.filter { $0.studentId == studentId })
        } catch {
            // A cancelled read is not an answer: the view that asked has gone,
            // and URLSession reports that as a URLError, not CancellationError.
            if error is CancellationError || Task.isCancelled { return }
            logger.error("Parent coach conversation list failed: [\(String(describing: error), privacy: .public)]")
            switch state {
            case .loading, .unavailable:
                state = .unavailable
            case .loaded:
                // Keep the list already on screen rather than blanking it.
                break
            }
        }
    }
}

/// One thread of the parent's coach: the student's `ConversationViewModel`
/// driven through `SupporterThreadClient`, on the shared chat views.
///
/// What differs from the student's screen is what a refusal shows. A spent
/// allowance shows the parent's OWN coaching block — the home's section, not
/// the student paywall, because nothing sells a parent a plan here. A
/// connection that has ended says so by name and stops taking turns; the
/// conversation stays on screen, because it is the parent's own words.
struct SupporterThreadView: View {
    let student: LinkedStudent
    @ObservedObject var coaching: SupporterCoachingViewModel
    @StateObject private var viewModel: ConversationViewModel
    @FocusState private var isComposerFocused: Bool

    init(
        student: LinkedStudent,
        client: SupporterConversationClientProtocol,
        coaching: SupporterCoachingViewModel,
        conversation: SupporterConversation?
    ) {
        let threadClient = SupporterThreadClient(client: client, studentId: student.studentId)
        // Both reports re-read the parent's meter: after a turn, so the bar
        // follows the spend; after a refusal, so the block it shows is current.
        let refreshMeter: () async -> Void = { [coaching] in await coaching.load() }
        let viewModel: ConversationViewModel
        if let conversation {
            viewModel = ConversationViewModel(
                conversation: conversation.asConversation,
                conversationClient: threadClient,
                onProfileRequired: {},
                onBudgetExhausted: refreshMeter,
                onTurnFinished: refreshMeter
            )
        } else {
            viewModel = ConversationViewModel(
                conversationClient: threadClient,
                onProfileRequired: {},
                onBudgetExhausted: refreshMeter,
                onTurnFinished: refreshMeter
            )
        }
        self.init(student: student, coaching: coaching, viewModel: viewModel)
    }

    /// Over a view model the caller built: the snapshot catalogue's door, so a
    /// scene can drive a turn to a refusal before the thread is photographed.
    init(student: LinkedStudent, coaching: SupporterCoachingViewModel, viewModel: ConversationViewModel) {
        self.student = student
        self.coaching = coaching
        _viewModel = StateObject(wrappedValue: viewModel)
    }

    /// The one starter the empty thread offers, which sends itself.
    static func starter(for student: LinkedStudent) -> String {
        String(localized: "What would \(student.name)'s list cost our family?")
    }

    var body: some View {
        VStack(spacing: 0) {
            threadArea
                .contentShape(Rectangle())
                .onTapGesture { isComposerFocused = false }
            disconnectedArea
            ChatComposer(
                text: $viewModel.messageText,
                isFocused: $isComposerFocused,
                isFieldDisabled: viewModel.isStreaming || !viewModel.isReady || viewModel.isDisconnected,
                isSending: viewModel.isStreaming,
                canSend: viewModel.canSend,
                send: send
            ) {
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        .navigationTitle("Coach")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.loadHistory() }
    }

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
                starterArea
            } else {
                ChatThread(turns: viewModel.turns, isStreaming: viewModel.isStreaming) { failure, turnId in
                    failureView(failure, turnId: turnId)
                }
            }
        }
    }

    /// The empty thread's one offer: the question a parent came to ask, as a
    /// tappable bubble that sends itself. Nothing else is asked of them.
    @ViewBuilder
    private var starterArea: some View {
        if viewModel.isDisconnected {
            Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            VStack {
                Spacer()
                Button(action: sendStarter) {
                    Text(Self.starter(for: student))
                        .font(.dsBody)
                        .foregroundStyle(Color.dsTextPrimary)
                        .multilineTextAlignment(.leading)
                        .padding(DSSpacing.md)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.dsSurface)
                        .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                                .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(viewModel.isStreaming)
                .accessibilityIdentifier("supporterCoachStarter")
            }
            .padding(DSSpacing.md)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private func failureView(_ failure: TurnFailure, turnId: ChatTurn.ID) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            switch failure {
            case .blocked:
                // The parent's own allowance is spent: their coaching block,
                // which says which allowance and how much. No Retry — it could
                // only be refused again.
                SupporterCoachingSection(viewModel: coaching)
                    .accessibilityIdentifier("supporterTurnBlocked")
            case .server(let error):
                FormErrorBanner(error.message)
                retryButton(turnId)
            case .infrastructure(let infra):
                TurnInfrastructureNotice(infra: infra)
                retryButton(turnId)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func retryButton(_ turnId: ChatTurn.ID) -> some View {
        ChatRetryButton {
            Task { await viewModel.retry(turnId) }
        }
    }

    /// The connection to the student has ended (`student_not_found`): said by
    /// name, just above the composer it has disabled.
    @ViewBuilder
    private var disconnectedArea: some View {
        if viewModel.isDisconnected {
            Text("You are no longer connected to \(student.name)")
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, DSSpacing.md)
                .padding(.bottom, DSSpacing.sm)
                .accessibilityIdentifier("supporterCoachDisconnected")
        }
    }

    private func send() {
        isComposerFocused = false
        Task { await viewModel.send() }
    }

    private func sendStarter() {
        viewModel.messageText = Self.starter(for: student)
        send()
    }
}

/// Whether, and how, the parent home offers the coach (RFC 258): only when a
/// student is selected, because every conversation is about one student. A
/// rule rather than an `if` in the view, so it is asserted directly.
enum SupporterCoachEntry {
    static func title(for subject: LinkedStudent?) -> String? {
        subject.map { String(localized: "Talk to your coach about \($0.name)") }
    }
}

// MARK: - Previews

@MainActor private func supporterCoachPreview(conversations: [SupporterConversation]) -> some View {
    let student = LinkedStudent(linkId: UUID(), studentId: UUID(), name: "Ava", relationship: .parent, otherSupporterNames: [])
    return NavigationStack {
        SupporterCoachView(
            student: student,
            client: PreviewSupporterConversationClient(conversations: conversations),
            coaching: SupporterCoachingViewModel(client: PreviewSupporterCoachingUsageClient())
        )
    }
}

#Preview("supporterCoach empty - Light") {
    supporterCoachPreview(conversations: [])
        .preferredColorScheme(.light)
}
