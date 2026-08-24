import SwiftUI
import XCTest
@testable import UnicoachiOS

/// Presentation only. These tests never assert on `finish` — that is
/// `TransactionRecorder`'s contract, asserted in `TransactionRecorderTests`,
/// and duplicating it here would be the duplication this design removes.
@MainActor
final class SubscriptionViewModelTests: XCTestCase {
    private var usageClient: MockCoachingUsageClient!
    private var store: MockSubscriptionStore!
    private var recorder: MockTransactionRecorder!
    private var viewModel: SubscriptionViewModel!

    private let freeUsage = CoachingUsage(usedPercent: 40, exhausted: false, resetsAt: nil)
    private let subscription = PublicSubscription(
        status: "active",
        productId: SubscriptionProduct.monthlyIdentifier,
        currentPeriodEnd: Date(timeIntervalSince1970: 1_773_000_000)
    )
    private let product = StoreProduct(
        id: SubscriptionProduct.monthlyIdentifier,
        displayName: "Unicoach Monthly",
        displayPrice: "£8.99"
    )
    /// A period end distinct from `subscription`'s, so a sentence that named
    /// the wrong date could not accidentally match.
    private let periodEnd = Date(timeIntervalSince1970: 1_780_000_000)
    private let transaction = StoreTransaction(id: 7, productID: SubscriptionProduct.monthlyIdentifier, jws: "a.b.c")

    override func setUp() {
        super.setUp()
        usageClient = MockCoachingUsageClient()
        store = MockSubscriptionStore()
        recorder = MockTransactionRecorder()
        usageClient.results = [.success(freeUsage)]
        store.productResult = .success(product)
        viewModel = SubscriptionViewModel(usageClient: usageClient, store: store, recorder: recorder)
    }

    private func error(_ code: String, _ status: Int) -> ErrorResponse {
        ErrorResponse(code: code, message: "m", fieldErrors: nil, status: status)
    }

    // MARK: - load

    func testLoadPublishesUsageAndProduct() async {
        await viewModel.load()

        XCTAssertEqual(viewModel.usageReading, .ready(freeUsage))
        XCTAssertEqual(viewModel.productReading, .ready(product))
        XCTAssertEqual(viewModel.offer, .subscribe(product))
        XCTAssertEqual(viewModel.phase, .idle)
        XCTAssertNil(viewModel.notice)
        XCTAssertTrue(recorder.recorded.isEmpty, "no entitlement, nothing to record")
    }

    func testLoadRecordsTheNewestEntitlementAndPublishesTheSubscription() async {
        store.entitlements = [
            StoreTransaction(id: 1, productID: SubscriptionProduct.monthlyIdentifier, jws: "old"),
            StoreTransaction(id: 9, productID: SubscriptionProduct.monthlyIdentifier, jws: "newest"),
        ]
        recorder.outcome = .recorded(subscription)

        await viewModel.load()

        XCTAssertEqual(recorder.recorded.count, 1)
        XCTAssertEqual(recorder.recorded.first?.jws, "newest")
        XCTAssertEqual(viewModel.subscription, subscription)
    }

    /// Degrade, not fail: the meter is the part that always works, so a
    /// StoreKit failure costs the Subscribe button — and **says so**, with a
    /// retry. Best-effort over two fetches that reported only one of them
    /// missing left a blocked student on the paywall with no purchase path and
    /// no reason for it (RFC 121).
    func testLoadStillPublishesUsageWhenTheProductFetchFails() async {
        struct Boom: Error {}
        store.productResult = .failure(Boom())

        await viewModel.load()

        XCTAssertEqual(viewModel.usageReading, .ready(freeUsage))
        XCTAssertEqual(viewModel.productReading, .unavailable)
        XCTAssertEqual(viewModel.offer, .unavailable, "the offer says the purchase path is gone rather than vanishing")
        XCTAssertNil(viewModel.notice)
    }

    /// …and the retry behind that line works: the same `load()` the button
    /// calls, and the offer comes back.
    func testRetryingAfterAFailedProductFetchRestoresTheOffer() async {
        struct Boom: Error {}
        store.productResult = .failure(Boom())
        usageClient.results = [.success(freeUsage), .success(freeUsage)]
        await viewModel.load()
        XCTAssertEqual(viewModel.offer, .unavailable)

        store.productResult = .success(product)
        await viewModel.load()

        XCTAssertEqual(viewModel.offer, .subscribe(product))
    }

    /// A failed *refresh* of the price is not that: the price does not change
    /// under us, so what is on screen stays and the button does not blink out.
    func testAFailedProductRefreshKeepsThePriceOnScreen() async {
        struct Boom: Error {}
        usageClient.results = [.success(freeUsage), .success(freeUsage)]
        await viewModel.load()
        store.productResult = .failure(Boom())

        await viewModel.load()

        XCTAssertEqual(viewModel.productReading, .ready(product))
        XCTAssertEqual(viewModel.offer, .subscribe(product))
    }

