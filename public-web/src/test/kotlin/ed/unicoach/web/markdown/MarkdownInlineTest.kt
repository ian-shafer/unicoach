package ed.unicoach.web.markdown

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inline parser against what Foundation's `AttributedString(markdown:)`
 * was PROBED to produce for the iOS client (`discover-markdown-subset.md` §3),
 * and the inline cases of `MarkdownParserTests.swift:417-464`.
 */
class MarkdownInlineTest {
  private fun parse(source: String) = MarkdownInline.parse(InlineMarkdown(source))

  private fun text(value: String) = Inline.Text(value)

  @Test
  fun `bold is strong and its characters are the rendered text`() {
    val inlines = parse("Submit the **Common App** today")
    assertEquals(listOf(text("Submit the "), Inline.Strong(listOf(text("Common App"))), text(" today")), inlines)
    assertEquals("Submit the Common App today", MarkdownInline.plain(inlines))
  }

  @Test
  fun `underscore and star italics are emphasis`() {
    assertEquals(listOf(text("that is "), Inline.Emphasis(listOf(text("optional")))), parse("that is _optional_"))
    assertEquals(listOf(Inline.Emphasis(listOf(text("x")))), parse("*x*"))
  }

  @Test
  fun `triple stars are both`() {
    assertEquals(listOf(Inline.Emphasis(listOf(Inline.Strong(listOf(text("both")))))), parse("***both***"))
  }

  @Test
  fun `one or two tildes strike through, three do not`() {
    assertEquals(listOf(Inline.Strikethrough(listOf(text("cancelled")))), parse("~~cancelled~~"))
    assertEquals(listOf(Inline.Strikethrough(listOf(text("one")))), parse("~one~"))
    assertEquals(listOf(text("~~~three~~~")), parse("~~~three~~~"))
  }

  @Test
  fun `inline code is code`() {
    assertEquals(listOf(text("run "), Inline.Code("bin/test"), text(" first")), parse("run `bin/test` first"))
  }

  @Test
  fun `an inline link keeps its destination and drops its title`() {
    assertEquals(listOf(text("see "), Inline.Link("https://example.com", "the guide")), parse("see [the guide](https://example.com)"))
    assertEquals(listOf(Inline.Link("https://e", "x")), parse("[x](https://e \"Title\")"))
  }

  @Test
  fun `a malformed link returns its source text`() {
    assertEquals(listOf(text("[unclosed](")), parse("[unclosed]("))
  }

  @Test
  fun `bare URL, www and angle autolinks`() {
    assertEquals(listOf(text("go to "), Inline.Link("https://e.com", "https://e.com")), parse("go to https://e.com"))
    assertEquals(listOf(text("visit "), Inline.Link("http://www.example.com", "www.example.com")), parse("visit www.example.com"))
    assertEquals(listOf(Inline.Link("https://e.com", "https://e.com")), parse("<https://e.com>"))
  }

  @Test
  fun `trailing punctuation is not part of a bare URL`() {
    assertEquals(listOf(text("see "), Inline.Link("https://e.com/a", "https://e.com/a"), text(".")), parse("see https://e.com/a."))
    assertEquals(
      listOf(text("(see "), Inline.Link("https://e.com", "https://e.com"), text(")")),
      parse("(see https://e.com)"),
    )
  }

  @Test
  fun `a bare email is parsed as an email link, as on iOS`() {
    assertEquals(listOf(text("write to "), Inline.Link("mailto:a@b.com", "a@b.com")), parse("write to a@b.com"))
  }

  @Test
  fun `an empty link text renders nothing`() {
    assertEquals(emptyList(), parse("[](https://e.com)"))
  }

  @Test
  fun `an image is its alt text, and inside a link it is the link's text`() {
    assertEquals(listOf(text("alt")), parse("![alt](https://x.png)"))
    assertEquals(listOf(Inline.Link("u", "alt")), parse("[![alt](i.png)](u)"))
  }

  @Test
  fun `emphasis inside a link is lost`() {
    assertEquals(listOf(Inline.Link("u", "b link")), parse("[**b** link](u)"))
  }

  @Test
  fun `raw inline HTML is its literal characters, and nothing inside a tag is parsed`() {
    assertEquals(listOf(text("<b>x</b>")), parse("<b>x</b>"))
    assertEquals(listOf(text("<script>alert(1)</script>")), parse("<script>alert(1)</script>"))
    assertEquals(listOf(text("<a href=\"*x*\">")), parse("<a href=\"*x*\">"))
  }

  @Test
  fun `backslash escapes are literal`() {
    assertEquals(listOf(text("*not italic*")), parse("\\*not italic\\*"))
  }

  @Test
  fun `entities are decoded, and an unknown one stays as written`() {
    assertEquals(listOf(text("& © é ✓ &nosuch;")), parse("&amp; &copy; &#233; &#x2713; &nosuch;"))
  }

  @Test
  fun `an intraword underscore is left alone`() {
    assertEquals(listOf(text("snake_case_word")), parse("snake_case_word"))
  }

  @Test
  fun `a backslash before a newline is a line break`() {
    assertEquals(listOf(text("a\nb")), parse("a\\\nb"))
  }

  @Test
  fun `unclosed emphasis is its own characters`() {
    assertEquals(listOf(text("**unclosed bold")), parse("**unclosed bold"))
  }

  @Test
  fun `a reference link is literal, since reference definitions are not supported`() {
    assertEquals("[x][ref]", MarkdownInline.plain(parse("[x][ref]")))
  }

  @Test
  fun `a hundred-kilobyte line parses promptly`() {
    val source = "word ".repeat(20 * 1024)
    val started = System.nanoTime()
    assertEquals(listOf(text(source)), parse(source))
    assertTrue((System.nanoTime() - started) < 1_000_000_000L, "a contract-maximum message must parse in under a second")
  }
}
