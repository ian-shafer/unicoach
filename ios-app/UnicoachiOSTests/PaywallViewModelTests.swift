import SwiftUI
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

    private func expiredSubscription() -> PublicSubscription {
        PublicSubscription(
            status: "expired",
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

    /// The refresh path re-reads the meter rather than setting anything — the
    /// block becomes true because the server said so.
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

    // MARK: - The refusal's own read

    /// **The empty paywall.** A stale `exhausted == false` reading, a 402, and
    /// a usage GET that fails with it — one bad minute on the server produces
    /// all three, so they correlate rather than compose independently. Under
    /// the ordinary keep-the-last-reading rule the budget stayed `open`, the
    /// basis was therefore `nil`, and the sheet opened with no title, no
    /// explanation, and a meter contradicting the refusal — with `onChange`
    /// unable to dismiss it, because `open` never *transitioned*.
    ///
    /// The refusal's read invalidates instead: `unknown`, which has words.
    func testAFailedForcedRefreshAfterARefusalDropsTheStaleOpenReading() async {
        struct Boom: Error {}
        let stale = CoachingUsage(usedPercent: 40, exhausted: false, resetsAt: nil)
        usageClient.results = [.success(stale), .failure(Boom())]

        await viewModel.refreshUsage()
        XCTAssertEqual(viewModel.budget, .open, "the stale reading the refusal is about to disprove")

        let flag = PresentationFlag()
        let gate = PaywallGate(
            subscriptions: viewModel,
            isPresented: Binding(get: { flag.value }, set: { flag.value = $0 })
        )
        await gate.handleBudgetExhausted()

        XCTAssertTrue(flag.value, "the 402 opens the sheet")
        XCTAssertEqual(viewModel.usageReading, .unavailable, "the refusal disproved the reading; a failed re-read must not keep it")
        XCTAssertEqual(viewModel.budget, .unknown, "not open: the 402 is the authority")
        XCTAssertNotNil(viewModel.coachingBasis, "and the sheet therefore has a basis")
        let copy = PaywallCopy(basisOrOpen: viewModel.coachingBasis)
        XCTAssertFalse(copy.title.isEmpty)
        XCTAssertFalse(copy.detail.isEmpty)
        XCTAssertEqual(copy.detail, "You've used your coaching allowance.")
    }

    /// **The stranded paywall.** The forced re-read succeeds and honestly
    /// reports the budget open — the refusal has been disproved, so there is
    /// no block to explain. Presenting anyway is worse than useless: the
    /// budget is already `.open` *before* `present()`, `PaywallView`'s
    /// `onChange` has no `initial: true` and so has no transition left to
    /// observe, and the sheet would sit there saying "Coaching is paused"
    /// over an unspent meter with "Not now" as its only exit. The gate
    /// declines to open it.
    func testARefusalDisprovedByItsOwnReReadOpensNoSheet() async {
        let stale = CoachingUsage(usedPercent: 40, exhausted: false, resetsAt: nil)
        let fresh = CoachingUsage(usedPercent: 40, exhausted: false, resetsAt: nil)
        usageClient.results = [.success(stale), .success(fresh)]

        await viewModel.refreshUsage()
        XCTAssertEqual(viewModel.budget, .open)

        let flag = PresentationFlag()
        let gate = PaywallGate(
            subscriptions: viewModel,
            isPresented: Binding(get: { flag.value }, set: { flag.value = $0 })
        )
        await gate.handleBudgetExhausted()

        XCTAssertEqual(viewModel.budget, .open, "the server's answer stands")
        XCTAssertNil(viewModel.coachingBasis, "an open budget names no basis")
        XCTAssertFalse(flag.value, "and a sheet with nothing to explain and no way out is not opened")
    }

    /// The **other** way in. "See options" calls `present()` directly, with no
    /// re-read in front of it, so a tap that races a meter refresh (scene
    /// activation re-reads usage) would open the same stranded sheet. The rule
    /// is the funnel's, not the 402 path's: `present()` declines an open
    /// budget whoever asks.
    func testASeeOptionsTapOverAnOpenBudgetOpensNoSheet() async {
        let unspent = CoachingUsage(usedPercent: 40, exhausted: false, resetsAt: nil)
        usageClient.results = [.success(unspent)]
        await viewModel.refreshUsage()
        XCTAssertEqual(viewModel.budget, .open)

        let flag = PresentationFlag()
        let gate = PaywallGate(
            subscriptions: viewModel,
            isPresented: Binding(get: { flag.value }, set: { flag.value = $0 })
        )
        gate.present()

        XCTAssertFalse(flag.value, "a tap has no more right to strand the sheet than a refusal does")
    }

    /// The same tap over a budget that *is* spent still opens the sheet — the
    /// rule at the funnel declines one verdict, it does not close the door.
    func testASeeOptionsTapOverASpentBudgetOpensTheSheet() async {
        let spent = CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: resetDate)
        usageClient.results = [.success(spent)]
        await viewModel.refreshUsage()
        XCTAssertEqual(viewModel.budget, .spent)

        let flag = PresentationFlag()
        let gate = PaywallGate(
            subscriptions: viewModel,
            isPresented: Binding(get: { flag.value }, set: { flag.value = $0 })
        )
        gate.present()

        XCTAssertTrue(flag.value)
    }

    /// And over a meter with no answer at all: `.unknown` has copy of its own,
    /// so the tap is answered rather than swallowed.
    func testASeeOptionsTapWithNoReadingOpensTheSheet() {
        let flag = PresentationFlag()
        let gate = PaywallGate(
            subscriptions: viewModel,
            isPresented: Binding(get: { flag.value }, set: { flag.value = $0 })
        )
        XCTAssertEqual(viewModel.budget, .unknown, "nothing has been read yet")

        gate.present()

        XCTAssertTrue(flag.value)
    }

    /// A forced read that *succeeds* is still just the server's answer: the
    /// refusal invalidates the old reading, it does not invent a block.
    func testAForcedRefreshThatSucceedsPublishesWhatTheServerSaid() async {
        let fresh = CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: resetDate)
        usageClient.results = [.success(fresh)]

        await viewModel.refreshUsageAfterRefusal()

        XCTAssertEqual(viewModel.usageReading, .ready(fresh))
        XCTAssertEqual(viewModel.budget, .spent)
        XCTAssertEqual(viewModel.coachingBasis, .period(resetsAt: resetDate))
    }

    /// The RFC 119 rule the fix must **not** have broken: an ordinary refresh
    /// that fails keeps the reading already on screen. Nothing has disproved
    /// it, and yanking a meter off a screen the student is reading is the
    /// behaviour that rule exists to prevent.
    func testAnOrdinaryFailedRefreshStillKeepsTheReadingOnScreen() async {
        struct Boom: Error {}
        let reading = CoachingUsage(usedPercent: 40, exhausted: false, resetsAt: nil)
        usageClient.results = [.success(reading), .failure(Boom())]

        await viewModel.refreshUsage()
        await viewModel.refreshUsage()

        XCTAssertEqual(viewModel.usageReading, .ready(reading), "only the post-refusal read invalidates")
        XCTAssertEqual(viewModel.budget, .open)
    }

    /// Whatever the meter says, the modal has words. Every reachable
    /// budget/basis combination — including the budget going open while the
    /// sheet is already up, in the frames before `onChange` dismisses it —
    /// renders a title and a detail. An empty sheet is never a rendering.
    func testTheSheetAlwaysHasATitleAndADetail() {
        let dates: [Date?] = [nil, resetDate]
        for budget in [CoachingBudget.unknown, .open, .spent] {
            for date in dates {
                let basis = CoachingBasis(budget: budget, resetsAt: date)
                let copy = PaywallCopy(basisOrOpen: basis)
                XCTAssertEqual(copy.title, PaywallCopy.pausedTitle, "\(budget)/\(String(describing: date))")
                XCTAssertFalse(copy.detail.isEmpty, "\(budget)/\(String(describing: date))")
                if budget == .open {
                    XCTAssertNil(basis, "an open budget still names no basis")
                    XCTAssertNotEqual(
                        copy.detail, PaywallCopy(basis: .unknown).detail,
                        "an open meter is not told its allowance is spent; the open case has words of its own"
                    )
                }
            }
        }
    }

    // MARK: - The offer's retry

    /// "Try again" retries the **price**, and nothing else. `load()` would also
    /// re-post the newest entitlement to `/verify` — a binding call with no
    /// business firing because a StoreKit price fetch failed.
    func testTheOfferRetryFetchesOnlyTheProductAndNeverPostsVerify() async {
        struct Boom: Error {}
        usageClient.results = [.success(CoachingUsage(usedPercent: 10, exhausted: false, resetsAt: nil))]
        store.entitlements = [transaction]
        recorder.outcome = .recorded(expiredSubscription())
        store.productResult = .failure(Boom())

        await viewModel.load()
        XCTAssertEqual(viewModel.offer, .unavailable, "the price fetch failed, which is what the button is for")
        XCTAssertEqual(viewModel.productReading, .unavailable)
        let postsAfterLoad = recorder.recorded.count
        let usageReadsAfterLoad = usageClient.callCount

        store.productResult = .success(product)
        await viewModel.refreshProduct()

        XCTAssertEqual(viewModel.productReading, .ready(product))
        XCTAssertEqual(viewModel.offer, .subscribe(product))
        XCTAssertEqual(store.productCallCount, 2, "the one thing that failed was retried")
        XCTAssertEqual(recorder.recorded.count, postsAfterLoad, "and no /verify POST fired")
        XCTAssertEqual(usageClient.callCount, usageReadsAfterLoad, "nor a usage re-read")
    }
}

/// A `Binding`'s storage for a test: a reference box, so the gate's write is
/// visible to the assertion without capturing a mutable local in an escaping
/// closure.
@MainActor
private final class PresentationFlag {
    var value = false
}
