import SwiftUI
import XCTest
@testable import UnicoachiOS

/// The capped multi-select's rules, tested where they live: the row's
/// presentation is a value, so "the add affordance is gone at three" is an
/// assertion rather than a screenshot somebody has to look at.
///
/// Each case also CONSTRUCTS the view, which is the tripwire that a change
/// breaking the row's body fails the suite with nobody looking (the
/// `SnapshotTests` argument, applied to one control).
final class DSSubjectsRowTests: XCTestCase {
    private let maxChips = PublicIntendedSubjects.maxSubjects

    private func presentation(
        _ answer: DSSubjectsAnswer,
        availability: DSListAvailability = .available
    ) -> DSSubjectsRowPresentation {
        DSSubjectsRowPresentation(answer: answer, availability: availability, maxChips: maxChips)
    }

    private func chips(_ count: Int) -> [DSSubjectChip] {
        PublicSubject.fixtures.prefix(count).map { DSSubjectChip(id: $0.slug, name: $0.name) }
    }

    /// The row is buildable for every case. `AnyView` erases the generic picker
    /// so one helper covers them all.
    @MainActor
    private func row(_ answer: DSSubjectsAnswer, availability: DSListAvailability = .available) -> AnyView {
        AnyView(
            DSSubjectsRow(
                label: "Subjects",
                answer: answer,
                availability: availability,
                maxChips: maxChips,
                addTitle: SubjectsRowCopy.add,
                capNote: SubjectsRowCopy.cap,
                listUnavailableNote: SubjectsRowCopy.listUnavailable,
                onRemove: { _ in },
                onUndecided: {},
                onDecline: {},
                picker: { _ in EmptyView() }
            )
        )
    }

    // MARK: - Empty

    @MainActor
    func testEmptyOffersTheSearchAndBothNamedAnswers() {
        let presentation = presentation(.unanswered)
        XCTAssertTrue(presentation.chips.isEmpty)
        XCTAssertTrue(presentation.showsAddAffordance)
        XCTAssertFalse(presentation.showsCapNote)
        XCTAssertFalse(presentation.showsListUnavailableNote)
        XCTAssertEqual(presentation.answerText, "Not answered yet")
        XCTAssertTrue(presentation.showsUndecidedChoice)
        XCTAssertTrue(presentation.showsDeclineChoice)
        _ = row(.unanswered)
    }

    // MARK: - One chip

    @MainActor
    func testOneChipStillOffersTheSearch() {
        let chosen = chips(1)
        let presentation = presentation(.answered(chosen))
        XCTAssertEqual(presentation.chips, chosen)
        XCTAssertTrue(presentation.showsAddAffordance, "one of three is room for two more")
        XCTAssertFalse(presentation.showsCapNote)
        XCTAssertNil(presentation.answerText, "the chip IS the answer; a sentence beside it only repeats it")
        XCTAssertTrue(presentation.showsUndecidedChoice)
        XCTAssertTrue(presentation.showsDeclineChoice)
        _ = row(.answered(chosen))
    }

    // MARK: - Three chips: the cap

    @MainActor
    func testThreeChipsTakeTheAddAffordanceAwayAndSayWhy() {
        let chosen = chips(maxChips)
        let presentation = presentation(.answered(chosen))
        XCTAssertEqual(presentation.chips.count, maxChips)
        XCTAssertFalse(presentation.showsAddAffordance, "at the cap the control is gone, not disabled")
        XCTAssertTrue(presentation.showsCapNote, "the row explains its own missing control")
        _ = row(.answered(chosen))
    }

    /// The cap is read from the parameter, never from a literal 3 inside the
    /// design system: the cap is the SERVER's rule (RFC 205 §2).
    func testTheCapIsWhoeverPassedIt() {
        let two = DSSubjectsRowPresentation(answer: .answered(chips(2)), availability: .available, maxChips: 2)
        XCTAssertFalse(two.showsAddAffordance)
        XCTAssertTrue(two.showsCapNote)
    }

    // MARK: - Undecided

