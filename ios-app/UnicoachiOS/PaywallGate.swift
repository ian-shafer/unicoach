import SwiftUI

/// The paywall gate's inseparable pieces: the shared subscription rail the block
/// is read from, the 402's landing point, and the sheet's opener (RFC 121).
///
/// **One value rather than three parameters**, because they are correct only
/// together — a screen handed a meter that its own callbacks do not refresh
/// would render an unblocked composer for a blocked student, and nothing in
/// three separate arguments says that cannot happen. `AuthenticatedRootView`
/// owns the rail and the sheet, so it is the only thing that can build one, and
/// a screen merely on the path between the root and a composer (the conversation
/// list) forwards one opaque value instead of keeping three in step.
///
/// The sheet's flag is reached through a `Binding` and mutated **here only**, by
/// the two methods below — so "present the paywall" is written once rather than
/// assigned at each call site.
@MainActor
struct PaywallGate {
    /// The one rail: read for the meter, refreshed by the 402, and rendered by
    /// the sheet.
    let subscriptions: SubscriptionViewModel

    private let isPresented: Binding<Bool>

    init(subscriptions: SubscriptionViewModel, isPresented: Binding<Bool>) {
        self.subscriptions = subscriptions
        self.isPresented = isPresented
    }

    /// The 402's landing point. It **refreshes usage from the server** rather
    /// than setting a blocked flag of its own: the block and the meter then come
    /// from one answer and cannot disagree, and every `ConversationViewModel` in
    /// the stack observes the same object. Awaited before presenting so the
    /// sheet opens on the fresh reading.
    ///
    /// The read is the *invalidating* one: the refusal has disproved whatever
    /// the meter last said, so a failed re-read must not leave a stale open
    /// budget standing behind the sheet this method is about to ask for. A
    /// re-read that reports the budget **open** has disproved the refusal
    /// itself and opens nothing — but that rule is not written here, because
    /// it is not a property of this path. It lives in `present()`, the one
    /// funnel every presentation goes through; this method is a forced re-read
    /// followed by an unconditional request to present.
    func handleBudgetExhausted() async {
        await subscriptions.refreshUsageAfterRefusal()
        present()
    }

    /// The one place the sheet is opened — a "See options" tap, the 402's
    /// landing point, and anything added later — and therefore the one place
    /// the rule lives: a paywall over an **open** budget has no basis to name
    /// and no way out. `PaywallView`'s `onChange` observes transitions seen
    /// *after* the sheet appears, so a budget already open when it opens never
    /// dismisses it; the modal would sit there saying "Coaching is paused"
    /// over an unspent meter, exitable only by "Not now". A caller with
    /// nothing to explain therefore gets no sheet, whether it is a refusal
    /// disproved by its own re-read or a "See options" tap that raced a meter
    /// refresh.
    ///
    /// The verdict is switched exhaustively rather than tested against `.open`
    /// because this is the gate: a fourth `CoachingBudget` case must not
    /// acquire a presentation policy by falling into an unnamed branch.
    func present() {
        switch subscriptions.budget {
        case .spent:
            // The meter confirms the block: the sheet explains a real one.
            isPresented.wrappedValue = true
        case .unknown:
            // No answer from the meter. A 402 that arrived before (or instead
            // of) a reading stays the authority, and `.unknown` has copy of
            // its own, so the sheet still has something to say.
            isPresented.wrappedValue = true
        case .open:
            // Nothing to explain, and no transition left for `onChange` to
            // observe — opening here strands the sheet behind "Not now".
            break
        }
    }
}
