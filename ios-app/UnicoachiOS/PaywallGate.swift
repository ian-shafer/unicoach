import SwiftUI

/// Which of the two subscription sheets is on screen, if either (RFC 123).
///
/// **One value, not two `Bool`s.** The pair was representable as `(true, true)`
/// and the app wrote it: the composer's budget control is deliberately never
/// disabled, so a 402 on a streaming turn runs `handleBudgetExhausted()` while
/// the subscription sheet is already up. SwiftUI will not present a second
/// sheet over the first, so the paywall never appeared, the flag stayed `true`,
/// and every later "See options" tap assigned `true` to `true` — a silent
/// no-op, leaving the block permanently unreachable for the rest of the
/// session.
///
/// `SubscriptionViewModel.Notice` already makes this argument on the same
/// grounds ("One field rather than two optionals: the two kinds are mutually
/// exclusive, and a single value is how that stays true"). Two sheets that can
/// never be on screen together are the same shape, so they get the same answer:
/// one optional, and `.sheet(item:)` rather than two chained
/// `.sheet(isPresented:)`.
///
/// `Identifiable` because `.sheet(item:)` needs an id, and the case itself is
/// the identity: re-presenting the *same* sheet is a no-op, while assigning the
/// other one swaps it — which is exactly the behaviour `handleBudgetExhausted()`
/// wants. `var id: Self { self }` rather than a `String` raw value, because the
/// id is never serialized, persisted or sent anywhere — a raw value existed
/// only to synthesize it, and spelling the cases twice invites the two to
/// disagree.
enum SubscriptionSheet: Identifiable {
    /// The block: a refused turn raised it and it says so.
    case paywall
    /// The read-only explanation, opened from the composer's budget control at
    /// any time, including while blocked. Named for what it says rather than
    /// for its type: `.paywall` is a subscription sheet too, so `.subscription`
    /// would not have told the two apart.
    case explanation

    var id: Self { self }
}

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
/// The presented sheet is reached through a **single** `Binding` and mutated
/// **here only**, by the methods below — so "present the paywall" is written
/// once rather than assigned at each call site, and the two screens' mutual
/// exclusion is a property of the type rather than a rule call sites are
/// trusted to keep (see `SubscriptionSheet`).
@MainActor
struct PaywallGate {
    /// The one rail: read for the meter, refreshed by the 402, and rendered by
    /// both sheets.
    let subscriptions: SubscriptionViewModel

    private let presentedSheet: Binding<SubscriptionSheet?>

    init(
        subscriptions: SubscriptionViewModel,
        presentedSheet: Binding<SubscriptionSheet?>
    ) {
        self.subscriptions = subscriptions
        self.presentedSheet = presentedSheet
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

    /// The one place the block is opened — a "See options" tap, the 402's
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
    ///
    /// What the single `SubscriptionSheet?` additionally **guarantees** is that
    /// the stuck-flag failure two `Bool`s allowed cannot recur: `.sheet(item:)`
    /// clears the binding on dismiss, so the value can never be left standing
    /// at a sheet that is not on screen, and every later tap presents rather
    /// than assigning `true` to `true`.
    ///
    /// What it does **not** claim is that assigning `.paywall` while the
    /// explanation sheet is up swaps one for the other. SwiftUI is unreliable
    /// at exactly that transition, and nothing here can make it otherwise. The
    /// student is not stranded either way: the sheet already on screen carries
    /// the same offer — `SubscriptionOffer` is shared by both — so the purchase
    /// path is in front of them regardless of which of the two won.
    func present() {
        switch subscriptions.budget {
        case .spent:
            // The meter confirms the block: the sheet explains a real one.
            presentedSheet.wrappedValue = .paywall
        case .unknown:
            // No answer from the meter. A 402 that arrived before (or instead
            // of) a reading stays the authority, and `.unknown` has copy of
            // its own, so the sheet still has something to say.
            presentedSheet.wrappedValue = .paywall
        case .open:
            // Nothing to explain, and no transition left for `onChange` to
            // observe — opening here strands the sheet behind "Not now".
            break
        }
    }

    /// The per-turn meter re-read: every finished coaching turn spent budget, so
    /// the ring beside the send button is re-read from the server rather than
    /// left saying whatever it said at launch.
    ///
    /// It exists on the gate — rather than the composer screens reaching into
    /// `subscriptions` themselves — for the reason the type exists at all: the
    /// screens take one opaque value and never touch the rail directly, so the
    /// meter, the block and the sheets stay one answer that cannot disagree.
    ///
    /// The **ordinary** read, `refreshUsage()`, not `refreshUsageAfterRefusal()`.
    /// Nothing here has been disproved: the turn succeeded (or died mid-reply),
    /// so a failed re-read should leave the last good reading on screen rather
    /// than blanking the ring to `.unknown` — which would additionally read as
    /// a *blocked-ish* budget on a student who has just been coached.
    func refreshBudget() async {
        await subscriptions.refreshUsage()
    }

    /// The composer budget control's tap: the explanation sheet, not the block
    /// (RFC 123). It is offered in every state, including while blocked — a
    /// student who has just been blocked needs exactly this door.
    ///
    /// It carries **no** budget guard, unlike `present()` above. That rule
    /// exists because a paywall over an open budget has nothing to say and no
    /// way out; neither is true here, where explaining a budget that is fine is
    /// the screen's whole job.
    func presentExplanation() {
        presentedSheet.wrappedValue = .explanation
    }
}
