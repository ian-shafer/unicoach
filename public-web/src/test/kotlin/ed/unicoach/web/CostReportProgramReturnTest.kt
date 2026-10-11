package ed.unicoach.web

import ed.unicoach.coaching.costs.BorrowingWire
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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The figures by field of study woven into the Family Cost Report (RFC 251),
 * asserted on the rendered page: every subject and every program printed, in
 * a neutral order, each figure with its own publisher and cohort, each gap with
 * its own mark, and nothing at all when the major answer is not on the profile.
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

  private fun provenance(variable: FieldOfStudyVariable): ProgramFigureProvenance =
    requireNotNull(ProgramFigureProvenance.ofAgreeingCohort(variable, variable.cohortFirstYear, variable.cohortLastYear))

  private val earnings = ProgramReturnFigure.Reported(41200, provenance(ScorecardFieldOfStudyColumns.EARNINGS))
  private val debt = ProgramReturnFigure.Reported(26000, provenance(ScorecardFieldOfStudyColumns.DEBT))
  private val payment = ProgramReturnFigure.Reported(291, provenance(ScorecardFieldOfStudyColumns.DEBT_MONTHLY_PAYMENT))

  private fun program(
    cip: String,
    title: String,
    vararg held: ProgramReturnFigure.Held,
  ): ProgramReturnProgram = ProgramReturnProgram.WithFigures.ofHeldFigures(CipCode(cip), title, held.associateBy { it.measure })

  private fun allWithheld(
    cip: String,
    title: String,
  ): ProgramReturnProgram =
    program(
      cip,
      title,
      *ScorecardFieldOfStudyColumns.VARIABLES.map { ProgramReturnFigure.Suppressed(provenance(it)) }.toTypedArray(),
    )

  private fun at(
    college: CollegeCost,
    vararg programs: ProgramReturnProgram,
  ): ProgramReturnCollege =
    if (programs.isEmpty()) {
      ProgramReturnCollege.NoProgram(college.collegeId, college.name)
    } else {
      ProgramReturnCollege.WithPrograms(college.collegeId, college.name, programs.first(), programs.drop(1))
    }

  private fun subject(
    name: String,
    vararg colleges: ProgramReturnCollege,
  ) = ProgramReturnSubject(name.lowercase(), name, colleges.first(), colleges.drop(1))

  private fun answerOf(vararg subjects: ProgramReturnSubject) = ProgramReturn(subjects.first(), subjects.drop(1))

  private fun render(profile: CollegeCostProfile): String {
    lateinit var body: String
    testApplication {
      application { testPublicWebModule(costReportSource = FakeCostReportSource(FakeReportAnswer.Live(profile))) }
      body = client.get("/report?token=$TEST_LIVE_TOKEN").bodyAsText()
    }
    return body
  }

  /** Each `report-debt` section, in page order -- one per school. */
  private fun debtSectionsOf(body: String): List<String> =
    body.split("<section class=\"report-debt\">").drop(1).map { it.substringBefore("</section>") }

  /** Each woven paragraph's text inside [html], in order. */
  private fun paragraphsOf(
    html: String,
    cssClass: String,
  ): List<String> = html.split("<p class=\"$cssClass\">").drop(1).map { proseOf(it.substringBefore("</p>")).trim() }

  /** Three schools and two subjects carrying every figure arm: shown figures, each gap mark, an all-withheld school and one with no program. */
  private fun everyFigureArm(): CollegeCostProfile {
    val noFigures = ProgramReturnProgram.WithFigures.ofHeldFigures(CipCode("2605"), "Biochemistry.", emptyMap())
    val gaps =
      program(
        "2613",
        "Ecology, Evolution, Systematics.",
        ProgramReturnFigure.Suppressed(provenance(ScorecardFieldOfStudyColumns.EARNINGS)),
        ProgramReturnFigure.NotReported(provenance(ScorecardFieldOfStudyColumns.DEBT)),
      )
    val answer =
      answerOf(
        subject(
          "Biology",
          at(lakeshore, program("2601", "Biology, General.", earnings, debt, payment), gaps, noFigures),
          at(eastbrook),
          at(wynnford, allWithheld("2601", "Biology, General."), allWithheld("2607", "Zoology.")),
        ),
        subject("Chemistry", at(lakeshore, program("4005", "Chemistry.", earnings)), at(eastbrook), at(wynnford)),
      )
    return costProfile(listOf(lakeshore, eastbrook, wynnford), programReturn = answer)
  }

  @Test
  fun `with no major answer on the profile the page says nothing about it`() {
    val report = reportSectionOf(render(costProfile(listOf(lakeshore, eastbrook, wynnford))))

    assertFalse(report.contains("report-program"), "no woven element without the answer: [$report]")
    assertFalse(proseOf(report).contains(ProgramReturnCopy.COVERAGE_SENTENCE), "no coverage sentence")
    assertFalse(proseOf(report).contains(ProgramReturnCopy.SOURCES.lead.trim()), "no field-of-study sources sentence")
    assertFalse(report.contains(ScorecardFieldOfStudyColumns.DATA_PAGE.toString()), "and no link to the Scorecard")
  }

  @Test
  fun `an answer with no matching program anywhere prints no coverage, sources or Scorecard link`() {
    val answer = answerOf(subject("Biology", at(lakeshore), at(eastbrook), at(wynnford)))
    val report = reportSectionOf(render(costProfile(listOf(lakeshore, eastbrook, wynnford), programReturn = answer)))
    val prose = proseOf(report)

    assertTrue(prose.contains(ProgramReturnCopy.NO_PROGRAM_STATEMENT), "the answer must reach the page: [$prose]")
    assertFalse(prose.contains(ProgramReturnCopy.COVERAGE_SENTENCE), "no coverage sentence without a program")
    assertFalse(prose.contains(ProgramReturnCopy.SOURCES.lead.trim()), "no field-of-study sources sentence")
    assertFalse(report.contains(ScorecardFieldOfStudyColumns.DATA_PAGE.toString()), "and no link to the Scorecard")
  }

  @Test
  fun `every subject appears in stored order, and every program by its published title in the answer's own order`() {
    val section = debtSectionsOf(render(everyFigureArm())).first()
    val prose = proseOf(section)

    val biology = prose.indexOf(ProgramReturnCopy.subjectLeadOf("Biology"))
    val chemistry = prose.indexOf(ProgramReturnCopy.subjectLeadOf("Chemistry"))
    assertTrue(biology in 0 until chemistry, "both subjects, in the stored order: [$prose]")
    assertEquals(
      listOf("Biology, General:", "Ecology, Evolution, Systematics:", "Biochemistry:", "Chemistry:"),
      paragraphsOf(section, "report-program").map { it.substringBefore(':') + ":" },
      "every program, by its title with the publisher's trailing period dropped, none promoted or dropped",
    )
  }

  @Test
  fun `each shown figure carries its own publisher and cohort in its own sentence`() {
    val section = debtSectionsOf(render(everyFigureArm())).first()
    val biologyGeneral = paragraphsOf(section, "report-program").first()

    listOf(earnings, debt, payment).forEach { figure ->
      val sentence = ProgramReturnCopy.sentenceOf(figure)
      assertTrue(biologyGeneral.contains(sentence), "[${figure.measure.value}] is said in full: [$biologyGeneral]")
    }
    assertTrue(
      biologyGeneral.contains(
        "\$41,200 a year, according to the U.S. Treasury (students who finished in 2017-18 and 2018-19, in 2024 dollars)",
      ),
      "earnings sentence missing: [$biologyGeneral]",
    )
    assertTrue(
      biologyGeneral.contains("\$26,000, according to the federal student loan system (students who finished in 2018-19 and 2019-20)"),
      "debt sentence missing: [$biologyGeneral]",
    )
    assertTrue(
      biologyGeneral.contains("\$291, by the federal student loan system's own estimate (students who finished in 2018-19 and 2019-20)"),
      "payment sentence missing: [$biologyGeneral]",
    )
  }

  @Test
  fun `each gap renders its own mark, and a subject with every figure withheld renders one`() {
    val sections = debtSectionsOf(render(everyFigureArm()))
    val lakeshoreProse = proseOf(sections[0])

    assertTrue(
      lakeshoreProse.contains("Median earnings: withheld by the U.S. Treasury to protect students' privacy."),
      "earnings mark missing: [$lakeshoreProse]",
    )
    assertTrue(
      lakeshoreProse.contains("Median federal loan debt: This school did not report this figure."),
      "debt mark missing: [$lakeshoreProse]",
    )
    assertTrue(lakeshoreProse.contains("Monthly payment on a standard 10-year plan: ${ProgramReturnCopy.NO_FIGURE_STATEMENT}"))
    assertTrue(lakeshoreProse.contains("Biochemistry: ${ProgramReturnCopy.NO_PUBLISHED_FIGURES_STATEMENT}"))

    val eastbrookSubjects = paragraphsOf(sections[1], "report-program-subject")
    assertEquals(
      listOf("Biology", "Chemistry").map { "${ProgramReturnCopy.subjectLeadOf(it)} ${ProgramReturnCopy.NO_PROGRAM_STATEMENT}" },
      eastbrookSubjects,
      "a school with no matching program says so once per subject",
    )

    val wynnford = sections[2]
    assertEquals(
      ProgramReturnCopy.subjectLeadOf("Biology") +
        " Every matching program's figures are withheld by the U.S. Treasury and the federal student loan system " +
        "to protect students' privacy.",
      paragraphsOf(wynnford, "report-program-subject").first(),
    )
    assertEquals(1, countOf(proseOf(wynnford), "withheld"), "one mark for the subject, not one per program and measure")
    assertFalse(proseOf(wynnford).contains("Zoology"), "and no program is laid out under it")
  }

  @Test
  fun `the coverage sentence is said once on a three-school page`() {
    val prose = proseOf(reportSectionOf(render(everyFigureArm())))

    assertEquals(1, countOf(prose, ProgramReturnCopy.COVERAGE_SENTENCE))
  }

  private val adminRecord = AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD, FilingLink.LINKED)

  /** Each kind paragraph of the page's kinds block: its scope label and its sentence, as read. */
  private fun kindParagraphsOf(body: String): List<String> =
    paragraphsOf(body.substringAfter("<section class=\"report-kinds\">").substringBefore("</section>"), "report-kind")

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
    val answer = answerOf(subject("Biology", at(noDebt, allWithheld("2601", "Biology, General."), allWithheld("2607", "Zoology."))))
    val body = render(costProfile(listOf(noDebt), programReturn = answer))
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
    val answer = answerOf(subject("Biology", at(eastbrook, allWithheld("2601", "Biology, General."))))
    val body = render(costProfile(listOf(eastbrook), programReturn = answer))
    val prose = proseOf(reportSectionOf(body))

    assertTrue(prose.contains("\$24,000"), "the fixture must show the school-wide debt figure, or this proves nothing: [$prose]")
    assertEquals(1, countOf(prose, ProgramReturnCopy.COVERAGE_SENTENCE), "the coverage is said: [$prose]")
    assertEquals(1, countOf(prose, adminRecord), "one tier sentence for the school's debt and the program figures: [$prose]")
  }

  @Test
  fun `every woven paragraph sits before the school's borrowing header`() {
    val section = debtSectionsOf(render(everyFigureArm())).first()
    val header = section.indexOf(BorrowingWire.HEADER_TAIL)

    assertTrue(header > 0, "the fixture must carry a borrowing block, or this proves nothing: [$section]")
    assertTrue(
      section.lastIndexOf("report-program") in 0 until header,
      "a woven figure under the header reads as the school's own: [$section]",
    )
  }

  @Test
  fun `the figures by field of study are covered by the one administrative-record sentence`() {
    val body = render(everyFigureArm())
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
    val prose = visibleProseOf(render(everyFigureArm()))

    assertEquals(emptyList(), figureComparisonWordsIn(prose), "the page narrates a relation between figures: [$prose]")
  }

  /**
   * The page's anchors, read off the rendered page: each school's own filing,
   * and the one link to the College Scorecard, every one with
   * `rel="noreferrer"` -- and nothing else.
   */
  @Test
  fun `the page links each filing and the College Scorecard once, and nothing else`() {
    val report = reportSectionOf(render(everyFigureArm()))
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
  fun `every measure has a sentence and a name in the copy`() {
    ProgramMoneyMeasure.entries.forEach { measure ->
      val variable = ScorecardFieldOfStudyColumns.VARIABLES.single { it.measure == measure }
      val sentence = ProgramReturnCopy.sentenceOf(ProgramReturnFigure.Reported(1000, provenance(variable)))
      assertTrue(sentence.contains("\$1,000"), "[${measure.value}] does not say its amount: [$sentence]")
      val label = ProgramReturnCopy.spokenLabelOf(measure)
      assertTrue(label.endsWith("by field of study"), "[${measure.value}] label is not scoped to the field: [$label]")
    }
  }
}