    /// A bound, active subscription is the one state where showing no Subscribe
    /// button is the honest answer — and it is a named one, not the absence of
    /// the other three.
    func testAnActiveSubscriptionLeavesNothingToOffer() async {
        store.entitlements = [transaction]
        recorder.outcome = .recorded(subscription)

        await viewModel.load()

        XCTAssertFalse(viewModel.offersSubscribe)
        XCTAssertEqual(viewModel.offer, .bound)
    }

    /// `load()` is a background refresh, not an action the student took — and
    /// in dev `/verify` answers 503 every time. It must not open with a banner.
    func testLoadDoesNotSurfaceARecordFailure() async {
        store.entitlements = [transaction]
        recorder.outcome = .deferred(.server(error("service_unavailable", 503)))

        await viewModel.load()

        XCTAssertNil(viewModel.notice)
        XCTAssertNil(viewModel.subscription)
        XCTAssertEqual(viewModel.usageReading, .ready(freeUsage))
    }

    // MARK: - subscribe

    func testSubscribeRecordsThePurchaseAndRefetchesUsage() async {
        // `subscribe()` refetches usage exactly once, so this is that fetch.
        let spent = CoachingUsage(usedPercent: 0, exhausted: false, resetsAt: Date())
        usageClient.results = [.success(spent)]
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .recorded(subscription)

        await viewModel.subscribe()

        XCTAssertEqual(store.purchasedProductIDs, [SubscriptionProduct.monthlyIdentifier])
        XCTAssertEqual(recorder.recorded, [transaction])
        XCTAssertEqual(viewModel.subscription, subscription)
        XCTAssertEqual(viewModel.usageReading, .ready(spent))
        XCTAssertEqual(viewModel.phase, .idle)
        XCTAssertNil(viewModel.notice)
    }

    /// A cancel is not an error and must not raise a banner.
    func testUserCancelledIsSilentAndRecordsNothing() async {
        store.purchaseResult = .success(.userCancelled)

        await viewModel.subscribe()

        XCTAssertTrue(recorder.recorded.isEmpty)
        XCTAssertNil(viewModel.notice)
        XCTAssertEqual(viewModel.phase, .idle)
    }

    /// Ask to Buy: the listener picks the transaction up if it is approved.
    func testPendingShowsANeutralMessageAndRecordsNothing() async {
        store.purchaseResult = .success(.pending)

        await viewModel.subscribe()

        XCTAssertTrue(recorder.recorded.isEmpty)
        XCTAssertEqual(
            viewModel.notice,
            .informational("Waiting for approval. We'll set this up as soon as the purchase is approved.")
        )
    }

    func testServiceUnavailableSaysThePurchaseIsSafe() async {
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .deferred(.server(error("service_unavailable", 503)))

        await viewModel.subscribe()

        XCTAssertEqual(
            viewModel.notice,
            .failure("Your purchase is safe. We couldn't finish setting it up just now, and will finish automatically.")
        )
    }

    func testOwnedByOtherAccountSaysAnotherAccount() async {
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .rejected(.server(error("subscription_owned_by_other_account", 409)))

        await viewModel.subscribe()

        XCTAssertEqual(
            viewModel.notice,
            .failure("This subscription is already linked to another Unicoach account.")
        )
    }

    func testSubscriptionNotFoundSaysItCouldNotBeConfirmed() async {
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .deferred(.server(error("subscription_not_found", 404)))

        await viewModel.subscribe()

        XCTAssertEqual(
            viewModel.notice,
            .failure("We couldn't confirm this purchase with the App Store.")
        )
    }

    /// The arm `ServerErrorCode.coachingBudgetExhausted` forced (RFC 121): a
    /// 402 has no business on `/verify`, but if one surfaces in the
    /// subscription surface it must read as what it is rather than fall to the
    /// generic purchase-failure string.
    func testCoachingBudgetExhaustedReadsAsTheBlockedCopy() async {
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .deferred(.server(error("coaching_budget_exhausted", 402)))

        await viewModel.subscribe()

        XCTAssertEqual(viewModel.notice, .failure("You've used your coaching allowance."))
    }

    /// ...but only where there is a block to describe. With the meter reporting
    /// the budget OPEN there is no basis, and the paywall's dismissing words
    /// ("your allowance is available") would render here as a red failure
    /// banner announcing that nothing is wrong. That case takes the generic
    /// purchase-failure string instead.
    func testCoachingBudgetExhaustedOverAnOpenMeterIsNotAnnouncedAsGoodNews() async {
        await viewModel.load()
        XCTAssertEqual(viewModel.budget, .open, "precondition: the meter says the budget is open")

        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .deferred(.server(error("coaching_budget_exhausted", 402)))

        await viewModel.subscribe()

        XCTAssertEqual(viewModel.notice, .failure("We couldn't complete your purchase. Please try again."))
    }

