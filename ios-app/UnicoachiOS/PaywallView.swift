import SwiftUI

/// Why coaching is paused: the three genuinely different situations the block
/// copy distinguishes (RFC 121), named once so no caller re-reads a `Date?`'s
/// nullability as a basis.
///
/// It is **composed** from the meter's verdict and the reading's reset date
/// rather than decoded from two collapsed optionals: `unknown` is the meter's
/// own answer for "no reading yet", not a `nil` that also has to mean the free
/// tier.
enum CoachingBasis: Equatable {
    /// No reading yet: a 402 can land before the initial usage load does.
    case unknown
    /// The lifetime free allowance, which never resets.
    case freeAllowance
    /// A subscription period, which does.
    case period(resetsAt: Date)

    /// Built only from a meter that is **not** open, and the switch names every
    /// verdict rather than wildcarding over two of them: a paywall basis for an
    /// unpaused budget is a contradiction, and mapping one onto `.period` is
    /// exactly how a student who has just subscribed reads "You've used this
    /// period's coaching" for the frame between the purchase landing and the
    /// sheet dismissing. `nil` is that state — not a failure, an absence of
    /// anything to explain.
    init?(budget: CoachingBudget, resetsAt: Date?) {
        switch (budget, resetsAt) {
        case (.open, _):
            return nil
        case (.unknown, _):
            self = .unknown
        case (.spent, .some(let resetsAt)):
            self = .period(resetsAt: resetsAt)
        case (.spent, nil):
            self = .freeAllowance
        }
    }
}

/// The words the paywall says, as a pure value derived from the basis — so the
/// copy rules are decided (and tested) without rendering a view.
///
/// The two exhausted states are genuinely different situations and never share
/// a sentence (RFC 121):
///
/// | basis          | detail                                                  |
/// | -------------- | ------------------------------------------------------- |
/// | free allowance | "You've used your free coaching."                       |
/// | subscription   | "You've used this period's coaching. It resets ⟨date⟩." |
///
/// A **missing** reading is a third case, not a default to the first: a 402 can
/// arrive before the initial usage load lands (a cold launch), and telling a
/// paying subscriber they have used their *free* coaching would be a guess
/// dressed as a fact. The neutral sentence is true either way, and the specific
/// one replaces it the moment the reading arrives. The switch is exhaustive
/// over `CoachingBasis` with no `default:`, so that distinction cannot be
/// deleted without the compiler saying so.
///
/// This is also the only place **the block's own words** are authored: the
/// subscription surface's 402 arm asks this type rather than re-typing the
/// sentence. It is not the only place the spent budget is described — the
/// subscription sheet's `SubscriptionExplanation` says something different
/// about the same situation, deliberately, because that screen is a read-only
/// explanation and this one is a refusal (RFC 123). What must never be
/// duplicated is *this* sentence, and it is not.
struct PaywallCopy: Equatable {
    let title: String
    let detail: String

    /// What a **refused turn** says under the student's own words. While the
    /// budget is spent (or the meter has no answer, so the 402 stands) that is
    /// the block's own sentence; once the meter reports the budget `open` the
    /// refusal is history, and repeating "You've used this period's coaching"
    /// beside the Retry button that just came back would be stale copy
    /// contradicting the control next to it.
    /// One argument, not two that must agree: the basis *is* the meter's answer
    /// once it has been through `CoachingBasis.init?` — `nil` is an open budget
    /// and nothing else — so a contradictory pair (`budget: .open` beside
    /// `basis: .period`) is not expressible here.
    static func refusedTurnDetail(basis: CoachingBasis?) -> String {
        guard let basis else {
            return String(localized: "This message wasn't sent. Send it again when you're ready.")
        }
        return PaywallCopy(basis: basis).detail
    }

    /// The heading, which every basis shares — so the surfaces that want only
    /// the title (the blocked composer) can say it without constructing a
    /// basis they would then have to invent for an open meter.
    static let pausedTitle = String(localized: "Coaching is paused")

    /// Copy for a surface that **must speak**, where an absent basis means an
    /// **open** budget and nothing else (`CoachingBasis.init?`). A paywall on
    /// screen with no heading and no sentence — or a 402 reported with no
    /// sentence at all — is not a rendering of an open budget, it is a
    /// rendering bug, so the open case gets words of its own here.
    ///
    /// Words of its *own*, not `.unknown`'s: "no reading yet" and "the meter
    /// answered, and the answer is that nothing is spent" are two different
    /// situations, and lending the first one's sentence to the second tells a
    /// student with an unspent allowance that they have used it. The optional
    /// stays meaningful for the surfaces that legitimately say nothing
    /// (`refusedTurnDetail(basis:)`); these are not among them.
    init(basisOrOpen: CoachingBasis?) {
        guard let basisOrOpen else {
            self.init(title: Self.pausedTitle, detail: String(localized: "Your coaching allowance is available."))
            return
        }
        self.init(basis: basisOrOpen)
    }

    private init(title: String, detail: String) {
        self.title = title
        self.detail = detail
    }

    init(basis: CoachingBasis) {
        title = Self.pausedTitle
        switch basis {
        case .period(let resetsAt):
            let date = resetsAt.dsCalendarDate
            detail = String(localized: "You've used this period's coaching. It resets \(date).")
        case .freeAllowance:
            detail = String(localized: "You've used your free coaching.")
        case .unknown:
            detail = String(localized: "You've used your coaching allowance.")
        }
    }
}

