package ed.unicoach.web.markdown

import java.util.regex.Pattern

/**
 * An inline span of a coach reply, as the iOS client draws it.
 *
 * iOS does not parse inline Markdown itself: it hands each block's text to
 * Foundation's `AttributedString(markdown:)` (`MarkdownInline.swift`), which is
 * closed source. This model is what that parser was PROBED to produce
 * (`research/discover-markdown-subset.md` §3), and nothing wider: there is no
 * image, no raw HTML and no hard-break node, because iOS draws none of them.
 */
sealed interface Inline {
  data class Text(
    val text: String,
  ) : Inline

  data class Strong(
    val children: List<Inline>,
  ) : Inline

  data class Emphasis(
    val children: List<Inline>,
  ) : Inline

  data class Strikethrough(
    val children: List<Inline>,
  ) : Inline

  data class Code(
    val text: String,
  ) : Inline

  /**
   * A link. Its [text] is PLAIN: emphasis inside a link is lost, as on iOS, and
   * an image inside one is its alt text. [destination] is exactly what the
   * source said; whether it may become an `href` is the renderer's policy,
   * never the parser's.
   */
  data class Link(
    val destination: String,
    val text: String,
  ) : Inline
}

/**
 * The inline parser: a CommonMark inline pass (backslash escapes, code spans,
 * entities, autolinks, raw-HTML-as-text, inline links and the delimiter-run
 * emphasis algorithm) plus the GFM extras Foundation applies — `~one~` and
 * `~~two~~` strikethrough, and bare `https://`, `www.` and email autolinks.
 *
 * Every construct it does not recognise stays as its own characters, so a
 * student never loses text to a parse failure. It produces DATA: nothing here
 * decides what is safe to put in a page — see `MarkdownHtml`.
 *
 * WHY A HAND-WRITTEN PARSER, not a CommonMark library (RFC 262 §9, "No new
 * library"): the target is not CommonMark but what Foundation's
 * `AttributedString(markdown:)` was probed to produce
 * (`research/discover-markdown-subset.md` §3), and it differs at PARSE time. A
 * SINGLE `~one~` is strikethrough (GFM needs two); bare `www.` hosts and bare
 * email addresses autolink (CommonMark links neither); and emphasis inside a
 * link's text is lost — `[**b** link](u)` is one plain link run — which
 * [Inline.Link] encodes by holding plain text. Escaping is NOT this parser's
 * job: `MarkdownHtml` builds every element through kotlinx.html, which escapes
 * every text node and attribute, so a bug here garbles a reply's rendering —
 * it cannot inject HTML.
 */
object MarkdownInline {
  fun parse(markdown: InlineMarkdown): List<Inline> = InlineParser(markdown.source).parse()

  /** The rendered characters of [inlines], with every piece of syntax gone. */
  fun plain(inlines: List<Inline>): String = buildString { appendPlain(inlines) }

  /** The rendered characters of [markdown]: what a reader sees, e.g. a table header's `data-label`. */
  fun plain(markdown: InlineMarkdown): String = plain(parse(markdown))

  private fun StringBuilder.appendPlain(inlines: List<Inline>) {
    for (inline in inlines) {
      when (inline) {
        is Inline.Text -> append(inline.text)
        is Inline.Code -> append(inline.text)
        is Inline.Link -> append(inline.text)
        is Inline.Strong -> appendPlain(inline.children)
        is Inline.Emphasis -> appendPlain(inline.children)
        is Inline.Strikethrough -> appendPlain(inline.children)
      }
    }
  }
}

/** One node of the working list: a finished inline, or the literal text of a delimiter run or bracket. */
private class Node(
  var inline: Inline,
) {
  var prev: Node? = null
  var next: Node? = null
}

/** A run of `*`, `_` or `~` that may still open or close a span (CommonMark's delimiter stack). */
private class Delimiter(
  val node: Node,
  val char: Char,
  var count: Int,
  val canOpen: Boolean,
  val canClose: Boolean,
) {
  val originalCount = count
  var prev: Delimiter? = null
  var next: Delimiter? = null
}