    func testAnUnrecognizedCodeGetsTheGenericMessage() async {
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .deferred(.server(error("some_new_server_code", 500)))

        await viewModel.subscribe()

        XCTAssertEqual(viewModel.notice, .failure("We couldn't complete your purchase. Please try again."))
    }

    // MARK: - restore

    func testRestoreSyncsThenRecordsEveryEntitlement() async {
        let first = StoreTransaction(id: 1, productID: SubscriptionProduct.monthlyIdentifier, jws: "one")
        let second = StoreTransaction(id: 2, productID: SubscriptionProduct.monthlyIdentifier, jws: "two")
        store.entitlements = [first, second]
        recorder.outcome = .recorded(subscription)

        await viewModel.restore()

        XCTAssertEqual(store.syncCallCount, 1)
        XCTAssertEqual(recorder.recorded, [first, second])
        XCTAssertEqual(viewModel.subscription, subscription)
        XCTAssertEqual(viewModel.phase, .idle)
    }

    /// The button is never a silent no-op — and an empty Apple Account is not
    /// a failure, so it is a notice rather than an error banner.
    func testRestoreWithNoEntitlementsSaysThereIsNothingToRestore() async {
        store.entitlements = []

        await viewModel.restore()

        XCTAssertTrue(recorder.recorded.isEmpty)
        XCTAssertEqual(
            viewModel.notice,
            .informational("There are no purchases to restore on this Apple Account.")
        )
    }

    func testRestoreReportsASyncFailure() async {
        store.syncResult = .failed

        await viewModel.restore()

        XCTAssertEqual(viewModel.notice, .failure("We couldn't reach the App Store. Please try again."))
        XCTAssertTrue(recorder.recorded.isEmpty)
    }

    /// Backing out of the App Store sign-in sheet is not a failure: the student
    /// did nothing wrong, so there is no banner.
    func testACancelledRestoreIsSilent() async {
        store.syncResult = .userCancelled
        store.entitlements = [transaction]

        await viewModel.restore()

        XCTAssertNil(viewModel.notice)
        XCTAssertTrue(recorder.recorded.isEmpty)
        XCTAssertEqual(viewModel.phase, .idle)
    }

    /// Last-write-wins reporting used to present a partly restored account as a
    /// clean one: a success after a refusal erased the refusal's banner.
    func testRestoreReportsAFailureEvenWhenALaterEntitlementSucceeds() async {
        let failing = StoreTransaction(id: 1, productID: SubscriptionProduct.monthlyIdentifier, jws: "one")
        let succeeding = StoreTransaction(id: 2, productID: SubscriptionProduct.monthlyIdentifier, jws: "two")
        store.entitlements = [failing, succeeding]
        recorder.outcomes = [
            .rejected(.server(error("subscription_owned_by_other_account", 409))),
            .recorded(subscription),
        ]

        await viewModel.restore()

        XCTAssertEqual(recorder.recorded, [failing, succeeding])
        XCTAssertEqual(viewModel.subscription, subscription, "what did record is still published")
        XCTAssertEqual(
            viewModel.notice,
            .failure("This subscription is already linked to another Unicoach account."),
            "a refusal outlives a later success"
        )
    }

    /// Only the plan this app sells is posted: `/verify` has no plan to match
    /// another product to and answers a 500.
    func testRestoreSkipsEntitlementsForOtherProducts() async {
        let ours = StoreTransaction(id: 1, productID: SubscriptionProduct.monthlyIdentifier, jws: "ours")
        let theirs = StoreTransaction(id: 2, productID: "coach.uni.SomethingElse", jws: "theirs")
        store.entitlements = [ours, theirs]
        recorder.outcome = .recorded(subscription)

        await viewModel.restore()

        XCTAssertEqual(recorder.recorded, [ours])
    }

    func testLoadIgnoresEntitlementsForOtherProducts() async {
        store.entitlements = [StoreTransaction(id: 99, productID: "coach.uni.SomethingElse", jws: "theirs")]

        await viewModel.load()

        XCTAssertTrue(recorder.recorded.isEmpty)
    }

    /// A transport failure has no wire code to explain, and must not borrow one:
    /// it gets the connection sentence of its own.
    func testATransportFailureGetsTheConnectionMessage() async {
        struct Boom: Error {}
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .deferred(.transport(Boom()))

        await viewModel.subscribe()

        XCTAssertEqual(
            viewModel.notice,
            .failure("Your purchase is safe. We couldn't reach Unicoach to finish setting it up — check your connection and try again.")
        )
    }

    /// A 200 the client could not read: the server holds the purchase and there
    /// is nothing for the student to do, so nothing is said.
    func testAnUndecodableTwoHundredIsSilent() async {
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .rejected(.server(error("DECODE_ERROR", 200)))

        await viewModel.subscribe()

        XCTAssertNil(viewModel.notice)
    }

