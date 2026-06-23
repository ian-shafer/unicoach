import XCTest
@testable import UnicoachiOS

@MainActor
class OnboardingViewModelTests: XCTestCase {
    var mockStudentClient: MockStudentClient!

    override func setUp() async throws {
        try await super.setUp()
        mockStudentClient = MockStudentClient()
    }

    private func makeViewModel(year: Int = 2028, onComplete: @escaping () -> Void = {}) -> OnboardingViewModel {
        OnboardingViewModel(studentClient: mockStudentClient, onComplete: onComplete, year: year)
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
        viewModel.setMonth(6)
        XCTAssertEqual(viewModel.isoDate, "2028-06")
    }

    func testIsoDateFullPrecision() {
        let viewModel = makeViewModel()
        viewModel.precision = .full
        viewModel.setMonth(6)
        viewModel.day = 15
        XCTAssertEqual(viewModel.isoDate, "2028-06-15")
    }

    func testDayRangeLeapFebruary() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.setMonth(2)
        XCTAssertEqual(viewModel.dayRange, 1...29)
    }

    func testDayRangeNonLeapFebruary() {
        let viewModel = makeViewModel(year: 2027)
        viewModel.setMonth(2)
        XCTAssertEqual(viewModel.dayRange, 1...28)
    }

    func testDayRangeApril() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.setMonth(4)
        XCTAssertEqual(viewModel.dayRange, 1...30)
    }

    func testDayRangeJanuary() {
        let viewModel = makeViewModel(year: 2028)
        viewModel.setMonth(1)
        XCTAssertEqual(viewModel.dayRange, 1...31)
    }

    func testDayClampsWhenMonthShortens() {
        let viewModel = makeViewModel(year: 2027)
        viewModel.setMonth(1)
        viewModel.day = 31
        viewModel.setMonth(2)
        XCTAssertEqual(viewModel.day, 28)
    }

    func testSubmitSuccessInvokesComplete() async {
        var completed = false
        let captured = CapturingStudentClient()
        captured.createStudentResult = .success(makeStudent())
        let viewModel = OnboardingViewModel(studentClient: captured, onComplete: { completed = true }, year: 2028)
        viewModel.precision = .full
        viewModel.setMonth(6)
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
            ("yearMonth", { $0.precision = .yearMonth; $0.setMonth(6) }, "2028-06"),
            ("full", { $0.precision = .full; $0.setMonth(6); $0.day = 12 }, "2028-06-12"),
        ]

        for testCase in cases {
            let captured = CapturingStudentClient()
            captured.createStudentResult = .success(makeStudent())
            let viewModel = OnboardingViewModel(studentClient: captured, onComplete: {}, year: 2028)
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

    func testSubmitUnknownErrorMapsToUnknown() async {
        var completed = false
        let viewModel = makeViewModel(onComplete: { completed = true })
        mockStudentClient.createStudentResult = .failure(NSError(domain: "test", code: 1, userInfo: nil))

        await viewModel.submit()

        XCTAssertFalse(completed)
        XCTAssertEqual(viewModel.errorResponse?.code, "UNKNOWN")
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