/// The block screen: what happened, the meter that says how much was used, and
/// the offer.
///
/// It is a **presentation of the rail, not a second one** — every value and
/// every action on it belongs to the one `SubscriptionViewModel`
/// `AuthenticatedRootView` owns, which is also what the Settings section
/// renders and what the transaction listener feeds. Nothing here derives
/// entitlement, decides whether to offer a purchase, or keeps a blocked flag of
/// its own: `usage` is the server's answer, `offersSubscribe` is the view
/// model's, and both are read rather than re-computed.
///
/// Whether a Subscribe button appears at all is `offersSubscribe`, read here
/// and never paraphrased — this comment used to enumerate the states, and that
/// enumeration is exactly what went stale when the rule changed. Restore stays
/// in every state, because a student whose purchase never bound to this account
/// is precisely who needs it.
struct PaywallView: View {
    @ObservedObject var viewModel: SubscriptionViewModel
    @Environment(\.dismiss) private var dismiss

    /// Always words. An open meter names no basis, and the sheet is never
    /// *opened* over one — `PaywallGate.present()`, the only opener, declines
    /// a budget with nothing to explain. What remains is the budget going open
    /// while the sheet is already up (a purchase, or a meter refresh
    /// mid-sheet); `onChange` below dismisses it, but the frames before that
    /// dismissal are still on screen, and a modal with a meter and a Subscribe
    /// button under a blank space reads as a bug rather than as a dismissal.
    /// So those frames say the allowance is available — never nothing, and
    /// never the spent-period sentence over an unspent meter.
    private var copy: PaywallCopy {
        PaywallCopy(basisOrOpen: viewModel.coachingBasis)
    }

    var body: some View {
        DSSheetScroll {
            // Not `if let`: the gate only opens this sheet when there is
            // something to explain, so the block always has its words.
            VStack(alignment: .leading, spacing: DSSpacing.sm) {
                Text(copy.title)
                    .font(.dsTitle)
                    .foregroundStyle(Color.dsTextPrimary)
                    .accessibilityIdentifier("paywallTitle")

                Text(copy.detail)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextSecondary)
                    .accessibilityIdentifier("paywallDetail")
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            CoachingUsageMeter(viewModel: viewModel)

            SubscriptionOffer(viewModel: viewModel)

            // RFC 121's open item, closed by composition: a subscriber who
            // has spent the period was shown a date and offered nothing.
            // The link renders only when a subscription is bound — its own
            // rule, so this surface and the subscription sheet cannot
            // disagree about when it appears.
            ManageSubscriptionLink(viewModel: viewModel)

            // Reading stays open while blocked (the server keeps it open),
            // so the sheet must have a way back to it that does not depend
            // on knowing the drag gesture. `.secondary`: it is the way out,
            // not the action this screen is asking for.
            DSTextButton(
                String(localized: "Not now"),
                role: .secondary,
                accessibilityIdentifier: "paywallDismissButton",
                accessibilityLabel: "Not now",
                action: { dismiss() }
            )
        }
        // The offer's own load. The authenticated root takes a **usage-only**
        // read at launch (it has nowhere to show a price and no business
        // re-posting an entitlement), so the sheet fetches the product it needs
        // for Subscribe when it is the thing on screen.
        .task { await viewModel.load() }
        // The gate's exit, beside the other one. This sheet exists only while
        // the budget is spent, so a meter that reports it **open** again
        // dismisses it: a student who has just subscribed must not be left
        // reading "Coaching is paused". Only `open` — `unknown` is a failed
        // refresh, which is no reason to close the screen that explains the
        // block.
        .onChange(of: viewModel.budget) { _, budget in
            if budget == .open { dismiss() }
        }
    }

}

// MARK: - Previews

/// The canvas needs the meter *loaded*, which on the real screen is
/// `AuthenticatedRootView`'s job — so the container does it here rather than
/// each preview constructing a view model that has never fetched anything.
private struct PaywallPreviewContainer: View {
    @StateObject private var viewModel: SubscriptionViewModel

    init(usage: CoachingUsage) {
        _viewModel = StateObject(wrappedValue: SubscriptionViewModel(
            usageClient: PreviewCoachingUsageClient(usage: usage),
            store: PreviewSubscriptionStore(),
            recorder: PreviewTransactionRecorder()
        ))
    }

    var body: some View {
        PaywallView(viewModel: viewModel)
            .task { await viewModel.load() }
    }
}

private let freeTierExhausted = CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)

#Preview("paywall free tier - Light") {
    PaywallPreviewContainer(usage: freeTierExhausted)
        .preferredColorScheme(.light)
}

#Preview("paywall free tier - Dark") {
    PaywallPreviewContainer(usage: freeTierExhausted)
        .preferredColorScheme(.dark)
}

/// The subscriber who has spent the period: a reset date, and — because the
/// canvas's store sells the one plan and nothing is bound — the offer block
/// still shows Restore.
#Preview("paywall subscriber - Light") {
    PaywallPreviewContainer(usage: CoachingUsage(
        usedPercent: 100,
        exhausted: true,
        resetsAt: Calendar.current.date(byAdding: .day, value: 12, to: Date()) ?? Date()
    ))
    .preferredColorScheme(.light)
}
