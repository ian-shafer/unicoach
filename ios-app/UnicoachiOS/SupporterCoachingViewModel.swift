import Foundation
import os

/// What the parent's home says about how their coaching is paid for (RFC 244).
///
/// FOUR rendered answers and the read that has not landed yet. The pair of
/// free-allowance cases is the state the sentence changes on — a one-time
/// credit that is still there reads differently from one that is gone — and it
/// is decided here, from the server's own `exhausted` flag, rather than from a
/// percentage compared against 100 in a view.
enum SupporterCoachingState: Equatable {
    /// No read has finished yet: the spinner, never an empty gap.
    case loading
    /// A one-time credit with something left on it. It carries NO reset date,
    /// because its caption cannot name one — and no `exhausted`, because this
    /// is the arm where that flag is false.
    case freeAllowanceAvailable(usedPercent: Int)
    case freeAllowanceSpent
    /// A paid period, which always ends: the caption IS that date, so the arm
    /// carries it non-optionally.
    case ownSubscription(usedPercent: Int, exhausted: Bool, resetsAt: Date)
    /// A read that produced nothing, or one whose coverage word this build
    /// cannot read. Said out loud, because a heading with nothing under it
    /// reads as a rendering bug — and because a silent 0% meter would be a
    /// claim about a parent's spend that nothing supports.
    case unavailable
}

/// The parent home's billing rail: one read of
/// `GET /api/v1/supporters/me/coaching-usage`, and the state the section
/// renders from it (RFC 244).
///
/// It holds a `SupporterCoachingUsageClientProtocol` and nothing else, which is
/// what keeps the student-scoped meter off this screen: there is no collaborator
/// here that could fire `/api/v1/students/me/coaching-usage`, rather than a flag
/// saying it must not.
///
/// The read is a `Reading`, shared with the student rail, so the merge rules —
/// a fresh value wins, a failed refresh keeps what is on screen, and a read
/// that produced nothing at all says so — are written once for both surfaces.
@MainActor
final class SupporterCoachingViewModel: ObservableObject {
    @Published private(set) var reading: Reading<SupporterCoachingUsage> = .loading

    private let client: SupporterCoachingUsageClientProtocol
    private let logger = Logger.unicoach(category: "SupporterCoachingViewModel")

    init(client: SupporterCoachingUsageClientProtocol) {
        self.client = client
    }

    /// The words to put on screen, derived on access rather than published a
    /// second time: the reading is the one fact, and the rule that turns it
    /// into a state has one home — this suite's, which has no view-test
    /// harness.
    ///
    /// Derived means PURE. A view reads this per body pass, so it decides and
    /// returns and does nothing else; what a read produced that cannot be read
    /// is said by `fetch()`, where the reading arrives.
    ///
    /// It is also the ONE place the wire's two halves are paired: the kind is
    /// in `coverage` and the date it governs is in `usage`, and every arm below
    /// is handed exactly the caption data its sentence admits. No layer under
    /// this one can see the halves apart, so "Your subscription." over
    /// "One-time free allowance" is not a state that can be built.
    var state: SupporterCoachingState {
        switch reading {
        case .loading:
            return .loading
        case .unavailable:
            return .unavailable
        case .ready(let read):
            switch read.coverage.kind {
            case .ownSubscription:
                guard let resetsAt = read.usage.resetsAt else {
                    // The server derives the kind and the date from one
                    // `EntitlementBasis`, so this pair cannot come from it.
                    // Admitted as unknown rather than drawn under the free
                    // tier's caption, which is what a nil date would caption.
                    //
                    // Nothing is SAID from here. SwiftUI reads this property on
                    // every body pass, so a diagnostic in it would repeat with
                    // every scroll and scene change for as long as one bad
                    // reading stands, burying the occurrence that mattered.
                    // `report(_:)` names the reading once, where it arrives.
                    return .unavailable
                }
                return .ownSubscription(
                    usedPercent: read.usage.usedPercent,
                    exhausted: read.usage.exhausted,
                    resetsAt: resetsAt
                )
            case .freeAllowance:
                // `resetsAt` is dropped rather than checked: the KIND is the
                // discriminator, and a one-time credit's caption names no
                // period whatever date sits beside it.
                return read.usage.exhausted
                    ? .freeAllowanceSpent
                    : .freeAllowanceAvailable(usedPercent: read.usage.usedPercent)
            case .unrecognised:
                // The server named an allowance this build cannot name back.
                // The meter beside it may be perfectly readable, and is still
                // not drawn: a bar with no honest caption is worse than the
                // sentence that admits the state is unknown.
                return .unavailable
            }
        }
    }

