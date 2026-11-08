package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.ProgramReturnCopy.ProgramCell
import ed.unicoach.coaching.costs.ProgramReturnCopy.ProgramRow
import ed.unicoach.coaching.costs.ProgramReturnCopy.SubjectLayout
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.college.figureComparisonWordsIn
import ed.unicoach.db.models.CipCode
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.ProgramMoneyMeasure
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ProgramReturnCopy] (RFC 251, glance/04): every figure arm, at every measure, has its own cell -- and says it without a comparison. */
class ProgramReturnCopyTest {
  private fun provenanceOf(variable: ScorecardFieldOfStudyColumns.FieldOfStudyVariable): ProgramFigureProvenance =
    requireNotNull(ProgramFigureProvenance.ofAgreeingCohort(variable, variable.cohortFirstYear, variable.cohortLastYear))

  private fun everyArmOf(variable: ScorecardFieldOfStudyColumns.FieldOfStudyVariable): List<ProgramReturnFigure> {
    val provenance = provenanceOf(variable)
    return listOf(
      ProgramReturnFigure.Reported(1234, provenance),
      ProgramReturnFigure.ImputedByPublisher(1234, provenance),
      ProgramReturnFigure.Suppressed(provenance),
      ProgramReturnFigure.NotReported(provenance),
      ProgramReturnFigure.NotApplicable(provenance),
      ProgramReturnFigure.NotCollectedByUs(provenance),
      ProgramReturnFigure.NoFigure(variable.measure),
    )
  }

  private fun programOf(
    title: String,
    vararg held: ProgramReturnFigure.Held,
  ): ProgramReturnProgram = ProgramReturnProgram.WithFigures.ofHeldFigures(CipCode("2601"), title, held.associateBy { it.measure })

  private fun collegeOf(vararg programs: ProgramReturnProgram): ProgramReturnCollege =
    ProgramReturnCollege.WithPrograms(CollegeId(UUID.randomUUID()), "Lakeshore", programs.first(), programs.drop(1))

  private fun tableOf(vararg programs: ProgramReturnProgram): ProgramReturnCopy.ProgramTable =
    assertIs<SubjectLayout.Table>(ProgramReturnCopy.subjectLayoutOf(collegeOf(*programs))).table

  /**
   * [figure]'s cell, in a program held open by one other measure's plain
   * "not reported": without it a withheld figure would be the program's only
   * figure and a no-row figure would leave nothing held at all, and neither
   * would reach a cell.
   */
  private fun cellOf(figure: ProgramReturnFigure): ProgramCell {
    val other = ScorecardFieldOfStudyColumns.VARIABLES.first { it.measure != figure.measure }
    val held = listOfNotNull(figure as? ProgramReturnFigure.Held, ProgramReturnFigure.NotReported(provenanceOf(other)))
    val row = assertIs<ProgramRow.Cells>(tableOf(programOf("Biology, General.", *held.toTypedArray())).rows.single())
    return row.cells.getValue(figure.measure)
  }

  private fun wordsOf(cell: ProgramCell): String =
    when (cell) {
      is ProgramCell.Figure -> cell.text
      is ProgramCell.Estimate -> cell.text
      is ProgramCell.Gap -> cell.statement
    }

  @Test
  fun `every arm at every measure is its own cell, with no comparison word in it`() {
    ScorecardFieldOfStudyColumns.VARIABLES.forEach { variable ->
      val cells = everyArmOf(variable).map { wordsOf(cellOf(it)) }

      assertEquals(cells.size, cells.distinct().size, "[${variable.sourceVariable}] folds two arms into one cell: $cells")
      cells.forEach { cell ->
        assertTrue(cell.isNotBlank(), "[${variable.sourceVariable}] has an empty cell: $cells")
        assertEquals(emptyList(), figureComparisonWordsIn(cell), "[$cell] compares its figure with another")
      }
    }
  }

  @Test
  fun `a figure's cell is the amount alone, and only an estimate carries the marker`() {
    val provenance = provenanceOf(ScorecardFieldOfStudyColumns.EARNINGS)

    assertEquals(ProgramCell.Figure("\$1,234"), cellOf(ProgramReturnFigure.Reported(1234, provenance)))
    val estimate = cellOf(ProgramReturnFigure.ImputedByPublisher(1234, provenance))
    assertEquals(ProgramCell.Estimate("\$1,234", provenance.variable.publishedBy), estimate)
    assertEquals("\$1,234*", wordsOf(estimate))
  }

