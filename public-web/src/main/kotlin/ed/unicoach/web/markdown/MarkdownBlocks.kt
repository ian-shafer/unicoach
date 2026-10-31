package ed.unicoach.web.markdown

/**
 * A block-level element of a coach reply: a PORT of the iOS client's
 * `ios-app/UnicoachiOS/Markdown/MarkdownBlock.swift` (RFC 118), so the web and
 * the app cannot disagree about what the coach said (RFC 262 §9).
 *
 * The same model, flattened exactly as iOS flattens it: a list is a run of
 * items carrying an integer [MarkdownList.Item.depth] rather than a tree, a
 * quote is a flat list of lines, and a code block's info string is parsed off
 * and discarded. A renderer that built more structure than this would draw
 * more than the app does.
 *
 * Inline spans are NOT modelled here. Each block keeps its text as raw
 * [InlineMarkdown] for [MarkdownInline] to parse at render time, except a code
 * block, whose body is verbatim characters and must never be inline-parsed.
 *
 * NOT supported, exactly as on iOS: indented code blocks, nested quotes, block
 * structure inside a quote, HTML blocks, reference links, footnotes. Each of
 * them degrades to the characters it was written in.
 *
 * WHY A HAND-WRITTEN PORT, not a CommonMark library (RFC 262 §9, "No new
 * library"): the iOS block parser differs from CommonMark at PARSE time, so a
 * library's tree would have to be un-parsed to reach what the app draws. An
 * indented line is never a code block; a `>` inside a quote is a literal
 * character, not a nested quote, and a list, heading or fence inside a quote is
 * literal text too; list depth comes from a stack of the indents the list has
 * used, so 2- and 4-space nesting both reach depth 1, and the result is a flat
 * run of items, not a tree (`research/discover-markdown-subset.md` §1-2). The
 * parser escapes nothing and decides nothing about safety: `MarkdownHtml`
 * builds every element through kotlinx.html, which escapes every text node, so
 * a parser bug garbles a reply's rendering — it cannot inject HTML.
 */
sealed interface MarkdownBlock {
  data class Heading(
    val level: HeadingLevel,
    val text: InlineMarkdown,
  ) : MarkdownBlock

  data class Paragraph(
    val text: InlineMarkdown,
  ) : MarkdownBlock

  data class ItemList(
    val list: MarkdownList,
  ) : MarkdownBlock

  /** The lines of a `>` run, one `>` and one optional space already stripped. */
  data class Quote(
    val lines: List<InlineMarkdown>,
  ) : MarkdownBlock

  /** Verbatim characters: the one payload that is never inline-parsed. */
  data class Code(
    val body: String,
  ) : MarkdownBlock

  data class Table(
    val table: MarkdownTable,
  ) : MarkdownBlock

  data object Rule : MarkdownBlock

  companion object {
    /**
     * A single line-oriented pass over [source] — `MarkdownBlock.parse` on iOS.
     *
     * The three streaming behaviours iOS needs are kept although the web
     * renders only a completed reply: an unclosed fence yields the code so far,
     * a header plus delimiter with no rows is an empty table, and a trailing
     * partial line parses as whatever it looks like. They are what the app
     * draws for the same text, and matching the app is the point.
     */
    fun parse(source: String): List<MarkdownBlock> = BlockParser(source).run()
  }
}

/**
 * Markdown that still holds inline syntax: the renderer must put it through
 * [MarkdownInline] before it reaches a page. A wrapper rather than a bare
 * `String`, for the iOS reason: a code body and a paragraph's text carry
 * opposite contracts, and two interchangeable strings let either mistake
 * compile.
 */
@JvmInline
value class InlineMarkdown(
  val source: String,
)

/** The six levels Markdown has, and no seventh: a seventh `#` has no level, so it stays a paragraph. */
enum class HeadingLevel {
  H1,
  H2,
  H3,
  H4,
  H5,
  H6,
  ;

  companion object {
    fun of(hashes: Int): HeadingLevel? = entries.getOrNull(hashes - 1)
  }
}

