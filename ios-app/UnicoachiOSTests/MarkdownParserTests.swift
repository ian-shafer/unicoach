import SwiftUI
import XCTest
@testable import UnicoachiOS

/// The parser is the only part of RFC 118's Markdown rendering that returns
/// values rather than views, and `bin/test` never compiles `ios-app` — so these
/// assertions are the entire mechanical authority over this feature. They are
/// written against behaviour a broken parser would actually get wrong (a
/// `#hashtag` promoted to a heading, a ragged table row indexing past its
/// header, a fence that has not closed yet), not against its own output.
final class MarkdownParserTests: XCTestCase {
    // MARK: - Blocks

    func testHeadingsOneThroughSix() {
        for level in 1 ... 6 {
            let hashes = String(repeating: "#", count: level)
            guard let heading = HeadingLevel(rawValue: level) else { return XCTFail("level \(level)") }
            XCTAssertEqual(
                MarkdownBlock.parse("\(hashes) Your next steps"),
                [.heading(level: heading, text: "Your next steps")],
                "level \(level)"
            )
        }
    }

    func testSevenHashesIsNotAHeading() {
        XCTAssertEqual(MarkdownBlock.parse("####### too deep"), [.paragraph("####### too deep")])
    }

    func testHashtagIsNotAHeading() {
        // The space after the hashes is the whole distinction; without this
        // check every "#firstgen" in a reply renders as a screen-sized title.
        XCTAssertEqual(MarkdownBlock.parse("#firstgen applicants"), [.paragraph("#firstgen applicants")])
    }

    func testClosingHashesAreStripped() {
        XCTAssertEqual(MarkdownBlock.parse("## Deadlines ##"), [.heading(level: .h2, text: "Deadlines")])
    }

    func testParagraphsSplitOnBlankLines() {
        XCTAssertEqual(
            MarkdownBlock.parse("First paragraph.\n\nSecond paragraph."),
            [.paragraph("First paragraph."), .paragraph("Second paragraph.")]
        )
    }

    func testSoftLineBreaksArePreservedWithinAParagraph() {
        XCTAssertEqual(
            MarkdownBlock.parse("Line one\nLine two"),
            [.paragraph("Line one\nLine two")]
        )
    }

    func testThematicBreaks() {
        XCTAssertEqual(MarkdownBlock.parse("---"), [.rule])
        XCTAssertEqual(MarkdownBlock.parse("***"), [.rule])
        XCTAssertEqual(MarkdownBlock.parse("- - -"), [.rule])
    }

    func testDashesUnderAParagraphAreASetextHeadingNotARule() {
        XCTAssertEqual(
            MarkdownBlock.parse("Your next steps\n---"),
            [.heading(level: .h2, text: "Your next steps")]
        )
        XCTAssertEqual(
            MarkdownBlock.parse("Your next steps\n==="),
            [.heading(level: .h1, text: "Your next steps")]
        )
    }

    func testRuleAfterABlankLineIsStillARule() {
        XCTAssertEqual(
            MarkdownBlock.parse("Prose.\n\n---"),
            [.paragraph("Prose."), .rule]
        )
    }

    func testBlockquoteRunCollectsItsLines() {
        XCTAssertEqual(
            MarkdownBlock.parse("> first\n> second"),
            [.quote(["first", "second"])]
        )
    }

    // MARK: - Lists

    func testBulletMarkers() {
        for bullet in ["-", "*", "+"] {
            let blocks = MarkdownBlock.parse("\(bullet) Common App\n\(bullet) Essays")
            XCTAssertEqual(
                blocks,
                [.list(MarkdownList(items: [
                    .init(depth: 0, marker: .bullet, text: "Common App"),
                    .init(depth: 0, marker: .bullet, text: "Essays"),
                ]))],
                "bullet \(bullet)"
            )
        }
    }