    /// Whether this account pays through its own subscription — what the
    /// account-deletion notice is driven by (RFC 252). The coverage KIND
    /// alone, not `state`: a subscription whose period end failed to decode
    /// renders `.unavailable`, and is still billed by Apple. `nil` while the
    /// reading is loading or unavailable: not knowing is not "does not pay".
    var holdsOwnSubscription: Bool? {
        switch reading {
        case .loading, .unavailable:
            return nil
        case .ready(let read):
            return read.coverage.kind == .ownSubscription
        }
    }

    /// Re-reads the meter. The parent's home takes it on appearance and on every
    /// return to the foreground, for the reason the roster refresh does: a
    /// parent's spend changes while the app is in the background, and nothing
    /// else would ask.
    func load() async {
        // A retry with nothing on screen is *in flight*, not unavailable: the
        // previous failure's sentence must not sit there through the new read.
        reading = reading.retrying()
        reading = reading.refreshed(with: await fetch())
    }

    /// The three things a read can produce, kept apart on the way in: a value,
    /// a server that said the caller has no `supporters` row, and a read that
    /// failed. The last two both leave no reading — but they are different
    /// facts and only one of them is ordinary, so they are logged as what they
    /// are rather than folded into one silent `nil`.
    ///
    /// It is also where a body that ARRIVED and cannot be read is said out
    /// loud, so all three diagnostics this rail emits are in one place and each
    /// is written once per read.
    private func fetch() async -> SupporterCoachingUsage? {
        do {
            guard let read = try await client.fetch() else {
                // A 404 is an ordinary answer for this route — and not on THIS
                // screen, which the root state machine only mounts for an
                // account whose `supporters` row it has already read.
                logger.error("Supporter coaching usage answered 404 on a resolved supporter home")
                return nil
            }
            report(read)
            return read
        } catch {
            logger.error("Supporter coaching usage fetch failed: [\(error, privacy: .public)]")
            return nil
        }
    }

    /// Names a reading that `state` will have to refuse — `own_subscription`
    /// with no period end — at the moment it arrives, and once.
    ///
    /// The pair is one the server cannot derive, since the kind and the date
    /// come from a single `EntitlementBasis`, so when this fires it is either a
    /// server regression or a date that failed to decode. An operator can only
    /// tell those apart from the reading itself, so the line carries the kind
    /// and the two figures the meter would have drawn — which, once the nil
    /// date is known, is the whole of the offending body.
    ///
    /// Its subject is the same pair the `.ownSubscription` arm of `state`
    /// rejects. It is stated HERE because `state` is a getter SwiftUI calls per
    /// body pass; what to DRAW stays there, because that is the question a view
    /// asks.
    private func report(_ read: SupporterCoachingUsage) {
        guard case .ownSubscription = read.coverage.kind, read.usage.resetsAt == nil else { return }
        logger.error("""
            Supporter coaching usage carried no resetsAt for its coverage, rendering unavailable: \
            kind=[own_subscription] resetsAt=[nil] \
            usedPercent=[\(read.usage.usedPercent, privacy: .public)] \
            exhausted=[\(read.usage.exhausted, privacy: .public)]
            """)
    }
}