/** A run of list items, nesting FLATTENED into [Item.depth] as iOS draws it: an indent, not a tree. */
data class MarkdownList(
  val items: List<Item>,
) {
  data class Item(
    /** 0 for a top-level item, 1 for its children, and so on — unbounded. */
    val depth: Int,
    val marker: Marker,
    val text: InlineMarkdown,
  ) {
    /** A wrapped continuation line belongs to the item above it, joined with a newline. */
    fun continuing(line: String): Item = copy(text = InlineMarkdown(text.source + "\n" + line))
  }

  /** What draws in the leading column. A task's checkbox REPLACES its bullet or number. */
  sealed interface Marker {
    data object Bullet : Marker

    /** The item's position in its run, RESOLVED at parse time so the renderer never counts rows. */
    data class Ordered(
      val ordinal: Int,
    ) : Marker

    data class Task(
      val done: Boolean,
    ) : Marker
  }
}

/**
 * A GFM table, rectangular BY CONSTRUCTION: [of] pads and truncates the
 * alignments and every row to the header count, so the renderer indexes a
 * column unguarded.
 */
data class MarkdownTable private constructor(
  val headers: List<InlineMarkdown>,
  val alignments: List<Alignment>,
  val rows: List<List<InlineMarkdown>>,
) {
  /** GFM's unspecified default is [LEADING]; an absent `:` is not a third state. */
  enum class Alignment { LEADING, CENTER, TRAILING }

  companion object {
    fun of(
      headers: List<InlineMarkdown>,
      alignments: List<Alignment>,
      rows: List<List<InlineMarkdown>>,
    ): MarkdownTable =
      MarkdownTable(
        headers,
        fit(alignments, headers.size, Alignment.LEADING),
        rows.map { fit(it, headers.size, InlineMarkdown("")) },
      )

    private fun <T> fit(
      values: List<T>,
      count: Int,
      pad: T,
    ): List<T> = if (values.size >= count) values.take(count) else values + List(count - values.size) { pad }
  }
}

/**
 * Swift's `trimmingCharacters(in: .whitespaces)`: spaces, tabs and the other
 * space separators — and NOT newlines, which a Kotlin `trim()` would also eat.
 */
private fun Char.isLineSpace(): Boolean = this == '\t' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

private fun String.trimLineSpace(): String = trim { it.isLineSpace() }

private fun String.leadingIndent(): Int = takeWhile { it == ' ' || it == '\t' }.length

