package ed.unicoach.web

import ed.unicoach.coaching.costs.CollegeControl
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.ProgramReturnFigure
import ed.unicoach.coaching.costs.TuitionApplicable
import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.web.render.DATA_PUBLISHER
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SHAPE of the Family Cost Report (RFC 260, brief 0016 D3, D9): four rules
 * asserted over every table and every school section, with a shrinking set of
 * named exemptions, and one measurement that is printed and never asserted
 * (brief 0009 D20).
 *
 * Parsed with jsoup rather than scraped with a regex (D-C): the rules are about
 * NESTING -- a figure inside a cell inside a section -- and the repo's own
 * tests say a hand-rolled tag strip is not an HTML parser.
 *
 * Two renders, because the rules have to hold on the page a family sees AND on
 * the page that prints every block the page can print: the three-school verbose
 * fixture, and the same three schools with a published grid, a National Merit
 * row and a program figure added. The second is where the ratchet is checked --
 * an exemption that is no longer needed fails here, which is how each later
 * `glance` slice is made to remove its own entry.
 */
class CostReportShapeTest {
  /** The report's own tree, with the marker ASSERTED for [reportSectionOf]'s reason: a miss must not widen the rules to the chrome. */
  private fun reportOf(body: String): Element =
    requireNotNull(Jsoup.parse(body).selectFirst("section.report")) { "the report section must be on the page to be measured" }