    func testOrderedListWithNonOneStart() {
        XCTAssertEqual(
            MarkdownBlock.parse("3. Third\n4. Fourth"),
            [.list(MarkdownList(items: [
                .init(depth: 0, marker: .ordered(ordinal: 3), text: "Third"),
                .init(depth: 0, marker: .ordered(ordinal: 4), text: "Fourth"),
            ]))]
        )
    }

    func testOrderedListRenumbersRepeatedOnes() {
        XCTAssertEqual(
            MarkdownBlock.parse("1. a\n1. b\n1. c"),
            [.list(MarkdownList(items: [
                .init(depth: 0, marker: .ordered(ordinal: 1), text: "a"),
                .init(depth: 0, marker: .ordered(ordinal: 2), text: "b"),
                .init(depth: 0, marker: .ordered(ordinal: 3), text: "c"),
            ]))]
        )
    }

    func testTwoLevelNestingMapsToDepth() {
        let blocks = MarkdownBlock.parse("- Applications\n  - Michigan\n  - Purdue\n- Essays")
        XCTAssertEqual(
            blocks,
            [.list(MarkdownList(items: [
                .init(depth: 0, marker: .bullet, text: "Applications"),
                .init(depth: 1, marker: .bullet, text: "Michigan"),
                .init(depth: 1, marker: .bullet, text: "Purdue"),
                .init(depth: 0, marker: .bullet, text: "Essays"),
            ]))]
        )
    }

    func testFourSpaceNestingIsAlsoDepthOne() {
        // Depth is a position in the hierarchy, not a column count: a reply
        // indented with four spaces must not render twice as deep as one
        // indented with two.
        let blocks = MarkdownBlock.parse("- Applications\n    - Michigan")
        guard case .list(let list) = blocks.first else { return XCTFail("expected a list, got \(blocks)") }
        XCTAssertEqual(list.items.map(\.depth), [0, 1])
    }

    func testTaskListItems() {
        let blocks = MarkdownBlock.parse("- [ ] Draft essay\n- [x] Fee waiver\n- Plain item")
        guard case .list(let list) = blocks.first else { return XCTFail("expected a list, got \(blocks)") }
        XCTAssertEqual(list.items.map(\.marker), [.task(done: false), .task(done: true), .bullet])
        XCTAssertEqual(list.items.map(\.text), ["Draft essay", "Fee waiver", "Plain item"])
    }

    func testWrappedBulletStaysOneItem() {
        // The commonest real shape in a coach reply, and the one the parser
        // used to break: an item whose text wraps onto an indented line is ONE
        // bullet, not a bullet plus a paragraph drawn back at the margin.
        XCTAssertEqual(
            MarkdownBlock.parse("- Submit the Common App\n  before November 1"),
            [.list(MarkdownList(items: [
                .init(depth: 0, marker: .bullet, text: "Submit the Common App\nbefore November 1"),
            ]))]
        )
    }

    func testWrappedOrderedItemStaysOneItem() {
        // Same rule for a numbered list, and it must not consume an ordinal:
        // the continuation is not a second step.
        XCTAssertEqual(
            MarkdownBlock.parse("1. Draft the essay\n   then read it aloud\n2. Send it"),
            [.list(MarkdownList(items: [
                .init(depth: 0, marker: .ordered(ordinal: 1), text: "Draft the essay\nthen read it aloud"),
                .init(depth: 0, marker: .ordered(ordinal: 2), text: "Send it"),
            ]))]
        )
    }

    func testUnindentedLineUnderAListStillEndsTheList() {
        // The deliberate counterpart to the two tests above: only an *indented*
        // line continues an item. A line at the margin reads as a new paragraph
        // to the student's eye, so that is what the renderer draws — CommonMark's
        // lazy continuation is not wanted here.
        XCTAssertEqual(
            MarkdownBlock.parse("- One\n- Two\nBack to prose."),
            [
                .list(MarkdownList(items: [
                    .init(depth: 0, marker: .bullet, text: "One"),
                    .init(depth: 0, marker: .bullet, text: "Two"),
                ])),
                .paragraph("Back to prose."),
            ]
        )
    }

