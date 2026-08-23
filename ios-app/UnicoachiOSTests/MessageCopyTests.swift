import UIKit
import XCTest
@testable import UnicoachiOS

/// `MarkdownPlainText` is the only new logic RFC 125 adds — the menu itself is
/// two SwiftUI modifiers and a pasteboard write, none of which this suite can
/// reach — so these assertions are the entire mechanical authority over the
/// feature. They are written against what a student would find wrong in a
/// paste (a stray `#`, a lost checkbox, a table collapsed into prose), not
/// against the renderer's own output.
final class MarkdownPlainTextTests: XCTestCase {
    // MARK: - Headings

    func testEveryHeadingLevelLosesItsHashes() {
        for level in 1 ... 6 {
            let hashes = String(repeating: "#", count: level)
            XCTAssertEqual(
                MarkdownPlainText.render("\(hashes) Your next steps"),
                "Your next steps",
                "level [\(level)]"
            )
        }
    }

    func testAHeadingLosesItsInlineSyntaxToo() {
        // The levels differ by font on screen and plain text has no font, so a
        // heading is indistinguishable from a paragraph here — deliberately.
        XCTAssertEqual(MarkdownPlainText.render("## Your **next** steps"), "Your next steps")
    }

    // MARK: - Paragraphs

    func testAParagraphYieldsTheRenderedCharacters() {
        XCTAssertEqual(
            MarkdownPlainText.render("You have **three deadlines** and _one_ `essay` left."),
            "You have three deadlines and one essay left."
        )
    }

    func testALinkYieldsItsTextNotItsUrl() {
        // A URL in the middle of a sentence is exactly the syntax "copy as
        // seen" exists to remove; the student who wants it has the other menu
        // item.
        XCTAssertEqual(
            MarkdownPlainText.render("See [the checklist](https://example.com) first."),
            "See the checklist first."
        )
    }

    func testASoftLineBreakInsideAParagraphSurvives() {
        // The coach's line breaks are meaning — the parser preserves them and
        // the view draws them, so the paste has to carry them.
        XCTAssertEqual(MarkdownPlainText.render("Line one\nLine two"), "Line one\nLine two")
    }

    // MARK: - Lists

    func testABulletListIsOneLinePerItemWithABulletGlyph() {
        XCTAssertEqual(
            MarkdownPlainText.render("- Applications\n- Financial aid"),
            "\u{2022} Applications\n\u{2022} Financial aid"
        )
    }

    func testAnOrderedListKeepsItsOrdinals() {
        XCTAssertEqual(
            MarkdownPlainText.render("3. Draft\n3. Revise\n3. Submit"),
            "3. Draft\n4. Revise\n5. Submit"
        )
    }

    func testATaskListDrawsItsCheckboxesByDoneness() {
        XCTAssertEqual(
            MarkdownPlainText.render("- [x] FSA ID created\n- [ ] FAFSA submitted"),
            "\u{2611} FSA ID created\n\u{2610} FAFSA submitted"
        )
    }

    func testNestedItemsAreIndentedTwoSpacesPerLevel() {
        // Two spaces per *depth*, not the column the coach happened to type:
        // the model flattened nesting into a depth, and a four-space source
        // must paste the same as a two-space one.
        XCTAssertEqual(
            MarkdownPlainText.render("- Applications\n    - Michigan\n        - Supplement"),
            "\u{2022} Applications\n  \u{2022} Michigan\n    \u{2022} Supplement"
        )
    }

    func testAWrappedItemStaysOneLine() {
        // The parser folds a continuation line into the item above it. Left as
        // a soft break the tail would paste at column zero with no marker and
        // read as loose prose between two bullets.
        XCTAssertEqual(
            MarkdownPlainText.render("- Michigan supplement\n  not started yet"),
            "\u{2022} Michigan supplement not started yet"
        )
    }

    // MARK: - Quotes and code

    func testAQuoteCarriesNoAngleBrackets() {
        XCTAssertEqual(
            MarkdownPlainText.render("> Anything you submit early\n> can still be revised."),
            "Anything you submit early\ncan still be revised."
        )
    }