    /// A StoreKit result this app has no case for is a failure, never "waiting
    /// for approval" — an approval that is never coming.
    func testAnUnrecognizedPurchaseResultIsAFailureNotice() async {
        store.purchaseResult = .success(.unrecognized)

        await viewModel.subscribe()

        XCTAssertTrue(recorder.recorded.isEmpty)
        XCTAssertEqual(
            viewModel.notice,
            .failure("The App Store returned something we couldn't make sense of. Please try again.")
        )
    }

    /// A finished load with no meter at all is a state the section renders,
    /// not an empty section.
    func testAFailedUsageLoadIsVisible() async {
        struct Boom: Error {}
        usageClient.results = [.failure(Boom())]

        await viewModel.load()

        XCTAssertEqual(viewModel.usageReading, .unavailable)
        XCTAssertEqual(viewModel.phase, .idle)
    }

    /// A failed *refresh* is not that: the reading already on screen stays, and
    /// the section keeps drawing the meter.
    func testAFailedUsageRefreshKeepsTheReadingOnScreen() async {
        struct Boom: Error {}
        usageClient.results = [.success(freeUsage), .failure(Boom())]

        await viewModel.load()
        await viewModel.load()

        XCTAssertEqual(viewModel.usageReading, .ready(freeUsage))
    }

    /// StoreKit signed something this app refuses to trust: a failure, and the
    /// words are the view model's — the rail carries no copy.
    func testAnUnverifiedPurchaseIsAFailureNotice() async {
        store.purchaseResult = .success(.unverified)

        await viewModel.subscribe()

        XCTAssertTrue(recorder.recorded.isEmpty, "an unverified payload is never posted")
        XCTAssertEqual(viewModel.notice, .failure("The App Store returned a purchase we couldn't verify."))
    }

    /// The plan is not offered on this storefront — an expected value, not a
    /// thrown infrastructure error.
    func testAnUnavailableProductIsAFailureNotice() async {
        store.purchaseResult = .success(.unavailable)

        await viewModel.subscribe()

        XCTAssertTrue(recorder.recorded.isEmpty)
        XCTAssertEqual(viewModel.notice, .failure("This subscription is unavailable right now."))
    }

    // MARK: - apply (the session-long listener's landing point)

    /// A renewal or an approved Ask to Buy arrives on the listener; what it
    /// records has to reach the screen, which is the whole reason the listener
    /// exists.
    func testIngestPublishesARecordedSubscriptionAndRefreshesUsage() async {
        let renewed = CoachingUsage(usedPercent: 3, exhausted: false, resetsAt: Date(timeIntervalSince1970: 1_773_000_000))
        usageClient.results = [.success(renewed)]

        await viewModel.apply(.recorded(subscription))

        XCTAssertEqual(viewModel.subscription, subscription)
        XCTAssertEqual(viewModel.usageReading, .ready(renewed))
        XCTAssertNil(viewModel.notice)
    }

    /// Silent on a failed arm: no student action prompted this, and in dev
    /// `/verify` answers 503 on every delivery.
    func testIngestIsSilentOnAFailedArm() async {
        await viewModel.apply(.deferred(.server(error("service_unavailable", 503))))

        XCTAssertNil(viewModel.subscription)
        XCTAssertNil(viewModel.notice)
    }

    // MARK: - Derived presentation

    func testNothingBoundOffersSubscribeAndHasNoStatusLine() {
        XCTAssertTrue(viewModel.offersSubscribe)
        XCTAssertNil(viewModel.statusLine)
    }

    func testAnActiveSubscriptionSuppressesTheOfferAndSaysWhenItRenews() async {
        store.entitlements = [transaction]
        recorder.outcome = .recorded(subscription)

        await viewModel.load()

        XCTAssertFalse(viewModel.offersSubscribe)
        let date = subscription.currentPeriodEnd.formatted(date: .abbreviated, time: .omitted)
        XCTAssertEqual(viewModel.statusLine, "Monthly · renews \(date)")
    }

    /// A subscription that is *failing to bill* is one the student already pays
    /// for: the offer is suppressed, and that reversal of RFC 119's rule is the
    /// point of RFC 128.
    func testABillingProblemSuppressesTheOffer() async {
        await bind(status: "grace", usage: freeUsage)

        XCTAssertFalse(viewModel.offersSubscribe)
    }

    /// The same for `billingRetry`, which is the other half of the same
    /// situation and must not drift from `grace` by being nobody's test.
    func testBillingRetrySuppressesTheOffer() async {
        await bind(status: "billing_retry", usage: freeUsage)

        XCTAssertFalse(viewModel.offersSubscribe)
    }

