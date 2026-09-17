package ed.unicoach.web

import ed.unicoach.common.util.UsDate
import ed.unicoach.web.render.ExplainerBlock
import ed.unicoach.web.render.NAMED_THIRD_PARTIES
import ed.unicoach.web.render.WHO_PAYS_PATH
import ed.unicoach.web.render.blocks
import ed.unicoach.web.render.sourcesInReferenceOrder
import ed.unicoach.web.render.thirdPartyLeaks
import ed.unicoach.web.render.unsourcedPassages
import ed.unicoach.web.render.whoPaysPage
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 204 — the guards that make the explainer's citation discipline structural
 * rather than a habit.
 *
 * Most of these tests walk the CONTENT VALUE (`whoPaysPage`), not the rendered
 * string, because that is the only place the distinction between a sourced
 * `Claim` and unsourced `Prose` still exists. Once the page is HTML, a claim
 * with no source is indistinguishable from one with a source, which is exactly
 * the failure the typed model was chosen to prevent.
 *
 * NO TEST HERE MAKES A NETWORK CALL (D-F). That every URL is live, and that each
 * page really says what we quote, was checked once during the run and recorded
 * in `source-check.md`; a test that re-fetched would fail the build whenever
 * somebody else's marketing site had an outage, and would still not be able to
 * prove the quote matched.
 */
class WhoPaysPageTest {
  /** The earliest date this page's research could honestly have been done (brief 0011). */
  private val researchDate = LocalDate.of(2026, 9, 10)

  /** The guide as a browser receives it. Owns the scaffold, like `CostReportPageTest.render()`. */
  private fun renderedBody(): String {
    var body: String? = null
    testApplication {
      application { testPublicWebModule() }
      val response = client.get(WHO_PAYS_PATH)
      // Status FIRST. Three tests below assert the ABSENCE of something, and a
      // 404 or 500 body satisfies all of them: a broken route would make them
      // pass rather than fail.
      assertEquals(HttpStatusCode.OK, response.status, "the guide did not render")
      body = response.bodyAsText()
    }
    return checkNotNull(body) { "the guide was never fetched" }
  }

