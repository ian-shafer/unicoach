import Foundation
import os

@MainActor
class OnboardingViewModel: ObservableObject {
    enum Precision: Hashable {
        case year
        case yearMonth
        case full
    }

    /// Selectable-year window relative to the initialization year.
    static let yearWindowBack = 4
    static let yearWindowForward = 8

    /// The months a year has. Named because three things read it — the picker's
    /// options, the clamp, and the month-name lookup — and a bare `1 ... 12`
    /// retyped at each is how they come to disagree.
    static let monthRange = 1 ... 12

    /// Gregorian **explicitly**, not `Calendar.current`: the wire format is an
    /// ISO-8601 proleptic-Gregorian date, so a device set to the Japanese or
    /// Buddhist calendar must not change what "February 2028" is. Display
    /// symbols still come from the user's own calendar — that is a reading, not
    /// arithmetic.
    private static let calendar = Calendar(identifier: .gregorian)

    /// Year, not `.full`: a US high-school student reliably knows the year and
    /// often not the month, so the honest default is also the cheapest first
    /// paint — one row instead of three. `PartialDate` has accepted `YYYY`
    /// since RFC 42, so nothing downstream changes.
    @Published var precision: Precision = .year
    @Published var year: Int {
        didSet { clampDay() }
    }
    @Published var month: Int = monthRange.lowerBound {
        didSet { clampMonth() }
    }
    @Published var day: Int = 1 {
        didSet { clampDay() }
    }
    /// The two optional answers, `nil` until the student picks one. `nil` means
    /// UNANSWERED, never declined: this screen writes values or writes nothing
    /// (RFC 163). Declining is a chat action, taken knowingly and permanently.
    @Published var residencyState: ResidencyState?
    @Published var incomeBand: IncomeBand?
    @Published var isLoading = false
    @Published var errorResponse: ErrorResponse?