    /// **Some door is always open.** This is the invariant that makes
    /// suppressing Subscribe safe, and the only form of it that can fail:
    /// asserting `offersManage` beside a suppressed offer proves nothing, since
    /// `offersManage` is `subscription != nil` and is therefore true of every
    /// bound subscription and was true before RFC 128. What must never become
    /// representable is `(false, false)` — a surface offering neither a
    /// purchase nor a way to reach the existing subscription, which is the
    /// stranding RFC 119 wrote its rule against.
    ///
    /// Driven off the whole `SubscriptionStatus` vocabulary rather than a
    /// hand-listed few, so a case added to this client's enum is covered by
    /// this test the day it is added, plus the two cases that are not in the
    /// enum at all: an unrecognized server status, and nothing bound.
    func testEveryStateOffersAtLeastOneDoor() async {
        XCTAssertTrue(
            viewModel.offersSubscribe || viewModel.offersManage,
            "nothing bound: neither a purchase nor management is offered"
        )

        for status in SubscriptionStatus.allCases {
            await bind(status: status.rawValue, usage: freeUsage)
            XCTAssertTrue(
                viewModel.offersSubscribe || viewModel.offersManage,
                "\(status.rawValue): neither a purchase nor management is offered"
            )
        }

        await bind(status: "paused_by_apple_2027", usage: freeUsage)
        XCTAssertTrue(
            viewModel.offersSubscribe || viewModel.offersManage,
            "an unrecognized status: neither a purchase nor management is offered"
        )
    }

    /// The suppression proven at the value the **view** renders, not only at
    /// the predicate behind it: `SubscriptionOffer` draws nothing for `.bound`,
    /// and that is what removes the button from the sheet.
    func testABillingProblemLeavesNothingToOffer() async {
        store.entitlements = [transaction]
        recorder.outcome = .recorded(PublicSubscription(
            status: "grace",
            productId: SubscriptionProduct.monthlyIdentifier,
            currentPeriodEnd: periodEnd
        ))

        await viewModel.load()

        XCTAssertEqual(viewModel.productReading, .ready(product), "the price is there; it is the offer that is withheld")
        XCTAssertEqual(viewModel.offer, .bound)
    }

    /// The words are **not** taken with the button. RFC 128 removes a control
    /// and changes no copy, so the one line on the Settings row that mentions
    /// the problem is pinned here.
    func testABillingProblemStillSaysThePaymentIsBeingRetried() async {
        await bind(status: "grace", usage: freeUsage)

        XCTAssertEqual(viewModel.statusLine, "Monthly · payment issue · retrying")
    }

    /// An **ended** subscription is the opposite case, and the contrast is the
    /// reason the rule is a table rather than "anything bound suppresses it":
    /// there is nothing live to repair, so buying really is the way back.
    func testAnEndedSubscriptionStillOffersSubscribe() async {
        await bind(status: "expired", usage: freeUsage)
        XCTAssertTrue(viewModel.offersSubscribe)

        await bind(status: "revoked", usage: freeUsage)
        XCTAssertTrue(viewModel.offersSubscribe)
    }

    /// A status this client has no case for offers the purchase, deliberately
    /// asymmetrically with the two rows above: an unneeded offer costs a
    /// dismissible App Store dialog, a withheld one costs a student with
    /// nothing their only purchase path. The recoverable error is the one to
    /// make.
    func testAnUnrecognizedStatusOffersSubscribe() async {
        await bind(status: "paused_by_apple_2027", usage: freeUsage)

        XCTAssertTrue(viewModel.offersSubscribe)
    }

    // MARK: - remainingPercent (the composer ring's reading, RFC 123)

