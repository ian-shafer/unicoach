import XCTest
@testable import UnicoachiOS

/// The parent home's billing rail (RFC 244): the four states it renders, and
/// the reads that make it say "unavailable" — an absence, an unreadable
/// coverage word, and a coverage whose caption data contradicts it.
@MainActor
final class SupporterCoachingViewModelTests: XCTestCase {
    private var client: MockSupporterCoachingUsageClient!
    private var viewModel: SupporterCoachingViewModel!

    /// A reset date distinct from anything else here, so a state that named the
    /// wrong period could not accidentally match.
    private let periodEnd = Date(timeIntervalSince1970: 1_780_000_000)

    override func setUp() {
        super.setUp()
        client = MockSupporterCoachingUsageClient()
        viewModel = SupporterCoachingViewModel(client: client)
    }

    private func reading(
        kind: CoachingCoverageKind,
        usedPercent: Int,
        exhausted: Bool = false,
        resetsAt: Date? = nil
    ) -> SupporterCoachingUsage {
        SupporterCoachingUsage(
            usage: CoachingUsage(usedPercent: usedPercent, exhausted: exhausted, resetsAt: resetsAt),
            coverage: CoachingCoverage(kind: kind)
        )
    }

    // MARK: - The four rendered states

    func testNothingIsReadBeforeTheFirstLoad() {
        XCTAssertEqual(viewModel.state, .loading)
        XCTAssertEqual(client.callCount, 0)
    }

    /// The server's own body from RFC 244 §6: a fresh account, nothing spent.
    func testAnUnusedFreeAllowanceReadsAsTheAvailableState() async {
        client.results = [.success(reading(kind: .freeAllowance, usedPercent: 0))]

        await viewModel.load()

        XCTAssertEqual(viewModel.state, .freeAllowanceAvailable(usedPercent: 0))
        XCTAssertEqual(client.callCount, 1)
    }

    /// The sentence changes on the server's `exhausted` flag, never on a
    /// percentage compared against 100 in a view.
    func testASpentFreeAllowanceReadsAsSpent() async {
        client.results = [.success(reading(kind: .freeAllowance, usedPercent: 100, exhausted: true))]

        await viewModel.load()

        XCTAssertEqual(viewModel.state, .freeAllowanceSpent)
    }

    /// The arm an account that holds BOTH a student and a supporter row reaches
    /// today: one account, one subscription, metered over its period.
    func testAnOwnSubscriptionReadsAsItsOwnStateAndCarriesThePeriodEnd() async {
        client.results = [.success(reading(kind: .ownSubscription, usedPercent: 64, resetsAt: periodEnd))]

        await viewModel.load()

        XCTAssertEqual(
            viewModel.state,
            .ownSubscription(usedPercent: 64, exhausted: false, resetsAt: periodEnd)
        )
    }

    /// The one pair the wire can state and the server cannot produce: a paid
    /// period with no end. The arm's date is non-optional, so there is nowhere
    /// to put a missing one — and it is NOT drawn under the free tier's
    /// caption, which is what a `nil` date would have captioned it as.
    func testAnOwnSubscriptionWithNoPeriodEndIsUnavailableRatherThanTheFreeTiersCaption() async {
        client.results = [.success(reading(kind: .ownSubscription, usedPercent: 64, resetsAt: nil))]

        await viewModel.load()

        XCTAssertEqual(viewModel.state, .unavailable)
        XCTAssertNotEqual(viewModel.state, .freeAllowanceAvailable(usedPercent: 64))
    }

    // MARK: - The ways the answer is "unavailable"

    /// A refusal — the student-scoped meter's 409, an expired session's 401, a
    /// 500 — renders the unavailable sentence. **Never a 0% bar**, which would
    /// be a claim about this parent's spend that no read supports.
    func testARefusedReadIsUnavailableAndNotAZeroPercentMeter() async {
        client.results = [.failure(ErrorResponse(code: "unauthorized", message: "m", fieldErrors: nil, status: 401))]

        await viewModel.load()

        XCTAssertEqual(viewModel.state, .unavailable)
        XCTAssertNotEqual(viewModel.state, .freeAllowanceAvailable(usedPercent: 0))
    }

