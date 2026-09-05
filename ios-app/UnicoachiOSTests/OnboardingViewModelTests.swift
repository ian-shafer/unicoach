import XCTest
@testable import UnicoachiOS

@MainActor
class OnboardingViewModelTests: XCTestCase {
    /// The two states these tests name. `ResidencyState` has a `fileprivate`
    /// init, so an offered value is the only value there is — the test cannot
    /// invent "XX" any more than the screen can.
    static let california = ResidencyStates.offered.first { $0.code == "CA" }!
    static let newYork = ResidencyStates.offered.first { $0.code == "NY" }!

    var mockStudentClient: MockStudentClient!
    var mockMoneyProfileClient: MockMoneyProfileClient!

    override func setUp() async throws {
        try await super.setUp()
        mockStudentClient = MockStudentClient()
        mockMoneyProfileClient = MockMoneyProfileClient()
    }

    private func makeViewModel(year: Int = 2028, onComplete: @escaping () -> Void = {}) -> OnboardingViewModel {
        OnboardingViewModel(
            studentClient: mockStudentClient,
            moneyProfileClient: mockMoneyProfileClient,
            onComplete: onComplete,
            year: year
        )
    }

    private func makeStudent() -> PublicStudent {
        PublicStudent(
            id: UUID(),
            expectedHighSchoolGraduationDate: "2028-06-15",
            version: 1,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0)
        )
    }

    func testIsoDateYearPrecision() {
        let viewModel = makeViewModel()
        viewModel.precision = .year
        XCTAssertEqual(viewModel.isoDate, "2028")
    }

    func testIsoDateYearMonthPrecision() {
        let viewModel = makeViewModel()
        viewModel.precision = .yearMonth
        viewModel.month = 6
        XCTAssertEqual(viewModel.isoDate, "2028-06")
    }

    func testIsoDateFullPrecision() {
        let viewModel = makeViewModel()
        viewModel.precision = .full
        viewModel.month = 6
        viewModel.day = 15
        XCTAssertEqual(viewModel.isoDate, "2028-06-15")
    }

    func testDayRangeLeapFebruary() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.month = 2
        XCTAssertEqual(viewModel.dayRange, 1...29)
    }

    func testDayRangeNonLeapFebruary() {
        let viewModel = makeViewModel(year: 2027)
        viewModel.month = 2
        XCTAssertEqual(viewModel.dayRange, 1...28)
    }

    func testDayRangeApril() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.month = 4
        XCTAssertEqual(viewModel.dayRange, 1...30)
    }

    func testDayRangeJanuary() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.month = 1
        XCTAssertEqual(viewModel.dayRange, 1...31)
    }

    func testDayClampsWhenMonthShortens() {
        let viewModel = makeViewModel(year: 2027)
        viewModel.month = 1
        viewModel.day = 31
        viewModel.month = 2
        XCTAssertEqual(viewModel.day, 28)
    }

    func testSubmitSuccessInvokesComplete() async {
        var completed = false
        let captured = CapturingStudentClient()
        captured.createStudentResult = .success(makeStudent())
        let viewModel = OnboardingViewModel(
            studentClient: captured,
            moneyProfileClient: mockMoneyProfileClient,
            onComplete: { completed = true },
            year: 2028
        )
        viewModel.precision = .full
        viewModel.month = 6
        viewModel.day = 15

        XCTAssertFalse(viewModel.isLoading)
        await viewModel.submit()

        XCTAssertEqual(captured.lastRequest?.expectedHighSchoolGraduationDate, "2028-06-15")
        XCTAssertTrue(completed)
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertFalse(viewModel.isLoading)
    }

    // Deterministic coverage of all three wire precisions through submit(),
    // independent of (and complementary to) the randomized RandomFixtures draws.
    func testSubmitEmitsCanonicalStringForEachPrecision() async {
        let cases: [(name: String, configure: (OnboardingViewModel) -> Void, expected: String)] = [
            ("year", { $0.precision = .year }, "2028"),
            ("yearMonth", { $0.precision = .yearMonth; $0.month = 6 }, "2028-06"),
            ("full", { $0.precision = .full; $0.month = 6; $0.day = 12 }, "2028-06-12"),
        ]

        for testCase in cases {
            let captured = CapturingStudentClient()
            captured.createStudentResult = .success(makeStudent())
            let viewModel = OnboardingViewModel(
                studentClient: captured,
                moneyProfileClient: MockMoneyProfileClient(),
                onComplete: {},
                year: 2028
            )
            testCase.configure(viewModel)

            await viewModel.submit()

            XCTAssertEqual(
                captured.lastRequest?.expectedHighSchoolGraduationDate,
                testCase.expected,
                "precision \(testCase.name) should submit \(testCase.expected)"
            )
        }
    }

    func testSubmitAlreadyExistsInvokesComplete() async {
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .failure(ErrorResponse(code: "student_already_exists", message: "Exists", fieldErrors: nil))

        await viewModel.submit()

        XCTAssertTrue(completed)
        XCTAssertNil(viewModel.errorResponse)
    }

    func testSubmitValidationErrorSetsError() async {
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .failure(ErrorResponse(code: "validation_error", message: "Bad", fieldErrors: nil))

        await viewModel.submit()

        XCTAssertFalse(completed)
        XCTAssertEqual(viewModel.errorResponse?.code, "validation_error")
    }

    func testSubmitUnknownErrorMapsToTheSharedFallback() async {
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .failure(NSError(domain: "test", code: 1, userInfo: nil))

        await viewModel.submit()

        XCTAssertFalse(completed)
        // The app's declared fallback for a failure with no decodable server
        // error, not a code minted at the call site.
        XCTAssertEqual(viewModel.errorResponse, .unexpected)
    }

    // MARK: - Default precision and the year window (RFC 163)

    /// The regression this RFC exists to prevent: `.full` as the default put
    /// month and day on screen at first paint for a student who usually knows
    /// only the year.
    func testDefaultPrecisionIsYear() {
        XCTAssertEqual(makeViewModel().precision, .year)
    }

    func testYearRangeSpansTheWindowAroundTheInitializationYear() {
        let viewModel = makeViewModel(year: 2028)
        XCTAssertEqual(viewModel.yearRange, 2024...2036)
        XCTAssertEqual(OnboardingViewModel.yearWindowBack, 4)
        XCTAssertEqual(OnboardingViewModel.yearWindowForward, 8)
    }

    /// Assigning the year clamps the day, which matters now that the year is a
    /// menu the student can move after picking Feb 29 — and the clamp is on the
    /// property, so a `Picker` bound straight to `$viewModel.year` cannot route
    /// around it.
    func testAssigningTheYearClampsTheDay() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.precision = .full
        viewModel.month = 2
        viewModel.day = 29
        XCTAssertEqual(viewModel.day, 29)

        viewModel.year = 2027

        XCTAssertEqual(viewModel.day, 28)
        XCTAssertEqual(viewModel.isoDate, "2027-02-28")
    }

    /// The same clamp on the other input a picker binds directly. Before the
    /// `didSet` moved onto the property, `month` was plain settable state and
    /// only the (now deleted) `setMonth` clamped, so a `Picker` bound to
    /// `$viewModel.month` could produce 31 February.
    func testAssigningTheMonthClampsTheDay() {
        let viewModel = makeViewModel(year: 2027)
        viewModel.precision = .full
        viewModel.month = 1
        viewModel.day = 31

        viewModel.month = 2

        XCTAssertEqual(viewModel.day, 28)
        XCTAssertEqual(viewModel.isoDate, "2027-02-28")
    }

    /// The lower half of the clamp. `dayRange` starts at 1, and only the upper
    /// bound was ever checked — so `day = 0` produced the ISO string
    /// `2028-06-00` and sent it to the server.
    func testAssigningADayBelowTheRangeClampsToTheFirst() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.precision = .full
        viewModel.month = 6

        viewModel.day = 0

        XCTAssertEqual(viewModel.day, 1)
        XCTAssertEqual(viewModel.isoDate, "2028-06-01")
    }

    /// The month clamp, at both ends. The hand-written month-length table this
    /// RFC deleted answered `31` for month 13, so `2028-13-31` was reachable
    /// the moment `setMonth` stopped being the only writer.
    func testAssigningAMonthOutsideTheYearClampsIntoIt() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.precision = .full

        viewModel.month = 13
        XCTAssertEqual(viewModel.month, 12)
        XCTAssertEqual(viewModel.isoDate, "2028-12-01")

        viewModel.month = 0
        XCTAssertEqual(viewModel.month, 1)
        XCTAssertEqual(viewModel.isoDate, "2028-01-01")
    }

    /// Month lengths now come from `Calendar`, so the leap rule is not this
    /// app's to get wrong. 2000 is a leap year (divisible by 400) and 1900 is
    /// not (divisible by 100) — the two cases a naive rule misses.
    func testDayRangeFollowsTheCalendarsLeapRule() {
        let leapCentury = makeViewModel(year: 2000)
        leapCentury.month = 2
        XCTAssertEqual(leapCentury.dayRange, 1...29)

        let commonCentury = makeViewModel(year: 1900)
        commonCentury.month = 2
        XCTAssertEqual(commonCentury.dayRange, 1...28)

        let september = makeViewModel(year: 2028)
        september.month = 9
        XCTAssertEqual(september.dayRange, 1...30)
    }

    // MARK: - The optional money-profile answers (RFC 163)

    func testSubmitWithNeitherOptionalAnsweredNeverCallsTheMoneyProfileClient() async {
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .success(makeStudent())

        await viewModel.submit()

        // An all-`unanswered` row and no row are the same fact; the absent row
        // is the cheaper one, so there must be no request at all.
        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 0)
        XCTAssertTrue(completed)
    }

    func testSubmitWithOnlyStateAnsweredSendsStateAndNoBand() async {
        let viewModel = makeViewModel()
        mockStudentClient.createStudentResult = .success(makeStudent())
        viewModel.residencyState = Self.california

        await viewModel.submit()

        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 1)
        XCTAssertEqual(mockMoneyProfileClient.lastUpdateRequest?.residency, .set(Self.california))
        XCTAssertNil(mockMoneyProfileClient.lastUpdateRequest?.income)
    }

    func testSubmitWithOnlyIncomeAnsweredSendsBandAndNoState() async {
        let viewModel = makeViewModel()
        mockStudentClient.createStudentResult = .success(makeStudent())
        viewModel.incomeBand = .k48To75k

        await viewModel.submit()

        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 1)
        XCTAssertEqual(mockMoneyProfileClient.lastUpdateRequest?.income, .set(.k48To75k))
        XCTAssertNil(mockMoneyProfileClient.lastUpdateRequest?.residency)
    }

    /// Onboarding may never spend a permanent decision the student did not take.
    func testSubmitNeverSendsADeclinedOrClearFlag() async {
        let viewModel = makeViewModel()
        mockStudentClient.createStudentResult = .success(makeStudent())
        viewModel.residencyState = Self.newYork
        viewModel.incomeBand = .under30k

        await viewModel.submit()

        // The WHOLE request in one assertion: field-by-field checks pass while
        // a field nobody thought to name goes out set.
        XCTAssertEqual(
            mockMoneyProfileClient.lastUpdateRequest,
            UpdateMoneyProfileRequest(income: .set(.under30k), residency: .set(Self.newYork))
        )
    }

    /// The one deliberately dropped error: the required outcome already holds
    /// and the optional answers are re-askable, so the screen completes.
    func testMoneyProfileFailureStillCompletesAndSurfacesNoError() async {
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .success(makeStudent())
        mockMoneyProfileClient.updateResult = .failure(
            ErrorResponse(code: "service_unavailable", message: "Down", fieldErrors: nil)
        )
        viewModel.residencyState = Self.california

        await viewModel.submit()

        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 1)
        XCTAssertTrue(completed)
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertFalse(viewModel.isLoading)
    }

    /// `student_already_exists` proves the student row IS there, so the PUT's
    /// `409 student_profile_required` precondition is satisfied and the write
    /// is still attempted.
    func testStudentAlreadyExistsStillAttemptsTheMoneyProfileWrite() async {
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .failure(
            ErrorResponse(code: "student_already_exists", message: "Exists", fieldErrors: nil)
        )
        viewModel.incomeBand = .over110k

        await viewModel.submit()

        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 1)
        XCTAssertEqual(mockMoneyProfileClient.lastUpdateRequest?.income, .set(.over110k))
        XCTAssertTrue(completed)
    }

    /// Any other create failure is fatal to the screen: the date is required, so
    /// nothing completes and the optional write is never attempted (it could
    /// only answer 409 anyway).
    func testCreateFailureSkipsTheMoneyProfileWriteAndDoesNotComplete() async {
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .failure(
            ErrorResponse(code: "validation_failed", message: "Bad", fieldErrors: nil)
        )
        viewModel.residencyState = Self.california

        await viewModel.submit()

        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 0)
        XCTAssertFalse(completed)
        XCTAssertEqual(viewModel.errorResponse?.knownCode, .validationFailed)
    }
}

private final class CapturingStudentClient: StudentClientProtocol, @unchecked Sendable {
    var createStudentResult: Result<PublicStudent, Error>?
    var fetchProfileResult: Result<PublicStudent?, Error>?
    nonisolated(unsafe) var lastRequest: CreateStudentRequest?

    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent {
        lastRequest = request
        switch createStudentResult! {
        case .success(let student): return student
        case .failure(let error): throw error
        }
    }

    func fetchProfile() async throws -> PublicStudent? {
        switch fetchProfileResult! {
        case .success(let student): return student
        case .failure(let error): throw error
        }
    }
}
