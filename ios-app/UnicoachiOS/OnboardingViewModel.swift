import Foundation
import os

@MainActor
class OnboardingViewModel: ObservableObject {
    enum Precision {
        case year
        case yearMonth
        case full
    }

    /// Selectable-year window relative to the initialization year.
    static let yearWindowBack = 4
    static let yearWindowForward = 8

    @Published var precision: Precision = .full
    @Published var year: Int
    @Published var month: Int = 1
    @Published var day: Int = 1 {
        didSet { clampDay() }
    }
    @Published var isLoading = false
    @Published var errorResponse: ErrorResponse?

    private let studentClient: StudentClientProtocol
    private let onComplete: () -> Void
    private let logger = Logger(subsystem: "com.unicoachapp.UnicoachiOS", category: "OnboardingViewModel")

    init(studentClient: StudentClientProtocol, onComplete: @escaping () -> Void, year: Int) {
        self.studentClient = studentClient
        self.onComplete = onComplete
        self.year = year
    }

    var yearRange: ClosedRange<Int> {
        (year - Self.yearWindowBack) ... (year + Self.yearWindowForward)
    }

    var dayRange: ClosedRange<Int> {
        1 ... daysInMonth(year: year, month: month)
    }

    var isoDate: String {
        let paddedMonth = String(format: "%02d", month)
        let paddedDay = String(format: "%02d", day)
        switch precision {
        case .year:
            return String(format: "%04d", year)
        case .yearMonth:
            return "\(String(format: "%04d", year))-\(paddedMonth)"
        case .full:
            return "\(String(format: "%04d", year))-\(paddedMonth)-\(paddedDay)"
        }
    }

    func setMonth(_ newMonth: Int) {
        month = newMonth
        clampDay()
    }

    func setYear(_ newYear: Int) {
        year = newYear
        clampDay()
    }

    func submit() async {
        errorResponse = nil
        isLoading = true
        defer { isLoading = false }

        let request = CreateStudentRequest(expectedHighSchoolGraduationDate: isoDate)
        do {
            _ = try await studentClient.createStudent(request: request)
            onComplete()
        } catch let error as ErrorResponse {
            if error.code == "student_already_exists" {
                onComplete()
            } else {
                logger.error("Student creation failed: code=[\(error.code, privacy: .public)] message=[\(error.message, privacy: .public)]")
                errorResponse = error
            }
        } catch {
            logger.error("Student creation failed (unexpected): [\(error, privacy: .public)]")
            errorResponse = ErrorResponse(code: "UNKNOWN", message: error.localizedDescription, fieldErrors: nil)
        }
    }

    private func clampDay() {
        let upperBound = daysInMonth(year: year, month: month)
        if day > upperBound {
            day = upperBound
        }
    }

    private func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12:
            return 31
        case 4, 6, 9, 11:
            return 30
        case 2:
            return isLeapYear(year) ? 29 : 28
        default:
            return 31
        }
    }

    private func isLeapYear(_ year: Int) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
}
