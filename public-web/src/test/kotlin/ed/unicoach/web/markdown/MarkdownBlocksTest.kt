package ed.unicoach.web.markdown

import ed.unicoach.web.markdown.MarkdownList.Marker
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The block parser against the iOS client's own corpus: a Kotlin translation
 * of every block case in `ios-app/UnicoachiOSTests/MarkdownParserTests.swift`
 * (source in, blocks out), plus the PROBE cases of
 * `research/discover-markdown-subset.md` §2 that the Swift suite never pinned.
 * Where the two clients disagree here, the web is wrong.
 */
class MarkdownBlocksTest {
  private fun parse(source: String) = MarkdownBlock.parse(source)

  private fun paragraph(text: String) = MarkdownBlock.Paragraph(InlineMarkdown(text))

  private fun heading(
    level: HeadingLevel,
    text: String,
  ) = MarkdownBlock.Heading(level, InlineMarkdown(text))

  private fun item(
    depth: Int,
    marker: Marker,
    text: String,
  ) = MarkdownList.Item(depth, marker, InlineMarkdown(text))

  private fun list(vararg items: MarkdownList.Item) = MarkdownBlock.ItemList(MarkdownList(items.toList()))

  private fun bullet(
    text: String,
    depth: Int = 0,
  ) = item(depth, Marker.Bullet, text)

  private fun ordered(
    n: Int,
    text: String,
  ) = item(0, Marker.Ordered(n), text)

  private fun quote(vararg lines: String) = MarkdownBlock.Quote(lines.map(::InlineMarkdown))

  private fun table(source: String): MarkdownTable = assertIs<MarkdownBlock.Table>(parse(source).first()).table

  private fun cells(vararg values: String) = values.map(::InlineMarkdown)

  // --- Blocks (MarkdownParserTests.swift:14-83) ---

  @Test
  fun `headings one through six`() {
    for (level in 1..6) {
      assertEquals(listOf(heading(HeadingLevel.of(level)!!, "Your next steps")), parse("#".repeat(level) + " Your next steps"))
    }
  }

  @Test
  fun `seven hashes is not a heading`() {
    assertEquals(listOf(paragraph("####### too deep")), parse("####### too deep"))
  }

  @Test
  fun `a hashtag is not a heading`() {
    assertEquals(listOf(paragraph("#firstgen applicants")), parse("#firstgen applicants"))
  }

  @Test
  fun `closing hashes are stripped`() {
    assertEquals(listOf(heading(HeadingLevel.H2, "Deadlines")), parse("## Deadlines ##"))
  }

  @Test
  fun `paragraphs split on blank lines`() {
    assertEquals(listOf(paragraph("First paragraph."), paragraph("Second paragraph.")), parse("First paragraph.\n\nSecond paragraph."))
  }

  @Test
  fun `soft line breaks are preserved within a paragraph`() {
    assertEquals(listOf(paragraph("Line one\nLine two")), parse("Line one\nLine two"))
  }

  @Test
  fun `thematic breaks`() {
    assertEquals(listOf(MarkdownBlock.Rule), parse("---"))
    assertEquals(listOf(MarkdownBlock.Rule), parse("***"))
    assertEquals(listOf(MarkdownBlock.Rule), parse("- - -"))
  }

  @Test
  fun `dashes under a paragraph are a setext heading, not a rule`() {
    assertEquals(listOf(heading(HeadingLevel.H2, "Your next steps")), parse("Your next steps\n---"))
    assertEquals(listOf(heading(HeadingLevel.H1, "Your next steps")), parse("Your next steps\n==="))
  }

  @Test
  fun `a rule after a blank line is still a rule`() {
    assertEquals(listOf(paragraph("Prose."), MarkdownBlock.Rule), parse("Prose.\n\n---"))
  }

  @Test
  fun `a blockquote run collects its lines`() {
    assertEquals(listOf(quote("first", "second")), parse("> first\n> second"))
  }

  // --- Lists (:85-256) ---

  @Test
  fun `bullet markers`() {
    for (marker in listOf("-", "*", "+")) {
      assertEquals(listOf(list(bullet("Common App"), bullet("Essays"))), parse("$marker Common App\n$marker Essays"), "bullet $marker")
    }
  }

  @Test
  fun `an ordered list with a non-one start`() {
    assertEquals(listOf(list(ordered(3, "Third"), ordered(4, "Fourth"))), parse("3. Third\n4. Fourth"))
  }

  @Test
  fun `an ordered list renumbers repeated ones`() {
    assertEquals(listOf(list(ordered(1, "a"), ordered(2, "b"), ordered(3, "c"))), parse("1. a\n1. b\n1. c"))
  }

  @Test
  fun `two-level nesting maps to depth`() {
    assertEquals(
      listOf(list(bullet("Applications"), bullet("Michigan", 1), bullet("Purdue", 1), bullet("Essays"))),
      parse("- Applications\n  - Michigan\n  - Purdue\n- Essays"),
    )
  }

