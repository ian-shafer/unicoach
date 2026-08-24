import os
import SwiftUI

/// How the subscription works, in the student's current situation — as a pure
/// value derived from the situation rather than a chain of `if`s inside a view,
/// in the shape `PaywallCopy` already established (RFC 123).
///
/// **An enum over the situations, not a rendered `String`.** The situation is
/// the pair (the bound subscription's `knownStatus`, the coaching budget), and
/// the cases below *are* the matrix this doc comment used to have to draw in a
/// table because the code could not show it: a flattened `let detail: String`
/// hid which branch ran, gave nothing downstream anything to switch on, and
/// pinned every assertion in the suite to literal English — so a copy edit
/// broke seven tests that were never about the wording.
///
/// | case                     | situation                  | what it says                                     |
/// | ------------------------ | -------------------------- | ------------------------------------------------ |
/// | `freeAllowanceAvailable` | nothing bound, budget open | free coaching is one-time; a subscription renews  |
/// | `freeAllowanceSpent`     | nothing bound, spent       | the free allowance is used up                     |
/// | `activeRunningTo`        | `active`, budget open      | monthly, running to ⟨date⟩; cancel in the App Store |
/// | `activePeriodSpent`      | `active`, budget spent     | this period's coaching is used; it resets ⟨date⟩ (RFC 121's open item) |
/// | `billingFailing`         | `grace` / `billingRetry`   | the last payment did not go through (RFC 119's open item) |
/// | `ended`                  | `expired` / `revoked`      | the subscription has ended                        |
/// | `boundUnknownStatus`     | bound, status unrecognized | the one thing true of every bound subscription    |
///
/// **The billing-failure case is deliberately not an error banner.** It is not
/// the student's mistake and nothing is broken yet — coaching keeps working
/// through grace — so it is stated in ordinary type with the control that
/// resolves it directly beneath, the same reasoning `SubscriptionOffer` applies
/// to its informational notices.
///
/// Two things this copy never does. It **never says a subscription can be
/// cancelled in the app**, because it cannot: Apple requires cancellation to go
/// through the App Store and offers no API that cancels on a student's behalf.
/// And it **never states a price** — StoreKit's localized `displayPrice` is the
/// only price anyone shows (RFC 119), and it is already on the Subscribe button.
///
/// The switch over `SubscriptionStatus?` is exhaustive with no `default:`, so a
/// case added to **this client's** `SubscriptionStatus` is a build failure here
/// rather than a silent fall-through to the free tier's sentence. A status
/// added to the **server's** vocabulary is not a build failure and is not meant
/// to be: it decodes to `knownStatus == nil` — that is what the raw wire string
/// is for — and lands on `boundUnknownStatus`, the arm that exists to say the
/// one thing still true of it. The compiler guards the vocabulary this client
/// knows; the `nil` arm guards the one it does not.
enum SubscriptionExplanation: Equatable {
    /// Nothing bound, coaching left.
    case freeAllowanceAvailable
    /// Nothing bound, the lifetime allowance used up.
    case freeAllowanceSpent
    /// `active` with coaching left, running to the period end.
    case activeRunningTo(periodEnd: Date)
    /// `active` with the period spent, and when it comes back.
    case activePeriodSpent(resetsAt: Date)
    /// `grace` / `billingRetry`: the last payment did not go through.
    case billingFailing
    /// `expired` / `revoked`.
    case ended
    /// Bound, with a status this client has no case for.
    case boundUnknownStatus

    /// Logged where the situation is *classified*, not where it is rendered:
    /// an unrecognized status reaches exactly one arm, and nothing else in the
    /// app records which one arrived — so without this the first sign of a new
    /// server status is a student reading the generic sentence.
    private static let logger = Logger.unicoach(category: "SubscriptionExplanation")

