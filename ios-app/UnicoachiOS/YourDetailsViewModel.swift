import Foundation
import os

/// One field of the money profile as this screen writes it. Two of the
/// server's three, because "Your details" asks the two questions that change
/// every number the coach shows (RFC 171); the living plan is asked in chat,
/// where it is situational.
enum MoneyProfileField: String, CaseIterable, Identifiable, Sendable {
    case income
    case residency

    var id: String { rawValue }

    /// The field's own name, as the screen says it. "State or territory" and
    /// not "State": the served menu lists Guam, and a menu that lists Guam must
    /// not call Guam a state. The label is this screen's whole answer to that —
    /// it needs no per-entry kind from the server to say it.
    var title: String {
        switch self {
        case .income: return "Household income"
        case .residency: return "State or territory of residence"
        }
    }
}

/// What the profile says about one field. The tri-state is the screen's whole
/// subject, so it is modelled outright rather than as a value plus a flag: a
/// declined field and an unanswered one differ in what the coach will do next,
/// and only a distinct case keeps them from collapsing into "no value".
enum FieldAnswer: Equatable {
    case unanswered
    case answered(String)
    case declined
}

/// The one logger for the wire-to-screen hop this file owns. Shared by
/// `FieldAnswer` and `MoneyProfileAnswers`, which record the same class of
/// drift — a persisted answer this build cannot read.
private let yourDetailsModelLogger = Logger.unicoach(category: "YourDetails")

extension FieldAnswer {
    /// The one place a wire status becomes a screen state. The status arrives
    /// **decoded** — `PublicMoneyProfile` keeps the raw column and publishes it
    /// through a `known…` accessor — so a status string cannot be passed where
    /// a value string belongs. A status this build has no case for arrives as
    /// `nil` and reads as "not answered" rather than throwing: the vocabulary
    /// is the server's, and a newer one must not blank the screen.
    ///
    /// `answered` with no value cannot happen (the schema's own CHECK), and if
    /// it ever did, "not answered" is the honest reading of it — but not a
    /// silent one: the field is logged, because a profile that answers with
    /// nothing is a server-side fault nobody can act on unrecorded.
    init(field: MoneyProfileField, status: AnswerStatus?, value: String?) {
        switch status {
        case .answered:
            guard let value else {
                yourDetailsModelLogger.error("""
                    Answered money-profile field carries no value, reading as unanswered: \
                    field=[\(field.rawValue, privacy: .public)]
                    """)
                self = .unanswered
                return
            }
            self = .answered(value)
        case .declined:
            self = .declined
        case .undecided:
            // RFC 205 §4, stated explicitly and never folded into `declined`:
            // "I don't know yet" and "I'd rather not say" are different
            // sentences. A MONEY field can never hold it — the database's
            // three-value CHECKs refuse it — so reaching here means the server
            // wrote a status this column may not hold. The screen degrades to
            // "not answered" (the honest reading of a value with no meaning
            // here) and says so in the log rather than silently.
            yourDetailsModelLogger.error("""
                Money-profile field carries the intended-subjects-only status \
                [\(AnswerStatus.undecided.rawValue, privacy: .public)], reading as unanswered: \
                field=[\(field.rawValue, privacy: .public)]
                """)
            self = .unanswered
        case .unanswered, .none:
            self = .unanswered
        }
    }
}

/// Both fields, as the screen renders them. Read-only: the only way to a
/// different `MoneyProfileAnswers` is to build one from a profile the server just
/// answered with, which is what `adopt` does.
struct MoneyProfileAnswers: Equatable {
    let income: FieldAnswer
    let residency: FieldAnswer

    /// The one place `404` — no profile row yet — becomes "nothing answered
    /// yet". The server creates the row on the first write (RFC 134), so a
    /// family that has never answered has no row at all, and that is a
    /// legitimate loaded screen rather than an error or an empty state.
    init(profile: PublicMoneyProfile?) {
        guard let profile else {
            self.init(income: .unanswered, residency: .unanswered)
            return
        }
        // The raw column is only in scope here, so this is where an
        // unreadable status is recorded: the typed accessor below hands
        // `FieldAnswer` a `nil` that no longer says what the server wrote.
        Self.logUnreadableStatus(.income, status: profile.incomeBandStatus, value: profile.incomeBand)
        Self.logUnreadableStatus(.residency, status: profile.residencyStatus, value: profile.residencyState)
        self.init(
            income: FieldAnswer(field: .income, status: profile.knownIncomeBandStatus, value: profile.incomeBand),
            residency: FieldAnswer(field: .residency, status: profile.knownResidencyStatus, value: profile.residencyState)
        )
    }