    func testListInterruptedByAParagraph() {
        XCTAssertEqual(
            MarkdownBlock.parse("- One\n- Two\n\nBack to prose."),
            [
                .list(MarkdownList(items: [
                    .init(depth: 0, marker: .bullet, text: "One"),
                    .init(depth: 0, marker: .bullet, text: "Two"),
                ])),
                .paragraph("Back to prose."),
            ]
        )
    }

    func testLooseBulletListStaysOneList() {
        // CommonMark makes a blank line inside a list *loose*, not finished, and
        // a coach reply routinely double-spaces its items. Flushing on the blank
        // line split this into three lists and dropped the child to depth 0.
        XCTAssertEqual(
            MarkdownBlock.parse("- Applications\n\n  - Michigan\n\n- Essays"),
            [.list(MarkdownList(items: [
                .init(depth: 0, marker: .bullet, text: "Applications"),
                .init(depth: 1, marker: .bullet, text: "Michigan"),
                .init(depth: 0, marker: .bullet, text: "Essays"),
            ]))]
        )
    }

    func testLooseOrderedListKeepsCounting() {
        // The visible half of the same bug: `1.` / blank / `1.` rendered "1."
        // twice, because each gap reset the per-depth ordinal.
        XCTAssertEqual(
            MarkdownBlock.parse("1. a\n\n1. b\n\n1. c"),
            [.list(MarkdownList(items: [
                .init(depth: 0, marker: .ordered(ordinal: 1), text: "a"),
                .init(depth: 0, marker: .ordered(ordinal: 2), text: "b"),
                .init(depth: 0, marker: .ordered(ordinal: 3), text: "c"),
            ]))]
        )
    }

    func testBlankLineStillEndsAListWhenAnotherBlockTypeFollows() {
        // The counterpart: the list is loose, not immortal. A real new block
        // after the gap closes it, in the order the source had them.
        XCTAssertEqual(
            MarkdownBlock.parse("- One\n\n## Next steps"),
            [
                .list(MarkdownList(items: [.init(depth: 0, marker: .bullet, text: "One")])),
                .heading(level: .h2, text: "Next steps"),
            ]
        )
        XCTAssertEqual(
            MarkdownBlock.parse("- One\n\n> Quoted"),
            [
                .list(MarkdownList(items: [.init(depth: 0, marker: .bullet, text: "One")])),
                .quote(["Quoted"]),
            ]
        )
        XCTAssertEqual(
            MarkdownBlock.parse("- One\n\n```\ncode\n```"),
            [
                .list(MarkdownList(items: [.init(depth: 0, marker: .bullet, text: "One")])),
                .code("code"),
            ]
        )
    }

    func testEmphasisIsNotABulletItem() {
        // "*Important*" starts with a bullet character and must not be eaten as
        // a list item; the space after the marker is what separates them.
        XCTAssertEqual(MarkdownBlock.parse("*Important* deadline"), [.paragraph("*Important* deadline")])
    }

    // MARK: - Code

    func testInfoStringIsStrippedFromTheCodeBody() {
        // The info string is parsed and then discarded — the model carries only
        // what the renderer draws. What still matters, and is what this asserts,
        // is that `swift` never lands in the code the student reads.
        XCTAssertEqual(
            MarkdownBlock.parse("```swift\nlet x = 1\n```"),
            [.code("let x = 1")]
        )
    }

    func testFencedCodeWithoutInfoString() {
        XCTAssertEqual(
            MarkdownBlock.parse("```\nplain\n```"),
            [.code("plain")]
        )
    }

    func testTildeFence() {
        XCTAssertEqual(
            MarkdownBlock.parse("~~~python\nprint(1)\n~~~"),
            [.code("print(1)")]
        )
    }

    func testUnclosedFenceYieldsThePartialBlock() {
        // The streaming case: mid-delta a fence has an opener and no closer,
        // and the alternative is garbled paragraphs that snap into a code block
        // when the final backticks land.
        XCTAssertEqual(
            MarkdownBlock.parse("```swift\nlet x = 1\nlet y = 2"),
            [.code("let x = 1\nlet y = 2")]
        )
    }