  private fun threeSchools(): CollegeCostProfile = costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney())

  /**
   * The three verbose schools with EVERY unconverted block on the page: the
   * first school publishes a grid and a National Merit row, and the major
   * answer gives it one program with a shown figure. Composed from the shared
   * fixture helpers, so the blocks are exactly the ones the page tests render.
   */
  private fun everyBlock(): CollegeCostProfile {
    val id = CollegeId(UUID.randomUUID())
    val name = "Riverside State University"
    val riverside =
      costFixture(
        name = name,
        collegeId = id,
        control = CollegeControl.Public(TuitionApplicable.IN_STATE),
        tuitionInState = 12000,
        tuitionOutOfState = 34000,
        publishedPrice = 28000,
        netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 15000),
        housingAndFoodOnCampus = 11000,
        booksAndSupplies = 1200,
        otherExpensesOnCampus = 2500,
        medianDebt = 21000,
        offersOnCampusHousing = true,
        meritAid = meritFixture(name),
        borrowing = borrowingFixture(name),
        automaticAwards = listOf(scoreBands(gridAward(id))),
        nationalMerit = listOf(meritAnswer(meritAward(id))),
      )
    val campus = verboseCampus()
    val private = verbosePrivate()
    val program =
      programReturnOf(
        subjectFixture(
          "Biology",
          programsAt(
            riverside,
            programFixture(
              "2601",
              "Biology, General.",
              ProgramReturnFigure.Reported(26000, fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.DEBT)),
            ),
          ),
          programsAt(campus),
          programsAt(private),
        ),
      )
    return costProfile(listOf(riverside, campus, private), answeredMoney(), programReturn = program)
  }

  /** Both renders, named so a failure says which page broke the rule. */
  private fun renders(): Map<String, String> =
    mapOf("three schools" to renderedReportOf(threeSchools()), "every block" to renderedReportOf(everyBlock()))

  /** A whole-dollar figure as the page prints one; the sweep (a) runs on. */
  private val currency = Regex("""\$[0-9,]+""")

  /**
   * The `p` and `li` elements of [root] that rule (a) reads, after removing
   * every element whose class is in [exemptClasses], every cell, and the one
   * span of a school's own quoted words. A clone is pruned so the tree the
   * other rules read is untouched.
   */
  private fun proseElementsOf(
    root: Element,
    exemptClasses: Set<String>,
  ): List<Element> {
    val pruned = root.clone()
    (exemptClasses.map { ".$it" } + listOf("td", "th", ".report-merit-words")).forEach { pruned.select(it).remove() }
    return pruned.select("p, li")
  }

  @Test
  fun `rule (a) - no currency figure in a school's prose`() {
    renders().forEach { (name, body) ->
      val schools = reportOf(body).select("section.report-school")
      assertTrue(schools.isNotEmpty(), "[$name] the fixture must render a school, or this rule proves nothing")
      schools.forEach { school ->
        val offending =
          proseElementsOf(school, UNCONVERTED_BLOCKS + FEDERAL_DEBT_SENTENCE)
            .filter { currency.containsMatchIn(it.text()) }
            .map { it.outerHtml() }
        assertEquals(emptyList(), offending, "[$name] a figure is said in a sentence rather than in a row (brief 0016 D3)")
      }
    }
  }

  @Test
  fun `the ratchet is live - every unconverted block is on the page and would fail rule (a) unexempted`() {
    val report = reportOf(renderedReportOf(everyBlock()))

    UNCONVERTED_BLOCKS.forEach { blockClass ->
      val blocks = report.select(".$blockClass")
      assertTrue(blocks.isNotEmpty(), "[$blockClass] is on no element: the every-block fixture no longer prints it")
      val figuresInProse = blocks.flatMap { proseElementsOf(it, emptySet()) }.filter { currency.containsMatchIn(it.text()) }
      assertTrue(
        figuresInProse.isNotEmpty(),
        "[$blockClass] no longer says a figure in prose, so it no longer needs its exemption: remove it from UNCONVERTED_BLOCKS",
      )
    }
    // And the permanent one really is one figure in one sentence (D4).
    val debt = report.select(".$FEDERAL_DEBT_SENTENCE")
    assertTrue(debt.isNotEmpty(), "the every-block fixture must show a federal debt figure")
    debt.forEach { assertTrue(currency.containsMatchIn(it.text()), "the exempted debt sentence holds no figure: [${it.outerHtml()}]") }
  }

  @Test
  fun `rule (b) - every table opens with a caption and names its publisher`() {
    renders().forEach { (name, body) ->
      val tables = reportOf(body).select("table")
      assertTrue(tables.isNotEmpty(), "[$name] the fixture must render a table, or this rule proves nothing")
      tables.filterNot { table -> table.classNames().any { it in UNCAPTIONED_TABLES } }.forEach { table ->
        val first = table.children().firstOrNull()
        assertEquals("caption", first?.tagName(), "[$name] a table's first child is its caption: [${table.outerHtml()}]")
        assertTrue(requireNotNull(first).text().isNotBlank(), "[$name] a caption says something: [${table.outerHtml()}]")
        assertTrue(
          table.attr(DATA_PUBLISHER).isNotBlank(),
          "[$name] a table names its publisher in [$DATA_PUBLISHER]: [${table.outerHtml()}]",
        )
      }
    }
  }

  @Test
  fun `the ratchet is live - every uncaptioned table class is still on some table`() {
    renders().forEach { (name, body) ->
      val tables = reportOf(body).select("table")
      UNCAPTIONED_TABLES.forEach { tableClass ->
        assertTrue(
          tables.any { it.hasClass(tableClass) },
          "[$name] [$tableClass] is on no table, so it no longer needs its exemption: remove it from UNCAPTIONED_TABLES",
        )
      }
    }
  }

  /** The words a row header may not carry outside the price table: a row that reads as a sum of the others (RFC 175 D3). */
  private val totalWords = Regex("""\b(total|all|combined|sum)\b""")

  @Test
  fun `rule (c) - no total row outside the price table`() {
    renders().forEach { (name, body) ->
      reportOf(body).select("table").filterNot { it.hasClass(PRICE_TABLE) }.forEach { table ->
        assertTrue(table.select("tfoot").isEmpty(), "[$name] a table other than the price table has a tfoot: [${table.outerHtml()}]")
        val totals = table.select("th[scope=row]").filter { totalWords.containsMatchIn(it.text().lowercase()) }
        assertEquals(emptyList(), totals.map { it.text() }, "[$name] a row header reads as a total: [${table.outerHtml()}]")
      }
    }
  }

  @Test
  fun `rule (d) - one publisher vocabulary per table`() {
    renders().forEach { (name, body) ->
      reportOf(body).select("table").forEach { table ->
        val text = table.text()
        assertFalse(
          text.contains("College Scorecard") && text.contains("Common Data Set"),
          "[$name] a table speaks two publishers' vocabularies: [${table.outerHtml()}]",
        )
      }
    }
  }

  /**
   * Measured, never asserted (brief 0009 D20): the visible words, the words in
   * tables, the prose share and each school's words, one line per render.
   *
   * Read them out of the captured standard output of this test:
   *   grep -h MEASURE public-web/build/test-results/test/TEST-ed.unicoach.web.CostReportShapeTest.xml
   */
  @Test
  fun `the page's words are measured and printed, never asserted`() {
    renders().forEach { (name, body) ->
      val report = reportOf(body)
      val visible = wordsOf(visibleProseOf(body))
      val inTables = report.select("table").sumOf { wordsOf(proseOf(it.outerHtml())) }
      val proseShare = if (visible == 0) 0.0 else (visible - inTables) * PERCENT / visible
      val perSchool =
        report.select("section.report-school").map { school ->
          "${school.selectFirst("h2")?.text()}=${wordsOf(proseOf(school.outerHtml()))}"
        }
      println(
        "[MEASURE] cost report ($name): [$visible] visible words, [$inTables] in tables, " +
          "prose share [${"%.1f".format(Locale.US, proseShare)}%], per school $perSchool",
      )
    }
  }

  /** Whitespace-separated tokens, the way RFC 188 counted them. */
  private fun wordsOf(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

  companion object {
    /** Blocks not yet converted (brief 0016 D9). Each later slice removes its own entry; the slice that empties this set deletes the mechanism. */
    val UNCONVERTED_BLOCKS = setOf("report-award-grid", "report-national-merit", "report-program-return")

    /** The one permanent exemption: the federal median debt is one figure, one sentence (brief 0016 D4). */
    const val FEDERAL_DEBT_SENTENCE = "report-debt-federal"

    /** The one table allowed a total row: its columns are ways of living, and a total belongs to exactly one of them. */
    const val PRICE_TABLE = "report-price-table"

    /**
     * Tables that exist today without a caption; `glance/02` captions them and empties this set. Built from
     * [PRICE_TABLE] rather than retyping it, so the caption exemption and the total-row rule name one table.
     */
    val UNCAPTIONED_TABLES = setOf("report-summary-table", PRICE_TABLE)

    private const val PERCENT = 100.0
  }
}
