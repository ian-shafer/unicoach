import Foundation
import os

/// Presentation for the Settings subscription section: what to show, what to
/// say, what is loading.
///
/// It does **not** own the finish policy and *cannot* touch `finish` — its
/// `store` is a `SubscriptionStoreProtocol`, which has no such member; that is
/// `TransactionRecorder`'s whole job, and this type reaches StoreKit's
/// transactions only through it. Nor does it derive entitlement: it renders
/// `CoachingUsage` from the server and nothing else decides what a student may
/// do.
/// The lifecycle of a value this rail fetches: a read in flight, a read that
/// produced something, and a read that finished with nothing. **One value, not
/// an optional beside a `Bool`** — three states in two fields leave the
/// in-flight one unnamed, so every view has to infer it from a flag still
/// sitting at its default, and a retry after a failed read then renders the
/// failure's words for the whole of the new read.
///
/// It is generic because the meter and the price want the same three answers
/// and the same merge rule (`refreshed(with:)`), and a second transcription of
/// that rule is a second place for it to drift.
enum Reading<Value: Equatable>: Equatable {
    /// No read has finished yet: the spinner, never an empty gap.
    case loading
    /// The value a read produced.
    case ready(Value)
    /// A read finished and produced nothing, with nothing already on screen.
    /// Said out loud by the surfaces that render it, because a header with
    /// nothing under it reads as a rendering bug rather than as a failed fetch.
    case unavailable

    /// The one three-case rule: a fresh value wins; a failed refresh keeps what
    /// is already on screen (it did not change under us, and dropping it would
    /// take a meter or a Subscribe button off a screen the student is looking
    /// at); and a read that produced nothing at all says so.
    func refreshed(with fresh: Value?) -> Reading {
        switch (fresh, self) {
        case (.some(let fresh), _):
            return .ready(fresh)
        case (nil, .ready):
            return self
        case (nil, .loading), (nil, .unavailable):
            return .unavailable
        }
    }

    /// The rule for a read whose *premise has been disproved*: a fresh value
    /// wins, and a failed read is `unavailable` — it does **not** keep what is
    /// on screen. This is the post-refusal path and nothing else: a 402 has
    /// just told us the reading on screen is wrong, so keeping it would leave
    /// a meter contradicting the refusal (and, on the paywall, a sheet with an
    /// open budget and therefore no basis and no words at all). Ordinary
    /// refreshes keep their reading — that is `refreshed(with:)`'s rule and
    /// RFC 119's deliberate choice; this one is the exception, written as its
    /// own method so neither can be mistaken for the other.
    /// The receiver is not a parameter because it is not consulted: a static
    /// factory makes the discarded reading visible at the call site and keeps
    /// this from being read as a merge.
    static func invalidated(by fresh: Value?) -> Reading {
        guard let fresh else { return .unavailable }
        return .ready(fresh)
    }

    /// The state a retry starts from: a previous failure's words must not sit
    /// on screen through the read that is meant to replace them. A reading
    /// already on screen stays put — that is `refreshed(with:)`'s rule.
    func retrying() -> Reading {
        switch self {
        case .unavailable:
            return .loading
        case .loading, .ready:
            return self
        }
    }
}

/// The coaching meter's read.
typealias UsageReading = Reading<CoachingUsage>
/// The StoreKit price's read.
typealias ProductReading = Reading<StoreProduct>

/// What the shared meter says about the coaching budget. `unknown` is a reading
/// that has not arrived or a refresh that failed: deliberately neither `open`
/// (the 402 stays the authority for a turn already refused) nor `spent` (a
/// failed read must never disable a composer). RFC 121.
enum CoachingBudget: Equatable {
    case unknown
    case open
    case spent
}