    func testIndentationInsideAFenceIsPreservedVerbatim() {
        let source = "```\nfunc f() {\n    return 1\n}\n```"
        XCTAssertEqual(
            MarkdownBlock.parse(source),
            [.code("func f() {\n    return 1\n}")]
        )
    }

    func testMarkdownInsideAFenceIsNotParsed() {
        XCTAssertEqual(
            MarkdownBlock.parse("```\n# not a heading\n- not a list\n```"),
            [.code("# not a heading\n- not a list")]
        )
    }

    // MARK: - Tables

    private func table(from source: String, file: StaticString = #filePath, line: UInt = #line) -> MarkdownTable? {
        let blocks = MarkdownBlock.parse(source)
        guard case .table(let table) = blocks.first else {
            XCTFail("expected a table, got \(blocks)", file: file, line: line)
            return nil
        }
        return table
    }

    func testCanonicalThreeColumnTable() {
        let source = """
        | College | Deadline | Status |
        | --- | --- | --- |
        | Michigan | Nov 1 | Draft |
        | Purdue | Nov 15 | Not started |
        """
        guard let table = table(from: source) else { return }
        XCTAssertEqual(table.headers, ["College", "Deadline", "Status"])
        XCTAssertEqual(table.rows, [
            ["Michigan", "Nov 1", "Draft"],
            ["Purdue", "Nov 15", "Not started"],
        ])
        XCTAssertEqual(table.alignments, [.leading, .leading, .leading])
    }

    func testPerColumnAlignment() {
        let source = """
        | Left | Center | Right |
        | :--- | :---: | ---: |
        | a | b | c |
        """
        XCTAssertEqual(table(from: source)?.alignments, [.leading, .center, .trailing])
    }

    func testLeadingAndTrailingPipesAreOptional() {
        let source = """
        College | Deadline
        --- | ---
        Michigan | Nov 1
        """
        guard let table = table(from: source) else { return }
        XCTAssertEqual(table.headers, ["College", "Deadline"])
        XCTAssertEqual(table.rows, [["Michigan", "Nov 1"]])
    }

    func testRaggedRowsArePaddedAndTruncated() {
        let source = """
        | A | B | C |
        | --- | --- | --- |
        | short |
        | one | two | three | four |
        """
        guard let table = table(from: source) else { return }
        XCTAssertEqual(table.rows, [
            ["short", "", ""],
            ["one", "two", "three"],
        ])
        // Rectangular by construction — the renderer indexes rows by column.
        XCTAssertTrue(table.rows.allSatisfy { $0.count == table.headers.count })
    }

    func testEscapedPipeStaysInsideItsCell() {
        let source = """
        | Command | Note |
        | --- | --- |
        | ls \\| wc | counts files |
        """
        XCTAssertEqual(table(from: source)?.rows, [["ls | wc", "counts files"]])
    }

    func testHeaderAndDelimiterWithNoRowsIsAnEmptyTable() {
        // The streaming case: the table draws its header and grows downward
        // rather than showing pipe soup that becomes a table three deltas on.
        let source = """
        | College | Deadline |
        | --- | --- |
        """
        guard let table = table(from: source) else { return }
        XCTAssertEqual(table.headers, ["College", "Deadline"])
        XCTAssertTrue(table.rows.isEmpty)
    }

    func testPipesWithoutADelimiterRowAreAParagraph() {
        let source = "Applications | essays | tests"
        XCTAssertEqual(MarkdownBlock.parse(source), [.paragraph("Applications | essays | tests")])
    }

    func testTableEndsAtABlankLine() {
        let source = """
        | A | B |
        | --- | --- |
        | 1 | 2 |

        After the table.
        """
        let blocks = MarkdownBlock.parse(source)
        XCTAssertEqual(blocks.count, 2)
        XCTAssertEqual(blocks.last, .paragraph("After the table."))
    }