  @Test
  fun `four-space nesting is also depth one`() {
    val items = assertIs<MarkdownBlock.ItemList>(parse("- Applications\n    - Michigan").first()).list.items
    assertEquals(listOf(0, 1), items.map { it.depth })
  }

  @Test
  fun `task list items`() {
    val items = assertIs<MarkdownBlock.ItemList>(parse("- [ ] Draft essay\n- [x] Fee waiver\n- Plain item").first()).list.items
    assertEquals(listOf(Marker.Task(false), Marker.Task(true), Marker.Bullet), items.map { it.marker })
    assertEquals(listOf("Draft essay", "Fee waiver", "Plain item"), items.map { it.text.source })
  }

  @Test
  fun `a wrapped bullet stays one item`() {
    assertEquals(listOf(list(bullet("Submit the Common App\nbefore November 1"))), parse("- Submit the Common App\n  before November 1"))
  }

  @Test
  fun `a wrapped ordered item stays one item`() {
    assertEquals(
      listOf(list(ordered(1, "Draft the essay\nthen read it aloud"), ordered(2, "Send it"))),
      parse("1. Draft the essay\n   then read it aloud\n2. Send it"),
    )
  }

  @Test
  fun `an unindented line under a list still ends the list`() {
    assertEquals(listOf(list(bullet("One"), bullet("Two")), paragraph("Back to prose.")), parse("- One\n- Two\nBack to prose."))
  }

  @Test
  fun `a list interrupted by a paragraph`() {
    assertEquals(listOf(list(bullet("One"), bullet("Two")), paragraph("Back to prose.")), parse("- One\n- Two\n\nBack to prose."))
  }

  @Test
  fun `a loose bullet list stays one list`() {
    assertEquals(
      listOf(list(bullet("Applications"), bullet("Michigan", 1), bullet("Essays"))),
      parse("- Applications\n\n  - Michigan\n\n- Essays"),
    )
  }

  @Test
  fun `a loose ordered list keeps counting`() {
    assertEquals(listOf(list(ordered(1, "a"), ordered(2, "b"), ordered(3, "c"))), parse("1. a\n\n1. b\n\n1. c"))
  }

  @Test
  fun `a blank line still ends a list when another block type follows`() {
    assertEquals(listOf(list(bullet("One")), heading(HeadingLevel.H2, "Next steps")), parse("- One\n\n## Next steps"))
    assertEquals(listOf(list(bullet("One")), quote("Quoted")), parse("- One\n\n> Quoted"))
    assertEquals(listOf(list(bullet("One")), MarkdownBlock.Code("code")), parse("- One\n\n```\ncode\n```"))
  }

  @Test
  fun `emphasis is not a bullet item`() {
    assertEquals(listOf(paragraph("*Important* deadline")), parse("*Important* deadline"))
  }

  // --- Code (:258-307) ---

  @Test
  fun `the info string is stripped from the code body`() {
    assertEquals(listOf(MarkdownBlock.Code("let x = 1")), parse("```swift\nlet x = 1\n```"))
  }

  @Test
  fun `a fenced code block without an info string`() {
    assertEquals(listOf(MarkdownBlock.Code("plain")), parse("```\nplain\n```"))
  }

  @Test
  fun `a tilde fence`() {
    assertEquals(listOf(MarkdownBlock.Code("print(1)")), parse("~~~python\nprint(1)\n~~~"))
  }

  @Test
  fun `an unclosed fence yields the partial block`() {
    assertEquals(listOf(MarkdownBlock.Code("let x = 1\nlet y = 2")), parse("```swift\nlet x = 1\nlet y = 2"))
  }

  @Test
  fun `indentation inside a fence is preserved verbatim`() {
    assertEquals(listOf(MarkdownBlock.Code("func f() {\n    return 1\n}")), parse("```\nfunc f() {\n    return 1\n}\n```"))
  }

  @Test
  fun `markdown inside a fence is not parsed`() {
    assertEquals(listOf(MarkdownBlock.Code("# not a heading\n- not a list")), parse("```\n# not a heading\n- not a list\n```"))
  }

  // --- Tables (:309-415) ---

  @Test
  fun `the canonical three-column table`() {
    val table =
      table("| College | Deadline | Status |\n| --- | --- | --- |\n| Michigan | Nov 1 | Draft |\n| Purdue | Nov 15 | Not started |")
    assertEquals(cells("College", "Deadline", "Status"), table.headers)
    assertEquals(listOf(cells("Michigan", "Nov 1", "Draft"), cells("Purdue", "Nov 15", "Not started")), table.rows)
    assertEquals(List(3) { MarkdownTable.Alignment.LEADING }, table.alignments)
  }

  @Test
  fun `per-column alignment`() {
    assertEquals(
      listOf(MarkdownTable.Alignment.LEADING, MarkdownTable.Alignment.CENTER, MarkdownTable.Alignment.TRAILING),
      table("| Left | Center | Right |\n| :--- | :---: | ---: |\n| a | b | c |").alignments,
    )
  }

  @Test
  fun `leading and trailing pipes are optional`() {
    val table = table("College | Deadline\n--- | ---\nMichigan | Nov 1")
    assertEquals(cells("College", "Deadline"), table.headers)
    assertEquals(listOf(cells("Michigan", "Nov 1")), table.rows)
  }