    func testACodeBlockIsVerbatimWithNoFences() {
        // Verbatim to the character, indentation included: the whole point of a
        // code block is that its bytes are the payload.
        XCTAssertEqual(
            MarkdownPlainText.render("```sh\nbin/deadlines --within 30d \\\n    --school michigan\n```"),
            "bin/deadlines --within 30d \\\n    --school michigan"
        )
    }

    func testInlineSyntaxInsideACodeBlockIsNotStripped() {
        XCTAssertEqual(MarkdownPlainText.render("```\na = **b**\n```"), "a = **b**")
    }

    // MARK: - Tables

    func testATableIsTabSeparatedWithItsHeaderRowFirst() {
        // Tab-separated because that is what pastes into Notes, Numbers and
        // Mail as an actual table; ASCII-art alignment would look right only in
        // a monospaced destination.
        let source = """
        | College | Deadline |
        | --- | --- |
        | Michigan | Nov 1 |
        | Purdue | Nov 15 |
        """
        XCTAssertEqual(
            MarkdownPlainText.render(source),
            "College\tDeadline\nMichigan\tNov 1\nPurdue\tNov 15"
        )
    }

    func testAMultiWordCellStaysOnItsOwnRow() {
        // The cell that wraps on screen is the one that would break a paste
        // into a spreadsheet if it carried a newline of its own.
        let source = """
        | College | Status |
        | --- | --- |
        | Illinois | Not started yet |
        """
        XCTAssertEqual(
            MarkdownPlainText.render(source),
            "College\tStatus\nIllinois\tNot started yet"
        )
    }

    func testTableCellsLoseTheirInlineSyntax() {
        let source = """
        | College | Status |
        | --- | --- |
        | Michigan | **Draft** |
        """
        XCTAssertEqual(
            MarkdownPlainText.render(source),
            "College\tStatus\nMichigan\tDraft"
        )
    }

    // MARK: - Separation

    func testARuleIsOmittedAndLeavesNoDoubledBlankLine() {
        // The failure this guards is the easy one: rendering a rule as an empty
        // string still costs it a place in the join, and the paste gains a gap
        // where the line used to be.
        XCTAssertEqual(
            MarkdownPlainText.render("Before.\n\n---\n\nAfter."),
            "Before.\n\nAfter."
        )
    }

    func testBlocksAreSeparatedByExactlyOneBlankLine() {
        XCTAssertEqual(
            MarkdownPlainText.render("## Steps\n\nProse.\n\n- One\n\n> Aside."),
            "Steps\n\nProse.\n\n\u{2022} One\n\nAside."
        )
    }

    func testThereIsNoTrailingNewline() {
        // A fence closed on the line after the last statement is the block that
        // can legitimately end in a newline; nothing else can.
        let rendered = MarkdownPlainText.render("Prose.\n\n```\ncommand\n\n```\n")
        XCTAssertFalse(rendered.hasSuffix("\n"), "rendered [\(rendered.debugDescription)]")
        XCTAssertEqual(rendered, "Prose.\n\ncommand")
    }

    func testAnEmptySourceRendersToAnEmptyString() {
        XCTAssertEqual(MarkdownPlainText.render(""), "")
    }

    func testAnEmptyBlockListRendersToAnEmptyString() {
        XCTAssertEqual(MarkdownPlainText.render([]), "")
    }

    // MARK: - A whole reply

