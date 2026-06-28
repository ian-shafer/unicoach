package ed.unicoach.admin.render

import ed.unicoach.admin.engine.FieldType
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellRenderTest {
  private val display =
    AdminDisplay(
      zone = ZoneId.of("UTC"),
      idLinkGlyph = "🔗",
      boolTrueGlyph = "✓",
      boolFalseGlyph = "✗",
      isSupported = { it == "user" },
    )

  /** Renders an arbitrary [FlowContent] block to its HTML string. */
  private fun render(block: FlowContent.() -> Unit): String = createHTML().div { block() }

  @Test
  fun `timestamp renders MMM d yyyy in the configured zone`() {
    val html = render { renderValue("2026-01-03T12:00:00Z", FieldType.TIMESTAMP, display) }
    assertTrue(html.contains("Jan 3, 2026"), "Expected formatted date, got: $html")
  }

  @Test
  fun `timestamp hover title carries the verbatim source ISO`() {
    val iso = "2026-01-03T12:00:00Z"
    val html = render { renderValue(iso, FieldType.TIMESTAMP, display) }
    assertTrue(html.contains("title=\"$iso\""), "Expected verbatim ISO title, got: $html")
  }

  @Test
  fun `non-UTC zone shifts the displayed date across midnight`() {
    val iso = "2026-06-28T02:00:00Z"
    val pacific = display.copy(zone = ZoneId.of("America/Los_Angeles"))
    val html = render { renderValue(iso, FieldType.TIMESTAMP, pacific) }
    assertTrue(html.contains("Jun 27, 2026"), "Expected the date shifted back across midnight, got: $html")
    assertTrue(html.contains("title=\"$iso\""), "Title must still carry the source UTC instant, got: $html")
  }

  @Test
  fun `blank timestamp renders nothing`() {
    val html = render { renderValue("", FieldType.TIMESTAMP, display) }
    assertEquals("<div></div>", html.trim())
  }

  @Test
  fun `unparseable timestamp renders the raw text without throwing`() {
    val html = render { renderValue("not-a-timestamp", FieldType.TIMESTAMP, display) }
    assertTrue(html.contains("not-a-timestamp"), "Expected raw text fallback, got: $html")
    assertFalse(html.contains("title="), "Unparseable timestamp must not emit a hover title, got: $html")
  }

  @Test
  fun `bool true renders the configured true glyph in bool-true`() {
    val html = render { renderValue("true", FieldType.BOOL, display) }
    assertTrue(html.contains("class=\"bool-true\""), "Expected bool-true class, got: $html")
    assertTrue(html.contains("✓"), "Expected the true glyph, got: $html")
  }

  @Test
  fun `bool false renders the configured false glyph in bool-false`() {
    val html = render { renderValue("false", FieldType.BOOL, display) }
    assertTrue(html.contains("class=\"bool-false\""), "Expected bool-false class, got: $html")
    assertTrue(html.contains("✗"), "Expected the false glyph, got: $html")
  }

  @Test
  fun `blank bool renders nothing`() {
    val html = render { renderValue("", FieldType.BOOL, display) }
    assertEquals("<div></div>", html.trim())
  }

  @Test
  fun `unknown bool value renders raw rather than masking as the false glyph`() {
    val html = render { renderValue("maybe", FieldType.BOOL, display) }
    assertTrue(html.contains("maybe"), "Expected the raw value surfaced, got: $html")
    assertFalse(html.contains("bool-false"), "An unknown bool must not be masked as false, got: $html")
    assertFalse(html.contains("✗"), "An unknown bool must not render the false glyph, got: $html")
  }

  @Test
  fun `supported entity ref renders value nbsp then a glyph link to slug detail`() {
    val id = "abc-123"
    val html = render { renderCell(id, FieldType.TEXT, "user", display) }
    assertTrue(html.contains(id), "Expected the value text, got: $html")
    assertTrue(html.contains(" "), "Expected a non-breaking space before the glyph, got: $html")
    assertTrue(html.contains("href=\"/user/$id\""), "Expected a link to the slug detail page, got: $html")
    assertTrue(html.contains("🔗"), "Expected the link glyph, got: $html")
  }

  @Test
  fun `unsupported entity ref renders the value with no link`() {
    val id = "abc-123"
    val html = render { renderCell(id, FieldType.TEXT, "convo", display) }
    assertTrue(html.contains(id), "Expected the value text, got: $html")
    assertFalse(html.contains("<a"), "An unsupported slug must produce no link, got: $html")
  }

  @Test
  fun `blank ref value renders no link`() {
    val html = render { renderRefLink("", "user", display) }
    assertEquals("<div></div>", html.trim())
  }

  @Test
  fun `JSON value renders pretty-printed inside pre`() {
    val html = render { renderValue("""{"a":1,"b":2}""", FieldType.JSON, display) }
    assertTrue(html.contains("<pre>"), "Expected a <pre> element, got: $html")
    // kotlinx.html escapes the JSON quotes (&quot;); assert on the unescaped key text and value.
    assertTrue(html.contains("a") && html.contains(": 1"), "Expected the pretty-printed JSON keys/values, got: $html")
    assertTrue(html.contains("\n"), "Expected pretty-print newlines, got: $html")
  }

  @Test
  fun `JSON array renders pretty-printed inside pre`() {
    val html = render { renderValue("""[1,2,3]""", FieldType.JSON, display) }
    assertTrue(html.contains("<pre>"), "Expected a <pre> element for a top-level array, got: $html")
    assertTrue(html.contains("\n"), "Expected pretty-print newlines, got: $html")
  }

  @Test
  fun `JSON primitive renders pretty-printed inside pre`() {
    val html = render { renderValue("\"hello\"", FieldType.JSON, display) }
    assertTrue(html.contains("<pre>"), "Expected a <pre> element for a top-level primitive, got: $html")
    assertTrue(html.contains("hello"), "Expected the primitive value, got: $html")
  }

  @Test
  fun `JSON blank value renders nothing`() {
    val html = render { renderValue("", FieldType.JSON, display) }
    assertEquals("<div></div>", html.trim())
  }

  @Test
  fun `unparseable JSON renders raw text and does not throw`() {
    val html = render { renderValue("not json {", FieldType.JSON, display) }
    assertTrue(html.contains("not json {"), "Expected the raw text fallback, got: $html")
    assertFalse(html.contains("<pre>"), "Unparseable JSON must not emit a <pre>, got: $html")
  }

  @Test
  fun `JSON field renders no ref link`() {
    val html = render { renderCell("""{"a":1}""", FieldType.JSON, null, display) }
    assertFalse(html.contains("<a"), "A JSON cell with no refSlug must emit no link glyph, got: $html")
  }

  @Test
  fun `configured idLinkGlyph and bool glyphs are honored`() {
    val custom =
      display.copy(idLinkGlyph = "LINK", boolTrueGlyph = "YES", boolFalseGlyph = "NO")
    val boolTrue = render { renderValue("true", FieldType.BOOL, custom) }
    assertTrue(boolTrue.contains("YES"), "Expected the configured true glyph, got: $boolTrue")
    val boolFalse = render { renderValue("false", FieldType.BOOL, custom) }
    assertTrue(boolFalse.contains("NO"), "Expected the configured false glyph, got: $boolFalse")
    val link = render { renderRefLink("abc-123", "user", custom) }
    assertTrue(link.contains("LINK"), "Expected the configured link glyph, got: $link")
  }
}