  @Test
  fun `ragged rows are padded and truncated`() {
    val table = table("| A | B | C |\n| --- | --- | --- |\n| short |\n| one | two | three | four |")
    assertEquals(listOf(cells("short", "", ""), cells("one", "two", "three")), table.rows)
    assertTrue(table.rows.all { it.size == table.headers.size })
  }

  @Test
  fun `an escaped pipe stays inside its cell`() {
    assertEquals(listOf(cells("ls | wc", "counts files")), table("| Command | Note |\n| --- | --- |\n| ls \\| wc | counts files |").rows)
  }

  @Test
  fun `a header and delimiter with no rows is an empty table`() {
    val table = table("| College | Deadline |\n| --- | --- |")
    assertEquals(cells("College", "Deadline"), table.headers)
    assertTrue(table.rows.isEmpty())
  }

  @Test
  fun `pipes without a delimiter row are a paragraph`() {
    assertEquals(listOf(paragraph("Applications | essays | tests")), parse("Applications | essays | tests"))
  }

  @Test
  fun `a table ends at a blank line`() {
    val blocks = parse("| A | B |\n| --- | --- |\n| 1 | 2 |\n\nAfter the table.")
    assertEquals(2, blocks.size)
    assertEquals(paragraph("After the table."), blocks.last())
  }

  // --- Degenerate input (:498-517) ---

  @Test
  fun `the empty string parses to no blocks`() {
    assertEquals(emptyList(), parse(""))
  }

  @Test
  fun `whitespace only parses to no blocks`() {
    assertEquals(emptyList(), parse("   \n\t\n  "))
  }

  @Test
  fun `a hundred-kilobyte single line parses promptly`() {
    val source = "a".repeat(100 * 1024)
    val started = System.nanoTime()
    assertEquals(listOf(paragraph(source)), parse(source))
    assertTrue((System.nanoTime() - started) < 1_000_000_000L, "a contract-maximum message must parse in under a second")
  }

  // --- The research PROBE cases (discover-markdown-subset.md §1-2) ---

  @Test
  fun `trailing double spaces are not a hard break, the line is trimmed`() {
    assertEquals(listOf(paragraph("line one\nline two")), parse("line one  \nline two"))
  }

  @Test
  fun `a setext underline must be one unbroken character, so spaced dashes are a rule`() {
    assertEquals(listOf(paragraph("Title"), MarkdownBlock.Rule), parse("Title\n- - -"))
  }

  @Test
  fun `underscores make a rule`() {
    assertEquals(listOf(MarkdownBlock.Rule), parse("___"))
  }

  @Test
  fun `a blank line ends a quote and a second run is a second quote`() {
    assertEquals(listOf(quote("one"), quote("two")), parse("> one\n\n> two"))
  }

  @Test
  fun `a fence body keeps the opener's indent`() {
    assertEquals(listOf(MarkdownBlock.Code("  code")), parse("  ```\n  code\n  ```"))
  }

  @Test
  fun `a closing paren is an ordered delimiter too`() {
    assertEquals(listOf(list(ordered(1, "one"), ordered(2, "two"))), parse("1) one\n2) two"))
  }

  @Test
  fun `two, four and six space nesting give depths zero to three`() {
    val items = assertIs<MarkdownBlock.ItemList>(parse("- a\n  - b\n      - c\n          - d").first()).list.items
    assertEquals(listOf(0, 1, 2, 3), items.map { it.depth })
  }

  @Test
  fun `a bullet run followed by an ordered run is two lists`() {
    assertEquals(listOf(list(bullet("a")), list(ordered(1, "b"))), parse("- a\n1. b"))
  }

  @Test
  fun `indented code is a paragraph with its indent stripped`() {
    assertEquals(listOf(paragraph("let x = 1")), parse("    let x = 1"))
  }

  @Test
  fun `a nested quote is one quote with a literal marker`() {
    assertEquals(listOf(quote("outer", "> inner")), parse("> outer\n> > inner"))
  }

  @Test
  fun `block structure inside a quote is literal text`() {
    assertEquals(listOf(quote("- one", "- two")), parse("> - one\n> - two"))
    assertEquals(listOf(quote("## Title")), parse("> ## Title"))
  }

  @Test
  fun `an HTML block is a paragraph of its characters`() {
    assertEquals(listOf(paragraph("<div>\nhi\n</div>")), parse("<div>\nhi\n</div>"))
  }

  @Test
  fun `the worst-case reply parses into its blocks`() {
    val blocks = parse(MarkdownFixture.worstCaseReply)
    assertTrue(blocks.any { it is MarkdownBlock.Heading && it.level == HeadingLevel.H2 })
    assertTrue(blocks.any { it is MarkdownBlock.ItemList })
    assertTrue(blocks.any { it is MarkdownBlock.Code })
    val table = blocks.filterIsInstance<MarkdownBlock.Table>().first().table
    assertEquals(5, table.headers.size)
    assertTrue(table.rows.all { it.size == 5 })
  }
}