    func testWorstCaseReplyRendersToItsPlainText() {
        // The suite's hard case, shared with the parser tests and both
        // `#Preview`s: every block type this renderer handles, in one message.
        // Pinned as a whole string rather than probed piecewise, because the
        // thing a student notices is the shape of the paste, and only an
        // end-to-end expectation can regress on it.
        let expected = """
        Your next steps

        You have three deadlines inside the next month, and the Common App \
        essay is the one that gates the rest.

        \u{2022} Applications
          \u{2022} Michigan — supplement not started
          \u{2022} Purdue — essay in draft
        \u{2022} Financial aid
          \u{2611} FSA ID created
          \u{2610} FAFSA submitted

        College\tDeadline\tType\tEssays\tStatus
        Michigan\tNov 1\tEA\t2\tDraft
        Purdue\tNov 15\tEA\t1\tNot started
        Illinois\tDec 1\tRD\t0\tSubmitted

        Run this to check your list against the deadlines:

        bin/deadlines --within 30d \\
            --school michigan

        Anything you submit early can still be revised until the deadline.

        See the Common App checklist when you start.
        """
        XCTAssertEqual(MarkdownPlainText.render(MarkdownFixture.worstCaseReply), expected)
    }

    /// A block that renders to nothing must vanish the way a rule does. The
    /// parser really produces them — a bare `##` is a heading with empty text —
    /// and left in, the empty string would still claim its slot in the join.
    func testABlockThatRendersToNothingLeavesNoDoubledBlankLine() {
        XCTAssertEqual(
            MarkdownPlainText.render("Before.\n\n##\n\nAfter."),
            "Before.\n\nAfter."
        )
    }

    /// A tab inside a cell would emit a phantom extra column and shift every
    /// following column in that row — silently, in the spreadsheet this format
    /// was chosen for.
    func testATabInsideACellCannotForgeAnExtraColumn() {
        let table = """
        | Task | Note |
        | --- | --- |
        | `a\tb` | fine |
        """
        let rows = MarkdownPlainText.render(table).split(separator: "\n")
        XCTAssertEqual(rows.count, 2)
        for row in rows {
            XCTAssertEqual(row.split(separator: "\t", omittingEmptySubsequences: false).count, 2, "row [\(row)]")
        }
    }

    /// A code block whose last line is indented ends in "\n    ". A trim that
    /// only dropped newlines would halt at the first space and leave the indent
    /// dangling at the end of the paste.
    func testTrailingIndentIsTrimmedNotJustTrailingNewlines() {
        let source = "```\nif x:\n    pass\n    \n```"
        let rendered = MarkdownPlainText.render(source)
        XCTAssertFalse(rendered.hasSuffix(" "), "rendered [\(rendered.debugDescription)]")
        XCTAssertTrue(rendered.hasSuffix("pass"), "rendered [\(rendered.debugDescription)]")
    }
}

/// The value behind each menu button. The button itself is unreachable from
/// XCTest — a `contextMenu` renders on a platform popover and `UIPasteboard`
/// is a device singleton — but *which string rides which label* is the one
/// thing about this feature a reader could get backwards, and it is a pure
/// value. So it is pinned here rather than left to a manual pass (RFC 125).
final class CopyActionTests: XCTestCase {
    private static let source = "## Next steps\n\nSubmit the **Common App** by Nov 1."

    func testTheBareCopyCarriesTheRenderedCharacters() {
        let action = CopyAction.rendered(source: Self.source)
        XCTAssertEqual(action.title, "Copy")
        XCTAssertEqual(action.identifier, "copyButton")
        XCTAssertEqual(action.text, "Next steps\n\nSubmit the Common App by Nov 1.")
        XCTAssertFalse(action.text?.contains("#") ?? true, "the bare Copy must not carry syntax")
        XCTAssertFalse(action.text?.contains("**") ?? true, "the bare Copy must not carry syntax")
    }

    func testCopyAsMarkdownCarriesTheSourceUntouched() {
        let action = CopyAction.markdown(source: Self.source)
        XCTAssertEqual(action.title, "Copy as Markdown")
        XCTAssertEqual(action.identifier, "copyMarkdownButton")
        XCTAssertEqual(action.text, Self.source, "the lossless option must be byte-identical to the source")
    }

    /// The student's utterance is already what the eye saw, so `verbatim` must
    /// not render it — a `*` the student typed is a `*` they meant.
    func testTheStudentsCopyIsNotRendered() {
        let typed = "should I apply *early*? # of essays?"
        let action = CopyAction.verbatim(text: typed)
        XCTAssertEqual(action.title, "Copy")
        XCTAssertEqual(action.identifier, "copyButton")
        XCTAssertEqual(action.text, typed)
    }

}

