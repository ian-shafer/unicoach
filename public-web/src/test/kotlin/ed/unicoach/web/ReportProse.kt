package ed.unicoach.web

import kotlin.test.assertTrue

/**
 * The REPORT's own markup, without the site chrome.
 *
 * The header and the footer open anchors legitimately -- Home, Terms, Privacy
 * -- so a claim about the report must not measure the layout. The opening
 * marker is ASSERTED, not assumed: `substringAfter` returns the WHOLE document
 * when it misses, so a renamed section would quietly widen every assertion to
 * the head and the site chrome.
 */
internal fun reportSectionOf(body: String): String {
  val opening = "<section class=\"report\""
  assertTrue(body.contains(opening), "the report section must be on the page to be measured")
  return body.substringAfter(opening).substringBeforeLast("</section>")
}

/**
 * [html]'s words as a parent reads them: markup stripped, entities restored,
 * and each run of whitespace one space, as a browser lays it out.
 */
internal fun proseOf(html: String): String =
  html
    .replace(Regex("<[^>]*>"), " ")
    .replace("&#39;", "'")
    .replace("&quot;", "\"")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    // LAST, always: decoding it first would turn `&amp;lt;` into a `<` the
    // page never printed.
    .replace("&amp;", "&")
    .replace(Regex("\\s+"), " ")

/** The PROSE a parent actually reads on the page: the report section's words. */
internal fun visibleProseOf(body: String): String = proseOf(reportSectionOf(body))

/** How many times [needle] occurs in [text], non-overlapping -- the measurement "said once" actually needs. */
internal fun countOf(
  text: String,
  needle: String,
): Int = text.split(needle).size - 1