    // MARK: - Inline

    func testBoldCarriesAStrongIntent() {
        let string = MarkdownInline.attributed("Submit the **Common App** today")
        XCTAssertEqual(String(string.characters), "Submit the Common App today")
        XCTAssertTrue(intents(in: string).contains { $0.contains(.stronglyEmphasized) })
    }

    func testItalicCarriesAnEmphasisIntent() {
        let string = MarkdownInline.attributed("that is _optional_")
        XCTAssertEqual(String(string.characters), "that is optional")
        XCTAssertTrue(intents(in: string).contains { $0.contains(.emphasized) })
    }

    func testStrikethroughCarriesItsIntent() {
        let string = MarkdownInline.attributed("~~cancelled~~")
        XCTAssertEqual(String(string.characters), "cancelled")
        XCTAssertTrue(intents(in: string).contains { $0.contains(.strikethrough) })
    }

    func testInlineCodeCarriesTheMonospacedToken() {
        let string = MarkdownInline.attributed("run `bin/test` first")
        XCTAssertEqual(String(string.characters), "run bin/test first")
        let codeRun = string.runs.first { $0.inlinePresentationIntent?.contains(.code) == true }
        XCTAssertNotNil(codeRun, "expected a code run")
        XCTAssertEqual(codeRun?.font, .dsCode)
        // And nothing else picks the token up, or the whole reply is monospaced.
        XCTAssertTrue(string.runs.filter { $0.font == .dsCode }.count == 1)
    }

    func testLinkIsUnderlinedTextPrimaryRatherThanAnAccent() {
        let string = MarkdownInline.attributed("see [the guide](https://example.com)")
        let linkRun = string.runs.first { $0.link != nil }
        XCTAssertNotNil(linkRun, "expected a link run")
        XCTAssertEqual(linkRun?.link?.absoluteString, "https://example.com")
        XCTAssertEqual(linkRun?.underlineStyle, .single)
        XCTAssertEqual(linkRun?.foregroundColor, .dsTextPrimary)
    }

    func testMalformedLinkReturnsItsSourceText() {
        // A student must never lose text to a parse failure.
        let source = "[unclosed]("
        XCTAssertEqual(String(MarkdownInline.attributed(InlineMarkdown(source)).characters), source)
    }

    private func intents(in string: AttributedString) -> [InlinePresentationIntent] {
        string.runs.compactMap(\.inlinePresentationIntent)
    }

    // MARK: - Accessibility

    func testCellLabelSpeaksRenderedTextNotMarkdownSyntax() {
        // The defect this RFC exists to remove, reintroduced in the audio
        // channel: a bold cell was announced "star star Draft star star".
        XCTAssertEqual(
            MarkdownAccessibility.cellLabel(header: "**Status**", value: "**Draft**"),
            "Status: Draft"
        )
        XCTAssertEqual(MarkdownAccessibility.plain("run `bin/test`"), "run bin/test")
    }

    func testHeaderlessColumnSpeaksTheValueAlone() {
        // Not ": Michigan" — a stray separator is worse than no prefix.
        XCTAssertEqual(MarkdownAccessibility.cellLabel(header: "", value: "Michigan"), "Michigan")
    }

    // MARK: - Degenerate input

    func testEmptyStringParsesToNoBlocks() {
        XCTAssertEqual(MarkdownBlock.parse(""), [])
    }

    func testWhitespaceOnlyParsesToNoBlocks() {
        XCTAssertEqual(MarkdownBlock.parse("   \n\t\n  "), [])
    }

    func testOneHundredKilobyteSingleLineParsesPromptly() {
        // The contract maximum for a message, on one line — the shape most
        // likely to make a line-oriented parser degenerate.
        let source = String(repeating: "a", count: 100 * 1024)
        let started = Date()
        let blocks = MarkdownBlock.parse(source)
        XCTAssertEqual(blocks.count, 1)
        XCTAssertEqual(blocks.first, .paragraph(InlineMarkdown(source)))
        XCTAssertLessThan(Date().timeIntervalSince(started), 1.0)
    }