    /// The route's `404` is an ordinary answer — "you hold no `supporters` row"
    /// — and a contradiction on a resolved supporter home, so it is no meter
    /// rather than an empty one.
    func testA404IsUnavailableRatherThanAnEmptyMeter() async {
        client.results = [.success(nil)]

        await viewModel.load()

        XCTAssertEqual(viewModel.state, .unavailable)
    }

    /// A coverage word this build has never heard of. The meter beside it
    /// decoded perfectly — and is still not drawn, because the sentence above
    /// it would have to name an allowance this build cannot name. It must not
    /// fall to the commoner answer: that is RFC 232's defect, an unreadable
    /// word becoming a silent default.
    func testAnUnknownCoverageKindIsUnavailableRatherThanTheCommonerAnswer() async {
        client.results = [.success(reading(kind: .unrecognised("family_plan"), usedPercent: 42))]

        await viewModel.load()

        XCTAssertEqual(viewModel.state, .unavailable)
        XCTAssertNotEqual(viewModel.state, .freeAllowanceAvailable(usedPercent: 42))
        // Absence and unreadability are decided SEPARATELY: this read produced
        // a value, and the rendered sentence is the only thing the two share.
        XCTAssertEqual(
            viewModel.reading,
            .ready(reading(kind: .unrecognised("family_plan"), usedPercent: 42)),
            "the unreadable word is kept, not discarded as a failed read"
        )
    }

    // MARK: - Refreshing

    /// The shared `Reading` rule, inherited rather than re-transcribed: a failed
    /// refresh keeps what is on screen. The home re-reads on every return to the
    /// foreground, and one failing read must not blank a section the parent is
    /// looking at.
    func testAFailedRefreshKeepsTheSectionOnScreen() async {
        client.results = [
            .success(reading(kind: .freeAllowance, usedPercent: 20)),
            .failure(ErrorResponse.unexpected),
        ]

        await viewModel.load()
        await viewModel.load()

        XCTAssertEqual(viewModel.state, .freeAllowanceAvailable(usedPercent: 20))
        XCTAssertEqual(client.callCount, 2)
    }

    func testAFreshReadingWins() async {
        client.results = [
            .success(reading(kind: .freeAllowance, usedPercent: 20)),
            .success(reading(kind: .freeAllowance, usedPercent: 100, exhausted: true)),
        ]

        await viewModel.load()
        await viewModel.load()

        XCTAssertEqual(viewModel.state, .freeAllowanceSpent)
    }
}

/// The parent meter's double. Results consumed in order, the last one
/// repeating — the `MockCoachingUsageClient` convention — so a test can say
/// "the first read lands, the second fails".
final class MockSupporterCoachingUsageClient: SupporterCoachingUsageClientProtocol, @unchecked Sendable {
    private let state = Locked(State())

    private struct State {
        var results: [Result<SupporterCoachingUsage?, Error>] = []
        var callCount = 0
    }

    var results: [Result<SupporterCoachingUsage?, Error>] {
        get { state.withLock { $0.results } }
        set { state.withLock { $0.results = newValue } }
    }

    var callCount: Int { state.withLock { $0.callCount } }

    func fetch() async throws -> SupporterCoachingUsage? {
        let next = state.withLock { state -> Result<SupporterCoachingUsage?, Error>? in
            guard !state.results.isEmpty else { return nil }
            let index = min(state.callCount, state.results.count - 1)
            state.callCount += 1
            return state.results[index]
        }
        guard let next else {
            throw ErrorResponse(code: ServerErrorCode.networkError.rawValue, message: "not configured", fieldErrors: nil)
        }
        return try next.get()
    }
}
