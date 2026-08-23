import XCTest
@testable import UnicoachiOS

/// The paywall's rules, which are two separable things: the **copy**, a pure
/// function of the meter (`PaywallCopy`), and the **offer**, which is the
/// shared `SubscriptionViewModel`'s answer and never re-derived by the sheet.
/// Both are asserted here rather than by rendering the view, which is why this
/// file is named for the model and not the screen.
@MainActor
final class PaywallViewModelTests: XCTestCase {
    private var usageClient: MockCoachingUsageClient!
    private var store: MockSubscriptionStore!
    private var recorder: MockTransactionRecorder!
    private var viewModel: SubscriptionViewModel!

    private let resetDate = Date(timeIntervalSince1970: 1_773_000_000)
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
        store.productResult = .success(product)
        viewModel = SubscriptionViewModel(usageClient: usageClient, store: store, recorder: recorder)
    }

    private func activeSubscription() -> PublicSubscription {
        PublicSubscription(
            status: "active",
            productId: SubscriptionProduct.monthlyIdentifier,
            currentPeriodEnd: resetDate
        )
    }

    // MARK: - Copy

    /// The free tier: a lifetime allowance, no reset date, and a Subscribe
    /// button because nothing is bound.
    func testFreeAllowanceExhaustedSaysFreeCoachingAndOffersSubscribe() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]

        await viewModel.load()

        XCTAssertEqual(viewModel.budget, .spent)
        XCTAssertEqual(viewModel.coachingBasis, .freeAllowance)
        XCTAssertEqual(viewModel.coachingBasis.map { PaywallCopy(basis: $0).detail }, "You've used your free coaching.")
        XCTAssertTrue(viewModel.offersSubscribe)
    }

    /// The subscriber who has spent the period: the copy names the reset date,
    /// and there is **no** Subscribe button — one plan is configured, so it
    /// could only invite a duplicate purchase StoreKit would refuse. Restore
    /// stays, which is a property of the offer view, not of this flag.
    func testSubscriberExhaustedNamesTheResetDateAndDoesNotOfferSubscribe() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: resetDate))]
        store.entitlements = [transaction]
        recorder.outcome = .recorded(activeSubscription())

        await viewModel.load()

        XCTAssertEqual(viewModel.budget, .spent)
        XCTAssertEqual(viewModel.coachingBasis, .period(resetsAt: resetDate))
        let expected = "You've used this period's coaching. It resets \(resetDate.dsCalendarDate)."
        XCTAssertEqual(viewModel.coachingBasis.map { PaywallCopy(basis: $0).detail }, expected)
        XCTAssertFalse(viewModel.offersSubscribe)

        // **Restore still offered.** The sheet's Restore button is
        // unconditional in `SubscriptionOffer` — there is no flag to read — so
        // what is assertable at this level is that the action behind it still
        // works in exactly this state: a student whose purchase never bound to
        // this account is who needs it, and they are the ones most likely to be
        // sitting on a spent period.
        XCTAssertNotEqual(viewModel.phase, .restoring)
        await viewModel.restore()

        XCTAssertEqual(store.syncCallCount, 1, "Restore reaches the App Store while Subscribe is withheld")
        XCTAssertEqual(viewModel.phase, .idle)
        XCTAssertFalse(viewModel.offersSubscribe, "and it did not resurrect the Subscribe button")
    }

    /// The cold-launch case: a 402 can arrive before the initial usage load
    /// lands. The sheet still has words — and they are neutral, because telling
    /// a subscriber they have used their *free* coaching would be a guess.
    func testCopyWithNoReadingYetIsNeutralRatherThanTheFreeTierGuess() {
        XCTAssertEqual(viewModel.usageReading, .loading)
        XCTAssertEqual(viewModel.budget, .unknown)
        XCTAssertEqual(viewModel.coachingBasis, .unknown, "no reading is its own basis, not the free tier's")
        XCTAssertEqual(PaywallCopy(basis: .unknown).detail, "You've used your coaching allowance.")
        XCTAssertNotEqual(
            PaywallCopy(basis: .unknown).detail,
            "You've used your free coaching.",
            "never the free-tier sentence: a paying subscriber has not used their *free* coaching"
        )
        XCTAssertFalse(PaywallCopy(basis: .unknown).title.isEmpty)
        XCTAssertEqual(PaywallCopy(basis: .unknown).title, PaywallCopy.pausedTitle, "one heading, shared with the blocked composer")
    }

    /// …and the meter fills in when the reading arrives, without the sheet
    /// being reopened.
    func testTheMeterFillsInWhenTheReadingArrives() async {
        let usage = CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil)
        usageClient.results = [.success(usage)]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.usageReading, .ready(usage))
        XCTAssertEqual(viewModel.coachingBasis.map { PaywallCopy(basis: $0).detail }, "You've used your free coaching.")
    }

    /// A finished load that produced no meter renders the RFC 119 unavailable
    /// state, which the sheet reads off the same flag Settings does — never an
    /// empty sheet.
    func testUsageUnavailableIsPublishedForTheSheet() async {
        struct Boom: Error {}
        usageClient.results = [.failure(Boom())]

        await viewModel.load()

        XCTAssertEqual(viewModel.usageReading, .unavailable)
        XCTAssertEqual(viewModel.budget, .unknown, "no reading is not a block: the 402 is the authority")
    }

    // MARK: - Clearing the block

    /// A purchase refreshes usage, and the block clears because the *server*
    /// says it has — not because the client set a flag.
    ///
    /// The meter going `open` is also what **dismisses the sheet**: `PaywallView`
    /// watches exactly this value, so a student who has just paid is not left
    /// reading "Coaching is paused". The `dismiss()` itself is a view effect and
    /// is the one half this suite cannot reach — see the RFC's "what unit tests
    /// cannot reach".
    func testASuccessfulPurchaseRefreshesUsageAndClearsTheBlock() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]
        await viewModel.load()
        XCTAssertEqual(viewModel.budget, .spent)

        let refreshed = CoachingUsage(usedPercent: 0, exhausted: false, resetsAt: resetDate)
        usageClient.results = [.success(refreshed)]
        store.purchaseResult = .success(.purchased(transaction))
        recorder.outcome = .recorded(activeSubscription())

        await viewModel.subscribe()

        XCTAssertEqual(viewModel.usageReading, .ready(refreshed))
        XCTAssertEqual(viewModel.budget, .open, "the sheet's dismissal condition")
        XCTAssertFalse(viewModel.offersSubscribe)
    }

    /// The 402's own landing point: `refreshUsage()` is the one thing the gate
    /// calls, and it re-reads the meter rather than setting anything.
    func testRefreshUsageIsWhatMakesTheBlockTrue() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]

        XCTAssertEqual(viewModel.budget, .unknown)
        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.budget, .spent)
        XCTAssertEqual(usageClient.callCount, 1)
    }

    /// The root's initial read is **usage only**: no StoreKit product fetch and
    /// no `/verify` POST, which `load()` would have put on every launch.
    func testTheGatesReadTouchesNeitherTheProductNorVerify() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]
        store.entitlements = [transaction]

        await viewModel.refreshUsage()

        XCTAssertEqual(usageClient.callCount, 1)
        XCTAssertEqual(store.productCallCount, 0, "no product fetch for a read the root only needs the meter from")
        XCTAssertEqual(recorder.recorded.count, 0, "and no /verify POST at launch")
        XCTAssertEqual(viewModel.productReading, .loading, "no fetch has finished, which is not the same as none being possible")
    }

    /// …while `load()` — what Settings and the sheet call — still does all
    /// three, and still publishes the same meter.
    func testTheSubscriptionSurfacesFullLoadStillFetchesTheProductAndVerifies() async {
        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]
        store.entitlements = [transaction]
        recorder.outcome = .recorded(activeSubscription())

        await viewModel.load()

        XCTAssertEqual(viewModel.budget, .spent)
        XCTAssertEqual(store.productCallCount, 1)
        XCTAssertEqual(recorder.recorded.count, 1)
        XCTAssertEqual(viewModel.productReading, .ready(product))
        XCTAssertEqual(viewModel.offer, .bound, "the price was fetched; this student simply has nothing left to buy")
    }

    /// A refresh that fails with a reading already on screen keeps it; one that
    /// fails with nothing on screen says so — the meter never invents `spent`
    /// out of a failed read, which is what would disable a composer for a
    /// student whose network blinked.
    func testAFailedRefreshNeverInventsABlock() async {
        struct Boom: Error {}
        let reading = CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
        // Consumed in order: a failed first read, then a good one, then another
        // failure — the three-case rule, in the order a student meets it.
        usageClient.results = [.failure(Boom()), .success(reading), .failure(Boom())]

        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.budget, .unknown)
        XCTAssertEqual(viewModel.usageReading, .unavailable)

        await viewModel.refreshUsage()
        XCTAssertEqual(viewModel.budget, .open)
        XCTAssertEqual(viewModel.usageReading, .ready(reading))

        await viewModel.refreshUsage()
        XCTAssertEqual(viewModel.usageReading, .ready(reading), "a failed refresh keeps the reading already on screen")
        XCTAssertEqual(viewModel.budget, .open)
    }
}