/** The line-oriented parser; one instance per parse, as iOS's `Parser` struct is. */
private class BlockParser(
  source: String,
) {
  // Blank lines are the block separator, so they are kept; a trailing `\r` is
  // a CRLF source's, not content.
  private val lines: List<String> = source.split("\n").map { it.removeSuffix("\r") }
  private val blocks = mutableListOf<MarkdownBlock>()

  /** Held rather than emitted, because a following `---` turns it into a setext heading. */
  private val paragraph = mutableListOf<String>()
  private val quote = mutableListOf<String>()
  private val listItems = mutableListOf<MarkdownList.Item>()
  private var listIsOrdered = false

  /** Indent columns of the open list, innermost last: depth is a position in this stack, not `indent / 2`. */
  private val listIndents = mutableListOf<Int>()

  /** Next ordinal per depth, so `1.` `1.` `1.` renders 1, 2, 3. */
  private val ordinals = mutableMapOf<Int, Int>()

  fun run(): List<MarkdownBlock> {
    var index = 0
    while (index < lines.size) index = consumeLine(index)
    flushAll()
    return blocks
  }

  /**
   * One step of the pass: the line at [index] goes to the first block kind, in
   * this order, that claims it, and the index after what that kind took comes
   * back. The ORDER is the rule: setext is tried before the thematic break,
   * because `---` under a paragraph underlines it.
   */
  private fun consumeLine(index: Int): Int {
    val line = lines[index]
    val trimmed = line.trimLineSpace()
    if (trimmed.isEmpty()) return blankLine(index)
    return fence(index, trimmed)
      ?: atxHeading(index, trimmed)
      ?: setextHeading(index, trimmed)
      ?: thematicBreak(index, trimmed)
      ?: quoteLine(index, trimmed)
      ?: table(index, line)
      ?: listItem(index, line)
      ?: listContinuation(index, line, trimmed)
      ?: paragraphLine(index, trimmed)
  }

  /** A blank line ends a paragraph and a quote. It makes a list LOOSE; it does not end it. */
  private fun blankLine(index: Int): Int {
    flushParagraph()
    flushQuote()
    return index + 1
  }

  private fun fence(
    index: Int,
    trimmed: String,
  ): Int? {
    val fence = fenceOpener(trimmed) ?: return null
    flushAll()
    return consumeFence(index, fence)
  }

  private fun atxHeading(
    index: Int,
    trimmed: String,
  ): Int? {
    val heading = atxHeadingOf(trimmed) ?: return null
    flushAll()
    blocks += heading
    return index + 1
  }

  /** `===` or `---` under a held paragraph: the paragraph, joined with spaces, becomes the heading. */
  private fun setextHeading(
    index: Int,
    trimmed: String,
  ): Int? {
    if (paragraph.isEmpty()) return null
    val level = setextLevel(trimmed) ?: return null
    val text = paragraph.joinToString(" ")
    paragraph.clear()
    blocks += MarkdownBlock.Heading(level, InlineMarkdown(text))
    return index + 1
  }

  private fun thematicBreak(
    index: Int,
    trimmed: String,
  ): Int? {
    if (!isThematicBreak(trimmed)) return null
    flushAll()
    blocks += MarkdownBlock.Rule
    return index + 1
  }

  /** One `>` and one optional space stripped; a quote holds lines, never blocks. */
  private fun quoteLine(
    index: Int,
    trimmed: String,
  ): Int? {
    if (!trimmed.startsWith(">")) return null
    flushParagraph()
    flushList()
    quote += trimmed.drop(1).removePrefix(" ")
    return index + 1
  }

  /** A table exists only once its delimiter row is here; a pipe line alone is a paragraph. */
  private fun table(
    index: Int,
    line: String,
  ): Int? {
    if (index + 1 >= lines.size || !containsUnescapedPipe(line)) return null
    val (table, next) = parseTable(lines, index) ?: return null
    flushAll()
    blocks += MarkdownBlock.Table(table)
    return next
  }

  private fun listItem(
    index: Int,
    line: String,
  ): Int? {
    val item = listItemOf(line) ?: return null
    flushParagraph()
    flushQuote()
    append(item)
    return index + 1
  }

  /** A line indented past the innermost marker continues that item. */
  private fun listContinuation(
    index: Int,
    line: String,
    trimmed: String,
  ): Int? {
    if (listItems.isEmpty() || line.leadingIndent() <= (listIndents.lastOrNull() ?: 0)) return null
    listItems[listItems.lastIndex] = listItems.last().continuing(trimmed)
    return index + 1
  }

  /** What no other kind claimed. An UNINDENTED plain line under a list ends the list. */
  private fun paragraphLine(
    index: Int,
    trimmed: String,
  ): Int {
    flushList()
    flushQuote()
    paragraph += trimmed
    return index + 1
  }

  /** Consumes a fence from [index]; an unterminated fence takes the rest of the input. */
  private fun consumeFence(
    index: Int,
    fence: Fence,
  ): Int {
    val body = mutableListOf<String>()
    var cursor = index + 1
    while (cursor < lines.size) {
      if (isFenceCloser(lines[cursor].trimLineSpace(), fence)) {
        blocks += MarkdownBlock.Code(body.joinToString("\n"))
        return cursor + 1
      }
      // Verbatim: a fence keeps its own indentation.
      body += lines[cursor]
      cursor += 1
    }
    blocks += MarkdownBlock.Code(body.joinToString("\n"))
    return cursor
  }

  private fun append(parsed: ListItemLine) {
    val isOrdered = parsed.ordinal != null
    // A bullet run and an ordered run are two lists, blank line or not.
    if (listItems.isNotEmpty() && listIsOrdered != isOrdered) flushList()
    listIsOrdered = isOrdered

    val depth = depthFor(parsed.indent)
    // A depth that has just closed its children must not resume their counter.
    ordinals.keys.removeAll { it > depth }
    val glyph: MarkdownList.Marker =
      if (parsed.ordinal != null) {
        // The first item at a depth sets the start; the rest count on from it.
        // A task item still consumes its ordinal.
        val resolved = ordinals[depth] ?: parsed.ordinal
        ordinals[depth] = resolved + 1
        MarkdownList.Marker.Ordered(resolved)
      } else {
        MarkdownList.Marker.Bullet
      }
    listItems +=
      MarkdownList.Item(
        depth = depth,
        marker = parsed.checked?.let { MarkdownList.Marker.Task(it) } ?: glyph,
        text = InlineMarkdown(parsed.text),
      )
  }

  /** Maps an indent onto a depth through the stack of indents this list has used. */
  private fun depthFor(indent: Int): Int {
    if (listIndents.isEmpty()) {
      listIndents += indent
      return 0
    }
    if (indent > listIndents.last()) {
      listIndents += indent
      return listIndents.lastIndex
    }
    while (listIndents.size > 1 && indent < listIndents.last()) listIndents.removeAt(listIndents.lastIndex)
    return listIndents.lastIndex
  }

  private fun flushParagraph() {
    if (paragraph.isEmpty()) return
    // Joined with "\n": soft breaks are kept and drawn, as on iOS.
    blocks += MarkdownBlock.Paragraph(InlineMarkdown(paragraph.joinToString("\n")))
    paragraph.clear()
  }

  private fun flushQuote() {
    if (quote.isEmpty()) return
    blocks += MarkdownBlock.Quote(quote.map(::InlineMarkdown))
    quote.clear()
  }

  private fun flushList() {
    if (listItems.isEmpty()) return
    blocks += MarkdownBlock.ItemList(MarkdownList(listItems.toList()))
    listItems.clear()
    listIndents.clear()
    ordinals.clear()
  }

  private fun flushAll() {
    flushParagraph()
    flushQuote()
    flushList()
  }
}

