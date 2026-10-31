package ed.unicoach.web.markdown

import kotlinx.html.div
import kotlinx.html.stream.createHTML
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The renderer, read as a browser reads it: every assertion is on the PARSED
 * DOM (jsoup), never on a substring of the markup, because an injection is a
 * claim about what elements and attributes a browser builds — and a string
 * search for `<script` both misses an attribute-borne payload and fires on an
 * escaped one.
 */
class MarkdownHtmlTest {
  private fun render(source: String): Element = Jsoup.parseBodyFragment(createHTML(prettyPrint = false).div { markdown(source) }).body()

  @Test
  fun `the worst-case reply renders every block type`() {
    val dom = render(MarkdownFixture.worstCaseReply)
    for (selector in listOf("h2", "p", "ul.md-list", "table.md-table", "pre > code", "blockquote", "a[href]")) {
      assertNotNull(dom.selectFirst(selector), "the worst-case reply rendered no [$selector]")
    }
    assertNotNull(render("---").selectFirst("hr"), "a rule renders as one")
    assertEquals("bin/deadlines --within 30d \\\n    --school michigan", dom.selectFirst("pre > code")!!.wholeText())
    assertEquals(
      listOf("•", "•", "•", "•", "☑", "☐"),
      dom.select(".$MARKDOWN_MARKER_CLASS").map { it.text() },
      "markers are the renderer's, resolved per item",
    )
    assertEquals(
      listOf("md-depth-0", "md-depth-1", "md-depth-1", "md-depth-0", "md-depth-1", "md-depth-1"),
      dom.select("li").map {
        it.className()
      },
    )
  }

  @Test
  fun `headings draw as their own levels`() {
    val dom = render("# One\n\n###### Six")
    assertEquals("One", dom.selectFirst("h1")!!.text())
    assertEquals("Six", dom.selectFirst("h6")!!.text())
  }

  @Test
  fun `a soft break survives into the paragraph`() {
    assertEquals("Line one\nLine two", render("Line one\nLine two").selectFirst("p")!!.wholeText())
  }

  @Test
  fun `ordinals are the parser's, not the browser's`() {
    assertEquals(listOf("3.", "4."), render("3. a\n7. b").select(".$MARKDOWN_MARKER_CLASS").map { it.text() })
  }

  @Test
  fun `a depth beyond the stylesheet's draws at the deepest it has`() {
    val source = (0..8).joinToString("\n") { depth -> " ".repeat(depth * 2) + "- d$depth" }
    assertEquals("md-depth-$MARKDOWN_MAX_DRAWN_DEPTH", render(source).select("li").last()!!.className())
  }

  // --- Sanitisation rule 1: every character is an escaped text node ---

  @Test
  fun `raw HTML in a reply renders as its characters, never as elements`() {
    val payloads =
      listOf(
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "[\"><svg onload=alert(1)>](https://example.com)",
        "| Head |\n| --- |\n| \"><svg onload=alert(1)> |",
        "| \"><img src=x onerror=alert(1)> |\n| --- |\n| cell |",
        "`<script>alert(1)</script>`",
        "```\n<script>alert(1)</script>\n```",
        "> <iframe src=javascript:alert(1)>",
      )
    for (payload in payloads) {
      val dom = render(payload)
      assertTrue(dom.select("script, img, svg, iframe, style, object, embed").isEmpty(), "[$payload] built an element: ${dom.html()}")
      val handlers = dom.getAllElements().flatMap { element -> element.attributes().map { it.key } }.filter { it.startsWith("on") }
      assertEquals(emptyList(), handlers, "[$payload] built an event-handler attribute")
      assertTrue(dom.text().contains("alert(1)"), "[$payload] lost the characters rather than escaping them: ${dom.html()}")
    }
  }

  @Test
  fun `no style attribute and no class from model text`() {
    val dom = render("<p class=\"evil\" style=\"color:red\">x</p>\n\n" + MarkdownFixture.worstCaseReply)
    assertTrue(dom.getAllElements().none { it.hasAttr("style") }, "a style attribute reached the page")
    val classes = dom.getAllElements().flatMap { it.classNames() }.toSet()
    assertTrue("evil" !in classes, "a class from model text reached the page: $classes")
  }

  // --- Sanitisation rule 2: only http and https links keep an href ---

  @Test
  fun `a link to anything but http or https renders as text with no anchor`() {
    val refused =
      listOf(
        "[x](javascript:alert(1))",
        "[x](JaVaScRiPt:alert(1))",
        "[x](data:text/html,hi)",
        "[x](vbscript:msgbox)",
        "[x](/relative)",
        "[x](//evil.example)",
        "[x](mailto:a@b.com)",
        "[x](https:evil)",
        "<javascript:alert(1)>",
      )
    for (source in refused) {
      val dom = render(source)
      assertNull(dom.selectFirst("a"), "[$source] rendered an anchor: ${dom.html()}")
      assertTrue(dom.text().isNotEmpty(), "[$source] lost its text")
    }
    val email = render("write to a@b.com")
    assertNull(email.selectFirst("a"), "a bare email is text on the web (Ian, at approval)")
    assertEquals("write to a@b.com", email.text())
  }

  @Test
  fun `http and https links carry the stated rel and target`() {
    for (source in listOf(
      "[x](https://example.com/a)",
      "[x](HTTP://example.com/a)",
      "see https://example.com/a",
      "visit www.example.com",
    )) {
      val anchor = assertNotNull(render(source).selectFirst("a"), "[$source] rendered no anchor")
      assertTrue(anchor.attr("href").lowercase().startsWith("http"), "[$source] href: ${anchor.attr("href")}")
      assertEquals(MARKDOWN_LINK_REL, anchor.attr("rel"))
      assertEquals("_blank", anchor.attr("target"))
    }
  }

  @Test
  fun `an href is attribute-escaped`() {
    val anchor = assertNotNull(render("[x](<https://e.com/\"onmouseover=\"alert(1)>)").selectFirst("a"))
    assertEquals("https://e.com/\"onmouseover=\"alert(1)", anchor.attr("href"))
    assertTrue(anchor.attributes().none { it.key.startsWith("on") })
  }

  // --- Tables: data-label ---

  @Test
  fun `every cell carries its header's rendered text as its label`() {
    val dom = render("| **College** | `Due` |\n| --- | :---: |\n| Michigan | Nov 1 |")
    assertEquals(listOf("College", "Due"), dom.select("td").map { it.attr("data-label") })
    assertTrue(dom.select("td").last()!!.hasClass("md-center"), "the column's alignment rides its cells")
  }

  /** iOS's `cellLabel(header: "", value:)` speaks the value alone; an empty label is what lets the stylesheet do the same. */
  @Test
  fun `a headerless column's cells carry no label`() {
    val dom = render("|  | Deadline |\n| --- | --- |\n| Michigan | Nov 1 |")
    assertEquals(listOf(false, true), dom.select("td").map { it.hasAttr("data-label") })
  }

  @Test
  fun `a header containing a quote is escaped in the attribute`() {
    val markup = createHTML(prettyPrint = false).div { markdown("| Say \"hi\" |\n| --- |\n| x |") }
    assertTrue(markup.contains("data-label=\"Say &quot;hi&quot;\""), "the quote was not escaped: $markup")
    assertEquals("Say \"hi\"", Jsoup.parseBodyFragment(markup).selectFirst("td")!!.attr("data-label"))
  }
}