    @MainActor
    func testUndecidedReadsAsItsOwnAnswerAndIsUndoable() {
        let presentation = presentation(.undecided)
        XCTAssertEqual(presentation.answerText, "You don't know yet")
        XCTAssertTrue(presentation.chips.isEmpty)
        XCTAssertFalse(presentation.showsUndecidedChoice, "already undecided; the control would be a no-op")
        XCTAssertTrue(presentation.showsDeclineChoice)
        XCTAssertTrue(presentation.showsAddAffordance, "choosing a subject is how 'I don't know yet' is undone")
        _ = row(.undecided)
    }

    // MARK: - Declined

    @MainActor
    func testDeclinedIsShownAndIsUndoableFromTheRow() {
        let presentation = presentation(.declined)
        XCTAssertEqual(presentation.answerText, "You chose not to say")
        XCTAssertTrue(presentation.chips.isEmpty)
        XCTAssertFalse(presentation.showsDeclineChoice, "already declined; the control would be a no-op")
        XCTAssertTrue(presentation.showsUndecidedChoice)
        XCTAssertTrue(presentation.showsAddAffordance, "a decline is never a one-way door (RFC 205 §7)")
        _ = row(.declined)
    }

    // MARK: - Copy, which the row does not own

    /// The number in the cap note is derived from the cap the row was given,
    /// not typed into the design system: change the server's rule and the
    /// sentence follows it.
    func testTheCapNoteSpellsWhateverCapItIsGiven() {
        XCTAssertEqual(SubjectsRowCopy.cap(3), "That's three — the most unicoach asks for. Remove one to swap it.")
        XCTAssertEqual(SubjectsRowCopy.cap(5), "That's five — the most unicoach asks for. Remove one to swap it.")
    }

    /// Moving the copy out of `DSSubjectsRow` changed nothing a student reads:
    /// at the cap this app actually ships, the note is word for word the one
    /// the row used to hold itself.
    func testTheShippedCopyIsWhatTheRowUsedToHardcode() {
        XCTAssertEqual(SubjectsRowCopy.add, "Add a subject")
        XCTAssertEqual(
            SubjectsRowCopy.cap(maxChips),
            "That's three — the most unicoach asks for. Remove one to swap it."
        )
    }

    /// The four answers are four, and the two named non-answers never read as
    /// each other — the whole reason `undecided` exists (D16/D30).
    func testTheTwoNamedNonAnswersReadDifferently() {
        XCTAssertNotEqual(presentation(.undecided).answerText, presentation(.declined).answerText)
        XCTAssertNotEqual(presentation(.undecided).answerText, presentation(.unanswered).answerText)
    }

    // MARK: - List unavailable

    @MainActor
    func testAnUnreachableListSaysSoAndStillShowsWhatIsStored() {
        let chosen = chips(1)
        let presentation = presentation(.answered(chosen), availability: .unavailable)
        XCTAssertEqual(presentation.chips, chosen, "what is already stored is still shown")
        XCTAssertTrue(presentation.showsListUnavailableNote)
        XCTAssertTrue(
            presentation.showsAddAffordance,
            "one failed probe must not remove the only control that could reach the list again"
        )
        _ = row(.answered(chosen), availability: .unavailable)
    }

    /// The case the acceptance criterion cares about: a declined student whose
    /// list probe failed must still be offered the control that undoes the
    /// decline.
    @MainActor
    func testAnUnreachableListStillOffersTheAddAffordanceToADeclinedStudent() {
        let presentation = presentation(.declined, availability: .unavailable)
        XCTAssertTrue(presentation.showsAddAffordance, "a decline is never a one-way door, list or no list")
        XCTAssertTrue(presentation.showsListUnavailableNote, "and the student is still told the list was unreachable")
        _ = row(.declined, availability: .unavailable)
    }

    @MainActor
    func testAnUnreachableListStillOffersBothNamedAnswers() {
        let presentation = presentation(.unanswered, availability: .unavailable)
        XCTAssertTrue(presentation.showsUndecidedChoice)
        XCTAssertTrue(presentation.showsDeclineChoice, "neither answer needs the list")
        XCTAssertTrue(presentation.showsListUnavailableNote)
        _ = row(.unanswered, availability: .unavailable)
    }
}
