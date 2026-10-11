import XCTest
@testable import UnicoachiOS

/// "Your details" as the door onto what a student wants to study (RFC 205 §7).
/// The acceptance criterion is "a decline is durable and undoable from the
/// screen", and a decline set in CHAT arrives here as a stored answer like any
/// other — which is why every one of these tests seeds the client rather than
/// writing first.
@MainActor
final class YourDetailsSubjectsTests: XCTestCase {
    private var moneyProfileClient: MockMoneyProfileClient!
    private var vocabularyClient: MockVocabularyClient!
    private var subjectsClient: MockSubjectsClient!
    private var intendedSubjectsClient: MockIntendedSubjectsClient!
    private var profileRequiredCount = 0

    override func setUp() {
        super.setUp()
        moneyProfileClient = MockMoneyProfileClient()
        vocabularyClient = MockVocabularyClient()
        subjectsClient = MockSubjectsClient()
        intendedSubjectsClient = MockIntendedSubjectsClient()
        profileRequiredCount = 0
    }

    private func makeViewModel() -> YourDetailsViewModel {
        YourDetailsViewModel(
            moneyProfileClient: moneyProfileClient,
            vocabularyClient: vocabularyClient,
            subjectsClient: subjectsClient,
            intendedSubjectsClient: intendedSubjectsClient,
            sharingPermissionsClient: MockSharingPermissionsClient(),
            onProfileRequired: { [weak self] in self?.profileRequiredCount += 1 },
            receiptDuration: .seconds(60)
        )
    }

    private func loaded(_ answer: PublicIntendedSubjects) async -> YourDetailsViewModel {
        intendedSubjectsClient.fetchResult = .success(answer)
        let viewModel = makeViewModel()
        await viewModel.load()
        return viewModel
    }

    private func slugs(_ request: UpdateIntendedSubjectsRequest?) -> [String]? {
        guard case .subjects(let slugs) = request?.answer else { return nil }
        return slugs
    }

    // MARK: - What the screen shows

    func testAStoredAnswerIsShownAsChips() async {
        let viewModel = await loaded(.answered(["nursing", "neuroscience"]))
        XCTAssertEqual(
            viewModel.subjectsAnswer,
            .answered([
                DSSubjectChip(id: "nursing", name: "Nursing"),
                DSSubjectChip(id: "neuroscience", name: "Neuroscience"),
            ])
        )
    }

    /// The two named non-answers are carried as themselves, never folded into
    /// each other or into an empty list (D16/D30).
    func testADeclineSetInChatIsVisibleHere() async {
        let viewModel = await loaded(.declined)
        XCTAssertEqual(viewModel.subjectsAnswer, .declined)
    }

    func testAnUndecidedAnswerSetInChatIsVisibleHere() async {
        let viewModel = await loaded(.undecided)
        XCTAssertEqual(viewModel.subjectsAnswer, .undecided)
    }

    /// `answered` with no subjects is refused by the schema's deferred
    /// trigger, so reaching it means the server broke its own rule. The screen
    /// says nothing about it rather than rendering an answer with nothing in
    /// it — or, worse, claiming the question was never answered.
    func testAnAnsweredAnswerWithNoSubjectsReadsAsUnreadable() async {
        let viewModel = await loaded(PublicIntendedSubjects(status: AnswerStatus.answered.rawValue, subjects: []))
        XCTAssertEqual(viewModel.subjectsAnswer, .unreadable)
    }

    /// The case this build is not the last version of. The status vocabulary is
    /// the SERVER's, and it is due to be retyped onto a shared domain, so a
    /// value this build cannot read is a realistic future — not a hypothetical.
    func testAStatusThisBuildHasNoCaseForIsNotShownAsUnanswered() async {
        let viewModel = await loaded(PublicIntendedSubjects(status: "deferred_to_mum", subjects: []))
        XCTAssertEqual(viewModel.subjectsAnswer, .unreadable)
        XCTAssertNotEqual(
            viewModel.subjectsAnswer,
            .unanswered,
            "an answer this build cannot read must never be reported as one the student never gave"
        )
    }

    /// The rendered consequence of the case above, asserted where the words
    /// are: NO answer line at all. Saying "Not answered yet" over an answer the
    /// student DID give tells them something false about their own data and
    /// invites them to answer it again.
    func testAnUnreadableAnswerRendersNoAnswerLine() async {
        let viewModel = await loaded(PublicIntendedSubjects(status: "deferred_to_mum", subjects: []))
        let presentation = DSSubjectsRowPresentation(
            answer: viewModel.subjectsAnswer,
            availability: .available,
            maxChips: PublicIntendedSubjects.maxSubjects
        )
        XCTAssertNil(presentation.answerText)
        XCTAssertTrue(presentation.chips.isEmpty)
        XCTAssertTrue(presentation.showsAddAffordance, "the student keeps a way to state an answer this build reads")
    }

