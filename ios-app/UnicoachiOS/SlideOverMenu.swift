import SwiftUI

/// The slide-over menu: everything secondary to the conversation itself
/// (DESIGN.md §7). A custom overlay rather than `NavigationSplitView`, whose
/// stock iPhone presentation does not survive the full-bleed gradient chrome.
///
/// The drawer is a **fast switcher**, not a management surface: it lists the
/// student's conversations in the server's MRU order and opens one on tap.
/// Delete, archive, and their confirmations stay in `ConversationListView`, one
/// tap further on, rather than being duplicated into a cramped space.
///
/// It **never scrolls**: at most `DSMenu.recentLimit` conversations, with
/// **All conversations** pinned in the footer as the complete surface one tap
/// on. A scrolling menu has no determinate height and hides its own footer
/// behind a gesture.
///
/// The `ConversationListViewModel` is **owned by the root**, not by this view:
/// the drawer stays alive across open/close so it can slide in already
/// populated. A view model created here would be rebuilt on every open and the
/// rows would pop in mid-animation, whenever the network happened to return.
struct SlideOverMenu: View {
    @ObservedObject private var viewModel: ConversationListViewModel

    private let onNewConversation: () -> Void
    private let onSelect: (Conversation) -> Void
    private let onAllConversations: () -> Void
    private let onSettings: () -> Void

    init(
        viewModel: ConversationListViewModel,
        onNewConversation: @escaping () -> Void,
        onSelect: @escaping (Conversation) -> Void,
        onAllConversations: @escaping () -> Void,
        onSettings: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.onNewConversation = onNewConversation
        self.onSelect = onSelect
        self.onAllConversations = onAllConversations
        self.onSettings = onSettings
    }

    /// The drawer must not scroll (DESIGN.md §7), which makes clipping the risk
    /// instead: at accessibility text sizes three recents plus the footer do not
    /// fit, and a clipped **Settings** entry would be worse than either. So the
    /// number of recents is chosen by the layout rather than by a hardcoded
    /// breakpoint — `ViewThatFits` takes the first candidate whose ideal height
    /// fits, degrading 3 -> 2 -> 1 -> none. The footer always survives, because
    /// past the recents it is the only route to a conversation.
    var body: some View {
        ViewThatFits(in: .vertical) {
            content(recentLimit: DSMenu.recentLimit)
            content(recentLimit: DSMenu.recentLimit - 1)
            content(recentLimit: DSMenu.recentLimit - 2)
            content(recentLimit: 0)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        // The fill and the hairline run to the physical bottom edge; the
        // CONTENT does not, or the last entry sits under the home indicator and
        // loses its bottom border.
        .background(Color.dsBackground.ignoresSafeArea(edges: .bottom))
        // No shadow: the drawer is separated from the scrimmed screen by the
        // same hairline everything else in this design uses (DESIGN.md §3).
        .overlay(alignment: .trailing) {
            Rectangle()
                .fill(Color.dsFieldBorder)
                .frame(width: DSControl.borderWidth)
                .ignoresSafeArea(edges: .bottom)
        }
        .accessibilityIdentifier("slideOverMenu")
    }

    private func content(recentLimit: Int) -> some View {
        VStack(alignment: .leading, spacing: DSControl.stackGap) {
            newConversationButton

            if recentLimit > 0 {
                Text("Recent")
                    .dsOverlineStyle()
                    .foregroundStyle(Color.dsTextSecondary)

                recents(limit: recentLimit)
            }

            // The recents hug the top and the footer stays pinned to the
            // bottom. Without the ScrollView that used to hold the space, the
            // footer would float up under the last row.
            Spacer(minLength: DSSpacing.md)

            allConversationsButton

            Divider()
                .overlay(Color.dsFieldBorder)

            settingsButton
        }
        .padding(DSSpacing.md)
    }

    // MARK: - Entries

    private var newConversationButton: some View {
        Button(action: onNewConversation) {
            Label("New conversation", systemImage: "square.and.pencil")
        }
        .buttonStyle(PrimaryButtonStyle())
        .accessibilityIdentifier("newConversationButton")
        .accessibilityLabel("New conversation")
    }

    @ViewBuilder
    private func recents(limit: Int) -> some View {
        switch viewModel.state {
        case .loading:
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Color.dsTextPrimary)
                    .frame(maxWidth: .infinity)
                .accessibilityIdentifier("menuConversationsLoading")
        case .loaded(let conversations):
            // Capped in the view, not in the view model: the same view model
            // backs `ConversationListView`, which must show everything.
            VStack(spacing: DSControl.stackGap) {
                ForEach(Array(conversations.prefix(limit))) { conversation in
                    conversationRow(conversation)
                }
            }
        case .empty:
            placeholder("No conversations yet.")
                .accessibilityIdentifier("menuConversationsEmpty")
        case .failed(let error):
            placeholder(error.message)
                .foregroundStyle(Color.dsError)
                .accessibilityIdentifier("menuConversationsFailed")
        }
    }

    private func placeholder(_ message: String) -> some View {
        Text(message)
            .font(.dsCaption)
            .foregroundStyle(Color.dsTextSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A switcher row. Tapping one **pushes** that conversation, exactly as the
    /// full list does, so chrome is a function of depth rather than of which
    /// door the student came through (DESIGN.md §7). Nothing is marked
    /// "current": the root is always a fresh conversation.
    private func conversationRow(_ conversation: Conversation) -> some View {
        Button {
            onSelect(conversation)
        } label: {
            Text(conversation.name)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .padding(DSSpacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.dsSurface)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                        .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
                )
                .contentShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("menuConversationRow")
    }

    /// The full management surface, one tap further on.
    private var allConversationsButton: some View {
        menuRow("All conversations", systemImage: "list.bullet", identifier: "allConversationsButton", action: onAllConversations)
    }

    private var settingsButton: some View {
        menuRow("Settings", systemImage: "gearshape", identifier: "settingsButton", action: onSettings)
    }

    /// A secondary entry: the shared control box, outlined rather than filled —
    /// there is exactly one filled control in the drawer, and it is
    /// "New conversation".
    private func menuRow(
        _ title: String,
        systemImage: String,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.dsButton)
                .foregroundStyle(Color.dsTextPrimary)
                .padding(.horizontal, DSControl.textInset)
                .frame(maxWidth: .infinity, minHeight: DSControl.height, alignment: .leading)
                .background(Color.dsSurface)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                        .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
                )
                .contentShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
        .accessibilityLabel(title)
    }
}