/** An open fence: the delimiter and run length a closer has to match. The info string is not carried. */
private class Fence(
  val delimiter: Char,
  val length: Int,
)

/** One source line recognised as a list item, before its depth and ordinal are resolved. */
private class ListItemLine(
  val indent: Int,
  /** Null for a bullet; the written number for an ordered item. */
  val ordinal: Int?,
  val text: String,
  /** GFM task state; null when the item is not a task. */
  val checked: Boolean?,
)

private fun fenceOpener(trimmed: String): Fence? {
  val first = trimmed.firstOrNull() ?: return null
  if (first != '`' && first != '~') return null
  val run = trimmed.takeWhile { it == first }.length
  if (run < 3) return null
  val info = trimmed.drop(run).trimLineSpace()
  // A backtick inside the info string is not an info string (CommonMark).
  if (first == '`' && info.contains('`')) return null
  return Fence(first, run)
}

private fun isFenceCloser(
  trimmed: String,
  fence: Fence,
): Boolean {
  if (trimmed.firstOrNull() != fence.delimiter) return false
  val run = trimmed.takeWhile { it == fence.delimiter }.length
  return run >= fence.length && trimmed.drop(run).trimLineSpace().isEmpty()
}

private fun atxHeadingOf(trimmed: String): MarkdownBlock.Heading? {
  if (!trimmed.startsWith("#")) return null
  val hashes = trimmed.takeWhile { it == '#' }.length
  val level = HeadingLevel.of(hashes) ?: return null
  val rest = trimmed.drop(hashes)
  // The space is what separates a heading from a `#hashtag`.
  if (rest.isNotEmpty() && rest.first() != ' ') return null
  val text = rest.trimLineSpace().trimEnd('#')
  return MarkdownBlock.Heading(level, InlineMarkdown(text.trimLineSpace()))
}

private fun setextLevel(trimmed: String): HeadingLevel? {
  val first = trimmed.firstOrNull() ?: return null
  if (first != '=' && first != '-') return null
  if (!trimmed.all { it == first }) return null
  return if (first == '=') HeadingLevel.H1 else HeadingLevel.H2
}

private fun isThematicBreak(trimmed: String): Boolean {
  val stripped = trimmed.filterNot { it.isWhitespace() }
  val first = stripped.firstOrNull() ?: return false
  if (stripped.length < 3 || (first != '-' && first != '*' && first != '_')) return false
  return stripped.all { it == first }
}