    /// A `DSSubjectsAnswer.answered([])` cannot come from the view model, but
    /// the design-system type admits it, so the row must still read something
    /// back. A row with no chips AND no answer line states nothing at all.
    func testAnEmptyAnsweredSetStillTellsTheStudentSomething() {
        let presentation = DSSubjectsRowPresentation(
            answer: .answered([]),
            availability: .available,
            maxChips: PublicIntendedSubjects.maxSubjects
        )
        XCTAssertTrue(presentation.chips.isEmpty)
        XCTAssertEqual(
            presentation.answerText,
            "Not answered yet",
            "an answered set with no chips must not render a silent row"
        )
    }

    // MARK: - The list, and its absence

    func testTheListIsReadSoTheRowCanSayWhetherItIsReachable() async {
        let viewModel = await loaded(.unanswered)
        XCTAssertEqual(subjectsClient.searchCallCount, 1)
        XCTAssertEqual(subjectsClient.lastQuery, .some(nil), "the reachability read asks for the whole list")
        XCTAssertEqual(viewModel.subjectsAvailability, .available)
    }

    /// No shipped fallback list: the row says the list is unavailable, and the
    /// screen still loads, because what is STORED is still known.
    func testAnUnreachableListDegradesTheRowAndNotTheScreen() async {
        subjectsClient.searchResult = .failure(ErrorResponse(code: "NETWORK_ERROR", message: "Offline", fieldErrors: nil))
        let viewModel = await loaded(.answered(["nursing"]))
        XCTAssertEqual(viewModel.subjectsAvailability, .unavailable)
        XCTAssertEqual(viewModel.subjectsAnswer, .answered([DSSubjectChip(id: "nursing", name: "Nursing")]))
        guard case .loaded = viewModel.state else {
            return XCTFail("an unreachable MENU is not a failed screen")
        }
    }

    /// The probe decides what the row SAYS, never what it OFFERS: one flaky
    /// request at load must not remove the only control that could reach the
    /// list again.
    func testAFailedListProbeLeavesTheAddAffordanceInPlace() async {
        subjectsClient.searchResult = .failure(ErrorResponse(code: "NETWORK_ERROR", message: "Offline", fieldErrors: nil))
        let viewModel = await loaded(.declined)
        let presentation = DSSubjectsRowPresentation(
            answer: viewModel.subjectsAnswer,
            availability: viewModel.subjectsAvailability,
            maxChips: PublicIntendedSubjects.maxSubjects
        )
        XCTAssertEqual(viewModel.subjectsAvailability, .unavailable)
        XCTAssertTrue(presentation.showsListUnavailableNote, "the student is still told the list was unreachable")
        XCTAssertTrue(presentation.showsAddAffordance, "and is still allowed to try it")
    }

    /// The acceptance criterion under the worst load this screen has: the
    /// decline is still undone, after the probe behind the picker failed.
    func testADeclineIsUndoneEvenAfterAFailedListProbe() async {
        subjectsClient.searchResult = .failure(ErrorResponse(code: "NETWORK_ERROR", message: "Offline", fieldErrors: nil))
        let viewModel = await loaded(.declined)
        await viewModel.addSubject(PublicSubject(slug: "nursing", name: "Nursing"))
        XCTAssertEqual(slugs(intendedSubjectsClient.lastUpdateRequest), ["nursing"])
        XCTAssertEqual(viewModel.subjectsAnswer, .answered([DSSubjectChip(id: "nursing", name: "Nursing")]))
    }

    /// The ANSWER is different: a screen that cannot read it would be showing
    /// "not answered yet" over an answer it merely failed to fetch.
    func testAnUnreadableAnswerFailsTheScreen() async {
        let failure = ErrorResponse(code: "SERVER_ERROR", message: "Nope", fieldErrors: nil)
        intendedSubjectsClient.fetchResult = .failure(failure)
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(viewModel.state, .failed(failure))
    }

    /// The profile gate reaching the screen through the SUBJECTS read is the
    /// same routing signal as through the money read, and is not an error to
    /// render.
    func testAProfileRefusalOnTheSubjectsReadEscalatesRatherThanFailingTheScreen() async {
        intendedSubjectsClient.fetchResult = .failure(ErrorResponse(
            code: "student_profile_required",
            message: "Student profile required",
            fieldErrors: nil
        ))
        let viewModel = makeViewModel()
        await viewModel.load()
        XCTAssertEqual(profileRequiredCount, 1)
        if case .failed = viewModel.state {
            XCTFail("a profile gate is a routing signal, not an error to render")
        }
    }

    // MARK: - Writing the whole answer

    func testAddingASubjectSendsTheWholeSetAndNotJustTheNewOne() async {
        let viewModel = await loaded(.answered(["nursing"]))
        await viewModel.addSubject(PublicSubject(slug: "neuroscience", name: "Neuroscience"))
        XCTAssertEqual(slugs(intendedSubjectsClient.lastUpdateRequest), ["nursing", "neuroscience"])
        XCTAssertEqual(
            viewModel.subjectsAnswer,
            .answered([
                DSSubjectChip(id: "nursing", name: "Nursing"),
                DSSubjectChip(id: "neuroscience", name: "Neuroscience"),
            ])
        )
    }