    // MARK: - Table column widths

    // `MarkdownTableLayout` is the second value-returning piece of this
    // feature, and it exists so this decision can be asserted at all: it is the
    // arithmetic that gives a table cell a DEFINITE width, without which a
    // wrapped cell reports a one-line height and draws over the row below it.

    // The defaults below deliberately spell the bounds as LITERALS rather than
    // reading DSSpacing/DSMarkdown: every assertion here is arithmetic worked
    // out by hand in its own comment ("64 + 64 + 16 = 144 exactly"), and a test
    // that computes its expectation from the same token as the code cannot
    // fail. The cost of that independence is that a retuned token would leave
    // this suite green while the shipped grid moved, so
    // `testTheFixtureBoundsStillMatchTheShippedTokens` pins the two together:
    // change a token and it fails, telling you to re-check the arithmetic here.
    private func widths(
        natural: [CGFloat],
        available: CGFloat,
        spacing: CGFloat = 16,
        minimum: CGFloat = 64,
        maximum: CGFloat = 220
    ) -> [CGFloat] {
        MarkdownTableLayout.columnWidths(
            natural: natural,
            available: available,
            spacing: spacing,
            minimum: minimum,
            maximum: maximum
        )
    }

    func testColumnsThatFitExactlyKeepTheirNaturalWidths() {
        // 120 + 16 + 100 = 236.
        XCTAssertEqual(widths(natural: [120, 100], available: 236), [120, 100])
    }

    func testColumnsThatFitWithRoomToSpareAreNotStretched() {
        // The whole of criterion D: a two-column table pulled out to the
        // bubble's full width reads as a layout accident, not as a table.
        XCTAssertEqual(widths(natural: [120, 100], available: 400), [120, 100])
    }

    func testAProseColumnIsCappedAtTheCeiling() {
        XCTAssertEqual(widths(natural: [900, 100], available: 400), [220, 100])
    }

    func testANarrowColumnIsFloored() {
        // "Yes" would otherwise collapse to the width of its own three
        // characters and leave the grid looking broken.
        XCTAssertEqual(widths(natural: [30, 120], available: 400), [64, 120])
    }

    func testTheColumnWithTheMostSlackPaysTheWholeDeficit() {
        // 200 + 100 + 16 = 316 wanted against 266 available: a 50pt deficit,
        // and the wide column has 136 of slack to cover it alone. A waterfall,
        // not a share — the narrow column is not touched at all.
        let result = widths(natural: [200, 100], available: 266)
        XCTAssertEqual(result, [150, 100])
        XCTAssertEqual(result.reduce(0, +) + 16, 266, accuracy: 0.01)
    }

    func testASmallDeficitDoesNotWrapAColumnSittingAtItsNaturalWidth() {
        // Ian's two-column table, and the defect a proportional shrink caused:
        // "School" clamps to the 220 ceiling and "Type" measures ~85 for
        // "Public (CC)", so 305 is wanted against 297 — a deficit of 8. Shared
        // by slack that is 7pt off School and 1pt off Type, and that single
        // point wrapped "Public (CC)" onto a second line, because a column at
        // its natural width has nothing to give. School pays all 8.
        XCTAssertEqual(widths(natural: [300, 85], available: 313), [212, 85])
    }

    func testADeficitLargerThanTheWidestSlackSpillsToTheNextColumn() {
        // 100 + 200 + 90 + 32 = 422 wanted against 272: a 150pt deficit. The
        // middle column is exhausted to the floor (136) and the remaining 14
        // falls to the next-most-slack column; the narrowest is never reached.
        XCTAssertEqual(widths(natural: [100, 200, 90], available: 272), [86, 64, 90])
    }

    func testTiedSlackBreaksOnColumnIndexRatherThanArbitrarily() {
        // Two identical columns and a 20pt deficit. Which one pays is a free
        // choice, but it must be the SAME free choice every time: a layout that
        // depended on hash or sort order would redraw differently on each SSE
        // delta of one streaming reply.
        XCTAssertEqual(widths(natural: [150, 150, 64], available: 376), [130, 150, 64])
    }