/** A `[` or `![` waiting for its `]`. */
private class Bracket(
  val node: Node,
  val image: Boolean,
  val delimiterBelow: Delimiter?,
) {
  var active = true
}

private class InlineParser(
  private val src: String,
) {
  private var pos = 0
  private val pending = StringBuilder()
  private var head: Node? = null
  private var tail: Node? = null
  private var lastDelimiter: Delimiter? = null
  private val brackets = ArrayList<Bracket>()

  fun parse(): List<Inline> {
    while (pos < src.length) {
      when (val c = src[pos]) {
        '\\' -> backslash()
        '`' -> codeSpan()
        '&' -> entity()
        '<' -> angle()
        '*', '_', '~' -> delimiterRun(c)
        '[' -> openBracket(image = false, width = 1)
        '!' -> if (src.startsWith("![", pos)) openBracket(image = true, width = 2) else literal(1)
        ']' -> closeBracket()
        else -> if (!autolink()) literal(1)
      }
    }
    flushText()
    processEmphasis(null)
    return collect(head, null)
  }

  // --- text --------------------------------------------------------------

  private fun literal(length: Int) {
    pending.append(src, pos, pos + length)
    pos += length
  }

  private fun flushText() {
    if (pending.isEmpty()) return
    append(Node(Inline.Text(pending.toString())))
    pending.clear()
  }

  private fun append(node: Node): Node {
    node.prev = tail
    tail?.next = node
    tail = node
    if (head == null) head = node
    return node
  }

  private fun unlink(node: Node) {
    node.prev?.next = node.next
    node.next?.prev = node.prev
    if (head === node) head = node.next
    if (tail === node) tail = node.prev
  }

  /** Unlinks every node from [from] up to (not including) [until]; a null [until] runs to the end. */
  private fun unlinkFrom(
    from: Node?,
    until: Node?,
  ) {
    var node = from
    while (node != null && node !== until) {
      val next = node.next
      unlink(node)
      node = next
    }
  }

  /** The inlines from [from] up to (not including) [until], adjacent text merged. */
  private fun collect(
    from: Node?,
    until: Node?,
  ): List<Inline> {
    val out = mutableListOf<Inline>()
    var node = from
    while (node != null && node !== until) {
      val inline = node.inline
      val last = out.lastOrNull()
      if (inline is Inline.Text && last is Inline.Text) {
        out[out.lastIndex] = Inline.Text(last.text + inline.text)
      } else if (!(inline is Inline.Text && inline.text.isEmpty())) {
        out += inline
      }
      node = node.next
    }
    return out
  }

  // --- backslash, code, entities ------------------------------------------

  private fun backslash() {
    val next = src.getOrNull(pos + 1)
    when {
      next != null && next.isAsciiPunctuation() -> {
        pending.append(next)
        pos += 2
      }

      // A backslash before a line ending is a hard break; iOS draws it as the
      // newline it already is.
      next == '\n' -> {
        pending.append('\n')
        pos += 2
      }

      else -> {
        literal(1)
      }
    }
  }

  private fun codeSpan() {
    val ticks = src.runLength(pos, '`')
    var search = pos + ticks
    while (true) {
      val open = src.indexOf('`', search)
      if (open < 0) break
      val run = src.runLength(open, '`')
      if (run == ticks) {
        var content = src.substring(pos + ticks, open).replace('\n', ' ')
        if (content.length >= 2 && content.first() == ' ' && content.last() == ' ' && content.isNotBlank()) {
          content = content.substring(1, content.length - 1)
        }
        flushText()
        append(Node(Inline.Code(content)))
        pos = open + run
        return
      }
      search = open + run
    }
    // No closer of the same length: the backticks are their own characters.
    literal(ticks)
  }

  private fun entity() {
    val matcher = ENTITY.matcher(src).region(pos, src.length)
    if (!matcher.lookingAt()) return literal(1)
    val decoded = decodeEntity(matcher.group()) ?: return literal(1)
    pending.append(decoded)
    pos = matcher.end()
  }

  // --- `<`: autolinks and raw HTML ----------------------------------------

  private fun angle() {
    val uri = AUTOLINK_URI.matcher(src).region(pos, src.length)
    if (uri.lookingAt()) {
      val target = uri.group(1)
      linkNode(target, target)
      pos = uri.end()
      return
    }
    val email = AUTOLINK_EMAIL.matcher(src).region(pos, src.length)
    if (email.lookingAt()) {
      val address = email.group(1)
      linkNode("mailto:$address", address)
      pos = email.end()
      return
    }
    // Raw inline HTML is drawn as its own characters on iOS, and it is ONE run
    // there: nothing inside a tag is parsed as emphasis or a link.
    val html = RAW_HTML.matcher(src).region(pos, src.length)
    if (html.lookingAt()) {
      pending.append(html.group())
      pos = html.end()
      return
    }
    literal(1)
  }

  private fun linkNode(
    destination: String,
    text: String,
  ) {
    flushText()
    append(Node(Inline.Link(destination, text)))
  }

  // --- GFM bare autolinks --------------------------------------------------

  /** A bare `https://`, `http://` or `www.` URL, or an email address, at [pos]; false when none starts here. */
  private fun autolink(): Boolean {
    val previous = src.getOrNull(pos - 1)
    if (previous == null || previous.isWhitespace() || previous in "*_~(") {
      val url = urlAt(pos)
      if (url != null) {
        linkNode(if (url.startsWith("www.")) "http://$url" else url, url)
        pos += url.length
        return true
      }
    }
    if (src[pos].isEmailLocal() && (previous == null || !previous.isEmailLocal())) {
      val matcher = EMAIL.matcher(src).region(pos, src.length)
      if (matcher.lookingAt() && matcher.group().last() !in "-_") {
        linkNode("mailto:${matcher.group()}", matcher.group())
        pos = matcher.end()
        return true
      }
    }
    return false
  }

  private fun urlAt(start: Int): String? {
    val scheme = URL_PREFIXES.firstOrNull { src.startsWith(it, start) } ?: return null
    val domainStart = if (scheme == "www.") start else start + scheme.length
    var end = domainStart
    while (end < src.length && (src[end].isLetterOrDigit() || src[end] in "._-")) end++
    if (!isValidDomain(src.substring(domainStart, end))) return null
    while (end < src.length && !src[end].isWhitespace() && src[end] != '<') end++
    val candidate = trimAutolinkTail(src.substring(start, end))
    return candidate.takeIf { it.length > scheme.length }
  }

  // --- emphasis delimiters -------------------------------------------------

  private fun delimiterRun(c: Char) {
    val count = src.runLength(pos, c)
    val before = src.getOrNull(pos - 1) ?: ' '
    val after = src.getOrNull(pos + count) ?: ' '
    pos += count
    // GFM strikethrough is one or two tildes; a longer run is its own characters.
    if (c == '~' && count > 2) {
      pending.append(c.toString().repeat(count))
      return
    }
    val leftFlanking = !after.isWhitespace() && (!after.isPunctuation() || before.isWhitespace() || before.isPunctuation())
    val rightFlanking = !before.isWhitespace() && (!before.isPunctuation() || after.isWhitespace() || after.isPunctuation())
    val canOpen = if (c == '_') leftFlanking && (!rightFlanking || before.isPunctuation()) else leftFlanking
    val canClose = if (c == '_') rightFlanking && (!leftFlanking || after.isPunctuation()) else rightFlanking
    flushText()
    val node = append(Node(Inline.Text(c.toString().repeat(count))))
    if (!canOpen && !canClose) return
    val delimiter = Delimiter(node, c, count, canOpen, canClose)
    delimiter.prev = lastDelimiter
    lastDelimiter?.next = delimiter
    lastDelimiter = delimiter
  }

  private fun removeDelimiter(delimiter: Delimiter) {
    delimiter.prev?.next = delimiter.next
    delimiter.next?.prev = delimiter.prev
    if (lastDelimiter === delimiter) lastDelimiter = delimiter.prev
  }

  /** CommonMark's `process emphasis`, over the delimiters above [bottom]. */
  private fun processEmphasis(bottom: Delimiter?) {
    var closer = firstDelimiterAbove(bottom)
    // Per closer kind, the delimiter below which no opener can be: a closer that
    // found none need not search that far again.
    val openersBottom = HashMap<Triple<Char, Boolean, Int>, Delimiter?>()
    while (closer != null) {
      closer = if (closer.canClose) matchCloser(closer, bottom, openersBottom) else closer.next
    }
    removeDelimitersAbove(bottom)
  }

  private fun firstDelimiterAbove(bottom: Delimiter?): Delimiter? {
    var first: Delimiter? = null
    var cursor = lastDelimiter
    while (cursor != null && cursor !== bottom) {
      first = cursor
      cursor = cursor.prev
    }
    return first
  }

  /** Matches [closer] against the openers below it, wrapping what lies between: the next closer to try. */
  private fun matchCloser(
    closer: Delimiter,
    bottom: Delimiter?,
    openersBottom: MutableMap<Triple<Char, Boolean, Int>, Delimiter?>,
  ): Delimiter? {
    val key = Triple(closer.char, closer.canOpen, closer.originalCount % 3)
    val floor = if (openersBottom.containsKey(key)) openersBottom[key] else bottom
    val opener = findOpener(closer, bottom, floor)
    if (opener == null) {
      openersBottom[key] = closer.prev
      return rejectCloser(closer)
    }
    val use = delimitersUsed(opener, closer)
    wrapBetween(opener, closer, use)
    return spendDelimiters(opener, closer, use)
  }

  /** The nearest opener below [closer] that matches it, searching no lower than [bottom] or [floor]. */
  private fun findOpener(
    closer: Delimiter,
    bottom: Delimiter?,
    floor: Delimiter?,
  ): Delimiter? {
    var opener = closer.prev
    while (opener != null && opener !== bottom && opener !== floor) {
      if (matches(opener, closer)) return opener
      opener = opener.prev
    }
    return null
  }

  /** A closer with no opener: it stays as text, and stays a delimiter only if it may still open. */
  private fun rejectCloser(closer: Delimiter): Delimiter? {
    val next = closer.next
    if (!closer.canOpen) removeDelimiter(closer)
    return next
  }

  /** How many delimiter characters a match spends: a strikethrough all of them, emphasis one or two. */
  private fun delimitersUsed(
    opener: Delimiter,
    closer: Delimiter,
  ): Int =
    if (closer.char == '~') {
      closer.count
    } else if (opener.count >= 2 && closer.count >= 2) {
      2
    } else {
      1
    }

  /** Replaces everything between [opener] and [closer] with ONE span node holding it. */
  private fun wrapBetween(
    opener: Delimiter,
    closer: Delimiter,
    use: Int,
  ) {
    val children = collect(opener.node.next, closer.node)
    unlinkFrom(opener.node.next, closer.node)
    val span =
      when {
        closer.char == '~' -> Inline.Strikethrough(children)
        use == 2 -> Inline.Strong(children)
        else -> Inline.Emphasis(children)
      }
    val spanNode = Node(span)
    spanNode.prev = opener.node
    spanNode.next = closer.node
    opener.node.next = spanNode
    closer.node.prev = spanNode
    // Delimiters between the two are spent: they can no longer match anything.
    opener.next = closer
    closer.prev = opener
  }

  /**
   * Takes [use] characters off both runs, dropping a run that is used up: the
   * next closer to try — [closer] again while it has characters left.
   */
  private fun spendDelimiters(
    opener: Delimiter,
    closer: Delimiter,
    use: Int,
  ): Delimiter? {
    opener.count -= use
    closer.count -= use
    opener.node.inline = Inline.Text(opener.char.toString().repeat(opener.count))
    closer.node.inline = Inline.Text(closer.char.toString().repeat(closer.count))
    if (opener.count == 0) {
      unlink(opener.node)
      removeDelimiter(opener)
    }
    if (closer.count > 0) return closer
    val next = closer.next
    unlink(closer.node)
    removeDelimiter(closer)
    return next
  }

  private fun removeDelimitersAbove(bottom: Delimiter?) {
    while (lastDelimiter != null && lastDelimiter !== bottom) removeDelimiter(lastDelimiter!!)
  }

  private fun matches(
    opener: Delimiter,
    closer: Delimiter,
  ): Boolean {
    if (opener.char != closer.char || !opener.canOpen) return false
    if (closer.char == '~') return opener.count == closer.count
    // CommonMark's rule of three.
    val bothSided = opener.canClose || closer.canOpen
    val sum = opener.originalCount + closer.originalCount
    return !(bothSided && sum % 3 == 0 && !(opener.originalCount % 3 == 0 && closer.originalCount % 3 == 0))
  }

  // --- links and images -----------------------------------------------------

  private fun openBracket(
    image: Boolean,
    width: Int,
  ) {
    flushText()
    val node = append(Node(Inline.Text(src.substring(pos, pos + width))))
    brackets += Bracket(node, image, lastDelimiter)
    pos += width
  }

  private fun closeBracket() {
    flushText()
    val opener = brackets.lastOrNull()
    if (opener == null || !opener.active) {
      if (opener != null) brackets.removeAt(brackets.lastIndex)
      return literal(1)
    }
    // Only the INLINE form is a link; a reference link stays its characters, as on iOS.
    val target = inlineLinkTarget(pos + 1)
    if (target == null) {
      brackets.removeAt(brackets.lastIndex)
      return literal(1)
    }
    pos = target.second
    val text = spendBracketNodes(opener)
    brackets.removeAt(brackets.lastIndex)
    appendLinkOrImage(opener, target.first, text)
  }

  /**
   * Resolves the emphasis inside [opener]'s brackets, then takes everything from
   * the `[` on out of the list: the plain text it held, emphasis and all
   * dropped, as Foundation drops it.
   */
  private fun spendBracketNodes(opener: Bracket): String {
    processEmphasis(opener.delimiterBelow)
    val text = MarkdownInline.plain(collect(opener.node.next, null))
    unlinkFrom(opener.node, null)
    return text
  }

  private fun appendLinkOrImage(
    opener: Bracket,
    destination: String,
    text: String,
  ) {
    when {
      // An image is drawn as its alt text, never as an image (iOS reads no image URL).
      opener.image -> {
        if (text.isNotEmpty()) append(Node(Inline.Text(text)))
      }

      else -> {
        // An empty link text renders nothing at all, as on iOS.
        if (text.isNotEmpty()) append(Node(Inline.Link(destination, text)))
        // A link may not contain a link: every earlier `[` is spent.
        brackets.filterNot { it.image }.forEach { it.active = false }
      }
    }
  }

  /** `(destination "title")` at [start]: the unescaped destination and the index after `)`, or null. */
  private fun inlineLinkTarget(start: Int): Pair<String, Int>? {
    if (src.getOrNull(start) != '(') return null
    val destination = StringBuilder()
    val afterDestination = linkDestination(skipSpace(start + 1), destination) ?: return null
    val end = skipTitle(afterDestination) ?: return null
    if (src.getOrNull(end) != ')') return null
    return decodeEntities(destination.toString()) to end + 1
  }

  /** The destination at [from], unescaped into [into]: the index after it, or null when it is not one. */
  private fun linkDestination(
    from: Int,
    into: StringBuilder,
  ): Int? = if (src.getOrNull(from) == '<') angleDestination(from, into) else bareDestination(from, into)

  /** `<…>`: no line ending and no `<` inside; the index after the `>`. */
  private fun angleDestination(
    from: Int,
    into: StringBuilder,
  ): Int? {
    var i = from + 1
    while (i < src.length && src[i] != '>') {
      if (src[i] == '\n' || src[i] == '<') return null
      i = appendEscaped(into, i)
    }
    if (i >= src.length) return null
    return i + 1
  }

  /** Up to whitespace, a control character or an unbalanced `)`; null when its parentheses do not balance. */
  private fun bareDestination(
    from: Int,
    into: StringBuilder,
  ): Int? {
    var i = from
    var depth = 0
    while (i < src.length) {
      val c = src[i]
      if (c.isWhitespace() || c.isISOControl()) break
      if (c == '(') depth++
      if (c == ')') {
        if (depth == 0) break
        depth--
      }
      i = appendEscaped(into, i)
    }
    return i.takeIf { depth == 0 }
  }

  /**
   * Skips the space after a destination and an optional `"title"`, `'title'` or
   * `(title)` — the title is DROPPED, as on iOS — and the space after it: the
   * index that follows, or null for an unterminated title. A title must be
   * separated from the destination by space.
   */
  private fun skipTitle(from: Int): Int? {
    val i = skipSpace(from)
    val quote = src.getOrNull(i)
    if (i == from || (quote != '"' && quote != '\'' && quote != '(')) return i
    val close = if (quote == '(') ')' else quote
    var cursor = i + 1
    while (cursor < src.length && src[cursor] != close) cursor += if (src[cursor] == '\\' && cursor + 1 < src.length) 2 else 1
    if (cursor >= src.length) return null
    return skipSpace(cursor + 1)
  }

  /** Appends the character at [i] (resolving a backslash escape) and returns the next index. */
  private fun appendEscaped(
    into: StringBuilder,
    i: Int,
  ): Int {
    val next = src.getOrNull(i + 1)
    if (src[i] == '\\' && next != null && next.isAsciiPunctuation()) {
      into.append(next)
      return i + 2
    }
    into.append(src[i])
    return i + 1
  }

  /** Spaces, tabs and at most one line ending. */
  private fun skipSpace(from: Int): Int {
    var i = from
    var newlines = 0
    while (i < src.length && (src[i] == ' ' || src[i] == '\t' || (src[i] == '\n' && newlines++ == 0))) i++
    return i
  }

  private companion object {
    val ENTITY: Pattern = Pattern.compile("&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{0,31});")
    val AUTOLINK_URI: Pattern = Pattern.compile("<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^\\s<>]*)>")
    val AUTOLINK_EMAIL: Pattern =
      Pattern.compile(
        "<([a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
          "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*)>",
      )
    val RAW_HTML: Pattern =
      Pattern.compile(
        "<[A-Za-z][A-Za-z0-9-]*(?:\\s+[A-Za-z_:][A-Za-z0-9_.:-]*(?:\\s*=\\s*(?:[^\\s\"'=<>`]+|'[^']*'|\"[^\"]*\"))?)*\\s*/?>" +
          "|</[A-Za-z][A-Za-z0-9-]*\\s*>" +
          "|<!--[\\s\\S]*?-->" +
          "|<\\?[\\s\\S]*?\\?>" +
          "|<![A-Za-z]+[^>]*>" +
          "|<!\\[CDATA\\[[\\s\\S]*?]]>",
      )
    val EMAIL: Pattern = Pattern.compile("[A-Za-z0-9._+-]+@[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+")
    val URL_PREFIXES = listOf("https://", "http://", "www.")
  }
}