    /// `100 - usedPercent`, on the server's own capped value.
    func testRemainingPercentIsTheComplementOfUsed() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 38, exhausted: false, resetsAt: nil))]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.remainingPercent, 62)
    }

    /// The case that matters most: a load in flight must never draw a full ring
    /// for a student with nothing left.
    func testRemainingPercentIsNilWhileLoading() {
        XCTAssertEqual(viewModel.usageReading, .loading)
        XCTAssertNil(viewModel.remainingPercent)
    }

    /// …and a read that finished with nothing is the same answer: no reading.
    func testRemainingPercentIsNilWhenTheReadingIsUnavailable() async {
        struct Boom: Error {}
        usageClient.results = [.failure(Boom())]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.usageReading, .unavailable)
        XCTAssertNil(viewModel.remainingPercent)
    }

    func testAnExhaustedBudgetHasNothingRemaining() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.remainingPercent, 0)
    }

    // MARK: - budgetGlance (the ring, the label, and what VoiceOver says)

    func testBudgetReadingIsThePercentageWhenThereIsOne() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 38, exhausted: false, resetsAt: nil))]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.budgetGlance, .remaining(percent: 62))
        XCTAssertEqual(viewModel.budgetGlance.label, "62% left")
        XCTAssertEqual(viewModel.budgetGlance.accessibilityValue, "62 percent remaining")
        XCTAssertFalse(viewModel.budgetGlance.isExhausted)
        XCTAssertEqual(viewModel.budgetGlance.ringRemainingPercent, 62)
    }

    /// The server's own `exhausted` flag decides this, never the arithmetic.
    func testBudgetReadingIsExhaustedWhenTheServerSaysSo() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.budgetGlance, .exhausted)
        XCTAssertEqual(viewModel.budgetGlance.label, "Out of coaching")
        XCTAssertEqual(viewModel.budgetGlance.accessibilityValue, "No coaching remaining")
        XCTAssertTrue(viewModel.budgetGlance.isExhausted)
        XCTAssertEqual(viewModel.budgetGlance.ringRemainingPercent, 0, "an exhausted budget draws an empty ring")
    }

    /// No reading at all: the bare track, and no words. Not a spinner, and
    /// emphatically not a full ring.
    func testBudgetReadingSaysNothingWithoutAReading() {
        XCTAssertEqual(viewModel.budgetGlance, .noReading)
        XCTAssertNil(viewModel.budgetGlance.label)
        XCTAssertNil(viewModel.budgetGlance.ringRemainingPercent)
        XCTAssertEqual(viewModel.budgetGlance.accessibilityValue, "Not loaded yet")
    }

    /// A broken server cap must not put a negative number beside an empty ring.
    /// The ring clamps its own sweep, so without a clamp here the two would
    /// contradict each other — an empty ring beside "-5% left".
    func testAnImpossibleReadingIsClampedForBothTheRingAndTheLabel() {
        let overspent = CoachingBudgetGlance(remainingPercent: -5, budget: .open)

        XCTAssertEqual(overspent, .remaining(percent: 0))
        XCTAssertEqual(overspent.label, "0% left")
        XCTAssertEqual(overspent.ringRemainingPercent, 0)

        let overfull = CoachingBudgetGlance(remainingPercent: 130, budget: .open)

        XCTAssertEqual(overfull, .remaining(percent: 100))
        XCTAssertEqual(overfull.label, "100% left")
        XCTAssertEqual(overfull.ringRemainingPercent, 100)
    }

    /// The free tier needs no case of its own: the ring and the label are a
    /// quantity, and say nothing about time.
    func testTheFreeTierReadsLikeAnyOtherQuantity() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 25, exhausted: false, resetsAt: nil))]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.budgetGlance.label, "75% left")
    }

    // MARK: - The explanation matrix (RFC 123)

    // Every row of the matrix is asserted as a **case**, not as a sentence:
    // which situation was classified is the rule, and the wording is a copy
    // decision that has already been revised twice. Two representative rows
    // below pin the exact string, so the copy is still covered without seven
    // assertions breaking on an edit that changes no behaviour.

    /// Nothing bound, coaching left: the free allowance is one-time, and what a
    /// subscription changes.
    func testExplanationForTheFreeTierWithCoachingLeft() async {
        await viewModel.refreshUsage()

        XCTAssertNil(viewModel.subscription)
        XCTAssertEqual(viewModel.explanation, .freeAllowanceAvailable)
    }

    /// Nothing bound, nothing left.
    func testExplanationForASpentFreeAllowance() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.explanation, .freeAllowanceSpent)
    }

    func testExplanationForAnActiveSubscriptionWithCoachingLeft() async {
        await bind(status: "active", usage: CoachingUsage(usedPercent: 20, exhausted: false, resetsAt: periodEnd))

        XCTAssertEqual(viewModel.explanation, .activeRunningTo(periodEnd: periodEnd))
    }

    /// RFC 121's open item: the subscriber who has spent the period.
    func testExplanationForAnActiveSubscriptionWithThePeriodSpent() async {
        await bind(status: "active", usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: periodEnd))

        XCTAssertEqual(viewModel.explanation, .activePeriodSpent(resetsAt: periodEnd))
    }

    /// RFC 119's open item: a subscription that is failing to bill. Both
    /// statuses classify the same, because they are the same situation — and
    /// each iteration re-binds over the same rail, which is sound because
    /// `bind` states the whole situation (the reading and the status) rather
    /// than adding to a previous one.
    func testExplanationForAFailingPayment() async {
        for status in ["grace", "billing_retry"] {
            await bind(status: status, usage: CoachingUsage(usedPercent: 20, exhausted: false, resetsAt: periodEnd))

            XCTAssertEqual(viewModel.explanation, .billingFailing, "status [\(status)]")
        }
    }

    func testExplanationForASubscriptionThatHasEnded() async {
        for status in ["expired", "revoked"] {
            await bind(status: status, usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: periodEnd))

            XCTAssertEqual(viewModel.explanation, .ended, "status [\(status)]")
        }
    }

    /// A status this client has no case for must not borrow another's words —
    /// it says the one thing true of every bound subscription.
    func testExplanationForAStatusThisClientDoesNotKnow() async {
        await bind(status: "some_new_server_status", usage: CoachingUsage(usedPercent: 20, exhausted: false, resetsAt: periodEnd))

        XCTAssertEqual(viewModel.explanation, .boundUnknownStatus)
        XCTAssertEqual(viewModel.explanation.detail, "Your subscription is managed by the App Store.")
    }

    /// The spent-period sentence, spelled out: it is the one that names a date,
    /// so a wrong `resetsAt` reaching the copy would show up here.
    func testTheSpentPeriodSentenceNamesTheResetDate() async {
        await bind(status: "active", usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: periodEnd))

        XCTAssertEqual(
            viewModel.explanation.detail,
            "You've used this period's coaching. It resets \(periodEnd.dsCalendarDate), when your subscription renews."
        )
    }

    /// The billing-failure sentence, spelled out — and specifically that it
    /// names the Manage subscription **button** rather than claiming a
    /// position. "Manage subscription below" pointed at the full-width
    /// Subscribe button, so a student who followed it attempted a second
    /// purchase.
    func testTheBillingFailureSentenceNamesTheControlRatherThanAPosition() async {
        await bind(status: "grace", usage: CoachingUsage(usedPercent: 20, exhausted: false, resetsAt: periodEnd))

        XCTAssertEqual(
            viewModel.explanation.detail,
            "Your last payment didn't go through and the App Store is retrying. You can update your payment method there — the Manage subscription button opens it."
        )
        XCTAssertFalse(viewModel.explanation.detail.contains("below"), "no positional claim about a control")
    }

    /// A reading that has not landed is no basis for telling a subscriber their
    /// coaching is gone: `unknown` reads as *not spent*, by an arm of the
    /// classifier's `switch` rather than by falling off a `== .spent`.
    func testAnUnknownBudgetExplainsTheOpenSituation() async {
        struct Boom: Error {}
        usageClient.results = [.failure(Boom())]
        store.entitlements = [transaction]
        recorder.outcome = .recorded(subscription)

        await viewModel.load()

        XCTAssertEqual(viewModel.budget, .unknown)
        XCTAssertEqual(viewModel.explanation, .activeRunningTo(periodEnd: subscription.currentPeriodEnd))
    }

    /// …and the same on the free tier: no reading is not a spent allowance.
    func testAnUnknownBudgetOnTheFreeTierReadsAsAvailable() async {
        struct Boom: Error {}
        usageClient.results = [.failure(Boom())]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.budget, .unknown)
        XCTAssertEqual(viewModel.explanation, .freeAllowanceAvailable)
    }

    // MARK: - Manage subscription (RFC 123)

    /// Bound in any state, because `grace` and `expired` are exactly the states
    /// where reaching the subscription matters most.
    func testManageIsOfferedOnlyWhenSomethingIsBound() async {
        XCTAssertFalse(viewModel.offersManage)

        await bind(status: "expired", usage: freeUsage)

        XCTAssertTrue(viewModel.offersManage)
    }

    /// Apple's sheet appearing over the app is the feedback; a banner
    /// underneath it would be talking over it.
    func testShowingApplesSheetLeavesNoNotice() async {
        store.manageResult = .shown

        await viewModel.showManagement()

        XCTAssertEqual(store.manageCallCount, 1)
        XCTAssertNil(viewModel.notice)
    }

    /// The round trip is closed here and **nowhere else**: Apple presents over
    /// the app's own scene, so `scenePhase` never leaves `.active`, and a
    /// renewal-state change pushes nothing onto `Transaction.updates`. The
    /// store's `await` returns when the sheet is dismissed, which is the moment
    /// to re-read — otherwise the student who has just repaired their card is
    /// still reading that the payment failed.
    func testDismissingApplesSheetReReadsTheRail() async {
        usageClient.results = [.success(freeUsage)]
        store.manageResult = .shown
        await viewModel.refreshUsage()
        let readsBefore = usageClient.callCount

        await viewModel.showManagement()

        XCTAssertGreaterThan(usageClient.callCount, readsBefore, "the meter is re-read when Apple's sheet closes")
        XCTAssertNil(viewModel.notice)
    }

    /// A failed presentation re-reads nothing: nothing happened, and the notice
    /// is the whole of the response.
    func testAFailedManageDoesNotReReadTheRail() async {
        usageClient.results = [.success(freeUsage)]
        store.manageResult = .unavailable
        await viewModel.refreshUsage()
        let readsBefore = usageClient.callCount

        await viewModel.showManagement()

        XCTAssertEqual(usageClient.callCount, readsBefore)
    }

    /// No scene, or Apple refused: the student asked for something and did not
    /// get it, so it is a failure notice naming the route that always works.
    func testAFailedManageRaisesAFailureNotice() async {
        store.manageResult = .unavailable

        await viewModel.showManagement()

        XCTAssertEqual(store.manageCallCount, 1)
        XCTAssertEqual(
            viewModel.notice,
            .failure("We couldn't open the App Store. You can manage this subscription in Settings › your name › Subscriptions.")
        )
    }

    /// The phase belongs to the actions with a control the student waits on;
    /// this one hands the screen to Apple.
    func testManageDoesNotTouchThePhase() async {
        await viewModel.showManagement()

        XCTAssertEqual(viewModel.phase, .idle)
    }

    // MARK: - Helpers

    /// Binds a subscription the only way one ever reaches this rail — through
    /// the recorder — and publishes a reading alongside it, so a test states the
    /// *situation* rather than the four calls that produce it.
    private func bind(status: String, usage: CoachingUsage) async {
        usageClient.results = [.success(usage)]
        recorder.outcome = .recorded(PublicSubscription(
            status: status,
            productId: SubscriptionProduct.monthlyIdentifier,
            currentPeriodEnd: periodEnd
        ))
        await viewModel.apply(recorder.outcome)
    }
}