    /// Records a persisted status this build has no case for. The screen still
    /// degrades to "not answered" — a newer server vocabulary must not blank
    /// the screen — but silently doing so is how a family is told they never
    /// answered a field they did answer. The raw status is `.public` (it is a
    /// closed server vocabulary, not a family's answer); the value itself is
    /// NOT logged, only whether one was present.
    private static func logUnreadableStatus(_ field: MoneyProfileField, status: String, value: String?) {
        guard AnswerStatus(rawValue: status) == nil else { return }
        yourDetailsModelLogger.error("""
            Unrecognised money-profile status, reading as unanswered: \
            field=[\(field.rawValue, privacy: .public)] \
            status=[\(status, privacy: .public)] \
            hasValue=[\(value != nil, privacy: .public)]
            """)
    }

    init(income: FieldAnswer, residency: FieldAnswer) {
        self.income = income
        self.residency = residency
    }

    func answer(_ field: MoneyProfileField) -> FieldAnswer {
        switch field {
        case .income: return income
        case .residency: return residency
        }
    }
}

/// The screen's load state — the `CollegeListState` shape, minus its `.empty`.
/// There is deliberately **no empty case**: all-unanswered is a legitimate
/// loaded profile, and the screen's whole job is to offer the two controls that
/// change it, so a dedicated "nothing here" state would hide exactly the
/// affordance the family came for.
enum YourDetailsState: Equatable {
    case loading
    case loaded(MoneyProfileAnswers)
    case failed(ErrorResponse)
}

/// One row of a picker: the typed value that will be written, and the label the
/// **server** gives it. The two travel together because the label is display
/// copy the server owns (RFC 165) while the value is the wire vocabulary this
/// app already types, and a picker that carried only one of them would either
/// invent copy or send a raw string.
struct DetailOption<Value: MoneyProfileFieldValue & Hashable>: Identifiable, Hashable {
    let value: Value
    let label: String

    var id: String { value.wireValue }
}

/// Where the picker vocabularies came from. A fallback list is a degradation,
/// not a silent equivalent: the shipped Swift lists are narrower than the
/// server's (51 jurisdictions against 59), so the screen says so rather than
/// letting a family conclude their territory is not offered at all.
enum VocabularySource: Equatable {
    case served
    case fallback
}

private extension DSSubjectChip {
    /// The chip's identity IS the subject slug: chips are built from
    /// `PublicSubject.slug`, and that slug is what the server stores and what a
    /// write sends back. Named here, on the screen that knows the domain, so no
    /// write path reads `\.id` and silently means "slug"; `DSSubjectChip`
    /// itself stays domain-free.
    var subjectSlug: String { id }
}

/// The student's answer to "may people you share with see what you want to
/// study?" (RFC 251 §5), as the control renders it.
///
/// `notAsked` is a case of its own rather than a `nil` choice because it is
/// the default every student starts in and the screen SAYS it, while
/// `unreadable` — a status this build has no case for, or no entry for the
/// permission at all — says nothing: printing "nobody sees them" over a grant
/// this build merely cannot read would be the false statement this screen
/// exists to avoid.
enum SharingAnswer: Equatable {
    case notAsked
    case chosen(SharingChoice)
    case unreadable

    /// The segment the control shows selected, or `nil` for none.
    var choice: SharingChoice? {
        guard case .chosen(let choice) = self else { return nil }
        return choice
    }
}

@MainActor
final class YourDetailsViewModel: ObservableObject {
    @Published private(set) var state: YourDetailsState = .loading

    /// Per-action failure channel, separate from `state` on the
    /// `CollegeListViewModel` precedent: `.failed` replaces the whole screen
    /// and is reserved for the initial load, while a failed save keeps the
    /// screen — and the family's selection — and surfaces here.
    @Published var actionError: ErrorResponse?

    /// The field whose save just succeeded, if any. A receipt beside the field
    /// it belongs to, cleared on the next edit or after `receiptDuration`
    /// (RFC 171 §6). Nothing is said in chat: the coach recomposes the money
    /// block from the database on every turn, so there is nothing to tell it.
    @Published private(set) var savedField: MoneyProfileField?

    @Published private(set) var incomeOptions: [DetailOption<IncomeBand>] = []
    @Published private(set) var residencyOptions: [DetailOption<ResidencyState>] = []
    @Published private(set) var vocabularySource: VocabularySource = .served

