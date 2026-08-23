import SwiftUI
// UIKit for `UIPasteboard`, the only system pasteboard iOS has. It is imported
// here and nowhere else: this file is the app's single point of contact with
// the clipboard, so a second copy surface cannot quietly reach for the
// singleton on its own terms (RFC 125).
import UIKit

// MARK: - What a bubble offers

/// The copy menu a message bubble carries. Two cases because a conversation has
/// exactly two kinds of message, and they differ in precisely one way: whether
/// the text on screen was rendered from something else.
///
/// A case per kind of message rather than a caller-assembled `[CopyAction]`,
/// because the array form admits states this app has no meaning for — an empty
/// menu, or two entries sharing the `id` a `ForEach` keys on, which would
/// silently collapse the coach's two items into one row. Here the menus are the
/// only two menus that exist, and both are correct by construction.
enum CopyMenu {
    /// The student's own words, drawn as plain `Text`. What they typed is what
    /// they saw, so there is nothing to convert and nothing to choose between.
    case utterance(text: String)

    /// A coach reply: Markdown source that was rendered to get what is on
    /// screen, so both readings are real and the student picks.
    case document(source: String)

    var actions: [CopyAction] {
        switch self {
        case .utterance(let text):
            return [.verbatim(text: text)]
        case .document(let source):
            return [.rendered(source: source), .markdown(source: source)]
        }
    }
}

// MARK: - One entry in the menu

/// One button in a bubble's copy menu: the words on it and the characters it
/// puts on the pasteboard.
///
/// At file scope and internal rather than nested in the view and private:
/// *which string rides which label* is the one thing about this feature that
/// can be silently wrong, and no XCTest can open a `contextMenu` or read
/// `UIPasteboard` to catch it. Hoisting the value out of the view is what gives
/// that mapping a test at all — the button is untestable, the value it carries
/// need not be.
///
/// The memberwise initialiser is deliberately **private**. Left synthesised, it
/// would spell `CopyAction(title: "Copy as Markdown", identifier: "copyButton",
/// text: rendered)` — the exact label/payload mismatch this type was hoisted out
/// to pin. With `title` and `identifier` derived from `Reading` instead, that
/// sentence has no spelling: there is one place each label is authored, and the
/// only way to build an action is to say which of the two things it is.
struct CopyAction: Identifiable {
    /// One of the two readings of a message: what distinguishes one menu entry
    /// from another, and the identity a menu's entries must not share.
    enum Reading {
        /// What the eye saw. The bare `Copy`, on both kinds of bubble.
        case plain
        /// The source behind it. Only a coach reply has one.
        case markdown

        var title: String {
            switch self {
            case .plain: return "Copy"
            case .markdown: return "Copy as Markdown"
            }
        }

        var identifier: String {
            switch self {
            case .plain: return "copyButton"
            case .markdown: return "copyMarkdownButton"
            }
        }
    }

    let reading: Reading

    /// Deferred, not a stored `String`. `CopyAction`s are built in a view body,
    /// so an eager `MarkdownPlainText.render(source)` would parse and render
    /// every visible reply on every SSE delta of a stream — the whole document,
    /// per token, for a menu almost nobody opens. The work belongs at the tap.
    private let makeText: () -> String

    private init(reading: Reading, makeText: @escaping () -> String) {
        self.reading = reading
        self.makeText = makeText
    }

    /// Identity is the *reading*, and that is load-bearing rather than
    /// incidental: it is what makes "two actions of the same reading in one
    /// menu" the case that visibly collapses, since the `ForEach` behind both
    /// the context menu and the rotor keys on it. `CopyMenu` is what guarantees
    /// it never happens.
    var id: Reading { reading }

    var title: String { reading.title }
    var identifier: String { reading.identifier }

    /// The characters this action would copy, or `nil` when there is nothing
    /// worth putting on a clipboard.
    ///
    /// Optional rather than `""`, because "the student's reply rendered to
    /// nothing" is a real state this feature can reach — a bubble is shown as
    /// soon as `coachStreamingText` is non-empty, and a reply that is a lone
    /// `---`, or whose first delta is a newline, draws while rendering blank.
    /// Swift has a spelling for absent, and an empty `String` standing in for
    /// it is the kind of marker a caller forgets to check.
    ///
    /// Whitespace-only counts as nothing. `rendered` trims itself, but
    /// `markdown` and `verbatim` hand back their source untouched, so a guard
    /// that only tested `isEmpty` would protect one of the three.
    ///
    /// Computing this is the point of `makeText`, so reading it is not free — it
    /// exists for the tap and for the suite, and nothing should call it while
    /// laying out. That is also why the menu does not hide an action whose text
    /// is nil: knowing would cost a full render of every visible reply on every
    /// frame, which is exactly the eager work `makeText` exists to avoid.
    var text: String? {
        let text = makeText()
        return text.contains(where: { !$0.isWhitespace }) ? text : nil
    }

