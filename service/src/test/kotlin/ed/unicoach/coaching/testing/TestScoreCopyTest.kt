package ed.unicoach.coaching.testing

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The score copy, asserted on the constants and the authored prompt file (RFC
 * 207, on RFC 195's `ScoreBandCopyTest` and RFC 187's lesson about
 * self-consistency).
 *
 * Two claims:
 *
 * 1. **The prompt carries every new sentence verbatim.** The copy is authored
 *    in `prompts/coach-system-prompt.md`, so a test that only read Kotlin
 *    constants would not see the sentence the coach is actually handed.
 * 2. **No new sentence predicts, gives odds, coaches prep, or compares a stored
 *    score to a school's band.** D9 is absolute and older than this slice; the
 *    sweep runs over the block constants AND the authored sentences, because a
 *    banned word can enter through either.
 */
class TestScoreCopyTest {
  private val promptSourceFile = File("../prompts/coach-system-prompt.md")

  /** The sentences this slice authors in the prompt file, in file order. */
  private val newPromptSentences: List<String> =
    listOf(
      "When the student shares a score, offer to keep it on file with the update_test_scores tool — then a later conversation still has it without asking her again.",
      "A score kept that way is what the student told us: it is self-reported and never an official score from the testing agency, so never state it as verified.",
      "If she would rather not share scores at all, record that with the same tool and never raise the question again yourself.",
      "No answer of yours is ever gated on a stored score.",
      // NARROWED BY RFC 239 (D-F), not retracted. The old sentence forbade the
      // coach from combining sittings AT ALL; the curated corpus now holds
      // schools that say in writing they accept a combined score, and
      // `college_automatic_awards` computes one under three guardrails. What
      // survives is the rule that mattered: the coach never does that arithmetic
      // from memory, and our figure is never presented as a score the school
      // holds -- and never as an "ACT Superscore", which is ACT's own computed
      // and reported figure and not ours.
      "Scores from different sittings are never added together from memory and never presented as a score the school holds: only the college_automatic_awards tool combines best sections, only where that school's own page says it accepts a combined score, and what it returns is our arithmetic over the school's stated rule and is never called an ACT Superscore.",
      "When she corrects or removes a score, record that with the same tool, and never mention a score she has asked you to remove.",
    )

  private val everySentence: List<String> =
    newPromptSentences +
      listOf(
        TestScoreCopy.ANSWERED_HEADER,
        TestScoreCopy.DECLINED_LINE,
        TestScoreCopy.SELF_REPORTED_NOTE,
        TestScoreCopy.DAY_NOT_RECORDED,
        TestScoreCopy.ONLY_YEAR_RECORDED,
        TestScoreCopy.scoreLineOf(
          ed.unicoach.db.models.AdmissionTestScoreComponent.SAT_TOTAL,
          1340,
          ed.unicoach.db.models.PartialDate
            .YearAndMonth(java.time.Year.of(2026), java.time.Month.MARCH),
        ),
      )

  @Test
  fun `the authored prompt carries every new sentence verbatim`() {
    assertTrue(promptSourceFile.isFile, "the authored prompt must exist at [${promptSourceFile.absolutePath}]")
    val body = promptSourceFile.readText()
    newPromptSentences.forEach { sentence ->
      assertTrue(body.contains(sentence), "the prompt must carry the sentence verbatim: [$sentence]")
    }
  }

  @Test
  fun `no score sentence predicts, gives odds, coaches prep, or compares a stored score to a band`() {
    val bannedWords = listOf("predict", "odds", "chance", "competitive", "prep", "compare", "cut-off", "cutoff")
    everySentence.forEach { sentence ->
      val lower = sentence.lowercase()
      bannedWords.forEach { word ->
        assertFalse(lower.contains(word), "banned word [$word] in score copy: [$sentence]")
      }
    }
  }

  @Test
  fun `a stored score is always labelled self-reported and never verified`() {
    assertTrue(TestScoreCopy.ANSWERED_HEADER.contains(TestScoreCopy.SELF_REPORTED_NOTE))
    // The only mention of "official" is the NEGATION: "never an official score
    // from the testing agency". A bare word probe would fail on the very
    // sentence that makes the claim correct.
    assertTrue(TestScoreCopy.ANSWERED_HEADER.contains("never an official score"), "the negation must be present")
    assertFalse(TestScoreCopy.ANSWERED_HEADER.contains("an official score from the testing agency and"))
  }
}
