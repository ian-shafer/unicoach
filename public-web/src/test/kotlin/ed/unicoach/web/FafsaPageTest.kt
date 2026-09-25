package ed.unicoach.web

import ed.unicoach.coaching.aid.FafsaDeadline
import ed.unicoach.coaching.aid.FafsaDeadlines
import ed.unicoach.coaching.aid.FafsaProcessCopy
import ed.unicoach.coaching.aid.FafsaProcessCopy.FafsaProcessTopic
import ed.unicoach.coaching.aid.FafsaUnreadableAgencies
import ed.unicoach.coaching.aid.MissingJurisdiction
import ed.unicoach.coaching.aid.NoDeadlinesReason
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.UsStateRegion
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.db.models.JurisdictionKind
import ed.unicoach.db.models.PublishedDeadline
import ed.unicoach.db.models.SourceUrlStatus
import ed.unicoach.web.render.AS_OF_PREFIX
import ed.unicoach.web.render.CitedRow
import ed.unicoach.web.render.DATA_TABLE_CLASS
import ed.unicoach.web.render.ExplainerBlock
import ed.unicoach.web.render.ExplainerSource
import ed.unicoach.web.render.FAFSA_PATH
import ed.unicoach.web.render.FAFSA_TITLE
import ed.unicoach.web.render.FILING_YEAR_COLUMN
import ed.unicoach.web.render.JURISDICTION_COLUMN
import ed.unicoach.web.render.TERRITORIES_SENTENCE
import ed.unicoach.web.render.TableColumn
import ed.unicoach.web.render.blocks
import ed.unicoach.web.render.fafsaPage
import ed.unicoach.web.render.fafsaRefusalSentence
import ed.unicoach.web.render.sourcesInReferenceOrder
import ed.unicoach.web.render.thirdPartyLeaks
import ed.unicoach.web.render.unsourcedPassages
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /fafsa` (RFC 238): the public FAFSA explainer, its deadline tables, and
 * what it does when there is no live answer to table.
 *
 * NO TEST HERE DRIVES A CLOCK OR SEEDS A DEADLINE. Which rows are live, which
 * cycle each belongs to and which of the three refusal arms fires are
 * `FafsaDeadlineService`'s decisions, proven against real Postgres in its own
 * suite. What this suite asks is what the PAGE adds: that every jurisdiction
 * the service returned reaches the rendered bytes, that a row says which cycle
 * it belongs to and where it came from, that a dead link is not a link, and
 * that a refusal is a page rather than an error.
 *
 * The one thing it DOES read from the database is the published jurisdiction
 * list, because "all fifty-one" is a claim about the codebook and typing the
 * names here would be a second copy of it.
 */
class FafsaPageTest {
  @Test
  fun `the page renders through the shared layout, with no account`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get(FAFSA_PATH)

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Text.Html.toString()) == true,
        "the page did not render as HTML",
      )
      val body = response.bodyAsText()
      assertTrue(body.contains("<h1>$FAFSA_TITLE</h1>"), "missing page marker")
      assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
      assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
      assertTrue(body.contains("<body class=\"wide-measure\">"), "a table of fifty-one jurisdictions rendered at the prose measure")
    }

  @Test
  fun `the shared header carries the door to this page`() =
    testApplication {
      application { testPublicWebModule() }

      val home = client.get("/").bodyAsText()

      assertTrue(
        home.contains("<a href=\"$FAFSA_PATH\">$FAFSA_TITLE</a>"),
        "the shared header carries no link to the FAFSA page, so only a URL-typer reaches it",
      )
    }

  @Test
  fun `every published jurisdiction reaches a table row, served or named as a gap`() {
    val body = bodyFor(wholeCountry())

    val published = publishedJurisdictions()
    // The fifty states and the District of Columbia (D13). MEASURED from the
    // codebook rather than counted here, so this stays a claim about what the
    // page renders and not a second copy of the jurisdiction list.
    assertEquals(
      SERVED_JURISDICTIONS,
      published.size,
      "the codebook no longer publishes the fifty states and DC as the served set: [${published.map { it.uspsCode }}]",
    )
    // THE CELLS THE PAGE TABLED, not the names that appear anywhere in it:
    // "District of Columbia" is in the page's own scope sentence, so a
    // body-wide substring sweep passes with DC's row missing altogether --
    // which is exactly the half-rendered table this assertion exists for.
    val tabledCells = renderedCells(body, JURISDICTION_COLUMN.header)
    assertEquals(
      published.map { it.name }.toSet(),
      tabledCells.toSet(),
      "the page tabled a different set of jurisdictions than the codebook publishes",
    )
    // THE CELLS AS A LIST, because a set cannot see a row rendered twice: the
    // claim is one row per jurisdiction, and the set equality above is silent
    // about a jurisdiction tabled by two rows of two different regions.
    assertEquals(
      published.size,
      tabledCells.size,
      "a jurisdiction was tabled twice, or not at all: [${tabledCells.sorted()}]",
    )
  }

  @Test
  fun `the page says which jurisdictions it holds no dates for at all`() {
    val body = bodyFor(wholeCountry())

    // READ FROM THE PAGE'S OWN CONSTANT, so the assertion cannot drift from
    // the copy it is about. D13's bound is the page's only statement about
    // the territories, and without this a copy edit deletes it with a green
    // suite.
    assertTrue(
      body.contains(TERRITORIES_SENTENCE),
      "the page does not bound its own coverage, so a family reads the table as the whole country",
    )
  }

  @Test
  fun `the form is free is said before any instruction about filing`() =
    testApplication {
      application { testPublicWebModule() }

      val body = renderedBody()

      val free = FafsaProcessCopy.rulesOf(FafsaProcessTopic.THE_FORM_IS_FREE).first().statement
      val whoFiles = FafsaProcessCopy.rulesOf(FafsaProcessTopic.WHO_FILES).first().statement
      assertTrue(body.contains(free), "the statutory free-form rule is not on the page")
      assertTrue(
        body.indexOf(free) < body.indexOf(whoFiles),
        "the page tells a family how to file before it tells them the form is free",
      )
      assertTrue(
        body.indexOf(free) < body.indexOf("When it is due"),
        "the page gives a deadline before it says the form is free",
      )
    }

  @Test
  fun `we never touch the form, in our own voice, and the page asks for nothing`() =
    testApplication {
      application { testPublicWebModule() }

      val body = renderedBody()

      assertTrue(
        body.contains("never fills the form in for you, never submits it, and never asks you for your"),
        "the page does not say what we never do with the form",
      )
      assertFalse(body.contains("<form"), "the page asks a family for something")
      assertFalse(body.contains("<input"), "the page asks a family for something")
      assertLoadsBundleAsItsOnlyScript(FAFSA_PATH, body)
    }

  @Test
  fun `every rule the coach speaks is on the page`() =
    testApplication {
      application { testPublicWebModule() }

      val body = renderedBody()

      assertEquals(
        emptyList(),
        FafsaProcessCopy.RULES.map { it.statement }.filterNot { body.contains(it) },
        "the page and the coach would tell a family two different stories",
      )
    }

  @Test
  fun `a row prints the agency's own words, whichever of the three shapes they are`() {
    val dated = PublishedDeadline.OnDate(LocalDate.parse("2027-03-02"), "March 2, 2027, 11:59 p.m. Pacific")
    val inWords = PublishedDeadline.InWords("as soon as possible after the form opens")
    val none = PublishedDeadline.NonePublished("this department publishes no state FAFSA deadline for this program")

    val body =
      bodyFor(
        resolvedFixture(
          states =
            listOf(
              stateFixture("CA", "California", "Far West", listOf(deadlineFixture(deadline = dated))),
              stateFixture("OR", "Oregon", "Far West", listOf(deadlineFixture(deadline = inWords))),
              stateFixture("WY", "Wyoming", "Rocky Mountains", listOf(deadlineFixture(deadline = none))),
            ),
        ),
      )

    assertTrue(body.contains(dated.deadlineAsPublished), "a dated row did not print the day the agency published")
    assertTrue(body.contains(inWords.deadlineAsPublished), "a rule-shaped row did not print the rule verbatim")
    assertTrue(body.contains(none.deadlineAsPublished), "a verified absence did not print the agency saying so")
  }

  @Test
  fun `every row names the cycle its OWN date belongs to, where two filing years are open at once`() {
    // TWO CYCLES IN THE FIXTURE, because one is what the column exists for
    // (D23): with every row on one filing year, a page printing the resolved
    // year for every row -- the exact defect this column guards -- renders
    // identically to a correct one.
    val nextYear = AcademicYear(FIXTURE_AWARD_YEAR.firstCalendarYear + 1)
    val answer =
      resolvedFixture(
        states =
          listOf(
            stateFixture("CA", "California", "Far West", listOf(deadlineFixture(awardYear = FIXTURE_AWARD_YEAR))),
            stateFixture("OR", "Oregon", "Far West", listOf(deadlineFixture(awardYear = nextYear))),
          ),
      )
    val body = bodyFor(answer)

    val served = answer.federal + answer.states.flatMap { it.deadlines }
    val expected = served.map { it.awardYear.label }
    assertEquals(2, expected.distinct().size, "the fixture carries one cycle, so this assertion proves nothing")
    assertEquals(
      expected.sorted(),
      renderedCells(body, FILING_YEAR_COLUMN.header).sorted(),
      "a row does not carry the filing year of its own date",
    )
    assertTrue(
      body.contains("as we read it on ${UsDate.of(FIXTURE_CHECKED_ON)}"),
      "a row does not say when we read the page it came from",
    )
  }

  @Test
  fun `a state that also demands its own application says so, in its program and its agency's words`() {
    val program = "Tennessee Promise (state application)"
    val published =
      "High School students will be required to complete the Tennessee Promise application in the " +
        "THEC Student Portal by November 2, 2026 and complete the FAFSA by April 1, 2027."

    val body =
      bodyForOneState(
        "TN",
        "Tennessee",
        "Southeast",
        deadlineFixture(program = program, deadline = PublishedDeadline.InWords(published)),
      )

    assertTrue(body.contains(program), "the program name that carries the extra application is not rendered")
    assertTrue(body.contains(published), "the agency's own words about its extra application are not rendered")
  }

  @Test
  fun `a row cited over plain http reaches the page, and is linked`() {
    // THE STORE ACCEPTS http BY AN EXPLICIT DECISION, and four rows of the
    // landed corpus cite `http://collegefortn.org/...`. An https-only citation
    // guard did not fail loudly on them: it dropped every Tennessee row, so the
    // state reached no table and -- being served -- no gap list either.
    val body =
      bodyForOneState("TN", "Tennessee", "Southeast", deadlineFixture(sourceUrl = FIXTURE_HTTP_SOURCE_URL))

    assertEquals(
      listOf("Tennessee"),
      renderedCells(body, JURISDICTION_COLUMN.header),
      "a jurisdiction cited over plain http reached no table row",
    )
    assertTrue(body.contains("href=\"$FIXTURE_HTTP_SOURCE_URL\""), "the page did not link the page it read")
  }

  @Test
  fun `a jurisdiction whose rows cannot be printed is named to the family, and takes no other row with it`() {
    val body =
      bodyFor(
        resolvedFixture(
          states =
            listOf(
              // A blank cell is the corpus defect this path exists for: the
              // agency's own program name arrived empty.
              stateFixture("NV", "Nevada", "Far West", listOf(deadlineFixture(program = " "))),
              stateFixture("CA", "California", "Far West"),
            ),
        ),
      )

    assertEquals(
      listOf("California"),
      renderedCells(body, JURISDICTION_COLUMN.header),
      "one unprintable row took a whole table with it",
    )
    assertTrue(
      body.contains("We hold dates for Nevada that we could not print here"),
      "a jurisdiction was dropped from the table and the family was told nothing",
    )
  }

  @Test
  fun `a dropped FEDERAL row is named to the family by the federal set, and not by the blank cell that dropped it`() {
    // THE PROGRAM NAME IS THE LABEL AND THE BLANK CELL AT ONCE. A federal row
    // is named to the family by its program, and an empty program is the
    // commonest reason such a row drops -- so labelling the drop with it names
    // nobody. The state rows print, because a fixture whose every row is blank
    // is the refusal page and hides the sentence entirely.
    val body =
      bodyFor(
        resolvedFixture(
          federal = listOf(deadlineFixture(program = " ")),
          states = listOf(stateFixture("CA", "California", "Far West")),
        ),
      )

    assertEquals(
      listOf("California"),
      renderedCells(body, JURISDICTION_COLUMN.header),
      "an unprintable federal row took the state tables with it",
    )
    assertTrue(
      body.contains("We hold dates for federal student aid that we could not print here"),
      "a dropped federal row was named to the family by its own blank program",
    )
  }

  @Test
  fun `a resolved answer no row of which can be printed is the refusal page, with no date`() {
    val body =
      bodyFor(
        resolvedFixture(
          federal = listOf(deadlineFixture(program = " ")),
          states = listOf(stateFixture("CA", "California", "Far West", listOf(deadlineFixture(program = " ")))),
        ),
      )

    assertFalse(body.contains("<table"), "the page printed a table with nothing to put in it")
    assertFalse(
      body.contains(AS_OF_PREFIX),
      "the page stood behind a reading date for a table not one row of which rendered",
    )
    assertTrue(
      body.contains("We are not showing the dates right now"),
      "the page withheld every date without telling the family it had",
    )
  }

  @Test
  fun `a row says what kind of date it is, so a priority date is not read as the last day`() {
    val body =
      bodyForOneState("CA", "California", "Far West", deadlineFixture(event = AidDeadlineEvent.PRIORITY_DEADLINE))

    assertTrue(
      body.contains("File by this day to be considered first"),
      "a priority date is rendered as though it were the last day to file",
    )
  }

  @Test
  fun `the ones we could not read say we tried, name the agency and link what we tried`() {
    val body = bodyFor(wholeCountry())

    assertTrue(body.contains("could not get a deadline out of it"), "the page does not say we tried and could not")
    FafsaUnreadableAgencies.BY_JURISDICTION.values.forEach { agency ->
      assertTrue(body.contains(agency.publisher), "the agency to ask [${agency.publisher}] is not named")
      assertTrue(
        body.contains("href=\"${agency.url}\""),
        "the page we tried [${agency.url}] is not linked, though the link check found it alive",
      )
    }
  }

  @Test
  fun `a gap we recorded no agency for is named rather than silently dropped`() {
    val body =
      bodyFor(
        resolvedFixture(
          missing =
            listOf(
              MissingJurisdiction(
                jurisdiction = jurisdictionOf("MT"),
                name = "Montana",
                region = "Rocky Mountains",
                agency = null,
              ),
            ),
        ),
      )

    assertTrue(body.contains("We hold no dates at all for Montana"), "a gap with no recorded agency vanished")
  }

  @Test
  fun `a source that answered 404 is printed as a name and never as a link`() {
    val grave = "https://hathawayscholarship.example/fafsa/"

    val body =
      bodyForOneState(
        "WY",
        "Wyoming",
        "Rocky Mountains",
        deadlineFixture(
          publisher = "Wyoming Department of Education",
          sourceUrl = grave,
          sourceUrlStatus = SourceUrlStatus.NOT_FOUND,
        ),
      )

    assertTrue(body.contains("Wyoming Department of Education"), "a dead link took the publisher's name with it")
    assertFalse(body.contains(grave), "the page linked a url that answered 404")
  }

  @Test
  fun `a source we got no usable answer about stays a link`() {
    val unswept = "https://ndus.example/paying-for-college/state-grant"

    val body =
      bodyForOneState(
        "ND",
        "North Dakota",
        "Plains",
        deadlineFixture(sourceUrl = unswept, sourceUrlStatus = SourceUrlStatus.UNKNOWN),
      )

    assertTrue(
      body.contains("href=\"$unswept\""),
      "a page we merely could not sweep was treated as a grave",
    )
  }

  @Test
  fun `an archived copy is offered where the row was read through one`() {
    val archive = "https://web.archive.example/web/2026/https://aid.example.gov/deadlines"

    val body = bodyForOneState("VA", "Virginia", "Southeast", deadlineFixture(archiveUrl = archive))

    assertTrue(body.contains("href=\"$archive\""), "the archived copy the row was read through is not offered")
    assertTrue(body.contains("archived copy"), "the archived link is offered without saying what it is")
  }

  @Test
  fun `an archived copy served over plain http is offered too, as six seeded ones are`() {
    // `http://web.archive.org/...` is the shape six archived addresses in the
    // committed seed already carry, and an https-only guard on this value
    // faulted the WHOLE deadline read rather than one row.
    val archive = "http://web.archive.example/web/2026/https://aid.example.gov/deadlines"

    val body = bodyForOneState("VA", "Virginia", "Southeast", deadlineFixture(archiveUrl = archive))

    assertTrue(body.contains("href=\"$archive\""), "a plain-http archived copy is not offered")
  }

  @Test
  fun `an archived copy that answered 404 is not offered at all`() {
    val grave = "https://web.archive.example/web/2026/gone"

    val body =
      bodyForOneState(
        "VA",
        "Virginia",
        "Southeast",
        deadlineFixture(archiveUrl = grave, archiveUrlStatus = SourceUrlStatus.NOT_FOUND),
      )

    assertFalse(body.contains(grave), "the page offered an archived address that answered 404")
    assertFalse(body.contains("archived copy"), "the page offered an archived copy it will not link")
  }

  @Test
  fun `outbound links are nofollow and open in place`() {
    val body = bodyFor(wholeCountry())

    // THE ANCHOR IS TOKENISED AND ITS ATTRIBUTES READ, never matched as bytes
    // anywhere in the document: `rel=` inside an href query string satisfied a
    // whole-tag search, `target=` in any prose sentence failed one, and the old
    // regex only matched anchors whose href came FIRST -- so one `a(classes =
    // ...)` in `outboundLink` would have made the sweep match nothing and pass.
    val outbound =
      ANCHOR_ELEMENT
        .findAll(body)
        .map { it.groupValues[1] }
        .filter { attributes -> OUTBOUND_HREF.containsMatchIn(attributes) }
        .toList()

    assertTrue(outbound.isNotEmpty(), "the page rendered no outbound anchor, so this proves nothing")
    assertEquals(
      emptyList(),
      outbound.filterNot { it.contains("""rel="nofollow noopener"""") },
      "an outbound link carries no rel=\"nofollow noopener\"",
    )
    assertEquals(
      emptyList(),
      outbound.filter { TARGET_ATTRIBUTE.containsMatchIn(it) },
      "an outbound link opens a new tab, which is the reader's choice to make",
    )
  }

  @Test
  fun `a table escapes the paragraph the other blocks render in`() =
    testApplication {
      application { testPublicWebModule() }

      val body = renderedBody()

      assertTrue(body.contains("<table class=\"$DATA_TABLE_CLASS guide-table\">"), "no table rendered")
      assertFalse(
        Regex("""<p>(?:(?!</p>).)*<table""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(body),
        "a <table> rendered inside a <p>, which a browser parser auto-closes: the markup read is not the markup written",
      )
    }

  @Test
  fun `each refusal arm is a page, with no table and no as-of date`() {
    // A CONTROL FIRST: the resolved page DOES print the line, so the absences
    // below are absences of something the page is otherwise known to render.
    assertTrue(
      bodyFor(resolvedFixture()).contains("$AS_OF_PREFIX${UsDate.of(FIXTURE_AS_OF)}"),
      "the resolved page prints no as-of line, so the refusal checks below prove nothing",
    )
    NoDeadlinesReason.entries.forEach { reason ->
      testApplication {
        application { testPublicWebModule(fafsaDeadlines = FakeFafsaDeadlineSource.refusing(reason)) }

        val response = client.get(FAFSA_PATH)
        assertEquals(HttpStatusCode.OK, response.status, "[$reason] did not render as a page")
        val body = response.bodyAsText()

        assertFalse(body.contains("<table"), "[$reason] rendered a table with nothing to put in it")
        // THE RENDERED SENTENCE, not the class it is printed in. The class is
        // `ExplainerPage.kt`'s and nothing else asserts it, so an absence keyed
        // on it would stay green through a rename while a refusal printed a
        // date over a table that is not there. The positive control above is
        // what stops this passing because the line moved rather than went.
        assertFalse(body.contains(AS_OF_PREFIX), "[$reason] printed an as-of date for an answer that carries none")
        assertTrue(
          body.contains(FafsaProcessCopy.rulesOf(FafsaProcessTopic.THE_FORM_IS_FREE).first().statement),
          "[$reason] dropped the free-form rule, which is the law and does not expire",
        )
        assertTrue(
          body.contains("We are not showing the dates right now"),
          "[$reason] withheld the dates without telling the family it had",
        )
        assertTrue(
          body.contains("financial aid office at each college on your list"),
          "[$reason] withheld the dates without saying who to ask instead",
        )
      }
    }
  }

  @Test
  fun `the three refusals do not say the same sentence, and each reaches the family it is for`() {
    // READ FROM THE PAGE'S OWN HOME for that sentence, not found by a prefix of
    // it: nothing declared that all three arms open with the same five words, so
    // a copy edit to one arm used to break the lookup rather than the claim.
    val sentences = NoDeadlinesReason.entries.map { reason -> fafsaRefusalSentence(reason) }

    assertEquals(sentences.size, sentences.distinct().size, "two refusal arms tell a family the same thing")
    NoDeadlinesReason.entries.forEach { reason ->
      assertTrue(
        bodyFor(FafsaDeadlines.NoDeadlines(emptyList(), reason)).contains(fafsaRefusalSentence(reason)),
        "[$reason] did not print its own refusal sentence",
      )
    }
  }

  @Test
  fun `a read that faulted is the branded 503 and never a fourth refusal`() =
    testApplication {
      application {
        testPublicWebModule(fafsaDeadlines = FakeFafsaDeadlineSource.faulting(IllegalStateException("pool exhausted")))
      }

      val response = client.get(FAFSA_PATH)

      assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
      assertFalse(response.bodyAsText().contains("pool exhausted"), "the 503 leaked the fault to a family")
    }

  @Test
  fun `the page publishes no figure of its own and quotes nobody unsourced`() {
    val unsourced = fafsaPage(wholeCountry()).unsourcedPassages()

    assertTrue(unsourced.isNotEmpty(), "the sweep found nothing to sweep, so it proves nothing")
    unsourced.forEach { passage ->
      val text = passage.text
      assertFalse(text.any { it.isDigit() }, "[${passage.origin}] carries a figure of unicoach's own: [$text]")
      assertFalse(text.contains('%'), "[${passage.origin}] carries a percentage of unicoach's own: [$text]")
      assertFalse(QUOTATION.containsMatchIn(text), "[${passage.origin}] quotes somebody with no source: [$text]")
    }
  }

  @Test
  fun `a table's published cells are sourced data and are not swept as our own prose`() {
    val page = fafsaPage(wholeCountry())

    val tables = page.blocks().filterIsInstance<ExplainerBlock.CitedTable>()
    val authored = tables.flatMap { it.authoredCells() }.toSet()
    val published = tables.flatMap { table -> table.rows.flatMap { it.cells } }.toSet() - authored
    assertTrue(published.isNotEmpty(), "the page rendered no published table cells, so this proves nothing")
    // EQUALITY, not containment: the page's own scope sentence names the
    // District of Columbia, which is also a jurisdiction cell, and a substring
    // match would call that a leak. A swept cell arrives as its own passage.
    assertEquals(
      emptyList(),
      page.unsourcedPassages().filter { it.text in published },
      "a publisher's cell was swept as unicoach's own prose, which would make the no-figures guard unwritable",
    )
  }

  /**
   * The other half of the bargain: what a table exempts is the PUBLISHER's
   * words, so everything in a table that unicoach wrote -- the caption, the
   * headers, and the cells of the columns the table declares as ours -- is
   * swept with the rest of our copy. Without this the one place a copy edit
   * could hide a figure is inside a table.
   */
  @Test
  fun `a table's caption, its headers and the cells we author are swept as our own prose`() {
    val page = fafsaPage(wholeCountry())

    val swept = page.unsourcedPassages().map { it.text }.toSet()
    val tables = page.blocks().filterIsInstance<ExplainerBlock.CitedTable>()
    assertTrue(tables.isNotEmpty(), "the page rendered no table, so this proves nothing")
    val ours = tables.flatMap { listOf(it.caption) + it.headers() + it.authoredCells() }
    assertEquals(
      emptyList(),
      ours.filterNot { it in swept }.distinct(),
      "a string unicoach wrote inside a table escaped the sweep",
    )
  }

  @Test
  fun `no named third party is spoken outside a claim`() {
    assertEquals(emptyList(), fafsaPage(wholeCountry()).thirdPartyLeaks())
  }

  @Test
  fun `the page-level reference list holds each document once and no agency page at all`() {
    val page = fafsaPage(wholeCountry())

    val sources = page.sourcesInReferenceOrder()
    assertEquals(sources.map { it.url }.distinct(), sources.map { it.url }, "a document reached the list twice")
    assertEquals(
      FafsaProcessCopy.DOCUMENTS.map { it.url }.toSet(),
      sources.map { it.url }.toSet(),
      "the list at the foot is not exactly the documents the page's prose cites",
    )
  }

  @Test
  fun `a table row cannot be built without cells that say something`() {
    val source = ExplainerSource("An Agency", "Deadlines", "https://aid.example.gov/x", LocalDate.parse("2026-09-14"))

    assertFailsWith<IllegalArgumentException> {
      CitedRow(cells = emptyList(), source = source, sourceUrlStatus = SourceUrlStatus.FOUND)
    }
    assertFailsWith<IllegalArgumentException> {
      CitedRow(cells = listOf("California", "  "), source = source, sourceUrlStatus = SourceUrlStatus.FOUND)
    }
  }

  @Test
  fun `a row's guards hold after construction, whatever the caller does with the list it passed`() {
    val source = ExplainerSource("An Agency", "Deadlines", "https://aid.example.gov/x", LocalDate.parse("2026-09-14"))
    val cells = mutableListOf("California")

    val row = CitedRow(cells = cells, source = source, sourceUrlStatus = SourceUrlStatus.FOUND)
    cells.clear()

    assertEquals(listOf("California"), row.cells, "a validated row lost its cells to the caller's own list")
  }

  @Test
  fun `a table refuses a row that does not fit its columns`() {
    val source = ExplainerSource("An Agency", "Deadlines", "https://aid.example.gov/x", LocalDate.parse("2026-09-14"))
    val row = CitedRow(cells = listOf("California"), source = source, sourceUrlStatus = SourceUrlStatus.FOUND)

    val failure =
      assertFailsWith<IllegalArgumentException> {
        ExplainerBlock.CitedTable(
          caption = "Far West",
          columns = listOf(TableColumn("Jurisdiction"), TableColumn("Cycle")),
          sourceColumn = TableColumn("Where we read it"),
          rows = listOf(row),
        )
      }

    assertTrue(failure.message!!.contains("Far West"), "the refusal does not name the table it is about")
  }

  @Test
  fun `a table with no rows is refused, because the page's answer for nothing to show is no table at all`() {
    assertFailsWith<IllegalArgumentException> {
      ExplainerBlock.CitedTable(
        caption = "Far West",
        columns = listOf(TableColumn("Jurisdiction")),
        sourceColumn = TableColumn("Where we read it"),
        rows = emptyList(),
      )
    }
  }

  @Test
  fun `a column with no header does not construct`() {
    // A blank header is a `<th>` with nothing in it and a phone card's cell
    // labelled with nothing.
    assertFailsWith<IllegalArgumentException> { TableColumn("  ") }
  }

  @Test
  fun `a table refuses a blank caption`() {
    val source = ExplainerSource("An Agency", "Deadlines", "https://aid.example.gov/x", LocalDate.parse("2026-09-14"))
    val row = CitedRow(cells = listOf("California"), source = source, sourceUrlStatus = SourceUrlStatus.FOUND)

    // A blank caption is a table a family cannot tell from the one above it.
    assertFailsWith<IllegalArgumentException> {
      ExplainerBlock.CitedTable(
        caption = "  ",
        columns = listOf(TableColumn("Jurisdiction")),
        sourceColumn = TableColumn("Where we read it"),
        rows = listOf(row),
      )
    }
  }

  @Test
  fun `a table refuses two columns of one name, the citation column included`() {
    val source = ExplainerSource("An Agency", "Deadlines", "https://aid.example.gov/x", LocalDate.parse("2026-09-14"))
    val row = CitedRow(cells = listOf("California", "2026-27"), source = source, sourceUrlStatus = SourceUrlStatus.FOUND)

    // Two columns of one name label two different values identically on a
    // stacked phone card, and anything reading the rendered cells back reads
    // one column's as the other's.
    val duplicated =
      assertFailsWith<IllegalArgumentException> {
        ExplainerBlock.CitedTable(
          caption = "Far West",
          columns = listOf(TableColumn("Jurisdiction"), TableColumn("Jurisdiction")),
          sourceColumn = TableColumn("Where we read it"),
          rows = listOf(row),
        )
      }
    assertTrue(duplicated.message!!.contains("Jurisdiction"), "the refusal does not name the header it refused")

    val collided =
      assertFailsWith<IllegalArgumentException> {
        ExplainerBlock.CitedTable(
          caption = "Far West",
          columns = listOf(TableColumn("Jurisdiction"), TableColumn("Where we read it")),
          sourceColumn = TableColumn("Where we read it"),
          rows = listOf(row),
        )
      }
    assertTrue(collided.message!!.contains("Where we read it"), "the refusal does not name the header it refused")
  }

  private suspend fun ApplicationTestBuilder.renderedBody(): String {
    // OK FIRST. Several assertions below are about the ABSENCE of something,
    // and a 404 or a 503 body satisfies every one of them.
    val response = client.get(FAFSA_PATH)
    assertEquals(HttpStatusCode.OK, response.status, "the page did not render")
    return response.bodyAsText()
  }

  /**
   * The page as a browser receives it, for one hand-built answer.
   *
   * IT OWNS THE SCAFFOLD, the way `WhoPaysPageTest.renderedBody()` does. Every
   * case below varies one value of one fixture, and writing the ladder out each
   * time -- `testApplication`, `application`, the module, the fake, the
   * `Result`, the fixture, the state, the row -- buried the one line the case
   * was actually about.
   */
  private fun bodyFor(answer: FafsaDeadlines): String {
    var body: String? = null
    testApplication {
      application { testPublicWebModule(fafsaDeadlines = FakeFafsaDeadlineSource(Result.success(answer))) }
      body = renderedBody()
    }
    return checkNotNull(body) { "the page was never fetched" }
  }

  /** The page for ONE served state, which is the shape most cases below vary by a single field. */
  private fun bodyForOneState(
    uspsCode: String,
    name: String,
    region: String,
    vararg deadlines: FafsaDeadline,
  ): String = bodyFor(resolvedFixture(states = listOf(stateFixture(uspsCode, name, region, deadlines.toList()))))

  private companion object {
    private val QUOTATION = Regex("[\u201c\u201d\"]")

    /** The fifty states and the District of Columbia -- the jurisdictions this page serves (D13). */
    private const val SERVED_JURISDICTIONS = 51

    /**
     * The jurisdiction whose fixture row is cited over plain http, as four rows
     * of the landed corpus are. Tennessee, because Tennessee is the state whose
     * rows the https-only citation guard actually dropped.
     */
    private const val HTTP_CITED_JURISDICTION = "TN"

    /** One anchor's attribute region -- read as attributes, never as bytes anywhere in the body. */
    val ANCHOR_ELEMENT = Regex("""(?is)<a\b([^>]*)>""")

    /** An `href` at a page we do not control, as an ATTRIBUTE of that region. */
    val OUTBOUND_HREF = Regex("""\shref="https?://""")

    /** `target` as an ATTRIBUTE: preceded by whitespace, so a `?target=` inside a url is not one. */
    val TARGET_ATTRIBUTE = Regex("""\starget=""")

    /**
     * The rendered cells of one column, in page order -- read from the BYTES
     * SERVED and not from the page value, because what a family is shown is
     * the claim these assertions are about.
     */
    fun renderedCells(
      body: String,
      column: String,
    ): List<String> =
      Regex("""data-label="${Regex.escape(column)}">([^<]*)<""")
        .findAll(body)
        .map { it.groupValues[1] }
        .toList()

    /** The served jurisdictions, read from the codebook the service itself reads. */
    fun publishedJurisdictions(): List<UsStateRegion> =
      runBlocking {
        WebAuthTestSupport.database.withConnection { session ->
          CodebooksDao.usStatesWithRegion(session).getOrThrow()
        }
      }.filter {
        it.jurisdictionKind == JurisdictionKind.STATE || it.jurisdictionKind == JurisdictionKind.FEDERAL_DISTRICT
      }

    /**
     * One served row for a jurisdiction, cited over plain http for exactly one of
     * them.
     *
     * THE CORPUS CITES BOTH SCHEMES, so a fixture whose every url is https
     * cannot see a page that drops an http-cited row -- which is what the real
     * corpus was doing to Tennessee while this suite was green.
     */
    fun servedRowsOf(uspsCode: String): List<FafsaDeadline> =
      if (uspsCode == HTTP_CITED_JURISDICTION) {
        listOf(deadlineFixture(sourceUrl = FIXTURE_HTTP_SOURCE_URL))
      } else {
        listOf(deadlineFixture())
      }

    /**
     * A resolved answer covering the WHOLE served country: every published
     * jurisdiction is either served or named as a gap, and the four the landed
     * corpus could not read are the gaps.
     */
    fun wholeCountry(): FafsaDeadlines.Resolved {
      val published = publishedJurisdictions()
      val unreadable =
        FafsaUnreadableAgencies.BY_JURISDICTION.keys
          .map { it.uspsCode }
          .toSet()
      return resolvedFixture(
        states =
          published
            .filterNot { it.uspsCode in unreadable }
            .map { stateFixture(it.uspsCode, it.name, it.region, servedRowsOf(it.uspsCode)) },
        missing =
          published
            .filter { it.uspsCode in unreadable }
            .map { missingFixture(it.uspsCode, it.name, it.region) },
      )
    }
  }
}