/// The gate itself (RFC 123). It is a `@MainActor` struct over a `Binding`, so
/// the presentation rules are reachable without rendering anything — and they
/// were the untested part of the change that removed the pair of `Bool`s.
///
/// Presentation is asserted through the binding's **written value**, which is
/// the whole of what the gate does: SwiftUI's behaviour when a sheet is
/// swapped for another is not this type's to promise (see `present()`), and
/// asserting it here would be asserting a claim the code deliberately no
/// longer makes.
@MainActor
final class PaywallGateTests: XCTestCase {
    private var usageClient: MockCoachingUsageClient!
    private var subscriptions: SubscriptionViewModel!

    /// The `@State` the gate writes through, and — recorded at the moment of
    /// each write — how many usage reads had happened by then. Ordering is the
    /// rule for `handleBudgetExhausted()`: the sheet must open **on** the fresh
    /// reading, so a refresh that lands after the assignment is a defect that a
    /// call-count checked at the end of the test cannot see.
    private let presentedSheet = Locked<SubscriptionSheet?>(nil)
    private let usageReadsAtAssignment = Locked<[Int]>([])

    private let usage = CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)

    override func setUp() {
        super.setUp()
        usageClient = MockCoachingUsageClient()
        usageClient.results = [.success(usage)]
        subscriptions = SubscriptionViewModel(
            usageClient: usageClient,
            store: MockSubscriptionStore(),
            recorder: MockTransactionRecorder()
        )
    }

    /// Built per test rather than stored, so the binding closures capture the
    /// boxes and the client directly instead of the test case.
    private func makeGate() -> PaywallGate {
        let sheet = presentedSheet
        let reads = usageReadsAtAssignment
        let client = usageClient!
        return PaywallGate(
            subscriptions: subscriptions,
            presentedSheet: Binding(
                get: { sheet.current },
                set: { newValue in
                    sheet.withLock { $0 = newValue }
                    reads.withLock { $0.append(client.callCount) }
                }
            )
        )
    }

    /// Nothing has been read, so the budget is `.unknown` — one of the two
    /// verdicts `present()` opens on. The gate declines only an **open**
    /// budget, and that rule has its own tests in `PaywallViewModelTests`;
    /// these are about which value the binding is left holding.
    func testPresentRaisesThePaywallFromNothing() {
        XCTAssertEqual(subscriptions.budget, .unknown)

        makeGate().present()

        XCTAssertEqual(presentedSheet.current, .paywall)
    }

    /// The case the pair of `Bool`s got wrong: the composer's budget control is
    /// never disabled, so a 402 can land while the explanation sheet is already
    /// up. With a budget the gate presents on, it writes the paywall over the
    /// explanation — what SwiftUI then does with the swap is not claimed, but
    /// the binding must not be left saying `.explanation`.
    func testPresentRaisesThePaywallOverTheExplanationSheet() {
        let gate = makeGate()
        gate.presentExplanation()
        XCTAssertEqual(presentedSheet.current, .explanation)

        gate.present()

        XCTAssertEqual(presentedSheet.current, .paywall)
    }

    /// The budget control's tap: the explanation, not the block.
    func testPresentExplanationRaisesTheExplanationSheet() {
        makeGate().presentExplanation()

        XCTAssertEqual(presentedSheet.current, .explanation)
    }

    /// The 402's landing point refreshes usage from the server **before** the
    /// sheet opens, so the block and the meter come from one answer and the
    /// paywall opens on the fresh reading rather than on whatever was there
    /// when the turn was refused.
    func testHandleBudgetExhaustedRefreshesUsageBeforePresenting() async {
        XCTAssertEqual(subscriptions.usageReading, .loading)

        await makeGate().handleBudgetExhausted()

        XCTAssertEqual(presentedSheet.current, .paywall)
        XCTAssertEqual(subscriptions.usageReading, .ready(usage))
        XCTAssertEqual(
            usageReadsAtAssignment.current,
            [1],
            "the read had already landed when the sheet was assigned"
        )
    }
}
