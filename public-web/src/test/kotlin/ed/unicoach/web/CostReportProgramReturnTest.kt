package ed.unicoach.web

import ed.unicoach.coaching.costs.CollegeCost
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.ProgramFigureProvenance
import ed.unicoach.coaching.costs.ProgramReturn
import ed.unicoach.coaching.costs.ProgramReturnCollege
import ed.unicoach.coaching.costs.ProgramReturnCopy
import ed.unicoach.coaching.costs.ProgramReturnFigure
import ed.unicoach.coaching.costs.ProgramReturnProgram
import ed.unicoach.coaching.costs.ProgramReturnSubject
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.coaching.costs.canonical.FilingLink
import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.college.ScorecardFieldOfStudyColumns.FieldOfStudyVariable
import ed.unicoach.college.figureComparisonWordsIn
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.CipCode
import ed.unicoach.db.models.ProgramMoneyMeasure
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The figures by field of study woven into the Family Cost Report (RFC 251, as
 * rows since glance/04), asserted on the rendered page: every subject and every
 * program printed, in a neutral order, each figure in its own cell under a header
 * naming its publisher, each gap holding its own sentence, each cohort said once,
 * and nothing at all when the major answer is not on the profile.
 *
 * Parsed with jsoup for [CostReportShapeTest]'s reason: which column a figure
 * sits in is NESTING, which a regex cannot read.
 */
class CostReportProgramReturnTest {
  private val lakeshore =
    costFixture(
      name = "Lakeshore State University",
      medianDebt = 21000,
      meritAid = meritFixture("Lakeshore State University", url = "https://lakeshore.example.test/cds.pdf"),
      borrowing = borrowingFixture("Lakeshore State University", url = "https://lakeshore.example.test/cds-h.pdf"),
    )
  private val eastbrook = costFixture(name = "Eastbrook University", medianDebt = 24000)
  private val wynnford = costFixture(name = "Wynnford College", medianDebt = 27000)