    /// What each picker shows. Held separately from `state` because a failed
    /// save must keep the family's choice on screen while the stored answer is
    /// still the old one (RFC 171 §7) — only a `200` moves `state`.
    @Published private(set) var selectedIncome: DetailOption<IncomeBand>?
    @Published private(set) var selectedResidency: DetailOption<ResidencyState>?

    /// The fields with a write in flight. Not `@Published`, and not read by the
    /// view: the controls stay live and never grey out (RFC 171 §4, D5). It
    /// exists only so a second action on the SAME field while its write is
    /// still open is ignored, rather than sending a second request whose
    /// response would then race the first one into `state`.
    private var writing: Set<MoneyProfileField> = []

    /// What the student says they want to study, as the row renders it
    /// (RFC 205 §7), and whether the searchable list behind it can be reached.
    ///
    /// `DSSubjectsAnswer` rather than a list plus two flags: a decline and an
    /// "I don't know yet" set in CHAT must be visible here and undoable here,
    /// and only a four-case value keeps them from arriving as an empty list.
    @Published private(set) var subjectsAnswer: DSSubjectsAnswer = .unanswered
    @Published private(set) var subjectsAvailability: DSListAvailability = .available

    /// True while a subjects write is open, for the reason `writing` exists on
    /// the money fields: the request is an absolute statement of the whole
    /// answer and is safe to repeat, but two responses adopting in an order
    /// nobody chose is not.
    private var writingSubjects = false

    /// Whether people the student shares with may see their subjects (RFC 251
    /// §5). Off until the student says yes: `notAsked` hides them exactly as
    /// `refused` does.
    @Published private(set) var sharingAnswer: SharingAnswer = .notAsked

    /// True while a permission write is open — the `writingSubjects` rule.
    private var writingSharing = false

    private let moneyProfileClient: MoneyProfileClientProtocol
    private let vocabularyClient: VocabularyClientProtocol
    private let subjectsClient: SubjectsClientProtocol
    private let intendedSubjectsClient: IntendedSubjectsClientProtocol
    private let sharingPermissionsClient: SharingPermissionsClientProtocol
    private let onProfileRequired: () -> Void
    private let receiptDuration: Duration
    private var receiptTask: Task<Void, Never>?
    private let logger = Logger.unicoach(category: "YourDetailsViewModel")

    /// `receiptDuration` is injected so a test can assert the receipt clears
    /// itself without spending three real seconds doing it.
    init(
        moneyProfileClient: MoneyProfileClientProtocol,
        vocabularyClient: VocabularyClientProtocol,
        subjectsClient: SubjectsClientProtocol,
        intendedSubjectsClient: IntendedSubjectsClientProtocol,
        sharingPermissionsClient: SharingPermissionsClientProtocol,
        onProfileRequired: @escaping () -> Void,
        receiptDuration: Duration = .seconds(3)
    ) {
        self.moneyProfileClient = moneyProfileClient
        self.vocabularyClient = vocabularyClient
        self.subjectsClient = subjectsClient
        self.intendedSubjectsClient = intendedSubjectsClient
        self.sharingPermissionsClient = sharingPermissionsClient
        self.onProfileRequired = onProfileRequired
        self.receiptDuration = receiptDuration
    }

    // MARK: - Load

    /// Reads the profile and the vocabularies **concurrently**, and degrades on
    /// the vocabulary alone: a served list this app failed to fetch costs it
    /// the extra jurisdictions and the server's own ordering, which is a worse
    /// menu, not a broken screen. A failed profile read is different — the
    /// screen would be showing answers it does not have — so it fails.
    func load() async {
        state = .loading
        async let profileOutcome = fetchProfile()
        async let vocabularies = fetchVocabularies()
        async let subjectListReachable = fetchSubjectListReachability()
        async let subjectsOutcome = fetchIntendedSubjects()
        async let sharingOutcome = fetchSharingPermissions()
        let profile = await profileOutcome
        let subjects = await subjectsOutcome
        let sharing = await sharingOutcome

        // The vocabularies are applied FIRST and unconditionally: `adopt` selects
        // each picker by matching the stored wire value against these option
        // lists, so a profile adopted before them lands on an empty menu and both
        // pickers render unselected over answers the family did give.
        applyVocabularies(await vocabularies)
        subjectsAvailability = await subjectListReachable ? .available : .unavailable

        // Any of the three answer reads failing fails the screen, and for the
        // same reason: this screen's whole job is to show what unicoach
        // believes about a family, and a screen that renders "Not answered yet"
        // over an answer it merely could not read tells them something false.
        // The LIST is different — that degrades, because it is a menu and not
        // an answer.
        //
        // Both outcomes are already typed, so they are SWITCHED rather than
        // re-thrown: `student_profile_required` is a state this screen expects
        // — the student has no profile yet, so this screen is the wrong one —
        // and routing on it here keeps it out of the error channel entirely.
        // The profile is named first in the failure patterns so that when
        // several reads failed, the profile's error is the one shown. The
        // sharing permission is an answer in the same sense (RFC 251 §5):
        // showing "nobody sees them" over an unread grant would be false.
        switch (profile, subjects, sharing) {
        case (.success(let loadedProfile), .success(let loadedSubjects), .success(let loadedSharing)):
            adopt(MoneyProfileAnswers(profile: loadedProfile))
            adopt(loadedSubjects)
            adopt(sharing: loadedSharing)
        case (.failure(let error), _, _) where error.knownCode == .studentProfileRequired,
             (_, .failure(let error), _) where error.knownCode == .studentProfileRequired,
             (_, _, .failure(let error)) where error.knownCode == .studentProfileRequired:
            onProfileRequired()
        case (.failure(let error), _, _), (_, .failure(let error), _), (_, _, .failure(let error)):
            state = .failed(error)
        }
    }