private fun String.runLength(
  from: Int,
  c: Char,
): Int {
  var end = from
  while (end < length && this[end] == c) end++
  return end - from
}

private fun Char.isAsciiPunctuation(): Boolean = this in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

/** CommonMark punctuation: ASCII punctuation or any Unicode punctuation category. */
private fun Char.isPunctuation(): Boolean =
  isAsciiPunctuation() ||
    when (Character.getType(this).toByte()) {
      Character.CONNECTOR_PUNCTUATION,
      Character.DASH_PUNCTUATION,
      Character.START_PUNCTUATION,
      Character.END_PUNCTUATION,
      Character.INITIAL_QUOTE_PUNCTUATION,
      Character.FINAL_QUOTE_PUNCTUATION,
      Character.OTHER_PUNCTUATION,
      -> true

      else -> false
    }

private fun Char.isEmailLocal(): Boolean = isLetterOrDigit() || this in "._+-"

/** GFM: segments of letters, digits, `_` and `-`, at least one period, and no `_` in the last two segments. */
private fun isValidDomain(domain: String): Boolean {
  val segments = domain.trimEnd('.').split('.')
  if (segments.size < 2 || segments.any { it.isEmpty() }) return false
  return segments.takeLast(2).none { '_' in it }
}

/**
 * GFM's extended-autolink tail rule: trailing `?!.,:*_~` are not part of the
 * link, nor an unbalanced `)`, nor an entity-like `&name;` at the very end.
 */