/// The menu each kind of bubble carries. A case per kind rather than a
/// caller-assembled array, so these assertions are about a total function over
/// two inputs rather than a spot-check of one call site (RFC 125).
final class CopyMenuTests: XCTestCase {
    func testTheStudentsBubbleOffersOneCopy() {
        let actions = CopyMenu.utterance(text: "should I apply *early*?").actions
        XCTAssertEqual(actions.map(\.title), ["Copy"])
        XCTAssertEqual(actions.map(\.text), ["should I apply *early*?"] as [String?])
    }

    func testTheCoachsBubbleOffersBothReadings() {
        let source = "## Next steps\n\nSubmit the **Common App**."
        let actions = CopyMenu.document(source: source).actions
        XCTAssertEqual(actions.map(\.title), ["Copy", "Copy as Markdown"])
        XCTAssertEqual(actions.map(\.identifier), ["copyButton", "copyMarkdownButton"])
        XCTAssertEqual(actions.map(\.text), ["Next steps\n\nSubmit the Common App.", source] as [String?])
    }

    /// `id` is what the `ForEach` behind both the context menu and the rotor
    /// keys on. Two entries sharing one would collapse the coach's menu to a
    /// single row — a bug with no visible cause.
    func testTheCoachsTwoEntriesAreDistinctlyIdentified() {
        XCTAssertEqual(Set(CopyMenu.document(source: "x").actions.map(\.id)).count, 2)
    }
}

/// The pasteboard write itself. Reachable here — the simulator has a real
/// `UIPasteboard` — which is worth using, because the one thing `copy()` does
/// beyond assigning is the thing a student would never forgive.
final class CopyActionPasteboardTests: XCTestCase {
    func testCopyingPutsTheTextOnThePasteboard() {
        UIPasteboard.general.string = "previous clipboard"
        CopyAction.markdown(source: "# Heading").copy()
        XCTAssertEqual(UIPasteboard.general.string, "# Heading")
    }

    /// A bubble can be on screen and render to nothing — a reply that is a lone
    /// `---`, or the first whitespace-only delta of a stream, which this
    /// feature keeps copyable on purpose. Writing `""` would not fail; it would
    /// silently wipe whatever the student had, which is much worse than a
    /// `Copy` that appears to do nothing.
    func testCopyingAnEmptyRenderingLeavesTheClipboardAlone() {
        UIPasteboard.general.string = "previous clipboard"
        CopyAction.rendered(source: "---").copy()
        XCTAssertEqual(UIPasteboard.general.string, "previous clipboard")
        XCTAssertNil(CopyAction.rendered(source: "---").text, "a lone rule really does render to nothing")
    }

    /// The clipboard guard has to cover all three constructors, not just the
    /// one that trims itself. `rendered` renders to nothing; `markdown` and
    /// `verbatim` hand back their source untouched, so a whitespace-only first
    /// SSE delta — a state the bubble is deliberately shown in — would sail
    /// past an `isEmpty` test and wipe the student's clipboard.
    func testAWhitespaceOnlyPayloadIsNothingToCopy() {
        for action in [
            CopyAction.rendered(source: "   \n\n  "),
            CopyAction.markdown(source: "  \n"),
            CopyAction.verbatim(text: "\t "),
        ] {
            XCTAssertNil(action.text, "[\(action.identifier)] should have nothing to copy")
            UIPasteboard.general.string = "previous clipboard"
            XCTAssertNil(action.copy(), "copying should decline")
            XCTAssertEqual(UIPasteboard.general.string, "previous clipboard")
        }
    }

    /// `copy()` reports what landed, so "wrote it" and "declined" are
    /// distinguishable to a caller rather than both being a silent `Void`.
    func testCopyingReturnsTheTextThatLanded() {
        XCTAssertEqual(CopyAction.verbatim(text: "hello").copy(), "hello")
    }
}