    /// The rendered characters — "as-is" read as "as-seen" (RFC 125). A student
    /// pasting a reply into a message to a parent wants the words, not
    /// `**Common App**`.
    static func rendered(source: String) -> CopyAction {
        CopyAction(reading: .plain) { MarkdownPlainText.render(source) }
    }

    /// A string that is already exactly what the eye saw — the student's own
    /// utterance. It wears the same label as `rendered` because from the
    /// student's side the two are one affordance; only the coach's bubble has a
    /// second, lossless one to distinguish it from.
    static func verbatim(text: String) -> CopyAction {
        CopyAction(reading: .plain) { text }
    }

    /// The source string the bubble was handed, verbatim: the lossless option,
    /// one menu item away from the lossy one, which is what makes the plain
    /// rendering's judgement calls affordable.
    static func markdown(source: String) -> CopyAction {
        CopyAction(reading: .markdown) { source }
    }

    /// Put this action's characters on the pasteboard, and say what landed.
    ///
    /// **Nothing to copy is a no-op, not an empty write.** Assigning `""` does
    /// not fail; it silently destroys whatever the student had on their
    /// clipboard, which is a far worse outcome than a `Copy` that appears to do
    /// nothing.
    ///
    /// The return value is what makes those two outcomes distinguishable
    /// instead of both being a silent `Void`: `nil` is "declined, there was
    /// nothing to put there". `@discardableResult` because the button genuinely
    /// does not care — but the suite does, and so would any future surface that
    /// wants to confirm a copy to the student.
    @discardableResult
    func copy() -> String? {
        guard let text else { return nil }
        UIPasteboard.general.string = text
        return text
    }
}

// MARK: - Mounting it on a view

extension View {
    /// Offer `menu`'s actions on this view, by long press and to VoiceOver.
    ///
    /// A modifier rather than something a bubble does for itself, so the chrome
    /// stays chrome: `ConversationView.messageBubble` draws a border and a
    /// radius and knows nothing about copying. It also means the next surface
    /// that wants this — `ConversationListView` has a context menu today with
    /// no rotor actions at all — gets the paired version by asking for it.
    func copyMenu(_ menu: CopyMenu) -> some View {
        modifier(CopyMenuModifier(actions: menu.actions))
    }
}

/// Both affordances, generated from one array so they cannot disagree about
/// what the view offers.
///
/// The context menu is what a sighted student long-presses. VoiceOver never
/// receives a long press, so the same actions are also published to the rotor —
/// a feature that exists only behind a gesture one class of user cannot make is
/// a feature that class does not have. `.accessibilityActions` is the SwiftUI
/// spelling that accepts a `ForEach`; `.accessibilityCustomAction` is UIKit's
/// and has no SwiftUI equivalent by that name.
///
/// Neither modifier participates in layout — `contextMenu` renders a platform
/// popover from a snapshot and proposes the decorated view nothing — which is
/// why this whole feature adds no view structure inside `MarkdownView`, whose
/// two shipped layout bugs both came from exactly that (RFC 118, RFC 120).
private struct CopyMenuModifier: ViewModifier {
    let actions: [CopyAction]

    func body(content: Content) -> some View {
        content
            .contextMenu { buttons }
            .accessibilityActions { buttons }
    }

    /// One authoring of the buttons, hosted twice. Written out once because the
    /// shared thing here is not merely the array — it is the label, the
    /// identifier and the action attached to each row, and two copies of that
    /// scaffolding are two places for the rotor and the menu to drift apart.
    ///
    /// The symbol and the `<reading>Button` identifiers follow
    /// `ConversationListView`'s context menu rather than inventing a second
    /// convention for the same gesture.
    @ViewBuilder private var buttons: some View {
        ForEach(actions) { action in
            Button {
                action.copy()
            } label: {
                Label(action.title, systemImage: "doc.on.doc")
            }
            .accessibilityIdentifier(action.identifier)
        }
    }
}
