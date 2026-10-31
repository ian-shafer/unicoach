package ed.unicoach.web.markdown

import ed.unicoach.web.render.DATA_LABEL
import ed.unicoach.web.render.DATA_TABLE_CLASS
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.a
import kotlinx.html.blockQuote
import kotlinx.html.code
import kotlinx.html.del
import kotlinx.html.div
import kotlinx.html.em
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.h4
import kotlinx.html.h5
import kotlinx.html.h6
import kotlinx.html.hr
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

/** The class the rendered reply carries, so the stylesheet's Markdown rules are scoped to it. */
const val MARKDOWN_CLASS = "markdown"

/** A list item's leading column: the bullet, number or checkbox. */
const val MARKDOWN_MARKER_CLASS = "md-marker"

/**
 * The deepest indent the stylesheet draws. A list's depth is unbounded, as on
 * iOS, but an indent is a CSS class (no `style` attribute reaches the page —
 * rule 3 below), and a class needs a rule; anything deeper draws at this one.
 * `ShellMechanismTest` holds every depth up to this one to the served
 * stylesheet, so raising it without the rules fails there.
 */
const val MARKDOWN_MAX_DRAWN_DEPTH = 6

/** A list: a flat run of rows. */
const val MARKDOWN_LIST_CLASS = "md-list"

/** A table column drawn centred. */
const val MARKDOWN_CENTER_CLASS = "md-center"

/** A table column drawn against the trailing edge. */
const val MARKDOWN_TRAILING_CLASS = "md-trailing"

/** The class a list item at [depth] draws its indent with; depth 0 is unindented. */
fun markdownDepthClass(depth: Int): String = "md-depth-${depth.coerceAtMost(MARKDOWN_MAX_DRAWN_DEPTH)}"

/** The `rel` every kept link carries (RFC 262 D-E). */
const val MARKDOWN_LINK_REL = "noopener noreferrer nofollow ugc"

/** The schemes a link may keep its `href` for. Anything else renders as its text. */
private val LINK_SCHEMES = listOf("http://", "https://")

/**
 * Renders a coach reply's Markdown as HTML: the iOS subset ([MarkdownBlock],
 * [MarkdownInline]), and nothing wider, so the web and the app cannot disagree
 * about what the coach said (RFC 262 §9).
 *
 * THE SANITISATION POLICY. Coach output is model-generated text reaching a
 * browser — an injection surface the iOS client, which draws characters, never
 * had. So:
 *
 * 1. Every character of model text reaches the page as an ESCAPED text node.
 *    This builds HTML only through kotlinx.html's tag builders and `+text`;
 *    `unsafe` and raw-string HTML are never used here. A `<script>` in a reply
 *    renders as those characters, exactly as on iOS.
 * 2. A link keeps its `href` only for an `http://` or `https://` target
 *    ([linkHref]). `mailto:`, `javascript:`, `data:`, a relative path and a
 *    protocol-relative `//host` all render as the link's text with no anchor. A
 *    kept link opens in a new tab with [MARKDOWN_LINK_REL], so following one
 *    never replaces the chat.
 * 3. No `<img>`, no `style` attribute, and no `class` from model text: every
 *    class here is the renderer's own constant.
 * 4. The enforcing CSP stays as the second line of defence, not the first.
 */
fun FlowContent.markdown(source: String) {
  div(MARKDOWN_CLASS) {
    for (block in MarkdownBlock.parse(source)) block(block)
  }
}

/** The `href` [destination] may have, or null when the policy renders it as text. */
internal fun linkHref(destination: String): String? {
  val trimmed = destination.trim()
  return trimmed.takeIf { target -> LINK_SCHEMES.any { target.startsWith(it, ignoreCase = true) } }
}

private fun FlowContent.block(block: MarkdownBlock) {
  when (block) {
    is MarkdownBlock.Heading -> heading(block)
    is MarkdownBlock.Paragraph -> p { inlines(block.text) }
    is MarkdownBlock.ItemList -> itemList(block.list)
    is MarkdownBlock.Quote -> blockQuote { for (line in block.lines) p { inlines(line) } }
    is MarkdownBlock.Code -> pre { code { +block.body } }
    is MarkdownBlock.Table -> table(block.table)
    MarkdownBlock.Rule -> hr {}
  }
}