    private let studentClient: StudentClientProtocol
    private let moneyProfileClient: MoneyProfileClientProtocol
    private let onComplete: () -> Void
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "OnboardingViewModel")

    init(
        studentClient: StudentClientProtocol,
        moneyProfileClient: MoneyProfileClientProtocol,
        onComplete: @escaping () -> Void,
        year: Int
    ) {
        self.studentClient = studentClient
        self.moneyProfileClient = moneyProfileClient
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

    /// Create the student profile, then — only if an optional answer was given —
    /// write the money profile, then complete.
    ///
    /// The order is forced by the server: `PUT .../money-profile` answers
    /// `409 student_profile_required` when no student row exists. The date is
    /// required, so a failed create stops here with the error on screen.
    func submit() async {
        errorResponse = nil
        isLoading = true
        defer { isLoading = false }

        guard await ensureStudentProfile() else { return }

        await updateMoneyProfileIfAnswered()
        onComplete()
    }

    /// Whether the account has a student profile once this returns — `true` if
    /// this call created it **or** it already existed. `false` means the error
    /// is on screen and nothing further should run.
    ///
    /// `student_already_exists` is the ordinary outcome of an idempotent create,
    /// not a failure: the required data is captured either way, and the
    /// money-profile write's `409` precondition is satisfied either way. It
    /// arrives as a thrown `ErrorResponse` because that is how this REST surface
    /// reports it, so a **scoped** `catch ... where` tells the two outcomes
    /// apart in the pattern rather than inside the error arm.
    private func ensureStudentProfile() async -> Bool {
        let request = CreateStudentRequest(expectedHighSchoolGraduationDate: isoDate)
        do {
            _ = try await studentClient.createStudent(request: request)
            return true
        } catch let error as ErrorResponse where error.knownCode == .studentAlreadyExists {
            // Worth a line: this account reached onboarding with a profile
            // already on the server.
            logger.debug("Student profile already existed: [\(error.message, privacy: .public)]")
            return true
        } catch let error as ErrorResponse {
            logger.error("Student creation failed: code=[\(error.code, privacy: .public)] message=[\(error.message, privacy: .public)]")
            errorResponse = error
            return false
        } catch {
            // `ErrorResponse.unexpected` is the app's single fallback for a
            // failure with no decodable server error (`Models.swift`). The real
            // cause goes to the log, where it is diagnosable, rather than into a
            // banner as a raw `localizedDescription`.
            logger.error("Student creation failed (unexpected): [\(error, privacy: .public)]")
            errorResponse = .unexpected
            return false
        }
    }

    /// The optional half of `submit()`, and the one place in this app where an
    /// error is deliberately dropped.
    ///
    /// Nothing chosen means **no request at all**: an all-`unanswered` row and no
    /// row are the same fact, and the absent row is the cheaper one.
    ///
    /// A failure is logged and swallowed because the student profile already
    /// exists and the required data is captured; failing the screen here would
    /// trade a required outcome for an optional one. The answers stay
    /// `unanswered`, so the coach's own `precision_offer` raises them again —
    /// which is exactly why this screen may never treat them as required.
    ///
    /// Only `.set` is ever constructed here: a decline is not representable from
    /// this screen, because `MoneyProfileFieldUpdate` makes "answered" and
    /// "declined" different cases and onboarding writes one of them (RFC 163).
    private func updateMoneyProfileIfAnswered() async {
        guard residencyState != nil || incomeBand != nil else {
            return
        }
        do {
            _ = try await moneyProfileClient.update(
                UpdateMoneyProfileRequest(
                    income: incomeBand.map(MoneyProfileFieldUpdate.set),
                    residency: residencyState.map(MoneyProfileFieldUpdate.set)
                )
            )
        } catch let error as ErrorResponse {
            // This log is the ONLY record that the write happened and failed —
            // the screen completes and says nothing — so it carries what is
            // needed to diagnose it without a reproduction: the status, the
            // field errors (a `validation_failed` here is diagnosable by
            // nothing else), and which answers were dropped.
            logger.error("""
                Money profile write failed and was swallowed: \
                code=[\(error.code, privacy: .public)] \
                status=[\(error.status.map(String.init) ?? "none", privacy: .public)] \
                message=[\(error.message, privacy: .public)] \
                fieldErrors=[\(Self.flattened(error.fieldErrors), privacy: .public)] \
                dropped residencyState=[\(self.droppedResidency, privacy: .public)] \
                incomeBand=[\(self.droppedIncome, privacy: .public)]
                """)
        } catch {
            logger.error("""
                Money profile write failed and was swallowed (unexpected): [\(error, privacy: .public)] \
                dropped residencyState=[\(self.droppedResidency, privacy: .public)] \
                incomeBand=[\(self.droppedIncome, privacy: .public)]
                """)
        }
    }

    /// The two answers this screen was writing, for the swallow log above. They
    /// are not secrets — they are what the student just chose in a form — and a
    /// dropped write that names neither the field nor the value cannot be acted
    /// on.
    private var droppedResidency: String { residencyState?.wireValue ?? "unanswered" }
    private var droppedIncome: String { incomeBand?.wireValue ?? "unanswered" }

    /// `fieldErrors` as one flat `field: message` list for that same line.
    private static func flattened(_ fieldErrors: [FieldError]?) -> String {
        guard let fieldErrors, !fieldErrors.isEmpty else { return "none" }
        return fieldErrors.map { "\($0.field): \($0.message)" }.joined(separator: "; ")
    }


    /// The day, held inside the month it belongs to — at **both** ends, because
    /// `2028-06-00` is as sendable a string as `2028-06-31`. A picker cannot
    /// offer 0, but the property is public state and `dayRange` is the
    /// authority, not whichever control happens to be bound to it.
    private func clampDay() {
        let range = dayRange
        let clamped = min(max(day, range.lowerBound), range.upperBound)
        if day != clamped {
            day = clamped
        }
    }

    /// The month, held inside the year — and then the day re-clamped, because a
    /// shorter month can strand it.
    ///
    /// This clamp is the whole reason `daysInMonth` can trust its input.
    /// `setMonth` used to be the only writer and did the guarding; RFC 163
    /// deleted it and bound `month` straight to a picker, which made the
    /// property itself the only place the invariant can live.
    private func clampMonth() {
        let clamped = min(max(month, Self.monthRange.lowerBound), Self.monthRange.upperBound)
        if month != clamped {
            // Re-entrant by design: the assignment fires this `didSet` again,
            // finds the value already in range, and falls through to the clamp
            // below on the second pass.
            month = clamped
            return
        }
        clampDay()
    }

    /// The length of `month` in `year`, from `Calendar` rather than a
    /// hand-written table.
    ///
    /// The hand-written table this replaces carried its own leap rule and a
    /// `default: 31` arm that answered for month 13. Calendrical arithmetic is a
    /// solved, notoriously fiddly primitive and is not this app's to reimplement.
    ///
    /// `RandomFixtures` keeps its own three-line `Calendar` call for the same
    /// question, because this one is private to a `@MainActor` view model and a
    /// fixture builder cannot reach it. Two callers of `Calendar` is not the
    /// duplication that mattered; two hand-written leap rules was.
    private func daysInMonth(year: Int, month: Int) -> Int {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = 1
        guard let firstOfMonth = Self.calendar.date(from: components),
              let range = Self.calendar.range(of: .day, in: .month, for: firstOfMonth) else {
            // Unreachable: `month` is clamped to `monthRange` and every `Int`
            // year is representable. The permissive answer is deliberate — a
            // shorter fallback would silently rewrite a day the user did pick.
            return 31
        }
        return range.count
    }
}