  @Test
  fun `the page renders through the shared layout`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get(WHO_PAYS_PATH)
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Text.Html.toString()) == true,
        "guide did not render as HTML",
      )

      val body = response.bodyAsText()
      // The page marker: proves THIS body rendered, not a static file.
      assertTrue(body.contains("<h1>${whoPaysPage.title}</h1>"), "missing guide page marker")
      // Shared chrome proves it rendered through siteLayout.
      assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
      assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
    }

  @Test
  fun `the nav link on every page points at the guide`() =
    testApplication {
      application { testPublicWebModule() }

      // The door (D-G): the link is in the SHARED header, so it is on the home
      // page too, not only on the guide itself.
      val home = client.get("/").bodyAsText()
      assertTrue(
        home.contains("<a href=\"$WHO_PAYS_PATH\">${whoPaysPage.title}</a>"),
        "shared header carries no link to the guide",
      )
    }

  @Test
  fun `every claim carries a source`() {
    val claims = whoPaysPage.blocks().filterIsInstance<ExplainerBlock.Claim>()
    assertTrue(claims.isNotEmpty(), "the page makes no sourced claim at all")
    claims.forEach { claim ->
      assertTrue(
        claim.source.url.startsWith("https://"),
        "claim source is not an absolute https URL: [${claim.source.url}]",
      )
      assertTrue(claim.source.publisher.isNotBlank(), "claim source has no publisher: [${claim.source.url}]")
      assertTrue(claim.source.title.isNotBlank(), "claim source has no title: [${claim.source.url}]")
    }
  }

  @Test
  fun `every source has a real fetch date`() {
    whoPaysPage.sourcesInReferenceOrder().forEach { source ->
      // Hermetic: the page may not claim to be current as of a date BEFORE the
      // pages it quotes were read. This is the relation that actually matters,
      // and it holds whenever the test runs.
      assertFalse(
        source.fetchedOn.isAfter(whoPaysPage.asOf),
        "source fetched after the page's as-of date: [${source.url}]",
      )
      assertFalse(
        source.fetchedOn.isBefore(researchDate),
        "source fetch date predates the research: [${source.url}]",
      )
    }
    // The one clock read, and the only thing that can catch a date typed into
    // the future: a page cannot have been fetched on a day that has not come.
    assertFalse(whoPaysPage.asOf.isAfter(LocalDate.now()), "the page is dated in the future")
  }

  @Test
  fun `every named third party is spoken only inside a claim`() {
    // The D-B sweep, over every UNSOURCED string on the page — not only the
    // blocks. The title, the standfirst and the section headings are
    // unicoach-authored and carry no source, so a company named in one would
    // escape D14(b) entirely; they are swept for exactly that reason.
    assertEquals(
      emptyList(),
      whoPaysPage.thirdPartyLeaks(),
      "a named third party is spoken outside a Claim",
    )
  }

  @Test
  fun `the guard set is live`() {
    assertTrue(NAMED_THIRD_PARTIES.isNotEmpty(), "the third-party guard set is empty")
    // Deliberately NOT the rendered body: the sources list prints every
    // publisher and page title, so a company whose sentence was deleted would
    // still "appear" on the page through its own citation line and the guard
    // could never fire. The claim TEXT is the only place the name has to be.
    val spoken = whoPaysPage.blocks().filterIsInstance<ExplainerBlock.Claim>().map { it.text }
    NAMED_THIRD_PARTIES.forEach { name ->
      assertTrue(
        spoken.any { it.contains(name) },
        "guarded name is spoken in no claim on the page: [$name]",
      )
    }
  }

  @Test
  fun `the guard set covers every company on the page`() {
    // The other direction, and the one that rots silently: write about a new
    // company, forget the set, and the sweep above is blind to it forever.
    // Every source is a page published BY the company it is cited for, so its
    // publisher must name a guarded company.
    whoPaysPage.sourcesInReferenceOrder().forEach { source ->
      assertTrue(
        source.publisher in NON_COMPANY_PUBLISHERS ||
          NAMED_THIRD_PARTIES.any { source.publisher.contains(it) },
        "a source publisher is in no guard set: [${source.publisher}]",
      )
    }
  }

  @Test
  fun `every source is referenced and every reference listed`() {
    val referenced =
      whoPaysPage
        .blocks()
        .filterIsInstance<ExplainerBlock.Claim>()
        .map { it.source.url }
        .toSet()
    val listed = whoPaysPage.sourcesInReferenceOrder().map { it.url }.toSet()
    assertEquals(referenced, listed, "the sources list and the claim set differ")
    assertEquals(
      listed.size,
      whoPaysPage.sourcesInReferenceOrder().size,
      "the sources list repeats a URL instead of deduplicating it",
    )
  }

  @Test
  fun `every source is printed on the page with its fetch date`() {
    val body = renderedBody()
    whoPaysPage.sourcesInReferenceOrder().forEach { source ->
      assertTrue(body.contains(source.url), "source URL missing from the page: [${source.url}]")
      assertTrue(
        // RFC 227: the one date seam, so this asserts the SHAPE a visitor
        // reads ("Fetched Sep 14, 2026.") and not a re-rendering of the field.
        body.contains("Fetched ${UsDate.of(source.fetchedOn)}."),
        "source fetch date missing from the page: [${source.url}]",
      )
    }
  }

  @Test
  fun `outbound links are nofollow and open in place`() {
    val body = renderedBody()
    whoPaysPage.sourcesInReferenceOrder().forEach { source ->
      assertTrue(
        body.contains("<a href=\"${source.url}\" rel=\"nofollow noopener\">"),
        "outbound link is not rel=nofollow noopener: [${source.url}]",
      )
    }
    assertFalse(body.contains("target="), "an outbound link opens a new window")
  }

  @Test
  fun `the body prose links nowhere but our own pages`() {
    // Every third-party URL appears exactly once — in the sources list. The
    // argument itself stays link-free, so the reader reads an argument and not
    // a field of blue.
    val body = renderedBody()
    whoPaysPage.sourcesInReferenceOrder().forEach { source ->
      assertEquals(
        1,
        body.split("href=\"${source.url}\"").size - 1,
        "third-party URL is linked more than once: [${source.url}]",
      )
    }
    val selfCited = whoPaysPage.blocks().filterIsInstance<ExplainerBlock.SelfCited>()
    assertTrue(selfCited.isNotEmpty(), "the page makes no checkable claim about unicoach")
    selfCited.forEach { block ->
      assertTrue(
        body.contains("<a href=\"${block.target.value}\">${block.linkText}</a>"),
        "missing self-citation link: [${block.target.value}]",
      )
    }
  }

  @Test
  fun `every page we cite about ourselves actually resolves`() =
    testApplication {
      application { testPublicWebModule() }

      // A claim about unicoach is checkable only if the link lands. Rename one
      // of these routes and the guide would otherwise keep rendering a
      // confident-looking link to a 404.
      whoPaysPage.blocks().filterIsInstance<ExplainerBlock.SelfCited>().forEach { block ->
        assertEquals(
          HttpStatusCode.OK,
          client.get(block.target.value).status,
          "a self-citation points at a page that does not resolve: [${block.target.value}]",
        )
      }
    }

  @Test
  fun `the as-of date is visible`() {
    assertTrue(
      renderedBody().contains("As of ${UsDate.of(whoPaysPage.asOf)}"),
      "the page does not print its as-of date",
    )
  }

  @Test
  fun `the page asks for nothing`() {
    val body = renderedBody()
    listOf("<form", "<input").forEach { token ->
      assertFalse(body.contains(token), "the page collects something: [$token]")
    }
    // RFC 225 gave EVERY page the one external module the shell loads, so a
    // blanket "no <script>" ban had to go. What this page still promises is
    // that it runs nothing OF ITS OWN: exactly one script, external, and it is
    // the shell's bundle, named from the manifest and never typed here.
    assertLoadsBundleAsItsOnlyScript(WHO_PAYS_PATH, body)
  }

  @Test
  fun `the page publishes no grade and quotes nobody unsourced`() {
    // Brief 0011 D3: no score, rank, grade or chance figure authored by us. The
    // guard is blunt on purpose — unicoach's OWN words carry no digit, no
    // percent sign and no letter grade at all, so every figure on the page is
    // demonstrably a quoted one and a figure of ours cannot be introduced
    // without failing here. A LETTER grade is banned by name because it carries
    // no digit, and it is the exact shape of scoring this page is written about.
    //
    // Quotation marks are banned in the same walk, and for the same reason one
    // level up: a quotation mark in unsourced prose is somebody's words with no
    // page behind them, which is the failure D14(b) exists to prevent and which
    // the third-party sweep misses whenever the speaker is not named.
    whoPaysPage.unsourcedPassages().forEach { (origin, text) ->
      assertFalse(text.any { it.isDigit() }, "unicoach-authored copy states a figure of its own: [$origin] [$text]")
      assertFalse(text.contains('%'), "unicoach-authored copy states a percentage of its own: [$origin] [$text]")
      assertFalse(
        LETTER_GRADE.containsMatchIn(text),
        "unicoach-authored copy states a letter grade of its own: [$origin] [$text]",
      )
      assertFalse(
        QUOTATION.containsMatchIn(text),
        "unicoach-authored copy quotes somebody with no source: [$origin] [$text]",
      )
    }
  }

  private companion object {
    /** Publishers that are not companies in this market, so the guard set does not name them. */
    val NON_COMPANY_PUBLISHERS = setOf("arXiv")

    /**
     * `A+`, `B-` — a grade on the scale this page exists to explain.
     *
     * The sign is REQUIRED, and that is not laziness: a bare `A` is the English
     * article and a bare `F` is a word boundary away from nothing, so matching
     * an unsigned letter would fail on "A family never sees the price list."
     * The signed form is the shape a grade actually takes in this market.
     */
    val LETTER_GRADE = Regex("""\b[A-F][+-]""")

    /** Straight and curly quotation marks: somebody else's words, wherever they came from. */
    val QUOTATION = Regex("[\u201c\u201d\"]")
  }
}