  /** The frozen sentences verbatim (X1): `SystemPromptCatalogTest` holds an immutable prompt row to these words. */
  @Test
  fun `each gap holds its status sentence verbatim`() {
    val debt = provenanceOf(ScorecardFieldOfStudyColumns.DEBT)

    assertEquals(
      ProgramCell.Gap(requireNotNull(FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_REPORTED_BY_INSTITUTION))),
      cellOf(ProgramReturnFigure.NotReported(debt)),
    )
    assertEquals(
      ProgramCell.Gap(requireNotNull(FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_APPLICABLE))),
      cellOf(ProgramReturnFigure.NotApplicable(debt)),
    )
    assertEquals(
      ProgramCell.Gap(requireNotNull(FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_COLLECTED_BY_US))),
      cellOf(ProgramReturnFigure.NotCollectedByUs(debt)),
    )
    assertEquals(ProgramCell.Gap(ProgramReturnCopy.NO_FIGURE_STATEMENT), cellOf(ProgramReturnFigure.NoFigure(debt.variable.measure)))
    assertEquals(
      ProgramCell.Gap("Withheld by the federal student loan system to protect students' privacy."),
      cellOf(ProgramReturnFigure.Suppressed(debt)),
    )
  }

  @Test
  fun `a column header names its measure's publisher in a family's words, and a row title drops the trailing period`() {
    val table = tableOf(programOf("Biology, General.", ProgramReturnFigure.Reported(1234, provenanceOf(ScorecardFieldOfStudyColumns.DEBT))))

    assertEquals(ScorecardFieldOfStudyColumns.VARIABLES.map { it.measure }, table.columns.map { it.measure }, "registry order")
    assertEquals(
      listOf(
        "Median earnings a year, four years after finishing, according to the U.S. Treasury",
        "Median federal loan debt at finishing, according to the federal student loan system",
        "Median monthly payment on a standard 10-year plan, by the federal student loan system's own estimate",
      ),
      table.columns.map { it.header },
    )
    table.columns.forEach { column ->
      assertFalse(column.header.contains("NSLDS"), "the dictionary's abbreviation reached a family: [${column.header}]")
      assertEquals(emptyList(), figureComparisonWordsIn(column.header), "[${column.header}] compares a figure with another")
    }
    assertEquals("Biology, General", table.rows.single().title)
  }

  @Test
  fun `a figure with a number reaches the cohort sentence with its own cohort, and only earnings names a dollar year`() {
    ScorecardFieldOfStudyColumns.VARIABLES.forEach { variable ->
      val figure = ProgramReturnFigure.Reported(1234, provenanceOf(variable))
      val sentence = requireNotNull(ProgramReturnCopy.cohortsSentenceOf(listOf(collegeOf(programOf("Biology, General.", figure)))))

      assertTrue(
        sentence.contains(variable.cohortFirstYear.label) && sentence.contains(variable.cohortLastYear.label),
        "[${variable.sourceVariable}] does not name its cohort: [$sentence]",
      )
      assertEquals(
        variable.inflationBaseYear != null,
        sentence.contains(" dollars)"),
        "[${variable.sourceVariable}] dollar base disagrees with the registry: [$sentence]",
      )
      assertEquals(emptyList(), figureComparisonWordsIn(sentence), "[$sentence] compares a figure with another")
    }
  }

  @Test
  fun `measures sharing a population and a cohort are one clause, in registry order`() {
    val program =
      programOf(
        "Biology, General.",
        ProgramReturnFigure.Reported(291, provenanceOf(ScorecardFieldOfStudyColumns.DEBT_MONTHLY_PAYMENT)),
        ProgramReturnFigure.Reported(41200, provenanceOf(ScorecardFieldOfStudyColumns.EARNINGS)),
        ProgramReturnFigure.ImputedByPublisher(26000, provenanceOf(ScorecardFieldOfStudyColumns.DEBT)),
      )

    assertEquals(
      "Earnings by field of study describe graduates working four years after finishing (students who finished in " +
        "2017-18 and 2018-19, in 2024 dollars). Federal loan debt and monthly payment by field of study describe " +
        "graduates who borrowed federal loans (students who finished in 2018-19 and 2019-20).",
      ProgramReturnCopy.cohortsSentenceOf(listOf(collegeOf(program))),
    )
  }

  @Test
  fun `a measure alone in its clause takes its own verb`() {
    val alone =
      mapOf(
        ScorecardFieldOfStudyColumns.DEBT to "Federal loan debt by field of study describes",
        ScorecardFieldOfStudyColumns.DEBT_MONTHLY_PAYMENT to "Monthly payment by field of study describes",
        ScorecardFieldOfStudyColumns.EARNINGS to "Earnings by field of study describe",
      )

    alone.forEach { (variable, opening) ->
      val program = programOf("Biology, General.", ProgramReturnFigure.Reported(1234, provenanceOf(variable)))
      val sentence = requireNotNull(ProgramReturnCopy.cohortsSentenceOf(listOf(collegeOf(program))))
      assertTrue(sentence.startsWith("$opening graduates"), "[$sentence] does not open with [$opening]")
    }
  }

  @Test
  fun `the cohort sentence is silent about every figure without a number`() {
    val noNumber =
      listOf(
        ProgramReturnProgram.NoPublishedFigures(CipCode("2605"), "Biochemistry."),
        programOf(
          "Zoology.",
          ProgramReturnFigure.Suppressed(provenanceOf(ScorecardFieldOfStudyColumns.EARNINGS)),
          ProgramReturnFigure.NotReported(provenanceOf(ScorecardFieldOfStudyColumns.DEBT)),
        ),
      )
    val noProgram = ProgramReturnCollege.NoProgram(CollegeId(UUID.randomUUID()), "Eastbrook")

    assertNull(ProgramReturnCopy.cohortsSentenceOf(listOf(collegeOf(*noNumber.toTypedArray()), noProgram)))
    assertNull(ProgramReturnCopy.cohortsSentenceOf(emptyList()))
  }

  @Test
  fun `the cells are keyed by every measure, so no column can read another's figure`() {
    val table = tableOf(programOf("Biology, General.", ProgramReturnFigure.Reported(1234, provenanceOf(ScorecardFieldOfStudyColumns.DEBT))))
    val row = assertIs<ProgramRow.Cells>(table.rows.single())

    assertEquals(ProgramMoneyMeasure.entries.toSet(), row.cells.keys)
    assertEquals(ProgramCell.Figure("\$1,234"), row.cells.getValue(ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION))
  }
}
