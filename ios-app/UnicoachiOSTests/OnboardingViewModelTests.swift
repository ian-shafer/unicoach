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

    /// A pinned date of birth, comfortably over the floor, written as a
    /// LITERAL. The server owns the age rule against its own clock; a fixture
    /// derived from `Date()` would drift and make these tests say something
    /// different every year (RFC 222).
    static let birthYear = 2009
    static let birthMonth = 7
    static let birthDay = 10
    static let birthDateISO = "2009-07-10"

    /// Every test that expects a request to LEAVE this screen must answer the
    /// date of birth, because an unanswered one is refused by the form before
    /// any client is touched. Defaulted on, so each test says only what it is
    /// about; the tests that are about the empty date pass `false`.
    private func makeViewModel(
        year: Int = 2028,
        dateOfBirthAnswered: Bool = true,
        onComplete: @escaping () -> Void = {}
    ) -> OnboardingViewModel {
        let viewModel = OnboardingViewModel(
            studentClient: mockStudentClient,
            moneyProfileClient: mockMoneyProfileClient,
            onComplete: onComplete,
            year: year
        )
        if dateOfBirthAnswered {
            Self.answerDateOfBirth(viewModel)
        }
        return viewModel
    }

    private static func answerDateOfBirth(_ viewModel: OnboardingViewModel) {
        viewModel.birthYear = birthYear
        viewModel.birthMonth = birthMonth
        viewModel.birthDay = birthDay
    }

    private func makeStudent() -> PublicStudent {
        PublicStudent(
            id: UUID(),
            expectedHighSchoolGraduationDate: "2028-06-15",
            dateOfBirth: Self.birthDateISO,
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
        Self.answerDateOfBirth(viewModel)
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
            Self.answerDateOfBirth(viewModel)
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

    // MARK: - The age floor: date of birth (RFC 222)

    /// The field's whole design in one assertion: nothing is pre-selected. A
    /// default birth year would nudge the answer the form is asking for, and a
    /// nudged age screen is not a neutral one.
    func testDateOfBirthStartsEmpty() {
        let viewModel = makeViewModel(dateOfBirthAnswered: false)

        XCTAssertNil(viewModel.birthYear)
        XCTAssertNil(viewModel.birthMonth)
        XCTAssertNil(viewModel.birthDay)
        XCTAssertNil(viewModel.dateOfBirthISO)
        XCTAssertNil(viewModel.dateOfBirthError)
    }

    /// An unanswered date is the FORM's refusal, not the server's: the inline
    /// error appears and no request is made at all.
    func testSubmitWithNoDateOfBirthShowsTheInlineErrorAndMakesNoRequest() async {
        var completed = false
        let viewModel = makeViewModel(dateOfBirthAnswered: false, onComplete: { completed = true })

        await viewModel.submit()

        XCTAssertEqual(viewModel.dateOfBirthError, OnboardingViewModel.dateOfBirthRequiredMessage)
        XCTAssertEqual(mockStudentClient.createStudentCallCount, 0)
        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 0)
        XCTAssertFalse(completed)
        // The banner is the SERVER's voice on this screen; nothing was asked of
        // it, so it stays silent.
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertFalse(viewModel.isLoading)
    }

    /// Two thirds of a date is no date. A half-answered field is the state a
    /// partial-precision date would have made sendable — this one is not.
    func testSubmitWithAPartialDateOfBirthMakesNoRequest() async {
        let viewModel = makeViewModel(dateOfBirthAnswered: false)
        viewModel.birthYear = Self.birthYear
        viewModel.birthMonth = Self.birthMonth

        await viewModel.submit()

        XCTAssertNil(viewModel.dateOfBirthISO)
        XCTAssertEqual(viewModel.dateOfBirthError, OnboardingViewModel.dateOfBirthRequiredMessage)
        XCTAssertEqual(mockStudentClient.createStudentCallCount, 0)
    }

    /// The wire shape: a FULL ISO date, zero-padded, whatever the menus hold.
    func testSubmitSendsTheDateOfBirthAsAFullIsoDate() async {
        let viewModel = makeViewModel(dateOfBirthAnswered: false)
        mockStudentClient.createStudentResult = .success(makeStudent())
        viewModel.birthYear = 2011
        viewModel.birthMonth = 3
        viewModel.birthDay = 4

        XCTAssertEqual(viewModel.dateOfBirthISO, "2011-03-04")

        await viewModel.submit()

        XCTAssertEqual(mockStudentClient.lastCreateStudentRequest?.dateOfBirth, "2011-03-04")
        XCTAssertEqual(mockStudentClient.createStudentCallCount, 1)
    }

    /// The error is a statement about the LAST submit, so answering the date
    /// and submitting again must clear it.
    func testAnsweringTheDateClearsTheInlineErrorOnTheNextSubmit() async {
        let viewModel = makeViewModel(dateOfBirthAnswered: false)
        mockStudentClient.createStudentResult = .success(makeStudent())

        await viewModel.submit()
        XCTAssertNotNil(viewModel.dateOfBirthError)

        Self.answerDateOfBirth(viewModel)
        await viewModel.submit()

        XCTAssertNil(viewModel.dateOfBirthError)
        XCTAssertEqual(mockStudentClient.lastCreateStudentRequest?.dateOfBirth, Self.birthDateISO)
    }

    /// A century back from the initialization year, inclusive of it, most
    /// recent first. The list must not stop thirteen years back: an offered
    /// range that encoded the floor would state the answer the screen is asking
    /// for.
    func testBirthYearOptionsSpanACenturyBackMostRecentFirst() {
        let viewModel = makeViewModel(year: 2026)

        XCTAssertEqual(viewModel.birthYearOptions.first, 2026)
        XCTAssertEqual(viewModel.birthYearOptions.last, 1926)
        XCTAssertEqual(viewModel.birthYearOptions.count, 101)
        XCTAssertEqual(OnboardingViewModel.birthYearWindowBack, 100)
    }

    /// The menu is anchored on the CONSTRUCTION year, not on the graduation
    /// year the student edits. Moving `year` to 2034 must not offer birth years
    /// the server refuses as future dates, and moving it back must not hide the
    /// most recent ones.
    func testBirthYearOptionsDoNotFollowTheGraduationYear() {
        let viewModel = makeViewModel(year: 2026)

        viewModel.year = 2034

        XCTAssertEqual(viewModel.birthYearOptions.first, 2026)
        XCTAssertEqual(viewModel.birthYearOptions.last, 1926)

        viewModel.year = 2022

        XCTAssertEqual(viewModel.birthYearOptions.first, 2026)
        XCTAssertEqual(viewModel.birthYearOptions.last, 1926)
    }

    /// Picked in any order: with no year chosen, February must still offer the
    /// 29th, or a leap-day student cannot enter their own birthday.
    func testBirthDayRangeOffersTheTwentyNinthBeforeAYearIsChosen() {
        let viewModel = makeViewModel(dateOfBirthAnswered: false)
        viewModel.birthMonth = 2

        XCTAssertEqual(viewModel.birthDayRange, 1...29)
    }

    /// ...and the year, chosen afterwards, takes it back. This is the clamp
    /// that keeps `2011-02-29` off the wire.
    func testChoosingACommonYearClampsFebruaryTwentyNine() {
        let viewModel = makeViewModel(dateOfBirthAnswered: false)
        viewModel.birthMonth = 2
        viewModel.birthDay = 29
        XCTAssertEqual(viewModel.birthDay, 29)

        viewModel.birthYear = 2011

        XCTAssertEqual(viewModel.birthDay, 28)
        XCTAssertEqual(viewModel.dateOfBirthISO, "2011-02-28")
    }

    func testBirthDayClampsWhenTheBirthMonthShortens() {
        let viewModel = makeViewModel(dateOfBirthAnswered: false)
        viewModel.birthYear = 2011
        viewModel.birthMonth = 1
        viewModel.birthDay = 31

        viewModel.birthMonth = 4

        XCTAssertEqual(viewModel.birthDay, 30)
        XCTAssertEqual(viewModel.dateOfBirthISO, "2011-04-30")
    }

    /// Both ends of the month clamp, and the day below the range — the three
    /// out-of-range writes the graduation date already guards, over optional
    /// state.
    func testBirthMonthAndDayAreHeldInRange() {
        let viewModel = makeViewModel(dateOfBirthAnswered: false)
        viewModel.birthYear = 2011

        viewModel.birthMonth = 13
        XCTAssertEqual(viewModel.birthMonth, 12)

        viewModel.birthMonth = 0
        XCTAssertEqual(viewModel.birthMonth, 1)

        viewModel.birthDay = 0
        XCTAssertEqual(viewModel.birthDay, 1)
        XCTAssertEqual(viewModel.dateOfBirthISO, "2011-01-01")
    }

    /// The refusal: a 403 whose sentence is the server's. It reaches the banner
    /// WORD FOR WORD — this app has no age copy of its own — the screen does not
    /// complete, and the optional write is never attempted.
    func testAgeRefusalShowsTheServersOwnMessageAndDoesNotComplete() async {
        let serverMessage = "unicoach is for students who are 13 or older. "
            + "Ask a parent to get in touch if you think this is wrong."
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        viewModel.incomeBand = .k48To75k
        mockStudentClient.createStudentResult = .failure(ErrorResponse(
            code: "age_requirement_not_met",
            message: serverMessage,
            fieldErrors: nil,
            status: 403
        ))

        await viewModel.submit()

        XCTAssertEqual(viewModel.errorResponse?.knownCode, .ageRequirementNotMet)
        XCTAssertEqual(viewModel.errorResponse?.message, serverMessage)
        // The inline error is for the form's own refusal only; the server's
        // belongs in the banner.
        XCTAssertNil(viewModel.dateOfBirthError)
        XCTAssertFalse(completed)
        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 0)
        XCTAssertFalse(viewModel.isLoading)
    }

    /// The other refusal of the same field: a 400 whose useful sentence is in
    /// `fieldErrors`, not in `message`. A banner reading "Invalid student
    /// parameters" names nothing, so the server's sentence is shown inline,
    /// under the date it refuses, and the banner stays empty.
    func testDateOfBirthFieldErrorShowsInlineAndNotInTheBanner() async {
        let serverMessage = "Date of birth cannot be in the future"
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .failure(ErrorResponse(
            code: "validation_error",
            message: "Invalid student parameters",
            fieldErrors: [FieldError(field: "dateOfBirth", message: serverMessage)],
            status: 400
        ))

        await viewModel.submit()

        XCTAssertEqual(viewModel.dateOfBirthError, serverMessage)
        XCTAssertNil(viewModel.errorResponse)
        XCTAssertFalse(completed)
        XCTAssertEqual(mockMoneyProfileClient.updateCallCount, 0)
        XCTAssertFalse(viewModel.isLoading)
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