private fun listItemOf(line: String): ListItemLine? {
  val indent = line.leadingIndent()
  val body = line.drop(indent)
  val first = body.firstOrNull() ?: return null

  val ordinal: Int?
  val rest: String
  if (first == '-' || first == '*' || first == '+') {
    rest = body.drop(1)
    // "- " is an item; "---" is a rule and "*bold*" is prose.
    if (rest.firstOrNull() != ' ') return null
    ordinal = null
  } else if (first.isDigit()) {
    val digits = body.takeWhile { it.isDigit() }
    val afterDigits = body.drop(digits.length)
    val delimiter = afterDigits.firstOrNull()
    if (delimiter != '.' && delimiter != ')') return null
    rest = afterDigits.drop(1)
    if (rest.firstOrNull() != ' ') return null
    // An ordinal too large for an Int restarts at 1, as on iOS.
    ordinal = digits.toIntOrNull() ?: 1
  } else {
    return null
  }

  var text = rest.trimLineSpace()
  var checked: Boolean? = null
  if (text.startsWith("[ ] ") || text == "[ ]") {
    checked = false
    text = text.drop(3).trimLineSpace()
  } else if (text.lowercase().startsWith("[x] ") || text.lowercase() == "[x]") {
    checked = true
    text = text.drop(3).trimLineSpace()
  }
  return ListItemLine(indent, ordinal, text, checked)
}

/**
 * The one place table backslash escaping is decided: splits on UNESCAPED `|`
 * only. `\|` becomes a literal pipe; any other `\x` keeps its backslash for the
 * inline layer.
 */
private fun rawCells(line: String): List<String> {
  val cells = mutableListOf<String>()
  val current = StringBuilder()
  var escaped = false
  for (character in line) {
    if (escaped) {
      if (character != '|') current.append('\\')
      current.append(character)
      escaped = false
    } else if (character == '\\') {
      escaped = true
    } else if (character == '|') {
      cells += current.toString()
      current.clear()
    } else {
      current.append(character)
    }
  }
  if (escaped) current.append('\\')
  cells += current.toString()
  return cells
}

private fun containsUnescapedPipe(line: String): Boolean = rawCells(line).size > 1

/** [rawCells], trimmed, with the empty edge cells of optional leading/trailing pipes dropped. */
private fun splitRow(line: String): List<String> {
  val cells = rawCells(line).toMutableList()
  if (cells.firstOrNull()?.trimLineSpace()?.isEmpty() == true) cells.removeAt(0)
  if (cells.lastOrNull()?.trimLineSpace()?.isEmpty() == true) cells.removeAt(cells.lastIndex)
  return cells.map { it.trimLineSpace() }
}

private fun delimiterAlignments(line: String): List<MarkdownTable.Alignment>? {
  val trimmed = line.trimLineSpace()
  if (trimmed.isEmpty() || !trimmed.all { it in "-:| \t" }) return null
  val cells = splitRow(trimmed)
  if (cells.isEmpty()) return null
  return cells.map { cell ->
    val leading = cell.startsWith(":")
    val trailing = cell.endsWith(":")
    val dashes = cell.trim(':')
    if (dashes.isEmpty() || !dashes.all { it == '-' }) return null
    when {
      leading && trailing -> MarkdownTable.Alignment.CENTER
      trailing -> MarkdownTable.Alignment.TRAILING
      else -> MarkdownTable.Alignment.LEADING
    }
  }
}

/** The table starting at [index] and the index after it, or null when this is not a table. */
private fun parseTable(
  lines: List<String>,
  index: Int,
): Pair<MarkdownTable, Int>? {
  val headers = splitRow(lines[index]).map(::InlineMarkdown)
  // A single-column table has had its edge pipes dropped; the raw line tells it from prose.
  if (headers.size <= 1 && !containsUnescapedPipe(lines[index])) return null
  if (index + 1 >= lines.size) return null
  val alignments = delimiterAlignments(lines[index + 1]) ?: return null
  if (alignments.size != headers.size) return null

  val rows = mutableListOf<List<InlineMarkdown>>()
  var cursor = index + 2
  while (cursor < lines.size) {
    val line = lines[cursor]
    if (line.trimLineSpace().isEmpty() || !containsUnescapedPipe(line)) break
    rows += splitRow(line).map(::InlineMarkdown)
    cursor += 1
  }
  return MarkdownTable.of(headers, alignments, rows) to cursor
}