  private val earnings = ProgramReturnFigure.Reported(41200, fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.EARNINGS))
  private val debt = ProgramReturnFigure.Reported(26000, fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.DEBT))
  private val payment = ProgramReturnFigure.Reported(291, fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.DEBT_MONTHLY_PAYMENT))

  private val earningsHeader = "Median earnings a year, four years after finishing, according to the U.S. Treasury"
  private val debtHeader = "Median federal loan debt at finishing, according to the federal student loan system"
  private val paymentHeader = "Median monthly payment on a standard 10-year plan, by the federal student loan system's own estimate"

  private val withheldByBoth = "the U.S. Treasury and the federal student loan system to protect students' privacy."

  /** Each `report-debt` section, in page order -- one per school. */
  private fun debtSectionsOf(body: String): List<Element> = Jsoup.parse(body).select("section.report-debt").toList()

  private fun tablesOf(section: Element): List<Element> = section.select("table.report-program-table").toList()

  private fun captionsOf(section: Element): List<String> = tablesOf(section).map { it.select("caption").text() }

  private fun rowTitlesOf(table: Element): List<String> = table.select("tbody th[scope=row]").map { it.text() }

  private fun rowOf(
    table: Element,
    title: String,
  ): Element = table.select("tbody tr").single { it.selectFirst("th[scope=row]")?.text() == title }

  /** The text of [row]'s one cell under [header], found by the label the page writes -- a figure in the wrong column does not answer. */
  private fun cellUnder(
    row: Element,
    header: String,
  ): String = row.select("td").single { it.attr("data-label") == header }.text()

  private fun textOf(
    section: Element,
    selector: String,
  ): List<String> = section.select(selector).map { it.text() }

  /** The one school's section of a one-school page carrying [programs] under Biology. */
  private fun sectionWith(vararg programs: ProgramReturnProgram): Element {
    val answer = programReturnOf(subjectFixture("Biology", programsAt(lakeshore, *programs)))
    return debtSectionsOf(renderedReportOf(costProfile(listOf(lakeshore), programReturn = answer))).single()
  }

  /** The one table of a one-school page carrying [programs] under Biology. */
  private fun tableWith(vararg programs: ProgramReturnProgram): Element = tablesOf(sectionWith(*programs)).single()

  private val biologyGeneral = programFixture("2601", "Biology, General.", earnings, debt, payment)

  /** Three schools and two subjects carrying every figure arm: shown figures, each gap mark, an all-withheld school and one with no program. */
  private fun everyFigureArm(): CollegeCostProfile {
    val noFigures = ProgramReturnProgram.WithFigures.ofHeldFigures(CipCode("2605"), "Biochemistry.", emptyMap())
    val gaps =
      programFixture(
        "2613",
        "Ecology, Evolution, Systematics.",
        ProgramReturnFigure.Suppressed(fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.EARNINGS)),
        ProgramReturnFigure.NotReported(fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.DEBT)),
      )
    val answer =
      programReturnOf(
        subjectFixture(
          "Biology",
          programsAt(lakeshore, programFixture("2601", "Biology, General.", earnings, debt, payment), gaps, noFigures),
          programsAt(eastbrook),
          programsAt(wynnford, allWithheldProgram("2601", "Biology, General."), allWithheldProgram("2607", "Zoology.")),
        ),
        subjectFixture(
          "Chemistry",
          programsAt(lakeshore, programFixture("4005", "Chemistry.", earnings)),
          programsAt(eastbrook),
          programsAt(wynnford),
        ),
      )
    return costProfile(listOf(lakeshore, eastbrook, wynnford), programReturn = answer)
  }

  @Test
  fun `with no major answer on the profile the page says nothing about it`() {
    val report = reportSectionOf(renderedReportOf(costProfile(listOf(lakeshore, eastbrook, wynnford))))

    assertFalse(report.contains("report-program"), "no woven element without the answer: [$report]")
    assertFalse(proseOf(report).contains(ProgramReturnCopy.COVERAGE_SENTENCE), "no coverage sentence")
    assertFalse(proseOf(report).contains(ProgramReturnCopy.SOURCES.lead.trim()), "no field-of-study sources sentence")
    assertFalse(report.contains(ScorecardFieldOfStudyColumns.DATA_PAGE.toString()), "and no link to the Scorecard")
  }

  @Test
  fun `an answer with no matching program anywhere prints no coverage, sources or Scorecard link`() {
    val answer = programReturnOf(subjectFixture("Biology", programsAt(lakeshore), programsAt(eastbrook), programsAt(wynnford)))
    val report = reportSectionOf(renderedReportOf(costProfile(listOf(lakeshore, eastbrook, wynnford), programReturn = answer)))
    val prose = proseOf(report)

    assertTrue(prose.contains(ProgramReturnCopy.NO_PROGRAM_STATEMENT), "the answer must reach the page: [$prose]")
    assertFalse(prose.contains(ProgramReturnCopy.COVERAGE_SENTENCE), "no coverage sentence without a program")
    assertFalse(prose.contains(ProgramReturnCopy.SOURCES.lead.trim()), "no field-of-study sources sentence")
    assertFalse(report.contains(ScorecardFieldOfStudyColumns.DATA_PAGE.toString()), "and no link to the Scorecard")
  }

  @Test
  fun `one table per school and subject, its caption naming the subject and the school and why they are here`() {
    val sections = debtSectionsOf(renderedReportOf(everyFigureArm()))

    assertEquals(
      listOf(
        "Biology programs at Lakeshore State University, which your student wants to study",
        "Chemistry programs at Lakeshore State University, which your student wants to study",
      ),
      captionsOf(sections[0]),
      "one table per subject, in the stored order",
    )
    assertEquals(emptyList(), captionsOf(sections[1]), "a school with no matching program prints no table")
    assertEquals(emptyList(), captionsOf(sections[2]), "nor one whose every figure is withheld")
  }

  @Test
  fun `each school that runs a subject has its own table of it`() {
    val answer =
      programReturnOf(
        subjectFixture("Biology", programsAt(lakeshore, biologyGeneral), programsAt(eastbrook, biologyGeneral), programsAt(wynnford)),
      )
    val sections = debtSectionsOf(renderedReportOf(costProfile(listOf(lakeshore, eastbrook, wynnford), programReturn = answer)))

    assertEquals(
      listOf(
        listOf("Biology programs at Lakeshore State University, which your student wants to study"),
        listOf("Biology programs at Eastbrook University, which your student wants to study"),
        emptyList(),
      ),
      sections.map { captionsOf(it) },
    )
  }

  @Test
  fun `the columns are the program and the three measures, each money header naming its publisher`() {
    tablesOf(debtSectionsOf(renderedReportOf(everyFigureArm())).first()).forEach { table ->
      assertEquals(
        listOf(ProgramReturnCopy.PROGRAM_COLUMN_HEADER, earningsHeader, debtHeader, paymentHeader),
        table.select("thead th[scope=col]").map { it.text() },
      )
      assertTrue(table.select("tfoot").isEmpty(), "no total: [${table.outerHtml()}]")
    }
  }

  @Test
  fun `every program is a row by its published title in the answer's own order`() {
    val tables = tablesOf(debtSectionsOf(renderedReportOf(everyFigureArm())).first())

    assertEquals(
      listOf(listOf("Biology, General", "Ecology, Evolution, Systematics", "Biochemistry"), listOf("Chemistry")),
      tables.map { rowTitlesOf(it) },
      "every program, by its title with the publisher's trailing period dropped, none promoted or dropped",
    )
  }

  @Test
  fun `each shown figure is alone in the cell under its own publisher's header`() {
    val table = tablesOf(debtSectionsOf(renderedReportOf(everyFigureArm())).first()).first()
    val row = rowOf(table, "Biology, General")

    assertEquals("\$41,200", cellUnder(row, earningsHeader))
    assertEquals("\$26,000", cellUnder(row, debtHeader))
    assertEquals("\$291", cellUnder(row, paymentHeader))
  }

  @Test
  fun `every value cell is labelled with exactly its column's header`() {
    val tables = tablesOf(debtSectionsOf(renderedReportOf(everyFigureArm())).first())
    var checked = 0

    tables.forEach { table ->
      val headers = table.select("thead th[scope=col]").map { it.text() }.drop(1)
      table.select("tbody tr").filter { it.select("td[colspan]").isEmpty() }.forEach { row ->
        assertEquals(headers, row.select("td").map { it.attr("data-label") }, "[${row.outerHtml()}]")
        checked += row.select("td").size
      }
    }
    assertEquals(9, checked, "two full rows in Biology and one in Chemistry, three cells each, or this proves nothing")
  }

  @Test
  fun `each gap holds its own sentence in its own column`() {
    val table = tablesOf(debtSectionsOf(renderedReportOf(everyFigureArm())).first()).first()
    val row = rowOf(table, "Ecology, Evolution, Systematics")

    assertEquals("Withheld by the U.S. Treasury to protect students' privacy.", cellUnder(row, earningsHeader))
    assertEquals("This school did not report this figure.", cellUnder(row, debtHeader))
    assertEquals(ProgramReturnCopy.NO_FIGURE_STATEMENT, cellUnder(row, paymentHeader))
  }

  @Test
  fun `a figure the source says cannot apply and one we have not collected each hold their frozen sentence`() {
    val table =
      tableWith(
        programFixture(
          "2613",
          "Ecology.",
          ProgramReturnFigure.NotApplicable(fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.EARNINGS)),
          ProgramReturnFigure.NotCollectedByUs(fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.DEBT)),
          ProgramReturnFigure.Suppressed(fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.DEBT_MONTHLY_PAYMENT)),
        ),
      )
    val row = rowOf(table, "Ecology")

    assertEquals("This figure does not apply at this school, and the source says so.", cellUnder(row, earningsHeader))
    assertEquals("We have not collected this figure yet.", cellUnder(row, debtHeader))
    assertEquals("Withheld by the federal student loan system to protect students' privacy.", cellUnder(row, paymentHeader))
  }

  @Test
  fun `a program with no published figures is one spanning row in the table`() {
    val table = tablesOf(debtSectionsOf(renderedReportOf(everyFigureArm())).first()).first()
    val cells = rowOf(table, "Biochemistry").select("td")

    assertEquals(1, cells.size, "one cell across the measures: [$cells]")
    assertEquals("3", cells.single().attr("colspan"))
    assertEquals(ProgramReturnCopy.NO_PUBLISHED_FIGURES_STATEMENT, cells.single().text())
  }

  @Test
  fun `a program with every figure withheld beside one with figures is one spanning row`() {
    val table = tableWith(biologyGeneral, allWithheldProgram("2607", "Zoology."))
    val cells = rowOf(table, "Zoology").select("td")

    assertEquals("3", cells.single().attr("colspan"))
    assertEquals("All of this program's figures are withheld by $withheldByBoth", cells.single().text())
  }

  private val treasuryNote = "* The U.S. Treasury estimated this figure; the school did not report it."
  private val loanSystemNote = "* The federal student loan system estimated this figure; the school did not report it."

  private val imputedEarnings = ProgramReturnFigure.ImputedByPublisher(41200, fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.EARNINGS))

  /** Each note under a table, as read, in order. */
  private fun imputedNotesOf(section: Element): List<String> = textOf(section, "p.report-program-imputed")

  @Test
  fun `an estimated figure keeps its amount and a marker in the cell, and its sentence once beneath the table`() {
    val section = sectionWith(programFixture("2601", "Biology, General.", imputedEarnings, debt))

    assertEquals("\$41,200*", cellUnder(rowOf(tablesOf(section).single(), "Biology, General"), earningsHeader))
    assertEquals(
      "\$26,000",
      cellUnder(rowOf(tablesOf(section).single(), "Biology, General"), debtHeader),
      "a reported figure takes no marker",
    )
    assertEquals(listOf(treasuryNote), imputedNotesOf(section))
    assertEquals("table", section.selectFirst("p.report-program-imputed")?.previousElementSibling()?.tagName(), "beneath its table")
  }

  @Test
  fun `a second estimate from the same publisher adds no second note`() {
    val section =
      sectionWith(
        programFixture("2601", "Biology, General.", imputedEarnings),
        programFixture("2613", "Ecology.", imputedEarnings),
      )

    assertEquals(listOf(treasuryNote), imputedNotesOf(section))
  }

  @Test
  fun `estimates from two publishers give one note each`() {
    val imputedDebt = ProgramReturnFigure.ImputedByPublisher(26000, fieldOfStudyProvenance(ScorecardFieldOfStudyColumns.DEBT))
    val section = sectionWith(programFixture("2601", "Biology, General.", imputedEarnings, imputedDebt))

    assertEquals(listOf(treasuryNote, loanSystemNote), imputedNotesOf(section))
  }

  @Test
  fun `no estimate on the table is no note`() {
    assertEquals(emptyList(), imputedNotesOf(sectionWith(biologyGeneral)))
  }

  private val biologyLead = ProgramReturnCopy.subjectLeadOf("Biology")

  @Test
  fun `a school with no matching program says so once per subject, in a paragraph and no table`() {
    val eastbrookSection = debtSectionsOf(renderedReportOf(everyFigureArm()))[1]

    assertEquals(
      listOf("Biology", "Chemistry").map { "${ProgramReturnCopy.subjectLeadOf(it)} ${ProgramReturnCopy.NO_PROGRAM_STATEMENT}" },
      textOf(eastbrookSection, "p.report-program-subject"),
    )
    assertTrue(eastbrookSection.select(".report-program-return table").isEmpty(), "[$eastbrookSection]")
  }

  @Test
  fun `several programs all withheld are one sentence for the subject and no table`() {
    val wynnford = debtSectionsOf(renderedReportOf(everyFigureArm()))[2]

    assertEquals(
      "$biologyLead Every matching program's figures are withheld by $withheldByBoth",
      textOf(wynnford, "p.report-program-subject").first(),
    )
    assertEquals(1, countOf(wynnford.text(), "withheld"), "one mark for the subject, not one per program and measure")
    assertFalse(wynnford.text().contains("Zoology"), "and no program is laid out under it")
    assertTrue(wynnford.select(".report-program-return table").isEmpty(), "[$wynnford]")
  }

  @Test
  fun `a lone program with every figure withheld is its own paragraph and no table`() {
    val section = sectionWith(allWithheldProgram("2601", "Biology, General."))

    assertEquals(listOf(biologyLead), textOf(section, "p.report-program-subject"))
    assertEquals(
      listOf("Biology, General: All of this program's figures are withheld by $withheldByBoth"),
      textOf(section, "p.report-program"),
    )
    assertTrue(section.select(".report-program-return table").isEmpty(), "[$section]")
  }

  @Test
  fun `only programs with no published figures are paragraphs and no table`() {
    val section =
      sectionWith(
        ProgramReturnProgram.WithFigures.ofHeldFigures(CipCode("2605"), "Biochemistry.", emptyMap()),
        ProgramReturnProgram.WithFigures.ofHeldFigures(CipCode("2607"), "Zoology.", emptyMap()),
      )

    assertEquals(listOf(biologyLead), textOf(section, "p.report-program-subject"))
    assertEquals(
      listOf("Biochemistry", "Zoology").map { "$it: ${ProgramReturnCopy.NO_PUBLISHED_FIGURES_STATEMENT}" },
      textOf(section, "p.report-program"),
    )
    assertTrue(section.select(".report-program-return table").isEmpty(), "[$section]")
  }

  private val earningsYears = "${earnings.provenance.cohortFirstYear.label} and ${earnings.provenance.cohortLastYear.label}"
  private val debtYears = "${debt.provenance.cohortFirstYear.label} and ${debt.provenance.cohortLastYear.label}"
  private val dollarYear = assertNotNull(earnings.provenance.amountUsdYear).year

  private val allCohorts =
    "Earnings by field of study describe graduates working four years after finishing (students who finished in " +
      "$earningsYears, in $dollarYear dollars). Federal loan debt and monthly payment by field of study describe " +
      "graduates who borrowed federal loans (students who finished in $debtYears)."

  private fun cohortParagraphsOf(body: String): List<String> =
    textOf(
      Jsoup.parse(body).selectFirst("section.report-kinds") ?: error("the kinds block must be on the page"),
      "p.report-program-cohorts",
    )

  @Test
  fun `the cohorts and the dollar year are said once on a three-school page, and only in the kinds block`() {
    val answer =
      programReturnOf(
        subjectFixture(
          "Biology",
          programsAt(lakeshore, biologyGeneral),
          programsAt(eastbrook, biologyGeneral),
          programsAt(wynnford, biologyGeneral),
        ),
      )
    val body = renderedReportOf(costProfile(listOf(lakeshore, eastbrook, wynnford), programReturn = answer))

    assertEquals(listOf(allCohorts), cohortParagraphsOf(body))
    assertEquals(1, countOf(proseOf(reportSectionOf(body)), "$dollarYear dollars"), "the cohort sentence is the only one that says it")
    listOf("rank", "score", "rating", "award").forEach { word ->
      assertFalse(allCohorts.lowercase().contains(word), "[$word] in the kinds block")
    }
  }

  @Test
  fun `a page with no figure that has a number names no cohort`() {
    val answer =
      programReturnOf(
        subjectFixture(
          "Biology",
          programsAt(lakeshore, allWithheldProgram("2601", "Biology, General."), allWithheldProgram("2607", "Zoology.")),
        ),
      )
    val body = renderedReportOf(costProfile(listOf(lakeshore), programReturn = answer))

    assertEquals(emptyList(), cohortParagraphsOf(body))
    assertTrue(proseOf(reportSectionOf(body)).contains(ProgramReturnCopy.COVERAGE_SENTENCE), "the fixture must print a program")
  }

  @Test
  fun `an earnings-only page has only the earnings clause`() {
    val answer = programReturnOf(subjectFixture("Biology", programsAt(lakeshore, programFixture("2601", "Biology, General.", earnings))))
    val body = renderedReportOf(costProfile(listOf(lakeshore), programReturn = answer))

    assertEquals(
      listOf(
        "Earnings by field of study describe graduates working four years after finishing (students who finished in " +
          "$earningsYears, in $dollarYear dollars).",
      ),
      cohortParagraphsOf(body),
    )
  }

  @Test
  fun `the coverage sentence is said once on a three-school page`() {
    val prose = proseOf(reportSectionOf(renderedReportOf(everyFigureArm())))

    assertEquals(1, countOf(prose, ProgramReturnCopy.COVERAGE_SENTENCE))
  }

  private val adminRecord = AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD, FilingLink.LINKED)

  /** Each kind paragraph of the page's kinds block: its scope label and its sentence, as read. */
  private fun kindParagraphsOf(body: String): List<String> =
    Jsoup.parse(body).select("section.report-kinds p[class=report-kind]").map { it.text() }

  /**
   * The short coverage sentence leaves "only students who got federal aid" to
   * the administrative-record kind sentence. A page whose every program figure
   * is withheld, at a school with no school-wide debt figure, has no figure
   * with a number for that sentence to cover -- so the withheld figures are
   * what it covers, and the label says which.
   */
  @Test
  fun `a page with every program figure withheld and no school-wide debt says the tier sentence once, over the field-of-study figures`() {
    val noDebt = costFixture(name = "Northfield College", medianDebt = null)
    val answer =
      programReturnOf(
        subjectFixture(
          "Biology",
          programsAt(noDebt, allWithheldProgram("2601", "Biology, General."), allWithheldProgram("2607", "Zoology.")),
        ),
      )
    val body = renderedReportOf(costProfile(listOf(noDebt), programReturn = answer))
    val prose = proseOf(reportSectionOf(body))

    assertEquals(1, countOf(prose, ProgramReturnCopy.COVERAGE_SENTENCE), "the coverage is said: [$prose]")
    assertEquals(1, countOf(prose, adminRecord), "the federal-aid tier sentence is said once: [$prose]")
    assertTrue(
      kindParagraphsOf(body).contains(
        "The median earnings by field of study, the median federal loan debt by field of study and the monthly loan " +
          "payment by field of study: $adminRecord",
      ),
      "the one tier sentence names the field-of-study figures it covers: [${kindParagraphsOf(body)}]",
    )
  }

  @Test
  fun `a page with every program figure withheld beside a school-wide debt figure says the tier sentence once`() {
    val answer = programReturnOf(subjectFixture("Biology", programsAt(eastbrook, allWithheldProgram("2601", "Biology, General."))))
    val body = renderedReportOf(costProfile(listOf(eastbrook), programReturn = answer))
    val prose = proseOf(reportSectionOf(body))

    assertTrue(prose.contains("\$24,000"), "the fixture must show the school-wide debt figure, or this proves nothing: [$prose]")
    assertEquals(1, countOf(prose, ProgramReturnCopy.COVERAGE_SENTENCE), "the coverage is said: [$prose]")
    assertEquals(1, countOf(prose, adminRecord), "one tier sentence for the school's debt and the program figures: [$prose]")
  }

  @Test
  fun `every woven paragraph sits before the school's borrowing table`() {
    val section = debtSectionsOf(renderedReportOf(everyFigureArm())).first().outerHtml()
    val borrowing = section.indexOf("<div class=\"report-borrowing\">")

    assertTrue(borrowing > 0, "the fixture must carry a borrowing table, or this proves nothing: [$section]")
    assertTrue(
      section.lastIndexOf("report-program") in 0 until borrowing,
      "a woven figure under the borrowing caption reads as the school's own: [$section]",
    )
  }

  @Test
  fun `the figures by field of study are covered by the one administrative-record sentence`() {
    val body = renderedReportOf(everyFigureArm())
    val kinds = body.substringAfter("<section class=\"report-kinds\">").substringBefore("</section>")

    assertEquals(1, countOf(proseOf(kinds), adminRecord), "one kind sentence for every federal-record figure: [$kinds]")
    val scope = proseOf(kinds.substringAfter("report-kind-scope\">").substringBefore("</span>"))
    assertEquals(
      "The median federal loan debt, the median earnings by field of study, the median federal loan debt by field of " +
        "study and the monthly loan payment by field of study:",
      scope,
      "and its scope names every figure it covers",
    )
  }

  @Test
  fun `no figure comparison word appears anywhere on a page carrying every arm`() {
    val prose = visibleProseOf(renderedReportOf(everyFigureArm()))

    listOf(
      "Biology programs at Lakeshore State University, which your student wants to study",
      earningsHeader,
      debtHeader,
      paymentHeader,
    ).forEach { assertTrue(prose.contains(it), "the sweep must read captions and headers too, or it proves nothing: [$it] in [$prose]") }
    assertEquals(emptyList(), figureComparisonWordsIn(prose), "the page narrates a relation between figures: [$prose]")
  }

  @Test
  fun `the program tables never say award`() {
    val tables = tablesOf(debtSectionsOf(renderedReportOf(everyFigureArm())).first())

    assertTrue(tables.isNotEmpty(), "the fixture must print a table, or this proves nothing")
    tables.forEach { assertFalse(it.text().lowercase().contains("award"), "[${it.text()}]") }
  }

  /**
   * The page's anchors, read off the rendered page: each school's own filing,
   * and the one link to the College Scorecard, every one with
   * `rel="noreferrer"` -- and nothing else.
   */
  @Test
  fun `the page links each filing and the College Scorecard once, and nothing else`() {
    val report = reportSectionOf(renderedReportOf(everyFigureArm()))
    val anchors = Regex("<a ([^>]*)>").findAll(report).map { it.groupValues[1] }.toList()
    val targets = anchors.map { Regex("href=\"([^\"]*)\"").find(it)?.groupValues?.get(1) }

    assertEquals(
      listOf(
        "https://lakeshore.example.test/cds.pdf",
        "https://lakeshore.example.test/cds-h.pdf",
        ScorecardFieldOfStudyColumns.DATA_PAGE.toString(),
      ),
      targets,
      "every anchor on the report, in page order: [$anchors]",
    )
    assertTrue(anchors.all { it.contains("rel=\"noreferrer\"") }, "every anchor carries rel=noreferrer: [$anchors]")
    val sources = report.substringAfter("<section class=\"report-sources\">")
    val sentence = ProgramReturnCopy.SOURCES
    assertTrue(
      sources.contains("${sentence.lead}<a href=\"${sentence.link}\" rel=\"noreferrer\">${sentence.linkText}</a>${sentence.tail}"),
      "the link sits inside its sources sentence: [$sources]",
    )
  }

  @Test
  fun `every measure has a column and a name in the copy`() {
    val headers = ScorecardFieldOfStudyColumns.VARIABLES.map { it.measure }
    assertEquals(ProgramMoneyMeasure.entries, headers, "every measure is a column, in the registry's order")
    ProgramMoneyMeasure.entries.forEach { measure ->
      val label = ProgramReturnCopy.spokenLabelOf(measure)
      assertTrue(label.endsWith("by field of study"), "[${measure.value}] label is not scoped to the field: [$label]")
    }
  }
}