    /// One answer read for `load`: the value, or the error the screen fails on.
    ///
    /// Every failure is logged HERE, not where the screen decides what to
    /// show: when several reads fail, `load`'s tuple pattern binds only the
    /// first — deliberately, the profile's error is the one shown — so the
    /// others would otherwise vanish with no trace anywhere, which is the worst
    /// case for diagnosing a report from a student. Errors the client layer did
    /// not convert are logged with their cause, because `.unexpected` carries
    /// none and this is the last place the real one is in hand.
    private func readAnswer<Answer>(
        _ what: String,
        _ read: () async throws -> Answer
    ) async -> Result<Answer, ErrorResponse> {
        do {
            return .success(try await read())
        } catch let error as ErrorResponse {
            logger.error("""
                \(what, privacy: .public) read failed: code=[\(error.code, privacy: .public)] \
                status=[\(error.status ?? -1, privacy: .public)]
                """)
            return .failure(error)
        } catch {
            logger.error("\(what, privacy: .public) read failed (unexpected): [\(error, privacy: .public)]")
            return .failure(.unexpected)
        }
    }

    private func fetchProfile() async -> Result<PublicMoneyProfile?, ErrorResponse> {
        await readAnswer("Money profile") { try await moneyProfileClient.fetch() }
    }

    /// Reads the student's stored answer. Failure fails the screen, like the
    /// profile read — see `load`.
    private func fetchIntendedSubjects() async -> Result<PublicIntendedSubjects, ErrorResponse> {
        await readAnswer("Intended subjects") { try await intendedSubjectsClient.fetch() }
    }

    /// Reads the sharing permissions. Failure fails the screen — see `load`.
    private func fetchSharingPermissions() async -> Result<[PublicSharingPermission], ErrorResponse> {
        await readAnswer("Sharing permissions") { try await sharingPermissionsClient.fetch() }
    }

    /// Whether the subject list could be reached, once, at load.
    ///
    /// It decides only what the row SAYS, never what the row OFFERS: the add
    /// affordance survives a failed read, because the picker refetches the
    /// list on every open and one flaky read must not remove the only control
    /// that could retry it — or the only way to undo a decline.
    ///
    /// The row has to be able to SAY "the list is unavailable" while it is on
    /// screen, before anybody opens the picker, so the only way to know is
    /// to ask. It is the same request the picker sheet makes when it opens, and
    /// the same cost as the vocabulary read beside it; there is no shipped
    /// fallback list to consult instead, by design (RFC 205 §7).
    ///
    /// The subjects themselves are deliberately DROPPED: caching them here
    /// would be a second, staler copy of a list the sheet fetches for itself
    /// every time it opens.
    private func fetchSubjectListReachability() async -> Bool {
        do {
            _ = try await subjectsClient.search(query: nil)
            return true
        } catch {
            logger.error("Subject list unreachable, the row will say so: [\(error, privacy: .public)]")
            return false
        }
    }

    private func fetchVocabularies() async -> VocabulariesResponse? {
        do {
            return try await vocabularyClient.fetch()
        } catch {
            logger.error("Vocabulary fetch failed, falling back to the shipped lists: [\(error, privacy: .public)]")
            return nil
        }
    }