@MainActor
final class SubscriptionViewModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        case loading
        case purchasing
        case restoring
    }

    /// A message with its kind attached, so the error banner is reserved for
    /// failures. Ask to Buy approval-pending and "nothing to restore" are
    /// ordinary, successful ends of a flow and must not be dressed as errors.
    /// One field rather than two optionals: the two kinds are mutually
    /// exclusive, and a single value is how that stays true.
    enum Notice: Equatable {
        case informational(String)
        case failure(String)
    }

    /// The offer block's own state. `bound` is the student who already has an
    /// active subscription — the one case where showing nothing is the honest
    /// answer, and the only one.
    enum Offer: Equatable {
        case bound
        case loading
        case subscribe(StoreProduct)
        case unavailable
    }

    /// The meter, as a lifecycle rather than as a reading beside a flag: the
    /// three states `CoachingUsageMeter` renders — in flight, a reading, and a
    /// finished-but-empty read — are one published value, so no surface has to
    /// infer "still loading" from the absence of the other two (RFC 121).
    @Published private(set) var usageReading: UsageReading = .loading
    @Published private(set) var subscription: PublicSubscription?
    /// The price, under the same rule as the meter. A load that produced no
    /// price at all is `unavailable` and is *said*: on the paywall a silently
    /// missing Subscribe button leaves a blocked student with no purchase path
    /// and no reason for it.
    @Published private(set) var productReading: ProductReading = .loading
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var notice: Notice?

    private let usageClient: CoachingUsageClientProtocol
    private let store: SubscriptionStoreProtocol
    private let recorder: TransactionRecording
    private let logger = Logger.unicoach(category: "SubscriptionViewModel")

    init(
        usageClient: CoachingUsageClientProtocol,
        store: SubscriptionStoreProtocol,
        recorder: TransactionRecording
    ) {
        self.usageClient = usageClient
        self.store = store
        self.recorder = recorder
    }

    // MARK: - Derived presentation

    /// Whether to *offer* a purchase — never what the student is entitled to,
    /// which is `CoachingUsage`, the server's own answer. Only `active`
    /// suppresses the button: a subscription in `grace` or `billingRetry` is
    /// *failing to bill*, and hiding the purchase path at exactly that moment
    /// strands the student with no in-app way forward.
    var offersSubscribe: Bool {
        subscription?.knownStatus != .active
    }

    /// The shared blocked truth (RFC 121) — **three answers, not two**. The
    /// server's own `exhausted` flag decides between `open` and `spent`; a
    /// reading that has not arrived, or a refresh that failed, is `unknown` and
    /// is deliberately neither. A `Bool` here would conflate "no reading yet"
    /// with "budget open", and that conflation forces a refused turn to suppress
    /// Retry forever — the student pays and still cannot send the words the 402
    /// arm made a point of keeping.
    ///
    /// Every `ConversationView` in the stack observes this one value, which is
    /// why it lives here and not on the per-screen `ConversationViewModel`.
    var budget: CoachingBudget {
        switch usageReading {
        case .loading, .unavailable:
            // No answer is not an answer of "open": a read that has not landed
            // (or failed) leaves the 402 as the authority and never blocks on
            // its own.
            return .unknown
        case .ready(let usage):
            return usage.exhausted ? .spent : .open
        }
    }

    /// Why coaching is paused, or `nil` when it is **not** paused — an open
    /// budget has no basis to name, and the surfaces that render a reason are
    /// the surfaces that have already established there is one (RFC 121).
    /// Composed here, from the one reading, rather than re-decoded from a
    /// `Date?`'s nullability at each call site.
    var coachingBasis: CoachingBasis? {
        switch usageReading {
        case .loading, .unavailable:
            return .unknown
        case .ready(let usage):
            return CoachingBasis(budget: budget, resetsAt: usage.resetsAt)
        }
    }

    /// What the offer block can put on screen — **four answers, not a button
    /// and a silence**. A price that never arrived is its own state, said out
    /// loud with a retry, because on the paywall the Subscribe button is the
    /// only exit from a blocked composer and its absence would be both the
    /// failure and the explanation (RFC 121).
    ///
    /// It lives here rather than as a compound `if` in `SubscriptionOffer` so
    /// the rule is asserted by this suite, which has no view-test harness.
    var offer: Offer {
        guard offersSubscribe else { return .bound }
        switch productReading {
        case .ready(let product):
            return .subscribe(product)
        case .loading:
            return .loading
        case .unavailable:
            return .unavailable
        }
    }

    /// The status line, or `nil` when nothing is bound. The price shown is
    /// always StoreKit's localized `displayPrice`; the server's `priceUsd` is a
    /// budget input, not display copy. `currentPeriodEnd` is displayed, never
    /// compared.
    var statusLine: String? {
        guard let subscription else { return nil }
        let date = subscription.currentPeriodEnd.dsCalendarDate
        switch subscription.knownStatus {
        case .active:
            return String(localized: "Monthly · renews \(date)")
        case .grace, .billingRetry:
            return String(localized: "Monthly · payment issue · retrying")
        case .expired:
            return String(localized: "Monthly · ended \(date)")
        case .revoked:
            return String(localized: "Monthly · refunded")
        case nil:
            // An unknown status still decodes (that is the point of the raw
            // string); it just has no words of its own.
            return String(localized: "Monthly · \(date)")
        }
    }

    // MARK: - Actions

    /// Fetches the meter and the product concurrently, then refreshes the bound
    /// subscription by re-posting the newest entitlement's JWS — `/verify` is
    /// idempotent and is the only read of subscription state the server offers.
    ///
    /// Best-effort over the two fetches, and it **reports both**: usage is the
    /// part that always works, so a StoreKit failure costs the price and not the
    /// screen — but a load that produced no price at all becomes an `offer` that
    /// says so and offers a retry, rather than a Subscribe button that quietly
    /// is not there. On the paywall that button is the blocked student's only
    /// exit, and a silent absence would be both the failure and the explanation.
    ///
    /// A record failure here is silent — this is a background refresh of a
    /// display value, not an action the student took, and in dev `/verify`
    /// answers 503 on every open.
    func load() async {
        phase = .loading
        notice = nil

        // Both reads publish their own reading, including the "a retry is in
        // flight" reset, so the `async let`s carry no value — they are bound
        // and awaited purely to run concurrently and to be joined before
        // `phase` goes back to `.idle`. Neither merge rule is transcribed
        // here: this method orchestrates two reads and a re-post, it performs
        // neither read itself.
        async let usageRead: Void = refreshUsage()
        async let productRead: Void = refreshProduct()
        _ = await (usageRead, productRead)

        if let newest = await newestEntitlement() {
            if case .recorded(let subscription) = await recorder.record(newest) {
                self.subscription = subscription
            }
        }

        phase = .idle
    }

    /// Buys the one configured plan. A cancel returns to `.idle` **silently** —
    /// a cancel is not an error and must not raise a banner. Ask to Buy
    /// (`.pending`) leaves an informational notice; the listener picks the
    /// transaction up if and when Apple approves it.
    func subscribe() async {
        phase = .purchasing
        notice = nil
        defer { phase = .idle }

        do {
            switch try await store.purchase(productID: SubscriptionProduct.monthlyIdentifier) {
            case .purchased(let transaction):
                apply(purchased: await recorder.record(transaction))
                await refreshUsage()
            case .userCancelled:
                break
            case .pending:
                notice = .informational(String(localized: "Waiting for approval. We'll set this up as soon as the purchase is approved."))
            case .unverified:
                notice = .failure(String(localized: "The App Store returned a purchase we couldn't verify."))
            case .unavailable:
                notice = .failure(String(localized: "This subscription is unavailable right now."))
            case .unrecognized:
                notice = .failure(String(localized: "The App Store returned something we couldn't make sense of. Please try again."))
            }
        } catch {
            logger.error("Purchase failed: [\(error, privacy: .public)]")
            notice = .failure(String(localized: "We couldn't start the purchase. Please try again."))
        }
    }

    /// Re-binds every entitlement this Apple Account holds for the one plan
    /// this app sells. Reports "nothing to restore" on an empty set — as a
    /// notice, not an error — so the button is never a silent no-op. A cancelled
    /// App Store sign-in ends the flow silently: the student did nothing wrong.
    func restore() async {
        phase = .restoring
        notice = nil
        defer { phase = .idle }

        switch await store.sync() {
        case .synced:
            break
        case .userCancelled:
            return
        case .failed:
            notice = .failure(String(localized: "We couldn't reach the App Store. Please try again."))
            return
        }

        let entitlements = await entitlementsForSoldProduct()
        guard !entitlements.isEmpty else {
            notice = .informational(String(localized: "There are no purchases to restore on this Apple Account."))
            return
        }

        // Every entitlement is attempted, and the reports are aggregated rather
        // than overwritten: a refusal outlives a later success, so a partly
        // restored account is never presented as a clean one.
        var failures: [RecordFailure] = []
        for entitlement in entitlements {
            switch await recorder.record(entitlement) {
            case .recorded(let subscription):
                self.subscription = subscription
            case .rejected(let failure), .deferred(let failure):
                failures.append(failure)
            }
        }
        notice = failures.compactMap(message(for:)).first.map(Notice.failure)
        await refreshUsage()
    }

    /// A transaction the session-long listener recorded — a renewal, an Ask to
    /// Buy approval, or a redelivery. This is the listener's landing point, so
    /// what it records reaches the screen instead of being dropped. Silent on a
    /// failed arm for the same reason `load()` is: no student action prompted
    /// it, and in dev `/verify` answers 503.
    func apply(_ outcome: RecordOutcome) async {
        guard case .recorded(let subscription) = outcome else { return }
        self.subscription = subscription
        // Clearing a notice the student may be mid-read is deliberate: the only
        // banner this can replace is the deferred one, which promised the
        // purchase would "finish automatically" — this is that, so the promise
        // has been kept and the banner has nothing left to say.
        notice = nil
        await refreshUsage()
    }

    /// Publishes a recorded subscription, or the copy for the arm that failed.
    /// The three codes with their own words are the ones a student can act on
    /// (or must be told not to); everything else gets the generic message.
    private func apply(purchased outcome: RecordOutcome) {
        switch outcome {
        case .recorded(let subscription):
            self.subscription = subscription
            // Same reason as the listener's arm above: the only standing notice
            // a success can replace promised exactly this completion.
            notice = nil
        case .rejected(let failure), .deferred(let failure):
            notice = message(for: failure).map(Notice.failure)
        }
    }

    /// The words for a failed record, or `nil` when the right thing to say is
    /// nothing. The two failure shapes are kept apart: a server refusal is
    /// explained by its code, while a transport failure has no code to explain
    /// and gets the connection sentence instead of an invented one.
    private func message(for failure: RecordFailure) -> String? {
        switch failure {
        case .server(let error):
            return message(for: error)
        case .transport(let error):
            logger.error("Record failed in transport: [\(error, privacy: .public)]")
            return String(localized: "Your purchase is safe. We couldn't reach Unicoach to finish setting it up — check your connection and try again.")
        }
    }

    /// Exhaustive over `ServerErrorCode` with no `default:`, so a new code has
    /// to be given its words here before this compiles.
    private func message(for error: ErrorResponse) -> String? {
        switch error.knownCode {
        case .serviceUnavailable, .serverError, .timeout, .networkError:
            return String(localized: "Your purchase is safe. We couldn't finish setting it up just now, and will finish automatically.")
        case .subscriptionOwnedByOtherAccount:
            return String(localized: "This subscription is already linked to another Unicoach account.")
        case .subscriptionNotFound:
            return String(localized: "We couldn't confirm this purchase with the App Store.")
        case .validationFailed:
            return String(localized: "The App Store sent a purchase we couldn't process. Contact support and we'll sort it out.")
        case .unauthorized, .emailNotVerified, .accountEmailNotVerified, .accountDisabled:
            return String(localized: "Please sign in again to finish setting up your subscription.")
        case .decodeError:
            // Only ever synthesized after a 200: the purchase *is* recorded and
            // there is nothing for the student to do, so nothing is said.
            logger.error("Unreadable /verify body for a recorded purchase: [\(error.message, privacy: .public)]")
            return nil
        case .coachingBudgetExhausted:
            // A 402 has no business on this endpoint, but if one reaches the
            // subscription surface it must read as what it is rather than as a
            // failed purchase — in the paywall's own words, so the sentence is
            // authored once and names the reset date when a reading is present.
            // A meter that says the budget is *open* names no basis, so the
            // refusal is reported in that case's own sentence rather than in a
            // period's the client cannot stand behind — or in the "no reading
            // yet" one, which would claim a spent allowance the meter beside
            // it contradicts. With NO basis the meter says the budget is open,
            // so there is no block to describe — and the paywall's dismissing
            // words ("your allowance is available") would render here as a red
            // failure banner reporting that nothing is wrong. That case falls in
            // with the generic purchase failure instead.
            guard let coachingBasis else {
                return String(localized: "We couldn't complete your purchase. Please try again.")
            }
            return PaywallCopy(basis: coachingBasis).detail
        case .studentAlreadyExists, .studentProfileRequired, .payloadTooLarge, nil:
            return String(localized: "We couldn't complete your purchase. Please try again.")
        }
    }

    /// Re-reads the meter, and **nothing else** — no product fetch, no
    /// `/verify` POST. This is the read the gate wants: `AuthenticatedRootView`
    /// takes it at launch and on every return to the foreground, and the 402 arm
    /// forces it so the blocked state and the number on the paywall come from
    /// one server answer rather than a boolean the client set. Hoisting the
    /// whole of `load()` for it would have put a `/verify` POST on every launch,
    /// which RFC 119 deliberately scoped to the subscription surface.
    ///
    /// `load()` calls it too, and both go through `Reading.refreshed(with:)` —
    /// fresh reading wins, a failed refresh keeps what is on screen, and a read
    /// that produced nothing at all says so — so that rule is written once and
    /// the meter and the price cannot drift apart on it.
    func refreshUsage() async {
        // A retry with nothing on screen is *in flight*, not unavailable: the
        // previous failure's words must not sit there through the new read.
        usageReading = usageReading.retrying()
        usageReading = usageReading.refreshed(with: await fetchUsage())
    }

    /// The **402's** re-read: same fetch, opposite failure rule. A refusal has
    /// just disproved whatever reading is on screen, so a read that fails goes
    /// to `unavailable` (`budget == .unknown`) rather than keeping a stale
    /// `exhausted == false` — a 402 and a failing usage GET are the same server
    /// having the same bad minute, so they correlate rather than compose
    /// independently, and the pair used to open a paywall with no title, no
    /// explanation, and a meter contradicting the refusal.
    ///
    /// `unknown` is the right landing: it already means "the 402 is the
    /// authority" everywhere else in this design, it has copy of its own, and
    /// it never blocks a composer on its own.
    func refreshUsageAfterRefusal() async {
        // Unlike `refreshUsage()`, the line below is not consulted by the next
        // one — `invalidated(by:)` is a factory that ignores the receiver. It
        // is still not dead: it is the state on screen *for the duration of
        // the read*, so a previous failure's words are replaced by the
        // in-flight meter rather than sitting under the sheet about to open.
        usageReading = usageReading.retrying()
        usageReading = .invalidated(by: await fetchUsage())
    }

    /// Re-reads the price, and **nothing else** — no meter read, no `/verify`
    /// POST. The offer's "Try again" wants exactly this: a control about a
    /// missing price has no business writing to the purchase rail. `load()`
    /// calls it too, so the three-case merge rule — a fresh price wins, a
    /// failed refresh keeps the price already on screen, and a read that
    /// produced no price at all is `unavailable`, which the offer says out
    /// loud instead of dropping the Subscribe button without a word — stays
    /// written once. The surfaces that genuinely want all three still call
    /// `load()`.
    func refreshProduct() async {
        productReading = productReading.retrying()
        productReading = productReading.refreshed(with: await fetchProduct())
    }

    private func fetchUsage() async -> CoachingUsage? {
        do {
            return try await usageClient.fetch()
        } catch {
            logger.error("Coaching usage fetch failed: [\(error, privacy: .public)]")
            return nil
        }
    }

    private func fetchProduct() async -> StoreProduct? {
        do {
            return try await store.product(id: SubscriptionProduct.monthlyIdentifier)
        } catch {
            logger.error("Product fetch failed: [\(error, privacy: .public)]")
            return nil
        }
    }

    /// The entitlements for the one plan this app sells. An entitlement for any
    /// other product is not this rail's business: `/verify` has no plan to match
    /// it to and answers a 500, so it is never posted.
    private func entitlementsForSoldProduct() async -> [StoreTransaction] {
        await store.currentEntitlements().filter { $0.productID == SubscriptionProduct.monthlyIdentifier }
    }

    /// The newest of those by StoreKit's monotonically increasing transaction
    /// id — the one whose JWS is worth re-posting.
    private func newestEntitlement() async -> StoreTransaction? {
        await entitlementsForSoldProduct().max(by: { $0.id < $1.id })
    }
}