    func testAddingASubjectAlreadyChosenSendsNothing() async {
        let viewModel = await loaded(.answered(["nursing"]))
        await viewModel.addSubject(PublicSubject(slug: "nursing", name: "Nursing"))
        XCTAssertEqual(intendedSubjectsClient.updateCallCount, 0, "a duplicate is a 400 the student cannot act on")
    }

    func testRemovingOneOfTwoKeepsTheOther() async {
        let viewModel = await loaded(.answered(["nursing", "neuroscience"]))
        await viewModel.removeSubject(DSSubjectChip(id: "nursing", name: "Nursing"))
        XCTAssertEqual(slugs(intendedSubjectsClient.lastUpdateRequest), ["neuroscience"])
    }

    /// D17, and the client does not translate it: the empty set is submitted as
    /// an empty set, and `undecided` is what the SERVER answers with. Nothing
    /// here may ever write `unanswered`.
    func testRemovingTheLastSubjectSubmitsTheEmptySetAndAdoptsUndecided() async {
        let viewModel = await loaded(.answered(["nursing"]))
        await viewModel.removeSubject(DSSubjectChip(id: "nursing", name: "Nursing"))
        XCTAssertEqual(slugs(intendedSubjectsClient.lastUpdateRequest), [])
        XCTAssertEqual(viewModel.subjectsAnswer, .undecided, "never back to unanswered")
    }

    func testRemovingASubjectThatIsNotThereSendsNothing() async {
        let viewModel = await loaded(.answered(["nursing"]))
        await viewModel.removeSubject(DSSubjectChip(id: "neuroscience", name: "Neuroscience"))
        XCTAssertEqual(intendedSubjectsClient.updateCallCount, 0)
    }

    func testDecliningStoresTheDeclineAndDropsTheSubjects() async {
        let viewModel = await loaded(.answered(["nursing"]))
        await viewModel.declineSubjects()
        XCTAssertEqual(intendedSubjectsClient.lastUpdateRequest, UpdateIntendedSubjectsRequest(.declined))
        XCTAssertEqual(viewModel.subjectsAnswer, .declined)
    }

    func testTheUndecidedAnswerIsRecordedAsAnAnswer() async {
        let viewModel = await loaded(.unanswered)
        await viewModel.markSubjectsUndecided()
        XCTAssertEqual(intendedSubjectsClient.lastUpdateRequest, UpdateIntendedSubjectsRequest(.undecided))
        XCTAssertEqual(viewModel.subjectsAnswer, .undecided)
    }

    /// The acceptance criterion, end to end: a decline arriving from chat is
    /// undone from this screen with one pick.
    func testADeclineIsUndoneByChoosingASubject() async {
        let viewModel = await loaded(.declined)
        await viewModel.addSubject(PublicSubject(slug: "nursing", name: "Nursing"))
        XCTAssertEqual(slugs(intendedSubjectsClient.lastUpdateRequest), ["nursing"])
        XCTAssertEqual(viewModel.subjectsAnswer, .answered([DSSubjectChip(id: "nursing", name: "Nursing")]))
    }

    func testTheChosenSlugsAreOfferedToThePickerSoItNeverOffersOneTwice() async {
        let viewModel = await loaded(.answered(["nursing", "neuroscience"]))
        XCTAssertEqual(viewModel.chosenSubjectSlugs, ["nursing", "neuroscience"])
    }

    // MARK: - Failures on write

    func testAFailedWriteSurfacesTheServerErrorAndKeepsTheStoredAnswer() async {
        let viewModel = await loaded(.answered(["nursing"]))
        let failure = ErrorResponse(
            code: "validation_failed",
            message: "You can choose at most three subjects.",
            fieldErrors: nil
        )
        intendedSubjectsClient.updateResult = .failure(failure)
        await viewModel.addSubject(PublicSubject(slug: "neuroscience", name: "Neuroscience"))
        XCTAssertEqual(viewModel.actionError, failure)
        XCTAssertEqual(
            viewModel.subjectsAnswer,
            .answered([DSSubjectChip(id: "nursing", name: "Nursing")]),
            "only a 200 moves the stored answer"
        )
    }

    /// A routing signal, not an error to show — the money writes' rule.
    func testTheProfileRefusalEscalatesRatherThanShowingAnAlert() async {
        let viewModel = await loaded(.unanswered)
        intendedSubjectsClient.updateResult = .failure(ErrorResponse(
            code: "student_profile_required",
            message: "Student profile required",
            fieldErrors: nil
        ))
        await viewModel.declineSubjects()
        XCTAssertEqual(profileRequiredCount, 1)
        XCTAssertNil(viewModel.actionError)
    }
}