    func testAColumnAlreadyAtTheFloorPaysNothingTowardsTheDeficit() {
        // Slack, not width, is the basis: squeezing a column that is already at
        // its minimum is how the narrow columns become illegible slivers.
        let result = widths(natural: [200, 40], available: 250)
        XCTAssertEqual(result[1], 64)
        XCTAssertEqual(result[0], 170, accuracy: 0.01)
    }

    func testTooManyColumnsToFitAreNotSqueezedAtAllAndScroll() {
        // Five columns at the 64pt floor plus four 16pt gutters is 384, wider
        // than the 300 offered — so no amount of shrinking removes the scroll,
        // and shrinking that does not remove the scroll only costs legibility.
        // Squeezing this case to the floor hyphenated "Michigan" into
        // "Mi-chigan" in a captured render and STILL scrolled.
        XCTAssertEqual(widths(natural: [200, 200, 200, 200, 200], available: 300), [200, 200, 200, 200, 200])
        XCTAssertEqual(widths(natural: [80, 70, 90, 60, 100], available: 300), [80, 70, 90, 64, 100])
    }

    func testTheFixtureBoundsStillMatchTheShippedTokens() {
        // Not a test of the tokens' values — a tripwire for this file. The cases
        // above hardcode 16/64/220 so their arithmetic is checkable by eye; if a
        // token moves, that arithmetic is describing a grid we no longer ship,
        // and this is what says so instead of the suite quietly staying green.
        XCTAssertEqual(DSSpacing.md, 16)
        XCTAssertEqual(DSMarkdown.columnMinWidth, 64)
        XCTAssertEqual(DSMarkdown.columnMaxWidth, 220)
    }

    func testColumnsThatFitOnlyAtTheirFloorsAreSqueezedRatherThanScrolled() {
        // The boundary rule 4 stops one point above. 64 + 64 + one 16pt gutter
        // is 144 exactly, so the floors DO fit and shrinking removes the
        // scroll — which is the entire justification for shrinking. This
        // shipped as `floors < content`, folding "fits exactly" into "cannot
        // possibly fit" and scrolling a table that seats perfectly, while rule
        // 2 twelve lines up read the same equality as a fit.
        XCTAssertEqual(widths(natural: [200, 100], available: 144), [64, 64])
    }

    func testAnUnknownAvailableWidthFallsBackToNaturalWidths() {
        // The first layout pass, or an unspecified proposal. Answering as if it
        // fits is what keeps the measure → decide → measure cycle from
        // oscillating: the scroll view then reports that same width back.
        XCTAssertEqual(widths(natural: [900, 100], available: 0), [220, 100])
        XCTAssertEqual(widths(natural: [900, 100], available: -1), [220, 100])
    }

    func testNoColumnsIsNoWidths() {
        XCTAssertEqual(widths(natural: [], available: 300), [])
    }

    func testASingleColumnHasNoGutterToPayFor() {
        XCTAssertEqual(widths(natural: [200], available: 200), [200])
    }

    // MARK: - A whole reply

    func testWorstCaseReplyParsesIntoItsBlocks() {
        let blocks = MarkdownBlock.parse(MarkdownFixture.worstCaseReply)
        XCTAssertTrue(blocks.contains { if case .heading(.h2, _) = $0 { return true } else { return false } })
        XCTAssertTrue(blocks.contains { if case .list = $0 { return true } else { return false } })
        XCTAssertTrue(blocks.contains { if case .code = $0 { return true } else { return false } })
        guard let table = blocks.compactMap({ block -> MarkdownTable? in
            if case .table(let table) = block { return table } else { return nil }
        }).first else { return XCTFail("expected a table in the fixture") }
        XCTAssertEqual(table.headers.count, 5)
        XCTAssertTrue(table.rows.allSatisfy { $0.count == 5 })
    }
}


