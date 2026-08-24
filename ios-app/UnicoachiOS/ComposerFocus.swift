import Foundation

/// The one way to close a composer's keyboard **from outside the view that owns
/// it**. `ConversationView` keeps its focus in a `@FocusState`, which is private
/// to that view by construction; the authenticated root owns the drawer, and
/// opening the drawer has to lower the keyboard or the drawer's own bottom row —
/// Settings — is behind it and unreachable (RFC 127).
///
/// A one-way channel rather than a shared focus binding: the root asks for a
/// close, the view decides what that means for its own field. Nothing here can
/// *raise* a keyboard, which is deliberate — opening is intent expressed inside
/// the view (a tap) or carried in at construction
/// (`focusesComposerOnAppear`), never a command sent down the tree.
///
/// Handed only to the **root** conversation, because the drawer only ever
/// covers the root. A pushed conversation gets `nil`, so one request can never
/// address two composers the way a shared binding down the stack would have.
@MainActor
final class ComposerFocus: ObservableObject {
    /// The most recent close request, or `nil` if none has been made.
    ///
    /// A fresh `UUID` per request rather than a `Bool` or a counter's identity:
    /// the view reacts to this value *changing*, so two consecutive closes —
    /// the drawer opened, dismissed, opened again with the keyboard raised in
    /// between — must publish two different values or the second one is
    /// silently swallowed as a no-change.
    @Published private(set) var closeRequest: UUID?

    /// Ask whichever composer is listening to resign focus. Idempotent from the
    /// caller's point of view: calling it while the keyboard is already down
    /// costs one published value and changes nothing.
    func requestClose() {
        closeRequest = UUID()
    }
}