    /// Takes the **subscription**, not a bare status, precisely so the two
    /// `active` cases can name a date without an optional to fall back on:
    /// a bound subscription always carries `currentPeriodEnd`, and "nothing
    /// bound" is the outer `nil` rather than a fourth argument that has to
    /// agree with the other three.
    ///
    /// `budget` is the meter's own three-answer verdict, switched exhaustively
    /// rather than compared to `.spent`: `.unknown` deliberately reads as *not
    /// spent* — a reading that has not landed is no basis for telling a student
    /// their coaching is gone — and stating that as an arm means a fourth
    /// budget case would be a build failure here instead of silently borrowing
    /// the open budget's sentence.
    init(subscription: PublicSubscription?, budget: CoachingBudget, resetsAt: Date?) {
        guard let subscription else {
            // The free tier. Neither case mentions a reset, because the free
            // allowance is a lifetime credit that never has one.
            switch budget {
            case .spent:
                self = .freeAllowanceSpent
            case .open, .unknown:
                self = .freeAllowanceAvailable
            }
            return
        }

        switch subscription.knownStatus {
        case .active:
            switch budget {
            case .spent:
                // RFC 121's open item: the subscriber who has spent the period
                // used to be told a date and offered nothing. The date is the
                // meter's own `resetsAt` when there is one; the period end is
                // the same instant, and is the honest fallback rather than a
                // sentence with a hole in it.
                self = .activePeriodSpent(resetsAt: resetsAt ?? subscription.currentPeriodEnd)
            case .open, .unknown:
                self = .activeRunningTo(periodEnd: subscription.currentPeriodEnd)
            }
        case .grace, .billingRetry:
            self = .billingFailing
        case .expired, .revoked:
            self = .ended
        case nil:
            // A status this app has no case for still decodes — that is what
            // the raw wire string is for — and must not borrow another's words:
            // saying "ended" about a status that might mean the opposite is a
            // guess dressed as a fact. It is also the only thing here worth
            // logging: the wire value is discarded from this point on.
            Self.logger.error("Unrecognized subscription status: [\(subscription.status, privacy: .public)]")
            self = .boundUnknownStatus
        }
    }

    /// The sentence, computed at the point of use rather than baked in at
    /// classification — so the situation and its wording are separately
    /// testable and a copy edit touches one line and one assertion.
    var detail: String {
        switch self {
        case .freeAllowanceAvailable:
            return String(localized: "Free coaching is a one-time allowance. A subscription gives you a fresh allowance every month, and renews until you cancel.")
        case .freeAllowanceSpent:
            return String(localized: "You've used your free coaching. A subscription gives you a fresh allowance every month.")
        case .activeRunningTo(let periodEnd):
            // "Runs to", not "renews on". `PublicSubscription` carries no
            // auto-renew field, and a cancelled subscription stays `active`
            // until the period ends — so "renews" would promise a renewal on
            // the exact date the subscription is about to *end*, to the student
            // who has already cancelled. "Runs to" is true either way, and the
            // allowance-reset clause still says what happens then for the
            // student who has not.
            return String(localized: "Your monthly subscription runs to \(periodEnd.dsCalendarDate), and your coaching allowance resets then. Cancel any time in the App Store.")
        case .activePeriodSpent(let resetsAt):
            return String(localized: "You've used this period's coaching. It resets \(resetsAt.dsCalendarDate), when your subscription renews.")
        case .billingFailing:
            // RFC 119's open item: `grace`/`billingRetry` had a status line and
            // no UX. Coaching keeps working through grace, so this states what
            // happened and what fixes it.
            //
            // It names the control **by name**, not by position. "Manage
            // subscription below" was both a false claim and a garden path: the
            // control immediately below this sentence is the full-width filled
            // Subscribe button, so a student who read "below" and tapped the
            // next thing attempted a second purchase — and "Manage subscription
            // below opens…" parses first as an imperative. Naming it as a
            // button survives every reordering of the sheet, and the paywall,
            // which lays the same pieces out differently.
            return String(localized: "Your last payment didn't go through and the App Store is retrying. You can update your payment method there — the Manage subscription button opens it.")
        case .ended:
            return String(localized: "Your subscription has ended. Subscribing starts a new one, with a fresh allowance every month.")
        case .boundUnknownStatus:
            // What is true of every bound subscription, whatever its state, is
            // where it is managed.
            return String(localized: "Your subscription is managed by the App Store.")
        }
    }
}

/// The subscription sheet: the meter, how the subscription works, the offer,
/// and the way out to the App Store (RFC 123). Opened from the composer's
/// budget control, which is the ambient reading this screen explains.
///
/// It is a **presentation of the one rail**, exactly as `PaywallView` is:
/// every value and every action belongs to the single `SubscriptionViewModel`
/// `AuthenticatedRootView` owns, which is also what Settings renders and what
/// the transaction listener feeds. Nothing here derives entitlement, decides
/// whether to offer a purchase, or keeps state of its own.
///
/// It is **not** the paywall. The paywall is a block — it appears because a
/// turn was refused and it says so; this is a read-only explanation a student
/// can open at any time, including when nothing is wrong. That is why the meter
/// and the offer are shared while the words around them are not.
///
/// Sections are separated by the 1pt `DSHairline` and nothing else: no cards,
/// no fills, no shadows (DESIGN.md §3, §8).
struct SubscriptionView: View {
    @ObservedObject var viewModel: SubscriptionViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        DSSheetScroll {
            Text("Your coaching")
                .font(.dsTitle)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityIdentifier("subscriptionSheetTitle")

            // The ring is a glance; this is where the number and the reset
            // date live, and they are already authored once.
            CoachingUsageMeter(viewModel: viewModel)

            DSHairline()

            Text(viewModel.explanation.detail)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("subscriptionExplanation")

            DSHairline()

            SubscriptionOffer(viewModel: viewModel)

            // Present only when something is bound, which is the link's own
            // rule — the condition lives in `ManageSubscriptionLink` so this
            // sheet and the paywall cannot disagree about it. The separating
            // rule above it is the link's too: written here as well, it would
            // be a second site to keep in step with `offersManage`, and the
            // paywall (which does not carry hairlines of its own) would go on
            // showing Restore and Manage as undifferentiated twins.
            ManageSubscriptionLink(viewModel: viewModel)

            // The dismiss, matching the paywall's "Not now" — the same
            // `.secondary` role, for the same reason. This sheet has no
            // `onChange` exit: unlike the block, it is not a state the student
            // is waiting to leave.
            DSTextButton(
                String(localized: "Done"),
                role: .secondary,
                accessibilityIdentifier: "subscriptionDismissButton",
                accessibilityLabel: "Done",
                action: { dismiss() }
            )
        }
        // The same load the paywall takes, and for the same reason: the
        // authenticated root's launch read is usage-only (it has nowhere to show
        // a price and no business re-posting an entitlement), so the screen that
        // needs a price and the bound subscription fetches them when it is the
        // thing on screen.
        .task { await viewModel.load() }
    }
}


