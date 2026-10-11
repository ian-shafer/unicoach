package ed.unicoach.coaching.costs

import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.college.figureComparisonWordsIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ProgramReturnCopy] (RFC 251): every figure arm, at every measure, is said -- and said without a comparison. */
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

  @Test
  fun `every arm at every measure is its own sentence, with no comparison word in it`() {
    ScorecardFieldOfStudyColumns.VARIABLES.forEach { variable ->
      val sentences = everyArmOf(variable).map { ProgramReturnCopy.sentenceOf(it) }

      assertEquals(sentences.size, sentences.distinct().size, "[${variable.sourceVariable}] folds two arms into one sentence: $sentences")
      sentences.forEach { sentence ->
        assertTrue(sentence.endsWith("."), "[${variable.sourceVariable}] says a fragment: [$sentence]")
        assertEquals(emptyList(), figureComparisonWordsIn(sentence), "[$sentence] compares its figure with another")
      }
    }
  }

  @Test
  fun `a figure with a number names its publisher and cohort, and only earnings names a dollar year`() {
    ScorecardFieldOfStudyColumns.VARIABLES.forEach { variable ->
      val sentence = ProgramReturnCopy.sentenceOf(ProgramReturnFigure.Reported(1234, provenanceOf(variable)))

      assertTrue(sentence.contains("\$1,234"), "[${variable.sourceVariable}] does not say its amount: [$sentence]")
      assertTrue(
        sentence.contains(variable.cohortFirstYear.label) && sentence.contains(variable.cohortLastYear.label),
        "[${variable.sourceVariable}] does not name its cohort: [$sentence]",
      )
      assertFalse(sentence.contains("NSLDS"), "the dictionary's abbreviation reached a family: [$sentence]")
      assertEquals(
        variable.inflationBaseYear != null,
        sentence.contains(" dollars)"),
        "[${variable.sourceVariable}] dollar base disagrees with the registry: [$sentence]",
      )
    }
  }
}