/** The six levels as six elements; the stylesheet draws h1–h2 at one size and h3–h6 at another, as iOS does. */
private fun FlowContent.heading(heading: MarkdownBlock.Heading) {
  when (heading.level) {
    HeadingLevel.H1 -> h1 { inlines(heading.text) }
    HeadingLevel.H2 -> h2 { inlines(heading.text) }
    HeadingLevel.H3 -> h3 { inlines(heading.text) }
    HeadingLevel.H4 -> h4 { inlines(heading.text) }
    HeadingLevel.H5 -> h5 { inlines(heading.text) }
    HeadingLevel.H6 -> h6 { inlines(heading.text) }
  }
}

/** A FLAT run of rows, indented by depth — not nested lists, which would draw more structure than iOS does. */
private fun FlowContent.itemList(list: MarkdownList) {
  ul(MARKDOWN_LIST_CLASS) {
    for (item in list.items) {
      li(markdownDepthClass(item.depth)) {
        span(MARKDOWN_MARKER_CLASS) { +markerText(item.marker) }
        span("md-item-text") { inlines(item.text) }
      }
    }
  }
}

private fun markerText(marker: MarkdownList.Marker): String =
  when (marker) {
    MarkdownList.Marker.Bullet -> "•"
    is MarkdownList.Marker.Ordered -> "${marker.ordinal}."
    is MarkdownList.Marker.Task -> if (marker.done) "☑" else "☐"
  }

/**
 * A `<table>`, which never scrolls sideways (RFC 120): it carries the site's
 * [DATA_TABLE_CLASS], so under the narrow breakpoint the stylesheet's one
 * stacking rule turns each row into a block, and every cell reads
 * "Header: value" from its `data-label` — the header's RENDERED characters,
 * escaped into the attribute by kotlinx.html, never its Markdown. A cell under
 * an empty header carries no label, so it reads as its value alone, as iOS
 * speaks it (`MarkdownAccessibility.cellLabel`).
 */
private fun FlowContent.table(table: MarkdownTable) {
  val labels = table.headers.map(MarkdownInline::plain)
  table("md-table $DATA_TABLE_CLASS") {
    thead {
      tr {
        table.headers.forEachIndexed { column, header ->
          th(classes = alignmentClass(table.alignments[column])) { inlines(header) }
        }
      }
    }
    tbody {
      for (row in table.rows) {
        tr {
          row.forEachIndexed { column, cell ->
            td(classes = alignmentClass(table.alignments[column])) {
              labels[column].takeIf { it.isNotEmpty() }?.let { attributes[DATA_LABEL] = it }
              inlines(cell)
            }
          }
        }
      }
    }
  }
}

private fun alignmentClass(alignment: MarkdownTable.Alignment): String? =
  when (alignment) {
    MarkdownTable.Alignment.LEADING -> null
    MarkdownTable.Alignment.CENTER -> MARKDOWN_CENTER_CLASS
    MarkdownTable.Alignment.TRAILING -> MARKDOWN_TRAILING_CLASS
  }

private fun FlowOrPhrasingContent.inlines(markdown: InlineMarkdown) = inlines(MarkdownInline.parse(markdown))

private fun FlowOrPhrasingContent.inlines(inlines: List<Inline>) {
  for (inline in inlines) {
    when (inline) {
      is Inline.Text -> +inline.text
      is Inline.Strong -> strong { inlines(inline.children) }
      is Inline.Emphasis -> em { inlines(inline.children) }
      is Inline.Strikethrough -> del { inlines(inline.children) }
      is Inline.Code -> code { +inline.text }
      is Inline.Link -> link(inline)
    }
  }
}

/** Rule 2: an anchor only for an `http(s)` target; any other link is its text alone. */
private fun FlowOrPhrasingContent.link(link: Inline.Link) {
  val href = linkHref(link.destination)
  if (href == null) {
    +link.text
    return
  }
  a(href = href, target = "_blank") {
    rel = MARKDOWN_LINK_REL
    +link.text
  }
}