    /// Builds both pickers. **The order is the server's and this method sorts
    /// nothing** (RFC 165): the entries are walked as served.
    ///
    /// Each vocabulary falls back **on its own** — a document that serves the
    /// bands but not the jurisdictions still gets the served bands — and the
    /// screen says "may be incomplete" if either one fell back. This method
    /// only orchestrates; each list is built by its own method below.
    ///
    /// A vocabulary is usable only if it yields a COMPLETE menu. An absent key,
    /// an empty array, or a list carrying one entry this build cannot render
    /// all degrade the WHOLE list to the shipped one: a picker missing a band
    /// is worse than the shipped list, because it is short while still claiming
    /// to be the server's, and the family is told nothing.
    private func applyVocabularies(_ response: VocabulariesResponse?) {
        let servedIncome = servedIncomeOptions(response)
        let servedResidency = servedResidencyOptions(response)
        incomeOptions = servedIncome ?? IncomeBand.allCases.map { DetailOption(value: $0, label: $0.bracket) }
        residencyOptions = servedResidency ?? ResidencyStates.offered.map { DetailOption(value: $0, label: $0.name) }
        vocabularySource = servedIncome == nil || servedResidency == nil ? .fallback : .served
        guard vocabularySource == .fallback else { return }
        // A failed fetch already logged its own cause (`fetchVocabularies`);
        // this line is the other road to the same degraded screen, and without
        // it a report of "menus may be incomplete" cannot tell the two apart.
        logger.error("""
            Vocabulary fallback in use, the shipped lists are narrower than the server's: \
            incomeBandsFellBack=[\(servedIncome == nil, privacy: .public)] \
            residencyStatesFellBack=[\(servedResidency == nil, privacy: .public)] \
            version=[\(response?.version ?? "none", privacy: .public)] \
            served=[\(response?.vocabularies.keys.sorted().joined(separator: ",") ?? "none", privacy: .public)]
            """)
    }

    /// The served bands, or `nil` when this build cannot render them whole.
    ///
    /// A served band this build has no case for cannot be written — the write
    /// path is typed on `IncomeBand` — so it cannot be offered; and dropping
    /// just that one would leave a menu that is short while still claiming to
    /// be the server's. The list therefore degrades as a unit, and
    /// `vocabularySource` says so. An empty served array is no menu either.
    private func servedIncomeOptions(_ response: VocabulariesResponse?) -> [DetailOption<IncomeBand>]? {
        guard let entries = response?.entries(.incomeBands), !entries.isEmpty else { return nil }
        let options = entries.compactMap { entry in
            IncomeBand(rawValue: entry.value).map { DetailOption(value: $0, label: entry.label) }
        }
        guard options.count == entries.count else {
            let dropped = entries.filter { IncomeBand(rawValue: $0.value) == nil }.map(\.value)
            logger.error("""
                Served income bands this build has no case for, falling back to the shipped list: \
                vocabulary=[\(VocabulariesResponse.Name.incomeBands.rawValue, privacy: .public)] \
                skipped=[\(dropped.joined(separator: ","), privacy: .public)] \
                served=[\(entries.count, privacy: .public)]
                """)
            return nil
        }
        return options
    }

    /// The served jurisdictions, or `nil` when this build cannot render them
    /// whole. A jurisdiction is built straight from the served entry, so all 59
    /// are offered where the shipped list has 51 — but only in the shape
    /// `ResidencyState` declares, so a malformed served code is refused here
    /// rather than becoming a menu row the server's own `PUT` answers with a
    /// 400 the family cannot act on. One malformed entry degrades the whole
    /// list, for the same reason a missing band does. An empty served array is
    /// no menu at all, and takes the shipped 51 as well.
    private func servedResidencyOptions(_ response: VocabulariesResponse?) -> [DetailOption<ResidencyState>]? {
        guard let entries = response?.entries(.residencyStates), !entries.isEmpty else { return nil }
        let options = entries.compactMap { entry in
            ResidencyState(served: entry).map { DetailOption(value: $0, label: entry.label) }
        }
        guard options.count == entries.count else {
            let dropped = entries.filter { ResidencyState(served: $0) == nil }.map(\.value)
            logger.error("""
                Served residency codes this build cannot accept, falling back to the shipped list: \
                vocabulary=[\(VocabulariesResponse.Name.residencyStates.rawValue, privacy: .public)] \
                skipped=[\(dropped.joined(separator: ","), privacy: .public)] \
                served=[\(entries.count, privacy: .public)]
                """)
            return nil
        }
        return options
    }

    // MARK: - Writes