// MARK: - Previews

/// The canvas needs the rail *loaded*, which on the real screen is the sheet's
/// own `.task` — so the container does it here rather than each preview
/// constructing a view model that has never fetched anything. A bound
/// subscription is injected through the recorder's canvas double, which is the
/// only way one reaches this rail.
private struct SubscriptionPreviewContainer: View {
    @StateObject private var viewModel: SubscriptionViewModel

    init(usage: CoachingUsage, subscription: PublicSubscription? = nil) {
        _viewModel = StateObject(wrappedValue: SubscriptionViewModel(
            usageClient: PreviewCoachingUsageClient(usage: usage),
            store: PreviewSubscriptionStore(),
            recorder: PreviewTransactionRecorder()
        ))
        self.subscription = subscription
    }

    private let subscription: PublicSubscription?

    var body: some View {
        SubscriptionView(viewModel: viewModel)
            .task {
                if let subscription {
                    await viewModel.apply(.recorded(subscription))
                }
            }
    }
}

private func previewPeriodEnd(inDays days: Int) -> Date {
    Calendar.current.date(byAdding: .day, value: days, to: Date()) ?? Date()
}

/// The closed vocabulary, not a hand-spelled wire string: nothing on the canvas
/// is decoding a server response, so a typo'd status should be a build error
/// here. (The test files spell wire strings by hand on purpose — there the raw
/// string is part of what is under test.)
private func previewSubscription(_ status: SubscriptionStatus) -> PublicSubscription {
    PublicSubscription(
        status: status.rawValue,
        productId: SubscriptionProduct.monthlyIdentifier,
        currentPeriodEnd: previewPeriodEnd(inDays: 12)
    )
}

/// The free tier with coaching left: nothing bound, so no manage link.
#Preview("subscription free - Light") {
    SubscriptionPreviewContainer(usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil))
        .preferredColorScheme(.light)
}

#Preview("subscription free - Dark") {
    SubscriptionPreviewContainer(usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil))
        .preferredColorScheme(.dark)
}

/// An active subscription mid-period: the renewal sentence, no Subscribe
/// button, and the manage link.
#Preview("subscription active - Light") {
    SubscriptionPreviewContainer(
        usage: CoachingUsage(usedPercent: 31, exhausted: false, resetsAt: previewPeriodEnd(inDays: 12)),
        subscription: previewSubscription(.active)
    )
    .preferredColorScheme(.light)
}

#Preview("subscription active - Dark") {
    SubscriptionPreviewContainer(
        usage: CoachingUsage(usedPercent: 31, exhausted: false, resetsAt: previewPeriodEnd(inDays: 12)),
        subscription: previewSubscription(.active)
    )
    .preferredColorScheme(.dark)
}

/// RFC 121's open item: the subscriber who has spent the period, now offered a
/// real action rather than a date and nothing.
#Preview("subscription spent period - Light") {
    SubscriptionPreviewContainer(
        usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: previewPeriodEnd(inDays: 12)),
        subscription: previewSubscription(.active)
    )
    .preferredColorScheme(.light)
}

/// RFC 119's open item: a subscription that is failing to bill, stated in
/// ordinary type with the control that fixes it directly beneath.
#Preview("subscription billing retry - Dark") {
    SubscriptionPreviewContainer(
        usage: CoachingUsage(usedPercent: 12, exhausted: false, resetsAt: previewPeriodEnd(inDays: 3)),
        subscription: previewSubscription(.billingRetry)
    )
    .preferredColorScheme(.dark)
}
