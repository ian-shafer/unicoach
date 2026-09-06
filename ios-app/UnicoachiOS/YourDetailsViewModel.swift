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

    private let moneyProfileClient: MoneyProfileClientProtocol
    private let vocabularyClient: VocabularyClientProtocol
    private let onProfileRequired: () -> Void
    private let receiptDuration: Duration
    private var receiptTask: Task<Void, Never>?
    private let logger = Logger.unicoach(category: "YourDetailsViewModel")

    /// `receiptDuration` is injected so a test can assert the receipt clears
    /// itself without spending three real seconds doing it.
    init(
        moneyProfileClient: MoneyProfileClientProtocol,
        vocabularyClient: VocabularyClientProtocol,
        onProfileRequired: @escaping () -> Void,
        receiptDuration: Duration = .seconds(3)
    ) {
        self.moneyProfileClient = moneyProfileClient
        self.vocabularyClient = vocabularyClient
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
        let profile = await profileOutcome

        // The vocabularies are applied FIRST and unconditionally: `adopt` selects
        // each picker by matching the stored wire value against these option
        // lists, so a profile adopted before them lands on an empty menu and both
        // pickers render unselected over answers the family did give.
        applyVocabularies(await vocabularies)

        switch profile {
        case .success(let profile):
            adopt(MoneyProfileAnswers(profile: profile))
        case .failure(let error):
            if error.knownCode == .studentProfileRequired {
                onProfileRequired()
                return
            }
            state = .failed(error)
        }
    }

    private func fetchProfile() async -> Result<PublicMoneyProfile?, ErrorResponse> {
        do {
            return .success(try await moneyProfileClient.fetch())
        } catch let error as ErrorResponse {
            return .failure(error)
        } catch {
            // Only errors the client layer did not convert reach here, and
            // `.unexpected` carries no cause, so this is the last place the
            // real one is in hand.
            logger.error("Money profile read failed (unexpected): [\(error, privacy: .public)]")
            return .failure(.unexpected)
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

        actionError = nil
        clearReceipt()
        do {
            let profile = try await moneyProfileClient.update(request)
            adopt(MoneyProfileAnswers(profile: profile))
            showReceipt(field)
        } catch let error as ErrorResponse {
            // A routing signal, not an error to show: the account has no
            // student row yet, which the root state machine — not this screen —
            // knows how to resolve (the `CollegeListViewModel` precedent).
            if error.knownCode == .studentProfileRequired {
                onProfileRequired()
                return
            }
            actionError = error
        } catch {
            logger.error("""
                Money profile write failed (unexpected): [\(error, privacy: .public)] \
                field=[\(field.rawValue, privacy: .public)]
                """)
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