    /// Answers a field with a value the family picked from the menu.
    ///
    /// The field is claimed **first**. A pick made while that field's write is
    /// still open is dropped whole, picker included, so the menu never shows a
    /// value no request ever carried.
    ///
    /// Once claimed, the selection is adopted before the request, so a failure
    /// leaves the family's choice on screen to retry rather than snapping back
    /// to the old answer (RFC 171 §7).
    func answer(income option: DetailOption<IncomeBand>) async {
        guard claim(.income) else { return }
        selectedIncome = option
        await send(.income, UpdateMoneyProfileRequest(income: .set(option.value)))
    }

    func answer(residency option: DetailOption<ResidencyState>) async {
        guard claim(.residency) else { return }
        selectedResidency = option
        await send(.residency, UpdateMoneyProfileRequest(residency: .set(option.value)))
    }

    /// "Prefer not to say": the field is closed and the coach stops asking it.
    /// Permanent only until the family says otherwise — `remove` is offered on
    /// a declined field too, so this is never a one-way door (RFC 171 §4).
    func decline(_ field: MoneyProfileField) async {
        guard claim(field) else { return }
        await send(field, request(field, .declined))
    }

    /// "Remove my answer": the field returns to *unanswered*, which is what the
    /// coach may invite again. It is the only undo there is — the row keeps no
    /// memory of a decline (`MoneyProfilesDao.kt:167-179`), so clearing is both
    /// "forget my answer" and "you may ask me again".
    func remove(_ field: MoneyProfileField) async {
        guard claim(field) else { return }
        await send(field, request(field, .clear))
    }

    /// Exactly one field's keys per request. The server takes an absolute
    /// per-field statement and answers with the whole post-write profile, so a
    /// single-field write is both the smallest honest request and a complete
    /// read — there is no Save button and no dirty state to lose (RFC 171 §3).
    private func request(_ field: MoneyProfileField, _ write: ValuelessWrite) -> UpdateMoneyProfileRequest {
        switch (field, write) {
        case (.income, .declined): return UpdateMoneyProfileRequest(income: .declined)
        case (.income, .clear): return UpdateMoneyProfileRequest(income: .clear)
        // `residencyDeclined` / `residencyClear`, not `residencyState*`: the
        // server's booleans drop the `State`, and the request's own
        // `CodingKeys` is the one place that asymmetry is spelled.
        case (.residency, .declined): return UpdateMoneyProfileRequest(residency: .declined)
        case (.residency, .clear): return UpdateMoneyProfileRequest(residency: .clear)
        }
    }

    /// The two writes that carry no value. Spelled as a type rather than a
    /// `Bool` so the four combinations above are exhaustive and named.
    private enum ValuelessWrite {
        case declined
        case clear
    }

    // MARK: - Writes: what you want to study (RFC 205)

    /// Adds one subject to the answer. The write states the WHOLE set, because
    /// that is the only operation the route has: there is no add-one endpoint,
    /// and inventing one client-side would be a request the server cannot
    /// honour (RFC 205 §3).
    ///
    /// The cap is not re-enforced here: the screen stops OFFERING a fourth
    /// subject (`DSSubjectsRowPresentation`), and a fourth that arrives anyway
    /// is the server's 400, surfaced with its reason.
    func addSubject(_ subject: PublicSubject) async {
        var slugs = chosenSlugs
        guard !slugs.contains(subject.slug) else { return }
        slugs.append(subject.slug)
        await sendSubjects(.subjects(slugs))
    }

    /// Removes one subject. Removing the LAST one submits the empty set, and
    /// the server answers `undecided` rather than `unanswered` (D17) — we do
    /// not re-arm an invitation the family already answered. The client does
    /// not translate that; it adopts what comes back.
    func removeSubject(_ chip: DSSubjectChip) async {
        let slugs = chosenSlugs.filter { $0 != chip.subjectSlug }
        guard slugs.count != chosenSlugs.count else { return }
        await sendSubjects(.subjects(slugs))
    }

    /// "I don't know yet" — a real answer, recorded as one, and undoable by
    /// choosing a subject afterwards.
    func markSubjectsUndecided() async {
        await sendSubjects(.undecided)
    }

    /// "Prefer not to say". Durable, and undoable from this screen: the add
    /// affordance stays, so answering it outright is one tap (RFC 205 §7).
    func declineSubjects() async {
        await sendSubjects(.declined)
    }

    /// The slugs currently on the answer, in the order the server gave them.
    private var chosenSlugs: [String] {
        guard case .answered(let chips) = subjectsAnswer else { return [] }
        return chips.map(\.subjectSlug)
    }

    /// The slugs the picker must not offer again — a duplicate is a 400 the
    /// student cannot act on.
    var chosenSubjectSlugs: Set<String> { Set(chosenSlugs) }

