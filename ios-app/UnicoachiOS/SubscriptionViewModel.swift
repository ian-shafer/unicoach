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

    @Published private(set) var usage: CoachingUsage?
    @Published private(set) var subscription: PublicSubscription?
    @Published private(set) var product: StoreProduct?
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var notice: Notice?
    /// A finished load that produced no meter at all — the state
    /// `SubscriptionSection` renders as "unavailable" rather than as nothing.
    /// A *failed refresh* with a reading already on screen is not this: the
    /// reading stays, and silence is right.
    @Published private(set) var usageUnavailable = false

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

    /// The status line, or `nil` when nothing is bound. The price shown is
    /// always StoreKit's localized `displayPrice`; the server's `priceUsd` is a
    /// budget input, not display copy. `currentPeriodEnd` is displayed, never
    /// compared.
    var statusLine: String? {
        guard let subscription else { return nil }
        let date = subscription.currentPeriodEnd.formatted(date: .abbreviated, time: .omitted)
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
    /// A StoreKit or product-fetch failure degrades to "the meter without a
    /// Subscribe button" rather than an empty screen: usage is the part that
    /// always works. A record failure here is likewise silent — this is a
    /// background refresh of a display value, not an action the student took,
    /// and in dev `/verify` answers 503 on every open.
    func load() async {
        phase = .loading
        notice = nil

        async let fetchedUsage = fetchUsage()
        async let fetchedProduct = fetchProduct()
        let (usage, product) = await (fetchedUsage, fetchedProduct)
        // Keep the product already fetched when this refresh fails — the same
        // rule the meter gets below: the price does not change under us, and
        // dropping it would take the Subscribe button off a screen the student
        // is looking at.
        self.product = product ?? self.product
        switch (usage, self.usage) {
        case (.some(let fresh), _):
            self.usage = fresh
            usageUnavailable = false
        case (nil, .some):
            // Keep the reading already on screen: a failed refresh is not news.
            break
        case (nil, nil):
            // Nothing to draw. Said out loud, because a section header with
            // nothing under it is indistinguishable from a rendering bug.
            usageUnavailable = true
        }

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
        case .studentAlreadyExists, .payloadTooLarge, nil:
            return String(localized: "We couldn't complete your purchase. Please try again.")
        }
    }

    private func refreshUsage() async {
        if let usage = await fetchUsage() {
            self.usage = usage
        }
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