private fun trimAutolinkTail(candidate: String): String {
  var link = candidate
  while (link.isNotEmpty()) {
    val last = link.last()
    link =
      when {
        last in "?!.,:*_~" -> {
          link.dropLast(1)
        }

        last == ')' && link.count { it == ')' } > link.count { it == '(' } -> {
          link.dropLast(1)
        }

        last == ';' -> {
          val entity = Regex("&[A-Za-z0-9]+;$").find(link) ?: return link
          link.substring(0, entity.range.first)
        }

        else -> {
          return link
        }
      }
  }
  return link
}

/** Entities and numeric references in a link destination. */
private fun decodeEntities(text: String): String =
  Regex("&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{0,31});").replace(text) { match ->
    decodeEntity(match.value) ?: match.value
  }

/**
 * One entity reference, decoded, or null when it names nothing: an unknown
 * `&name;` stays as its own characters, the safe failure.
 *
 * Numeric references decode in full. Named references come from the table
 * below — the ones a coach reply plausibly carries — rather than HTML's 2,231;
 * a name outside it degrades to its literal characters, never to anything
 * else.
 */
private fun decodeEntity(reference: String): String? {
  val body = reference.substring(1, reference.length - 1)
  if (body.startsWith("#")) {
    val code =
      if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) body.substring(2).toIntOrNull(16) else body.substring(1).toIntOrNull()
    // CommonMark: an invalid or zero code point is the replacement character.
    return if (code == null || code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) "�" else String(Character.toChars(code))
  }
  return NAMED_ENTITIES[body]
}