    private func sendSubjects(_ answer: UpdateIntendedSubjectsRequest.Answer) async {
        guard !writingSubjects else { return }
        writingSubjects = true
        defer { writingSubjects = false }

        await performWrite("Intended subjects") {
            adopt(try await intendedSubjectsClient.update(UpdateIntendedSubjectsRequest(answer)))
        }
    }

    /// The one place a stored answer becomes the row's state. A status this
    /// build has no case for becomes `.unreadable`, NOT `unanswered`: the
    /// vocabulary is the server's, so a value this build cannot read is a real
    /// possibility, and answering it with "Not answered yet" would print a
    /// falsehood over an answer the student gave and invite them to give it
    /// again. It is also logged, because an unreadable answer is a fact nobody
    /// can act on unrecorded.
    ///
    /// `answered` with no subjects cannot happen (the deferred "status iff
    /// subjects" trigger), and if it ever did, it is unreadable too: chips are
    /// what `answered` means on this screen, so an `answered` with none of them
    /// is a shape this build has no honest reading for either.
    private func adopt(_ answer: PublicIntendedSubjects) {
        let chips = answer.subjects.map { DSSubjectChip(id: $0.slug, name: $0.name) }
        switch answer.knownStatus {
        case .answered where !chips.isEmpty:
            subjectsAnswer = .answered(chips)
        case .undecided:
            subjectsAnswer = .undecided
        case .declined:
            subjectsAnswer = .declined
        case .unanswered:
            subjectsAnswer = .unanswered
        case .answered, .none:
            logger.error("""
                Unusable intended-subjects answer, shown as NO answer line: \
                status=[\(answer.status, privacy: .public)] \
                subjects=[\(chips.count, privacy: .public)]
                """)
            subjectsAnswer = .unreadable
        }
    }

    // MARK: - Writes: who can see what you want to study (RFC 251)

    /// Answers the sharing question. Only `granted` or `refused` can be sent —
    /// "never asked" is not an answer, so nothing here can write it.
    ///
    /// Choosing the answer already stored sends nothing. A write while another
    /// is open is dropped, for the reason `writingSubjects` exists. A failed
    /// write leaves the STORED answer on the control: for a consent, showing a
    /// choice the server never recorded would say something false about who
    /// can see the student's subjects.
    func answerSharing(_ choice: SharingChoice) async {
        guard !writingSharing, sharingAnswer != .chosen(choice) else { return }
        writingSharing = true
        defer { writingSharing = false }

        await performWrite(
            "Sharing permission (permission=\(SharingPermission.intendedSubjects.rawValue) status=\(choice.rawValue))"
        ) {
            adopt(sharing: [try await sharingPermissionsClient.update(.intendedSubjects, to: choice)])
        }
    }

    /// The one place a stored permission becomes the control's state. Only the
    /// intended-subjects entry is read; a permission this build does not know
    /// is someone else's question. Exactly one entry is an answer: none, two
    /// (even agreeing), or a status this build has no case for is `unreadable`
    /// and logged with what arrived — never "not asked", and never whichever
    /// of two conflicting entries came first.
    private func adopt(sharing permissions: [PublicSharingPermission]) {
        let entries = permissions.filter { $0.knownPermission == .intendedSubjects }
        let entry = entries.count == 1 ? entries[0] : nil
        switch entry?.knownStatus {
        case .notAsked:
            sharingAnswer = .notAsked
        case .granted:
            sharingAnswer = .chosen(.granted)
        case .refused:
            sharingAnswer = .chosen(.refused)
        case .none:
            let received = permissions.map { "\($0.permission)=\($0.status)" }.joined(separator: ",")
            logger.error("""
                Unusable sharing permission, shown with NO answer: \
                permission=[\(SharingPermission.intendedSubjects.rawValue, privacy: .public)] \
                entries=[\(entries.count, privacy: .public)] \
                received=[\(received, privacy: .public)]
                """)
            sharingAnswer = .unreadable
        }
    }

    /// Claims a field for one write, or reports that one is already open. A
    /// second action on the same field is dropped here — the request itself is
    /// an absolute per-field statement and is safe to repeat; what is not safe
    /// is two responses adopting into `state` in an order nobody chose.
    ///
    /// Every caller claims **before** it moves anything on screen, so a dropped
    /// action leaves no trace at all.
    private func claim(_ field: MoneyProfileField) -> Bool {
        writing.insert(field).inserted
    }

