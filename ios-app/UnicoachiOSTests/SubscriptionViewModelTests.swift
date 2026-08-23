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

    /// A subscription that is *failing to bill* still offers the purchase path:
    /// hiding it at exactly that moment strands the student.
    func testABillingProblemStillOffersSubscribe() async {
        store.entitlements = [transaction]
        recorder.outcome = .recorded(PublicSubscription(
            status: "grace",
            productId: SubscriptionProduct.monthlyIdentifier,
            currentPeriodEnd: Date(timeIntervalSince1970: 1_773_000_000)
        ))

        await viewModel.load()

        XCTAssertTrue(viewModel.offersSubscribe)
        XCTAssertEqual(viewModel.statusLine, "Monthly · payment issue · retrying")
    }
}