private val NAMED_ENTITIES: Map<String, String> =
  mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "lsquo" to "‘",
    "rsquo" to "’",
    "ldquo" to "“",
    "rdquo" to "”",
    "laquo" to "«",
    "raquo" to "»",
    "bull" to "•",
    "middot" to "·",
    "deg" to "°",
    "times" to "×",
    "divide" to "÷",
    "plusmn" to "±",
    "frac12" to "½",
    "frac14" to "¼",
    "frac34" to "¾",
    "cent" to "¢",
    "pound" to "£",
    "euro" to "€",
    "yen" to "¥",
    "sect" to "§",
    "para" to "¶",
    "larr" to "←",
    "rarr" to "→",
    "uarr" to "↑",
    "darr" to "↓",
    "harr" to "↔",
    "le" to "≤",
    "ge" to "≥",
    "ne" to "≠",
    "asymp" to "≈",
    "minus" to "−",
    "infin" to "∞",
    "check" to "✓",
    "eacute" to "é",
    "egrave" to "è",
    "aacute" to "á",
    "agrave" to "à",
    "iacute" to "í",
    "oacute" to "ó",
    "uacute" to "ú",
    "ntilde" to "ñ",
    "uuml" to "ü",
    "ouml" to "ö",
    "auml" to "ä",
    "ccedil" to "ç",
    "szlig" to "ß",
  )