    /// Sends one already-claimed field's write and adopts the server's answer.
    private func send(_ field: MoneyProfileField, _ request: UpdateMoneyProfileRequest) async {
        defer { writing.remove(field) }

        clearReceipt()
        await performWrite("Money profile (field=\(field.rawValue))") {
            adopt(MoneyProfileAnswers(profile: try await moneyProfileClient.update(request)))
            showReceipt(field)
        }
    }

    /// Runs one write on this screen and routes its failure by the screen's
    /// one rule. `student_profile_required` is a routing signal, not an error
    /// to show: the account has no student row yet, which the root state
    /// machine — not this screen — knows how to resolve (the
    /// `CollegeListViewModel` precedent). Any other `ErrorResponse` is shown;
    /// anything else is logged as `what` with its cause and shown as
    /// `.unexpected`. Only a `200` moves state: `write` adopts the server's
    /// answer itself.
    private func performWrite(_ what: String, _ write: () async throws -> Void) async {
        actionError = nil
        do {
            try await write()
        } catch let error as ErrorResponse {
            if error.knownCode == .studentProfileRequired {
                onProfileRequired()
                return
            }
            actionError = error
        } catch {
            logger.error("\(what, privacy: .public) write failed (unexpected): [\(error, privacy: .public)]")
            actionError = .unexpected
        }
    }

    // MARK: - Adopting the server's answer

    /// The server's post-write profile replaces the screen's state wholesale,
    /// pickers included — only a `200` moves state, and what it moves to is
    /// what the server says, never what the client hoped.
    private func adopt(_ details: MoneyProfileAnswers) {
        state = .loaded(details)
        selectedIncome = option(for: details.income, in: incomeOptions)
        selectedResidency = option(for: details.residency, in: residencyOptions)
    }

    private func option<Value: MoneyProfileFieldValue & Hashable>(for answer: FieldAnswer, in options: [DetailOption<Value>]) -> DetailOption<Value>? {
        guard case .answered(let value) = answer else { return nil }
        return options.first { $0.value.wireValue == value }
    }

    /// Shows the receipt beside `field` and takes it away again after
    /// `receiptDuration` (RFC 171 §6).
    private func showReceipt(_ field: MoneyProfileField) {
        clearReceipt()
        savedField = field
        receiptTask = Task { [receiptDuration] in
            try? await Task.sleep(for: receiptDuration)
            // `try?` swallows the cancellation, so the body runs on even when
            // this task was superseded. Without this guard, a receipt cancelled
            // by a second save would resume and blank the NEW field's receipt.
            guard !Task.isCancelled else { return }
            savedField = nil
        }
    }

    /// Takes the receipt away now, cancelling the expiry it was waiting on.
    private func clearReceipt() {
        receiptTask?.cancel()
        receiptTask = nil
        savedField = nil
    }

    // MARK: - Reading the current answer

    /// The line under the field's name, in the three shapes RFC 171 §4 fixes,
    /// or `nil` before a profile is loaded — there is no answer to read yet,
    /// which is a state of its own and not an empty answer, so the view leaves
    /// the line out rather than rendering a blank one.
    ///
    /// An answered value is shown with the label the menu gives it; a value
    /// with no matching option — a band or jurisdiction served after this build
    /// shipped — falls back to the raw code rather than to nothing, because a
    /// family must always be able to see what unicoach believes about them.
    func answerText(_ field: MoneyProfileField) -> String? {
        guard case .loaded(let details) = state else { return nil }
        switch details.answer(field) {
        case .unanswered:
            return "Not answered yet"
        case .declined:
            return "You chose not to say"
        case .answered(let value):
            return label(for: value, field: field) ?? value
        }
    }

    /// Routed through `option(for:in:)` so the rule that decides which option a
    /// stored wire value denotes is spelled in exactly one place.
    private func label(for value: String, field: MoneyProfileField) -> String? {
        let answer = FieldAnswer.answered(value)
        switch field {
        case .income: return option(for: answer, in: incomeOptions)?.label
        case .residency: return option(for: answer, in: residencyOptions)?.label
        }
    }

    /// Both controls are always visible except where they would be a no-op:
    /// declining an already-declined field, or clearing an already-unanswered
    /// one. A declined field is **never greyed out** — answering it outright is
    /// one tap, and so is returning it to unanswered (RFC 171 §4).
    func canDecline(_ field: MoneyProfileField) -> Bool {
        guard case .loaded(let details) = state else { return false }
        return details.answer(field) != .declined
    }

    func canRemove(_ field: MoneyProfileField) -> Bool {
        guard case .loaded(let details) = state else { return false }
        return details.answer(field) != .unanswered
    }
}
