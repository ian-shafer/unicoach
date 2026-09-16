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

    /// The birth-year window: a full century back from the initialization year,
    /// which in production is the current one. It ends AT that year rather than
    /// short of it, because the offered list must not itself state the age rule
    /// — the server owns the floor, and a list that stops thirteen years back
    /// would announce the answer the form is asking for (RFC 222).
    static let birthYearWindowBack = 100

    /// The inline message shown under the date-of-birth rows when the form is
    /// submitted with the date incomplete.
    ///
    /// It names the MISSING ANSWER and nothing else. The age rule's wording is
    /// the server's, shown verbatim in the banner, so this app mints no age
    /// copy of its own (RFC 222).
    static let dateOfBirthRequiredMessage = String(localized: "Select your date of birth.")

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

    /// The year `birthDayRange` asks about while no birth year is chosen. Leap
    /// deliberately — see `birthDayRange`. Any leap year answers the same
    /// question; 2000 is one.
    private static let leapYearStandIn = 2000

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
    /// The date of birth, in three parts, ALL starting empty (RFC 222).
    ///
    /// `nil` rather than a pre-selected year is the whole design of this field:
    /// a screen that opens on a plausible adult birth year invites the answer it
    /// wants, and a neutral age screen is what keeps this product outside
    /// COPPA's consent machinery. Emptiness is therefore a state the form must
    /// carry — hence three optionals and an inline error — not one a default
    /// quietly removes.
    @Published var birthYear: Int? {
        didSet { clampBirthDay() }
    }
    @Published var birthMonth: Int? {
        didSet { clampBirthMonth() }
    }
    @Published var birthDay: Int? {
        didSet { clampBirthDay() }
    }
    /// The inline error under the date-of-birth rows: set only by `submit()`
    /// finding the date incomplete, and cleared at the head of the next submit.
    @Published var dateOfBirthError: String?
    /// The two optional answers, `nil` until the student picks one. `nil` means
    /// UNANSWERED, never declined: this screen writes values or writes nothing
    /// (RFC 163). Declining is a chat action, taken knowingly and permanently.
    @Published var residencyState: ResidencyState?
    @Published var incomeBand: IncomeBand?
    @Published var isLoading = false
    @Published var errorResponse: ErrorResponse?

    /// The year this screen was CONSTRUCTED in — in production the current one
    /// — and the anchor the birth-year menu is built from.
    ///
    /// Deliberately NOT `year`: that one is the graduation year the student
    /// edits, so anchoring on it would let a student who picks graduation 2034
    /// be offered birth years the server rejects as future dates, and one who
    /// picks 2022 lose the four most recent ones. A birth-year menu must not
    /// move when an unrelated field does (RFC 222).
    private let constructionYear: Int

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
        self.constructionYear = year
    }

    var yearRange: ClosedRange<Int> {
        (year - Self.yearWindowBack) ... (year + Self.yearWindowForward)
    }

    var dayRange: ClosedRange<Int> {
        1 ... daysInMonth(year: year, month: month)
    }

    /// A century back from the initialization year, most recent first: the
    /// order a birth-year menu is read in, and the one that keeps a plausible
    /// student's year within a short scroll of the top. No year is selected,
    /// so ordering costs nothing in neutrality — it is a reading order, not a
    /// default (RFC 222).
    var birthYearOptions: [Int] {
        Array(((constructionYear - Self.birthYearWindowBack) ... constructionYear).reversed())
    }

    /// The days the birth-day menu offers. With no birth year chosen yet the
    /// length of February is undecidable, so it is asked of a LEAP year: a
    /// student picking the day before the year must still be able to pick their
    /// own birthday, and `clampBirthDay` takes 29 back if the year they then
    /// choose is a common one.
    var birthDayRange: ClosedRange<Int> {
        1 ... daysInMonth(
            year: birthYear ?? Self.leapYearStandIn,
            month: birthMonth ?? Self.monthRange.lowerBound
        )
    }

    /// The date of birth as the wire's full ISO date, or `nil` while any of the
    /// three parts is unanswered. `nil` is the ONLY thing that stops the
    /// request: there is no half-date to send and none is sent.
    var dateOfBirthISO: String? {
        guard let birthYear, let birthMonth, let birthDay else { return nil }
        return Self.fullISODate(year: birthYear, month: birthMonth, day: birthDay)
    }

    var isoDate: String {
        switch precision {
        case .year:
            return String(format: "%04d", year)
        case .yearMonth:
            return String(format: "%04d-%02d", year, month)
        case .full:
            return Self.fullISODate(year: year, month: month, day: day)
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
        dateOfBirthError = nil

        // Before `isLoading`, and before any client is touched: an incomplete
        // date of birth is refused by the FORM, so a submit with nothing chosen
        // makes no network call at all. The server's age rule judges a date; it
        // has nothing to judge here.
        guard let dateOfBirth = dateOfBirthISO else {
            dateOfBirthError = Self.dateOfBirthRequiredMessage
            return
        }

        isLoading = true
        defer { isLoading = false }

        guard await ensureStudentProfile(dateOfBirth: dateOfBirth) else { return }

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
    /// A refusal on age arrives here as an ordinary `ErrorResponse` — a 403
    /// with the server's own sentence — so the last `catch` arm already does
    /// the right thing: the message goes in the banner verbatim, `onComplete()`
    /// is not called, and the student stays on a form they can correct. No arm
    /// of its own, and no second wording of the rule in Swift (RFC 222).
    private func ensureStudentProfile(dateOfBirth: String) async -> Bool {
        let request = CreateStudentRequest(
            expectedHighSchoolGraduationDate: isoDate,
            dateOfBirth: dateOfBirth
        )
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
            // A refusal that NAMES the date goes to the date. A 400
            // `validation_error` carries its one useful sentence in
            // `fieldErrors`; its `message` is the generic "Invalid student
            // parameters", which tells the student nothing about the answer
            // that was refused. Shown on the same inline control the
            // incomplete-date guard uses (`RegistrationView` precedent).
            // Everything else — the 403 age refusal included, whose sentence
            // IS its `message` — keeps the banner.
            if let fieldMessage = error.fieldError(for: "dateOfBirth") {
                dateOfBirthError = fieldMessage
                return false
            }
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

    /// `value` held inside `range`, at both ends. One shape for all four clamps
    /// below, so that "held inside its range" is written once and cannot drift
    /// between the graduation-date fields and the birth-date ones.
    private static func clamped(_ value: Int, into range: ClosedRange<Int>) -> Int {
        min(max(value, range.lowerBound), range.upperBound)
    }

    /// A full `YYYY-MM-DD` wire date. Named for the precision it builds rather
    /// than `isoDate`, which is the instance property that renders whichever
    /// precision the graduation-date form is on — and calls this for its full
    /// arm.
    private static func fullISODate(year: Int, month: Int, day: Int) -> String {
        String(format: "%04d-%02d-%02d", year, month, day)
    }

    /// The day, held inside the month it belongs to — at **both** ends, because
    /// `2028-06-00` is as sendable a string as `2028-06-31`. A picker cannot
    /// offer 0, but the property is public state and `dayRange` is the
    /// authority, not whichever control happens to be bound to it.
    private func clampDay() {
        let clamped = Self.clamped(day, into: dayRange)
        if day != clamped {
            day = clamped
        }
    }

    /// The birth day, held inside the month it belongs to, over optional state.
    /// An unanswered day has nothing to hold, so `nil` is left alone: it is the
    /// field's starting state, not a value out of range.
    private func clampBirthDay() {
        guard let birthDay else { return }
        let clamped = Self.clamped(birthDay, into: birthDayRange)
        if birthDay != clamped {
            self.birthDay = clamped
        }
    }

    /// The birth month, held inside the year, then the birth day re-clamped,
    /// including the deliberate re-entrancy.
    private func clampBirthMonth() {
        guard let birthMonth else { return }
        let clamped = Self.clamped(birthMonth, into: Self.monthRange)
        if birthMonth != clamped {
            // Re-entrant by design, as in `clampMonth`: the assignment fires this
            // `didSet` again, finds the value already in range, and falls through
            // to `clampBirthDay()` on the second pass.
            self.birthMonth = clamped
            return
        }
        clampBirthDay()
    }

    /// The month, held inside the year — and then the day re-clamped, because a
    /// shorter month can strand it.
    ///
    /// This clamp is the whole reason `daysInMonth` can trust its input.
    /// `setMonth` used to be the only writer and did the guarding; RFC 163
    /// deleted it and bound `month` straight to a picker, which made the
    /// property itself the only place the invariant can live.
    private func clampMonth() {
        let clamped = Self.clamped(month, into: Self.monthRange)
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
