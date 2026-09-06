import XCTest
@testable import UnicoachiOS

@MainActor
final class YourDetailsViewModelTests: XCTestCase {
    private var moneyProfileClient: MockMoneyProfileClient!
    private var vocabularyClient: MockVocabularyClient!
    private var profileRequiredCount = 0

    override func setUp() {
        super.setUp()
        moneyProfileClient = MockMoneyProfileClient()
        vocabularyClient = MockVocabularyClient()
        profileRequiredCount = 0
    }

    private func makeViewModel() -> YourDetailsViewModel {
        YourDetailsViewModel(
            moneyProfileClient: moneyProfileClient,
            vocabularyClient: vocabularyClient,
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 },
            // Long enough that no test races the receipt away; the receipt's
            // own expiry is not what these tests are about.
            receiptDuration: .seconds(60)
        )
    }

    private func profile(
        income: FieldAnswer = .unanswered,
        residency: FieldAnswer = .unanswered
    ) -> PublicMoneyProfile {
        func projection(_ answer: FieldAnswer) -> (status: String, value: String?) {
            switch answer {
            case .unanswered: return (AnswerStatus.unanswered.rawValue, nil)
            case .declined: return (AnswerStatus.declined.rawValue, nil)
            case .answered(let value): return (AnswerStatus.answered.rawValue, value)
            }
        }
        let incomeProjection = projection(income)
        let residencyProjection = projection(residency)
        return PublicMoneyProfile(
            incomeBandStatus: incomeProjection.status,
            incomeBand: incomeProjection.value,
            residencyStatus: residencyProjection.status,
            residencyState: residencyProjection.value,
            livingPlanStatus: AnswerStatus.unanswered.rawValue,
            livingPlan: nil,
            version: 1,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0)
        )
    }

    private func incomeOption(_ band: IncomeBand, on viewModel: YourDetailsViewModel) throws -> DetailOption<IncomeBand> {
        try XCTUnwrap(viewModel.incomeOptions.first { $0.value == band })
    }

    private func residencyOption(_ code: String, on viewModel: YourDetailsViewModel) throws -> DetailOption<ResidencyState> {
        try XCTUnwrap(viewModel.residencyOptions.first { $0.value.code == code })
    }

    // MARK: - load

    func testNoProfileYetLoadsBothFieldsAsNotAnswered() async {
        // The server's 404 before the first write, which the client maps to nil.
        moneyProfileClient.fetchResult = .success(nil)

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(viewModel.state, .loaded(MoneyProfileAnswers(income: .unanswered, residency: .unanswered)))
        XCTAssertEqual(viewModel.answerText(.income), "Not answered yet")
        XCTAssertEqual(viewModel.answerText(.residency), "Not answered yet")
        XCTAssertNil(viewModel.actionError, "no profile yet is a state, never an error")
    }

    func testLoadRendersEachFieldsOwnStatusAndADeclinedFieldCarriesNoValue() async throws {
        moneyProfileClient.fetchResult = .success(
            profile(income: .answered(IncomeBand.k48To75k.rawValue), residency: .declined)
        )

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(
            viewModel.state,
            .loaded(MoneyProfileAnswers(income: .answered("48k_to_75k"), residency: .declined))
        )
        XCTAssertEqual(viewModel.answerText(.income), "$48,001 to $75,000")
        XCTAssertEqual(viewModel.answerText(.residency), "You chose not to say")
        XCTAssertEqual(viewModel.selectedIncome?.value, .k48To75k)
        XCTAssertNil(viewModel.selectedResidency, "a declined field carries no value to select")
    }

    func testLoadFailureIsTheWholeScreenAndRetriable() async {
        let error = ErrorResponse(code: "SERVER_ERROR", message: "boom", fieldErrors: nil)
        moneyProfileClient.fetchResult = .failure(error)

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(viewModel.state, .failed(error))
    }

    func testLoadProfileRequiredEscalatesInsteadOfFailing() async {
        moneyProfileClient.fetchResult = .failure(
            ErrorResponse(code: "student_profile_required", message: "A student profile is required", fieldErrors: nil)
        )

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(profileRequiredCount, 1)
        if case .failed = viewModel.state {
            XCTFail("a profile gate is a routing signal, not an error to render")
        }
    }

    func testServedVocabularyIsRenderedInTheServersOrderIncludingTerritories() async {
        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(viewModel.vocabularySource, .served)
        XCTAssertEqual(
            viewModel.residencyOptions.map(\.label),
            ["Alabama", "American Samoa", "California", "District of Columbia", "Guam", "New York"],
            "the client sorts nothing: the order is the one the server served"
        )
        XCTAssertTrue(
            viewModel.residencyOptions.contains { $0.value.code == "GU" },
            "the served menu offers jurisdictions the shipped 51-state list does not"
        )
        XCTAssertEqual(viewModel.incomeOptions.map(\.value), IncomeBand.allCases)
    }

    func testAVocabularyEntryWithAnUnknownExtraStillRenders() async throws {
        // Extras are flattened beside `value`/`label` and are an open set; an
        // extra this build has never heard of must be ignored, never fatal.
        let json = Data("""
        {
          "version": "abc",
          "vocabularies": {
            "income_bands": { "entries": [ { "value": "under_30k", "label": "$0 to $30,000", "somethingNew": "x" } ] },
            "residency_states": { "entries": [ { "value": "CA", "label": "California", "jurisdictionKind": "state", "region": "west" } ] }
          }
        }
        """.utf8)
        vocabularyClient.fetchResult = .success(
            try JSONDecoder().decode(VocabulariesResponse.self, from: json)
        )

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(viewModel.vocabularySource, .served)
        XCTAssertEqual(viewModel.incomeOptions.map(\.label), ["$0 to $30,000"])
        XCTAssertEqual(viewModel.residencyOptions.map(\.label), ["California"])
    }

    func testAFailedVocabularyFetchStillRendersBothPickersFromTheFallbackLists() async {
        vocabularyClient.fetchResult = .failure(
            ErrorResponse(code: "NETWORK_ERROR", message: "offline", fieldErrors: nil)
        )

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(viewModel.vocabularySource, .fallback, "the screen says the list may be incomplete")
        XCTAssertEqual(viewModel.incomeOptions.map(\.value), IncomeBand.allCases)
        XCTAssertEqual(viewModel.incomeOptions.map(\.label), IncomeBand.allCases.map(\.bracket))
        XCTAssertEqual(viewModel.residencyOptions.count, ResidencyStates.offered.count)
        if case .failed = viewModel.state {
            XCTFail("a vocabulary failure degrades the menus; it must not fail the screen")
        }
    }

    /// Each vocabulary degrades on its own. A document that serves the bands
    /// but not the jurisdictions used to throw the served bands away too, which
    /// is a worse menu than the server actually offered.
    func testADocumentMissingOneVocabularyKeepsTheOtherAndFallsBackOnlyForTheMissingOne() async {
        vocabularyClient.fetchResult = .success(
            VocabulariesResponse(
                version: "partial",
                vocabularies: [
                    VocabulariesResponse.Name.incomeBands.rawValue: PublicVocabulary(
                        entries: [VocabularyEntry(value: "under_30k", label: "Served band copy")]
                    ),
                ]
            )
        )

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(
            viewModel.incomeOptions.map(\.label), ["Served band copy"],
            "the served bands are the server's, and a missing residency list is no reason to discard them"
        )
        XCTAssertEqual(
            viewModel.residencyOptions.count, ResidencyStates.offered.count,
            "only the vocabulary the server did not serve falls back"
        )
        XCTAssertEqual(
            viewModel.vocabularySource, .fallback,
            "one fallen-back list is enough for the screen to say the menus may be incomplete"
        )
    }

    /// A served list this build cannot render WHOLE is not a better menu than
    /// the shipped one. A picker missing a band is short while still claiming
    /// to be the server's, so the vocabulary degrades as a unit.
    func testAServedBandThisBuildCannotMapFallsBackTheWholeBandList() async {
        vocabularyClient.fetchResult = .success(
            .fixture(bands: VocabularyEntry.bandFixtures + [
                VocabularyEntry(value: "over_500k", label: "Over $500,000"),
            ])
        )

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(
            viewModel.incomeOptions.map(\.value), IncomeBand.allCases,
            "one unmappable band falls back to the shipped list whole, never to a partial menu"
        )
        XCTAssertEqual(viewModel.incomeOptions.map(\.label), IncomeBand.allCases.map(\.bracket))
        XCTAssertEqual(
            viewModel.residencyOptions.map(\.label), VocabularyEntry.stateFixtures.map(\.label),
            "the other vocabulary is unaffected: each list degrades on its own"
        )
        XCTAssertEqual(viewModel.vocabularySource, .fallback, "the screen says the menus may be incomplete")
    }

    /// The same rule for the residency list, whose entries this app types
    /// only by shape: a code that is not the two-letter USPS shape would be a
    /// menu row the server's own `PUT` answers with a 400.
    func testAMalformedServedResidencyCodeFallsBackTheWholeJurisdictionList() async {
        vocabularyClient.fetchResult = .success(
            .fixture(states: VocabularyEntry.stateFixtures + [
                VocabularyEntry(value: "california", label: "California"),
            ])
        )

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(
            viewModel.residencyOptions.count, ResidencyStates.offered.count,
            "one malformed code falls back to the shipped 51, never to a partial served menu"
        )
        XCTAssertEqual(
            viewModel.incomeOptions.map(\.label), VocabularyEntry.bandFixtures.map(\.label),
            "the band list the server did serve whole is kept"
        )
        XCTAssertEqual(viewModel.vocabularySource, .fallback)
    }

    /// A key that is present with no entries is no menu at all — the state
    /// that used to render a picker with zero rows and call it complete.
    func testAnEmptyServedVocabularyFallsBackRatherThanRenderingAnEmptyPicker() async {
        vocabularyClient.fetchResult = .success(.fixture(bands: [], states: []))

        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertEqual(viewModel.incomeOptions.map(\.value), IncomeBand.allCases)
        XCTAssertEqual(viewModel.residencyOptions.count, ResidencyStates.offered.count)
        XCTAssertEqual(viewModel.vocabularySource, .fallback)
    }

    // MARK: - answering

    func testPickingABandSendsExactlyTheIncomeKeyAndAdoptsTheResponse() async throws {
        let viewModel = makeViewModel()
        await viewModel.load()

        await viewModel.answer(income: try incomeOption(.k75To110k, on: viewModel))

        XCTAssertEqual(moneyProfileClient.updateCallCount, 1)
        let request = try XCTUnwrap(moneyProfileClient.lastUpdateRequest)
        XCTAssertEqual(request.income, .set(.k75To110k))
        XCTAssertNil(request.residency, "one control writes one field")
        XCTAssertNil(request.living)
        XCTAssertEqual(viewModel.answerText(.income), "$75,001 to $110,000")
        XCTAssertEqual(viewModel.savedField, .income)
    }

    func testWritingOneFieldNeverSendsTheOtherFieldsKeys() async throws {
        moneyProfileClient.fetchResult = .success(
            profile(income: .answered(IncomeBand.under30k.rawValue), residency: .answered("CA"))
        )
        let viewModel = makeViewModel()
        await viewModel.load()

        await viewModel.answer(residency: try residencyOption("NY", on: viewModel))

        let request = try XCTUnwrap(moneyProfileClient.lastUpdateRequest)
        let newYork = try XCTUnwrap(ResidencyState(served: VocabularyEntry(value: "NY", label: "New York")))
        XCTAssertEqual(request.residency, .set(newYork))
        XCTAssertNil(request.income, "an already-answered income band must not be restated by a residency write")
        XCTAssertNil(request.living)
    }

    // MARK: - declining

    func testPreferNotToSaySendsOnlyTheDeclineFlagForThatField() async throws {
        let viewModel = makeViewModel()
        await viewModel.load()

        await viewModel.decline(.income)
        let incomeRequest = try XCTUnwrap(moneyProfileClient.lastUpdateRequest)
        XCTAssertEqual(incomeRequest.income, .declined)
        XCTAssertNil(incomeRequest.residency)
        XCTAssertEqual(viewModel.answerText(.income), "You chose not to say")

        await viewModel.decline(.residency)
        let residencyRequest = try XCTUnwrap(moneyProfileClient.lastUpdateRequest)
        // The wire key is `residencyDeclined`, not `residencyStateDeclined`:
        // the booleans drop the `State` the value key carries.
        XCTAssertEqual(residencyRequest.residency, .declined)
        XCTAssertNil(residencyRequest.income)
        XCTAssertEqual(viewModel.answerText(.residency), "You chose not to say")
    }

    func testADeclinedFieldOffersBothUndoDirectionsAndNeverTheRedundantOne() async {
        moneyProfileClient.fetchResult = .success(profile(income: .declined))
        let viewModel = makeViewModel()
        await viewModel.load()

        XCTAssertFalse(viewModel.canDecline(.income), "declining a declined field is a no-op")
        XCTAssertTrue(viewModel.canRemove(.income), "a decline is never a one-way door")
        XCTAssertTrue(viewModel.canDecline(.residency))
        XCTAssertFalse(viewModel.canRemove(.residency), "clearing an unanswered field is a no-op")
    }

    // MARK: - undo, both directions

    func testDeclinedBecomesAnsweredByPickingAValue() async throws {
        moneyProfileClient.fetchResult = .success(profile(income: .declined))
        let viewModel = makeViewModel()
        await viewModel.load()

        await viewModel.answer(income: try incomeOption(.over110k, on: viewModel))

        let request = try XCTUnwrap(moneyProfileClient.lastUpdateRequest)
        XCTAssertEqual(request.income, .set(.over110k))
        XCTAssertEqual(viewModel.state, .loaded(MoneyProfileAnswers(income: .answered("over_110k"), residency: .unanswered)))
    }

    func testDeclinedBecomesUnansweredByRemovingTheAnswer() async throws {
        moneyProfileClient.fetchResult = .success(profile(income: .declined))
        let viewModel = makeViewModel()
        await viewModel.load()

        await viewModel.remove(.income)

        let request = try XCTUnwrap(moneyProfileClient.lastUpdateRequest)
        XCTAssertEqual(request.income, .clear, "the wire says incomeBandClear: true")
        XCTAssertNil(request.residency)
        XCTAssertEqual(viewModel.answerText(.income), "Not answered yet")
    }

    // MARK: - failure

    func testAFailedSaveKeepsTheSelectionSurfacesTheErrorAndLeavesTheStoredStateAlone() async throws {
        moneyProfileClient.fetchResult = .success(profile(income: .answered(IncomeBand.under30k.rawValue)))
        let viewModel = makeViewModel()
        await viewModel.load()

        let chosen = try incomeOption(.over110k, on: viewModel)
        moneyProfileClient.updateResult = .failure(
            ErrorResponse(code: "SERVER_ERROR", message: "boom", fieldErrors: nil)
        )
        await viewModel.answer(income: chosen)

        XCTAssertEqual(viewModel.selectedIncome, chosen, "a failed save must not snap the picker back")
        XCTAssertEqual(viewModel.actionError?.message, "boom")
        XCTAssertEqual(viewModel.state, .loaded(MoneyProfileAnswers(income: .answered("under_30k"), residency: .unanswered)))
        XCTAssertNil(viewModel.savedField, "nothing was saved, so nothing says so")

        // The retry succeeds and only then does the stored state move.
        moneyProfileClient.updateResult = nil
        await viewModel.answer(income: chosen)

        XCTAssertEqual(viewModel.state, .loaded(MoneyProfileAnswers(income: .answered("over_110k"), residency: .unanswered)))
        XCTAssertNil(viewModel.actionError)
        XCTAssertEqual(viewModel.savedField, .income)
    }

    func testProfileRequiredOnASaveEscalatesAndRendersNoError() async {
        let viewModel = makeViewModel()
        await viewModel.load()

        moneyProfileClient.updateResult = .failure(
            ErrorResponse(code: "student_profile_required", message: "A student profile is required", fieldErrors: nil)
        )
        await viewModel.decline(.residency)

        XCTAssertEqual(profileRequiredCount, 1)
        XCTAssertNil(viewModel.actionError, "a profile gate is routed, not shown")
    }

    // MARK: - the in-flight guard

    /// The undo controls are never greyed out (RFC 171 §4, D5), so a second tap
    /// while the first write is still open is a thing a family can really do.
    /// It must be dropped in the view model: two writes of the same field would
    /// otherwise land two responses into `state` in an order nobody chose.
    func testASecondActionOnAFieldWithAWriteInFlightSendsNoSecondRequest() async {
        let gate = WriteGate()
        moneyProfileClient.updateGate = { await gate.hold() }
        let viewModel = makeViewModel()
        await viewModel.load()

        let inFlight = Task { await viewModel.decline(.income) }
        await gate.waitUntilHeld()

        // Both actions of the same field, fired while the first is still open.
        await viewModel.remove(.income)
        await viewModel.decline(.income)
        XCTAssertEqual(moneyProfileClient.updateCallCount, 1, "the field is already being written")

        await gate.release()
        _ = await inFlight.value
        XCTAssertEqual(moneyProfileClient.updateCallCount, 1)

        // Once it settles, the field is writable again.
        await viewModel.remove(.income)
        XCTAssertEqual(moneyProfileClient.updateCallCount, 2)
    }

    /// A PICK, unlike a decline, moves the picker as well as sending a request.
    /// The claim is therefore taken BEFORE the picker moves: a dropped pick
    /// must leave no trace at all, or the menu shows a band no request ever
    /// carried.
    func testASecondPickOnAFieldWithAWriteInFlightSendsNothingAndLeavesThePickerOnTheSentValue() async throws {
        let gate = WriteGate()
        moneyProfileClient.updateGate = { await gate.hold() }
        let viewModel = makeViewModel()
        await viewModel.load()

        let sent = try incomeOption(.under30k, on: viewModel)
        let dropped = try incomeOption(.over110k, on: viewModel)

        let inFlight = Task { await viewModel.answer(income: sent) }
        await gate.waitUntilHeld()

        await viewModel.answer(income: dropped)
        XCTAssertEqual(moneyProfileClient.updateCallCount, 1, "the field is already being written")
        XCTAssertEqual(
            viewModel.selectedIncome, sent,
            "the dropped pick must not move the picker: the menu would show a value no request carried"
        )

        await gate.release()
        _ = await inFlight.value

        XCTAssertEqual(moneyProfileClient.updateCallCount, 1)
        XCTAssertEqual(viewModel.selectedIncome?.value, .under30k)
        XCTAssertEqual(viewModel.answerText(.income), sent.label)
    }

    /// The other field is never blocked: the guard is per field, not a screen
    /// lock.
    func testAWriteInFlightOnOneFieldDoesNotBlockTheOther() async {
        let gate = WriteGate()
        moneyProfileClient.updateGate = { await gate.hold() }
        let viewModel = makeViewModel()
        await viewModel.load()

        let inFlight = Task { await viewModel.decline(.income) }
        await gate.waitUntilHeld()

        await viewModel.decline(.residency)
        XCTAssertEqual(moneyProfileClient.updateCallCount, 2)

        await gate.release()
        _ = await inFlight.value
    }

    // MARK: - the receipt

    /// The receipt is transient by design (RFC 171 §6): it says the write
    /// landed and then gets out of the way. `receiptDuration` is injected for
    /// exactly this test, so the timer that removes it is behaviour under
    /// check rather than a seam kept alive by nothing.
    func testTheSavedReceiptClearsItselfAfterItsDuration() async throws {
        // Short enough that the test costs nothing, and the wait is a generous
        // multiple of it rather than a second number to keep in step.
        let receiptDuration = Duration.milliseconds(20)
        let expiryWaitMultiple = 20
        let viewModel = YourDetailsViewModel(
            moneyProfileClient: moneyProfileClient,
            vocabularyClient: vocabularyClient,
            onProfileRequired: {},
            receiptDuration: receiptDuration
        )
        await viewModel.load()

        await viewModel.decline(.income)
        XCTAssertEqual(viewModel.savedField, .income)

        try await Task.sleep(for: receiptDuration * expiryWaitMultiple)
        XCTAssertNil(viewModel.savedField, "the receipt says the write landed, then leaves")
    }
}

/// A write held open, so a test can act on the view model while a request is
/// genuinely in flight rather than guessing at a sleep.
private actor WriteGate {
    private var isReleased = false
    private var isHeld = false
    private var releaseWaiters: [CheckedContinuation<Void, Never>] = []
    private var heldWaiters: [CheckedContinuation<Void, Never>] = []

    /// Called from inside the client double. Only the FIRST write is held —
    /// a later one passes straight through, so a test can assert that the
    /// other field is not blocked.
    func hold() async {
        guard !isHeld else { return }
        isHeld = true
        for waiter in heldWaiters { waiter.resume() }
        heldWaiters = []
        guard !isReleased else { return }
        await withCheckedContinuation { releaseWaiters.append($0) }
    }

    /// Returns once a write has actually reached the client.
    func waitUntilHeld() async {
        guard !isHeld else { return }
        await withCheckedContinuation { heldWaiters.append($0) }
    }

    func release() {
        isReleased = true
        for waiter in releaseWaiters { waiter.resume() }
        releaseWaiters = []
    }
}
